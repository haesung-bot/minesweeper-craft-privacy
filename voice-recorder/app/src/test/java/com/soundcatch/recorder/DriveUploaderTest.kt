package com.soundcatch.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 구글 Apps Script 웹앱을 흉내 내는 가짜 서버로 업로드 과정을 확인한다.
 * (POST → 302 리다이렉트 → GET 으로 결과 JSON 읽기)
 */
class DriveUploaderTest {

    /** 아주 작은 HTTP 서버 (안드로이드 단위 테스트에서도 쓸 수 있게 java.net 만 사용) */
    private class FakeScript(private val secret: String = "pw") : AutoCloseable {
        class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
        class Response(val code: Int, val body: String = "", val type: String = "text/plain", val location: String? = null)

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val replies = ConcurrentHashMap<String, String>()
        private val counter = AtomicInteger()

        @Volatile var lastBytes: ByteArray? = null
        @Volatile var lastName: String? = null
        @Volatile var lastFolder: String? = null
        @Volatile var lastContentLength = -1L
        @Volatile var lastReceivedLength = -1L

        /** 테스트별로 응답을 바꾸고 싶을 때 (null을 돌려주면 기본 동작) */
        @Volatile var override: ((Request) -> Response?)? = null
        @Volatile var reportSizeDelta = 0L

        val port: Int get() = socket.localPort
        val url: String get() = "http://127.0.0.1:$port/macros/s/TEST/exec"

        init {
            Thread {
                while (!socket.isClosed) {
                    val s = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    try {
                        s.use { handle(it) }
                    } catch (_: IOException) {
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(body, off, len - off)
                if (r < 0) break
                off += r
            }
            val req = Request(parts[0], parts[1], headers, body.copyOf(off))
            write(s.getOutputStream(), route(req))
        }

        private fun route(req: Request): Response {
            if (req.method == "GET" && req.path.startsWith("/echo?key=")) {
                return Response(200, replies[req.path.removePrefix("/echo?key=")] ?: "{}", "application/json")
            }
            if (req.method != "POST" || req.path != "/macros/s/TEST/exec") return Response(404, "not found")
            override?.invoke(req)?.let { return it }
            lastContentLength = req.headers["content-length"]?.toLong() ?: -1
            lastReceivedLength = req.body.size.toLong()
            val text = String(req.body, Charsets.UTF_8)
            val reply = when {
                field(text, "secret") != secret ->
                    """{"ok":false,"code":"bad_secret","error":"비밀번호가 \"틀립니다\""}"""
                text.contains("\"ping\":true") ->
                    """{"ok":true,"pong":true,"folderUrl":"https://drive.google.com/drive/folders/abc"}"""
                else -> {
                    val data = Base64.getDecoder().decode(field(text, "data"))
                    lastBytes = data
                    lastName = field(text, "name")
                    lastFolder = field(text, "folder")
                    """{"ok":true,"id":"f1","size":${data.size + reportSizeDelta}}"""
                }
            }
            val key = "k" + counter.incrementAndGet()
            replies[key] = reply
            return Response(302, "", "text/html", location = "/echo?key=$key")
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(Char(c))
            }
        }

        private fun write(out: OutputStream, r: Response) {
            val bytes = r.body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(r.code).append(" X\r\n")
                .append("Content-Type: ").append(r.type).append("\r\n")
                .append("Content-Length: ").append(bytes.size).append("\r\n")
            if (r.location != null) head.append("Location: ").append(r.location).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        override fun close() = socket.close()

        companion object {
            /** 아주 긴 base64 값도 처리하도록 정규식 대신 직접 훑는다. */
            fun field(json: String, name: String): String? {
                val key = "\"$name\":\""
                val start = json.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
                val sb = StringBuilder()
                var i = start
                while (i < json.length) {
                    val c = json[i]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && i + 1 < json.length) {
                        val n = json[i + 1]
                        if (n == 'u') {
                            sb.append(Char(json.substring(i + 2, i + 6).toInt(16)))
                            i += 6
                            continue
                        }
                        sb.append(if (n == 'n') '\n' else n)
                        i += 2
                        continue
                    }
                    sb.append(c)
                    i++
                }
                return null
            }
        }
    }

    private val uploader = DriveUploader(
        base64 = { bytes, len -> Base64.getEncoder().encode(bytes.copyOf(len)) },
        connectTimeoutMs = 5_000,
        readTimeoutMs = 10_000,
    )

    private fun tempFile(size: Int, seed: Long = size.toLong()): File {
        val f = File.createTempFile("rec", ".m4a")
        f.deleteOnExit()
        val bytes = ByteArray(size).also { Random(seed).nextBytes(it) }
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun uploadsFilesOfAllSizesExactly() {
        FakeScript().use { s ->
            val c = DriveUploader.CHUNK_BYTES
            for (size in listOf(1, 2, 3, 4, 1000, c - 1, c, c + 1, 3 * c + 2, 250_003)) {
                val f = tempFile(size)
                val r = uploader.upload(s.url, "pw", f, "2026-10-24_14-35-12_1m05s.m4a", "2026-10-24")
                assertEquals("size=$size ${r.message}", DriveUploader.Kind.OK, r.kind)
                assertArrayEquals("size=$size", f.readBytes(), s.lastBytes)
                assertEquals(s.lastContentLength, s.lastReceivedLength)
                assertEquals(size.toLong(), r.savedSize)
            }
            assertEquals("2026-10-24_14-35-12_1m05s.m4a", s.lastName)
            assertEquals("2026-10-24", s.lastFolder)
        }
    }

    @Test
    fun wrongSecretIsConfigError() {
        FakeScript().use { s ->
            val r = uploader.upload(s.url, "nope", tempFile(10), "a.m4a", "d")
            assertEquals(DriveUploader.Kind.CONFIG, r.kind)
            assertEquals("비밀번호가 \"틀립니다\"", r.message)
        }
    }

    @Test
    fun pingReturnsFolderUrl() {
        FakeScript().use { s ->
            val r = uploader.ping(s.url, "pw")
            assertTrue(r.message, r.ok)
            assertEquals("https://drive.google.com/drive/folders/abc", r.folderUrl)
            assertEquals(DriveUploader.Kind.CONFIG, uploader.ping(s.url, "x").kind)
        }
    }

    @Test
    fun secretWithSpecialCharactersIsEscaped() {
        val secret = "a\"b\\c 한글"
        FakeScript(secret).use { s ->
            assertTrue(uploader.ping(s.url, secret).ok)
        }
        assertEquals("\"a\\\"b\\\\c\\u000a가\"", DriveUploader.jsonString("a\"b\\c\n가"))
    }

    @Test
    fun loginRedirectMeansAccessSettingProblem() {
        FakeScript().use { s ->
            s.override = { FakeScript.Response(302, location = "https://accounts.google.com/ServiceLogin?continue=x") }
            val r = uploader.upload(s.url, "pw", tempFile(100), "a.m4a", "d")
            assertEquals(DriveUploader.Kind.CONFIG, r.kind)
            assertTrue(r.message, r.message.contains("모든 사용자"))
        }
    }

    @Test
    fun htmlPageIsConfigError() {
        FakeScript().use { s ->
            s.override = { FakeScript.Response(200, "<html><body>Sign in</body></html>", "text/html") }
            assertEquals(DriveUploader.Kind.CONFIG, uploader.upload(s.url, "pw", tempFile(5), "a.m4a", "d").kind)
        }
    }

    @Test
    fun serverErrorsAreRetryable() {
        FakeScript().use { s ->
            s.override = { FakeScript.Response(500, "oops") }
            assertEquals(DriveUploader.Kind.SERVER, uploader.upload(s.url, "pw", tempFile(5), "a.m4a", "d").kind)
        }
        assertEquals(
            DriveUploader.Kind.SERVER,
            DriveUploader.parseReply("""{"ok":false,"code":"server","error":"Exception: 할당량 초과"}""").kind,
        )
    }

    @Test
    fun sizeMismatchIsNotSuccess() {
        FakeScript().use { s ->
            s.reportSizeDelta = -1
            assertEquals(DriveUploader.Kind.SERVER, uploader.upload(s.url, "pw", tempFile(50), "a.m4a", "d").kind)
        }
    }

    @Test
    fun notFoundIsConfigError() {
        FakeScript().use { s ->
            val bad = s.url.replace("/exec", "/nothing")
            assertEquals(DriveUploader.Kind.CONFIG, uploader.upload(bad, "pw", tempFile(5), "a.m4a", "d").kind)
        }
    }

    @Test
    fun unreachableServerIsNetworkError() {
        val port = FakeScript().use { it.port }
        val r = uploader.upload("http://127.0.0.1:$port/macros/s/TEST/exec", "pw", tempFile(5), "a.m4a", "d")
        assertEquals(DriveUploader.Kind.NETWORK, r.kind)
    }

    @Test
    fun badUrlIsConfigError() {
        assertEquals(DriveUploader.Kind.CONFIG, uploader.ping("script.google.com/macros", "pw").kind)
        assertEquals(DriveUploader.Kind.CONFIG, uploader.ping("ftp://example.com/x", "pw").kind)
    }

    @Test
    fun base64LengthMatchesEncoder() {
        for (n in 0L..10L) {
            assertEquals(Base64.getEncoder().encode(ByteArray(n.toInt())).size.toLong(), DriveUploader.base64Length(n))
        }
    }
}
