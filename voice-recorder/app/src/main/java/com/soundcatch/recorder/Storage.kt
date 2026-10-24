package com.soundcatch.recorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/**
 * 녹음 파일 폴더
 * - recordings/ : 업로드 기다리는 파일 (*.m4a). 녹음 중인 파일은 *.m4a.part
 * - uploaded/   : '업로드 후 삭제'를 끈 경우 업로드 끝난 파일
 * - failed/     : 여러 번 실패한 파일 ('지금 업로드'를 누르면 다시 시도)
 */
class Storage(context: Context) {
    private val root: File = context.getExternalFilesDir(null) ?: context.filesDir
    val pendingDir = File(root, "recordings").also { it.mkdirs() }
    val uploadedDir = File(root, "uploaded").also { it.mkdirs() }
    val failedDir = File(root, "failed").also { it.mkdirs() }

    fun pending(): List<File> = m4a(pendingDir)
    fun failed(): List<File> = m4a(failedDir)

    private fun m4a(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".m4a") }?.sortedBy { it.name } ?: emptyList()

    /** 앱이 강제 종료돼 마무리되지 못한 조각 파일 정리 */
    fun deleteParts() {
        pendingDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun requeueFailed(): Int {
        var n = 0
        for (f in failed()) if (f.renameTo(File(pendingDir, f.name))) n++
        return n
    }

    fun moveTo(file: File, dir: File): Boolean = file.renameTo(uniqueFile(dir, file.name))

    fun freeBytes(): Long = pendingDir.usableSpace

    /** 남은 공간이 [minFree]보다 적으면 업로드 끝난 파일부터 오래된 순으로 지운다. */
    fun pruneUploaded(minFree: Long) {
        for (f in m4a(uploadedDir)) {
            if (freeBytes() >= minFree) return
            f.delete()
        }
    }

    companion object {
        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            var i = 2
            while (f.exists()) {
                f = File(dir, name.removeSuffix(".m4a") + "_$i.m4a")
                i++
            }
            return f
        }
    }
}

object Net {
    fun available(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!wifiOnly) return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
