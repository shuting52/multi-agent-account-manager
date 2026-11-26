package com.ldjx.accountmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * PackageInstaller 安装状态接收器（在插件 AndroidManifest 里注册，
 * 即使安装过程中 App 进程被杀也能收到结果）。
 *
 * - STATUS_PENDING_USER_ACTION：系统要求用户确认安装时发来，
 *   必须由 App 主动拉起 intent 里带的确认页，否则安装会一直卡住；
 *   非系统应用这一步点「安装」省不掉，这里不承诺静默安装。
 * - STATUS_SUCCESS：安装成功，自动拉起主界面（通过
 *   getLaunchIntentForPackage 找 launcher Activity，不写死类名），
 *   用户不用再点系统的「完成/打开」。
 * - 其余状态：安装失败，用 Toast 告诉用户系统给出的原因。
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.ldjx.accountmanager.INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    confirmIntent?.let { context.startActivity(it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    launch?.let { context.startActivity(it) }
                }
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "未知原因（状态码 $status）"
                Toast.makeText(context, "安装失败：$reason", Toast.LENGTH_LONG).show()
            }
        }
    }
}
