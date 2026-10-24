package com.soundcatch.recorder

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 구글 드라이브 업로드: 사용자가 배포한 구글 Apps Script 웹앱(…/exec)으로 파일을 보낸다.
 * 안드로이드 의존성이 없어서 PC(JVM)에서도 테스트할 수 있다.
 *
 * 요청 본문(JSON): {"secret":..,"name":..,"folder":..,"mime":..,"size":..,"data":"<base64>"}
 * 파일 전체를 메모리에 올리지 않고 조금씩 base64로 바꿔 흘려보낸다.
 */
class DriveUploader(
    /** 표준 Base64(줄바꿈 없음) 인코더: (바이트, 길이) → 결과 */
    private val base64: (ByteArray, Int) -> ByteArray,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 300_000,
) {
    enum class Kind {
        OK,

        /** 주소·비밀번호·배포 설정 문제 → 설정을 고치기 전엔 다시 해도 실패 */
        CONFIG,

        /** 인터넷 끊김 등 → 나중에 다시 시도 */
        NETWORK,

        /** 서버(구글) 쪽 오류 → 몇 번 다시 시도 */
        SERVER,
    }

    data class Result(
        val kind: Kind,
        val message: String,
        val savedSize: Long? = null,
        val folderUrl: String? = null,
    ) {
        val ok: Boolean get() = kind == Kind.OK
    }

    /** 연결 테스트 */
    fun ping(url: String, secret: String): Result {
        val body = "{\"ping\":true,\"secret\":${jsonString(secret)}}".toByteArray(Charsets.UTF_8)
        return send(url, body.size.toLong()) { it.write(body) }
    }

    /** 파일 업로드. [folder]는 드라이브 '폰녹음' 폴더 아래 하위 폴더 이름(예: 날짜). */
    fun upload(url: String, secret: String, file: File, remoteName: String, folder: String): Result {
        val size = file.length()
        val prefix = (
            "{\"secret\":${jsonString(secret)},\"name\":${jsonString(remoteName)}," +
                "\"folder\":${jsonString(folder)},\"mime\":\"audio/mp4\",\"size\":$size,\"data\":\""
            ).toByteArray(Charsets.UTF_8)
        val suffix = "\"}".toByteArray(Charsets.UTF_8)
        val total = prefix.size + base64Length(size) + suffix.size
        val result = send(url, total) { out ->
            out.write(prefix)
            file.inputStream().use { writeBase64(it, out) }
            out.write(suffix)
        }
        if (result.ok && result.savedSize != null && result.savedSize != size) {
            return Result(Kind.SERVER, "저장된 크기가 달라요 (${result.savedSize}/$size)")
        }
        return result
    }

    private fun send(url: String, length: Long, writer: (OutputStream) -> Unit): Result {
        val target = try {
            URL(url.trim())
        } catch (e: Exception) {
            null
        }
        if (target == null || (target.protocol != "https" && target.protocol != "http")) {
            return Result(Kind.CONFIG, "스크립트 주소 형식이 올바르지 않아요")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setFixedLengthStreamingMode(length)
            }
            BufferedOutputStream(conn.outputStream, 64 * 1024).use { writer(it) }
            val code = conn.responseCode
            val text = when (code) {
                in 200..299 -> readText(conn.inputStream)
                in 300..399 -> {
                    // Apps Script는 POST를 처리한 뒤 결과 페이지로 302 리다이렉트한다.
                    val location = conn.getHeaderField("Location")
                        ?: return Result(Kind.SERVER, "서버 응답 오류 ($code)")
                    if (isLoginPage(location)) return loginNeeded()
                    fetch(URL(target, location), 0) ?: return loginNeeded()
                }
                404 -> return Result(Kind.CONFIG, "스크립트 주소를 찾을 수 없어요 (배포 주소 확인)")
                401, 403 -> return accessDenied()
                else -> return Result(Kind.SERVER, "서버 오류 ($code)")
            }
            return parseReply(text)
        } catch (e: HttpStatusException) {
            return when (e.code) {
                401, 403 -> accessDenied()
                404 -> Result(Kind.CONFIG, "스크립트 주소를 찾을 수 없어요 (배포 주소 확인)")
                else -> Result(Kind.SERVER, "서버 오류 (${e.code})")
            }
        } catch (e: IOException) {
            return Result(Kind.NETWORK, "네트워크 오류: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            return Result(Kind.NETWORK, "연결 오류: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 리다이렉트를 따라가며 응답 본문을 읽는다. 구글 로그인 페이지로 가면 null. */
    private fun fetch(url: URL, depth: Int): String? {
        if (depth > 5) throw IOException("리다이렉트가 너무 많아요")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.useCaches = false
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("리다이렉트 주소 없음")
                if (isLoginPage(location)) return null
                return fetch(URL(url, location), depth + 1)
            }
            if (code !in 200..299) throw HttpStatusException(code)
            return readText(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private fun isLoginPage(location: String) =
        location.contains("accounts.google.com") || location.contains("ServiceLogin")

    private fun loginNeeded() = Result(
        Kind.CONFIG,
        "구글 로그인을 요구해요. 배포할 때 '액세스 권한: 모든 사용자'로 설정해 주세요",
    )

    private fun accessDenied() = Result(
        Kind.CONFIG,
        "접근이 거부됐어요. 배포 설정(실행: 나, 액세스: 모든 사용자)을 확인해 주세요",
    )

    private fun writeBase64(input: InputStream, out: OutputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            var filled = 0
            while (filled < chunk.size) {
                val r = input.read(chunk, filled, chunk.size - filled)
                if (r < 0) break
                filled += r
            }
            if (filled > 0) out.write(base64(chunk, filled))
            if (filled < chunk.size) break
        }
    }

    companion object {
        /** 3의 배수여야 중간에 '=' 패딩이 생기지 않는다. */
        const val CHUNK_BYTES = 3 * 8192

        fun base64Length(size: Long): Long = (size + 2) / 3 * 4

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        fun readText(input: InputStream, limit: Int = 64 * 1024): String {
            input.use {
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (buf.size() < limit) {
                    val r = it.read(tmp)
                    if (r < 0) break
                    buf.write(tmp, 0, r)
                }
                return String(buf.toByteArray(), Charsets.UTF_8)
            }
        }

        /** Apps Script 응답(JSON)을 해석한다. JSON이 아니면 배포 설정 문제로 본다. */
        fun parseReply(text: String): Result {
            val t = text.trim()
            if (!t.startsWith("{")) {
                return Result(
                    Kind.CONFIG,
                    "드라이브 스크립트 응답이 아니에요. 주소가 …/exec 로 끝나는지, 액세스가 '모든 사용자'인지 확인해 주세요",
                )
            }
            val ok = Regex("\"ok\"\\s*:\\s*true").containsMatchIn(t)
            val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
            val folderUrl = stringField(t, "folderUrl")
            if (ok) return Result(Kind.OK, "성공", savedSize = size, folderUrl = folderUrl)
            val error = stringField(t, "error") ?: "알 수 없는 오류"
            return when (stringField(t, "code")) {
                "bad_secret", "no_secret", "bad_request" -> Result(Kind.CONFIG, error)
                else -> Result(Kind.SERVER, error)
            }
        }

        /** "name":"값" 을 찾아 값을 돌려준다. (긴 문자열에도 안전하도록 정규식 없이 훑는다) */
        private fun stringField(json: String, name: String): String? {
            val key = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"").find(json) ?: return null
            val start = key.range.last + 1
            var i = start
            while (i < json.length) {
                when (json[i]) {
                    '\\' -> i += 2
                    '"' -> return unescape(json.substring(start, i))
                    else -> i++
                }
            }
            return null
        }

        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    sb.append(c)
                    i++
                    continue
                }
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) {
                            sb.append(Char(code))
                            i += 6
                            continue
                        }
                        sb.append(n)
                    }
                    else -> sb.append(n)
                }
                i += 2
            }
            return sb.toString()
        }
    }
}
