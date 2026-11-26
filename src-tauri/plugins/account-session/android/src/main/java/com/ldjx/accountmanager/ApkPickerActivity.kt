package com.ldjx.accountmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

object ApkPickerBridge {
    @Volatile
    private var pendingCallback: ((String?, Boolean, String?) -> Unit)? = null

    fun setCallback(cb: (String?, Boolean, String?) -> Unit) {
        pendingCallback?.invoke(null, true, null)
        pendingCallback = cb
    }

    fun complete(filePath: String?, cancelled: Boolean, error: String?) {
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(filePath, cancelled, error)
    }
}

class ApkPickerActivity : AppCompatActivity() {
    private var finished = false

    private val pickApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                finishWith(null, true, null)
                return@registerForActivityResult
            }
            Thread {
                try {
                    val targetFile = copyPickedApk(uri)
                    runOnUiThread { finishWith(targetFile.absolutePath, false, null) }
                } catch (t: Throwable) {
                    runOnUiThread { finishWith(null, false, t.message ?: "读取 APK 文件失败") }
                }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
        } catch (t: Throwable) {
            finishWith(null, false, "无法打开文件选择器：${t.message ?: "未知错误"}")
        }
    }

    private fun copyPickedApk(uri: Uri): File {
        val displayName = queryDisplayName(uri) ?: "picked.apk"
        val safeName = displayName
            .substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trimStart('.')
            .ifEmpty { "picked.apk" }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, safeName)
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开所选文件")
        input.use { stream ->
            val magic = ByteArray(4)
            var read = 0
            while (read < magic.size) {
                val n = stream.read(magic, read, magic.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                throw IllegalStateException("选择的文件不是有效的 APK 安装包")
            }
            FileOutputStream(targetFile).use { output ->
                output.write(magic, 0, read)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.fd.sync()
            }
        }
        if (targetFile.length() <= 0L) {
            targetFile.delete()
            throw IllegalStateException("选择的文件为空")
        }
        return targetFile
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun finishWith(filePath: String?, cancelled: Boolean, error: String?) {
        if (finished) return
        finished = true
        ApkPickerBridge.complete(filePath, cancelled, error)
        finish()
    }

    override fun onDestroy() {
        if (!finished) {
            ApkPickerBridge.complete(null, true, null)
        }
        super.onDestroy()
    }
}
