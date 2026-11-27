package com.ldjx.accountmanager

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@InvokeArg
class SubscribeEventsArgs {
    lateinit var channel: Channel
}

@InvokeArg
class OpenSessionArgs {
    var providerId: String = ""
    var providerName: String = ""
    var uuid: String = ""
    var alias: String = ""
    var autoAlias: Boolean = false
    var entryUrl: String = ""
    var allowedHosts: Array<String> = emptyArray()
    var proxyUrl: String? = null
    var timezone: String? = null
    var timezoneInitScript: String? = null
    var switchSeq: Long = 0L
    var viewInstanceId: Long = 0L
    var isColdStart: Boolean = false
    // 0.2.8：群聊回合后台打开（不抢前台），前台切账号为 false。
    var background: Boolean = false
}

@InvokeArg
class UpdateTitleArgs {
    var switchSeq: Long = 0L
    var title: String = ""
}

@InvokeArg
class CloseSessionArgs {
    var switchSeq: Long = 0L
}

@InvokeArg
class DeleteProfileArgs {
    var uuid: String = ""
}

@InvokeArg
class CopyProfileCookieArgs {
    var uuid: String = ""
    var url: String = ""
}

@InvokeArg
class ImportProfileCookiesArgs {
    var uuid: String = ""
    var url: String = ""
    var cookies: Array<String> = emptyArray()
}

@InvokeArg
class ExportProfileCookiesArgs {
    var uuid: String = ""
    var allowedHosts: Array<String> = emptyArray()
    var urls: Array<String> = emptyArray()
}

@InvokeArg
class OpenExternalUrlArgs {
    var url: String = ""
}

@InvokeArg
class FetchTextArgs {
    var url: String = ""
    var userAgent: String = ""
    var accept: String? = null
}

@InvokeArg
class HttpRequestArgs {
    var method: String = "GET"
    var url: String = ""
    var headers: Map<String, String>? = null
    var body: String? = null
}

@InvokeArg
class DownloadApkArgs {
    var url: String = ""
    var sha256: String? = null
    var filename: String = ""
    var userAgent: String = ""
}

@InvokeArg
class EvaluateJavascriptArgs {
    var uuid: String = ""
    var script: String = ""
}

@TauriPlugin
class AccountSessionPlugin(private val activity: Activity) : Plugin(activity) {
    @Volatile
    private var lastDownloadedUrl: String? = null

    @Volatile
    private var lastDownloadedPath: String? = null

    @Command
    fun subscribeEvents(invoke: Invoke) {
        val args = invoke.parseArgs(SubscribeEventsArgs::class.java)
        AccountSessionCoordinator.setEventChannel(args.channel)
        invoke.resolve(JSObject())
    }

    @Command
    fun checkCapabilities(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                val supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                val profiles = JSArray()
                if (supported) {
                    for (name in ProfileStore.getInstance().allProfileNames) {
                        profiles.put(name)
                    }
                }
                val ret = JSObject().apply {
                    put("multiProfileSupported", supported)
                    put("profileNames", profiles)
                }
                invoke.resolve(ret)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "检测 Android WebView 能力异常")
            }
        }
    }

    @Command
    fun openAccountSession(invoke: Invoke) {
        val args = invoke.parseArgs(OpenSessionArgs::class.java)
        activity.runOnUiThread {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                invoke.reject("系统 WebView 不支持 MULTI_PROFILE")
                return@runOnUiThread
            }
            if (args.isColdStart) {
                val allNames = ProfileStore.getInstance().allProfileNames
                if (!allNames.contains(args.uuid)) {
                    invoke.reject("已提交账号的 Profile 不存在")
                    return@runOnUiThread
                }
            }

            val spec = AndroidAccountSessionSpec(
                providerId = args.providerId,
                providerName = args.providerName,
                uuid = args.uuid,
                alias = args.alias,
                autoAlias = args.autoAlias,
                entryUrl = args.entryUrl,
                allowedHosts = args.allowedHosts.toList(),
                timezone = args.timezone,
                timezoneInitScript = args.timezoneInitScript,
                switchSeq = args.switchSeq,
                viewInstanceId = args.viewInstanceId,
                isColdStart = args.isColdStart,
                background = args.background,
            )

            val existing = AccountSessionCoordinator.beginSession(spec)
            if (!AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
                invoke.reject("切换请求已过期")
                return@runOnUiThread
            }
            if (existing != null && !existing.isFinishing && !existing.isDestroyed) {
                existing.bindSessionSpec(spec)
            } else {
                val intent = Intent(activity, AccountWebActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)
            }
            // 0.2.8：后台回合不抢前台——成员页起来后，延时把主 Activity 带回前台，
            // 用户全程留在群聊页看气泡，成员 WebView 在后台继续跑回合。
            if (args.background) bringMainActivityToFrontDelayed()
            invoke.resolve(JSObject())
        }
    }

    // 0.2.8：延时把启动器 Activity（主界面）带回前台；REORDER_TO_FRONT 只复用
    // 已存在的实例，不重建。失败时静默（用户仍可手动按返回回到群聊页）。
    private fun bringMainActivityToFrontDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launchIntent = activity.packageManager
                    .getLaunchIntentForPackage(activity.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
                if (launchIntent != null) activity.startActivity(launchIntent)
            } catch (_: Exception) {
            }
        }, 700)
    }

    @Command
    fun updateAccountTitle(invoke: Invoke) {
        val args = invoke.parseArgs(UpdateTitleArgs::class.java)
        activity.runOnUiThread {
            AccountSessionCoordinator.markCommittedAndReveal(args.switchSeq, args.title)
            invoke.resolve(JSObject())
        }
    }

    @Command
    fun closeActiveSession(invoke: Invoke) {
        val args = invoke.parseArgs(CloseSessionArgs::class.java)
        activity.runOnUiThread {
            val previous = AccountSessionCoordinator.currentActiveSpec()
            val act = AccountSessionCoordinator.invalidateAndClose(args.switchSeq)
            act?.closeAndFinishFromManager(keepPage = previous != null && args.switchSeq > previous.switchSeq)
            invoke.resolve(JSObject())
        }
    }

    @Command
    fun deleteProfile(invoke: Invoke) {
        val args = invoke.parseArgs(DeleteProfileArgs::class.java)
        activity.runOnUiThread {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    invoke.resolve(JSObject())
                    return@runOnUiThread
                }
                if (AccountSessionCoordinator.isUuidActive(args.uuid)) {
                    invoke.reject("目标 Profile 视图尚未销毁")
                    return@runOnUiThread
                }
                RecentAccountPages.discardProfile(args.uuid)
                val store = ProfileStore.getInstance()
                if (store.allProfileNames.contains(args.uuid)) {
                    val deleted = store.deleteProfile(args.uuid)
                    if (!deleted) {
                        invoke.reject("系统 Profile 正在被占用，待下次启动重试")
                        return@runOnUiThread
                    }
                }
                invoke.resolve(JSObject())
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "删除 Android Profile 失败")
            }
        }
    }

    @Command
    fun copyProfileCookie(invoke: Invoke) {
        val args = invoke.parseArgs(CopyProfileCookieArgs::class.java)
        activity.runOnUiThread {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    invoke.reject("系统 WebView 不支持 MULTI_PROFILE")
                    return@runOnUiThread
                }
                val cookies = ProfileStore.getInstance().getProfile(args.uuid)
                    ?.cookieManager?.getCookie(args.url)
                if (cookies.isNullOrEmpty()) {
                    invoke.reject("该账号暂无可复制的 Cookie，请先登录")
                    return@runOnUiThread
                }
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                if (clipboard == null) {
                    invoke.reject("无法写入系统剪贴板，请重试")
                    return@runOnUiThread
                }
                val clip = ClipData.newPlainText("Cookie", cookies)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                invoke.resolve(JSObject())
            } catch (error: Exception) {
                invoke.reject("复制 Cookie 失败，请稍后重试")
            }
        }
    }

    @Command
    fun importProfileCookies(invoke: Invoke) {
        val args = invoke.parseArgs(ImportProfileCookiesArgs::class.java)
        activity.runOnUiThread {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    invoke.reject("系统 WebView 不支持 MULTI_PROFILE")
                    return@runOnUiThread
                }
                val cookieManager = ProfileStore.getInstance().getProfile(args.uuid)?.cookieManager
                if (cookieManager == null) {
                    invoke.reject("无法访问该账号的隔离 Cookie 存储")
                    return@runOnUiThread
                }
                cookieManager.setAcceptCookie(true)
                for (header in args.cookies) {
                    if (header.isNotBlank()) {
                        val targetUrl = resolveCookieTargetUrl(header, args.url)
                        cookieManager.setCookie(targetUrl, header)
                    }
                }
                cookieManager.flush()
                invoke.resolve(JSObject())
            } catch (error: Exception) {
                invoke.reject("导入 Cookie 失败，请检查格式后重试")
            }
        }
    }

    private fun resolveCookieTargetUrl(header: String, fallbackUrl: String): String {
        val parts = header.split(';')
        for (i in 1 until parts.size) {
            val kv = parts[i].trim()
            if (kv.startsWith("domain=", ignoreCase = true)) {
                val rawDomain = kv.substring(7).trim().trim('.', ' ', '"')
                if (rawDomain.isNotEmpty() && !rawDomain.contains('/') && !rawDomain.contains(' ')) {
                    return "https://$rawDomain/"
                }
            }
        }
        return fallbackUrl
    }

    @Command
    fun exportProfileCookies(invoke: Invoke) {
        val args = invoke.parseArgs(ExportProfileCookiesArgs::class.java)
        activity.runOnUiThread {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    invoke.reject("系统 WebView 不支持 MULTI_PROFILE")
                    return@runOnUiThread
                }
                val cookieManager = ProfileStore.getInstance().getProfile(args.uuid)?.cookieManager
                val arr = JSArray()
                if (cookieManager != null) {
                    val seen = HashSet<String>()
                    for (rawUrl in args.urls) {
                        val u = rawUrl.trim()
                        if (u.isEmpty()) continue
                        val host = try {
                            Uri.parse(u).host?.lowercase()?.trimStart('.') ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        if (host.isEmpty()) continue
                        val rawCookie = cookieManager.getCookie(u) ?: continue
                        for (pair in rawCookie.split(';')) {
                            val trimmed = pair.trim()
                            val eqIdx = trimmed.indexOf('=')
                            if (eqIdx <= 0) continue
                            val name = trimmed.substring(0, eqIdx).trim()
                            val value = trimmed.substring(eqIdx + 1).trim()
                            if (name.isEmpty()) continue
                            val dedupKey = "$host|$name"
                            if (!seen.add(dedupKey)) continue
                            val item = JSObject().apply {
                                put("name", name)
                                put("value", value)
                                put("domain", host)
                                put("path", "/")
                                put("secure", u.startsWith("https://", ignoreCase = true))
                                put("httpOnly", false)
                            }
                            arr.put(item)
                        }
                    }
                }
                val ret = JSObject().apply {
                    put("cookies", arr)
                }
                invoke.resolve(ret)
            } catch (error: Exception) {
                invoke.reject("导出 Cookie 失败，请稍后重试")
            }
        }
    }

    @Command
    fun openExternalUrl(invoke: Invoke) {
        val args = invoke.parseArgs(OpenExternalUrlArgs::class.java)
        activity.runOnUiThread {
            try {
                val uri = Uri.parse(args.url)
                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") {
                    invoke.reject("仅允许打开 http/https 外部链接")
                    return@runOnUiThread
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                invoke.resolve(JSObject())
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "无法调用系统浏览器打开链接")
            }
        }
    }

    @Command
    fun fetchText(invoke: Invoke) {
        val args = invoke.parseArgs(FetchTextArgs::class.java)
        thread(name = "maam-update-fetch") {
            var conn: HttpURLConnection? = null
            try {
                conn = openHttpConnection(args.url, args.userAgent, args.accept, 20_000)
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = if (stream != null) {
                    stream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var totalRead = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            totalRead += n
                            if (totalRead > 2 * 1024 * 1024) {
                                throw IllegalStateException("响应内容过大")
                            }
                            out.write(buf, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                } else {
                    ""
                }
                val ret = JSObject().apply {
                    put("status", status)
                    put("body", body)
                }
                invoke.resolve(ret)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "网络请求失败")
            } finally {
                conn?.disconnect()
            }
        }
    }

    @Command
    fun httpRequest(invoke: Invoke) {
        val args = invoke.parseArgs(HttpRequestArgs::class.java)
        thread(name = "maam-relay-http") {
            var conn: HttpURLConnection? = null
            try {
                val method = args.method.uppercase()
                if (method !in setOf("GET", "POST", "DELETE")) {
                    throw IllegalArgumentException("不支持的 HTTP 方法")
                }
                val parsed = URL(args.url)
                if (!parsed.protocol.equals("https", ignoreCase = true)) {
                    throw IllegalArgumentException("仅允许 HTTPS 中转地址")
                }
                conn = (parsed.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    requestMethod = method
                    useCaches = false
                    args.headers?.forEach { (k, v) ->
                        setRequestProperty(k, v)
                    }
                    if (args.body != null && method == "POST") {
                        doOutput = true
                        val bytes = args.body!!.toByteArray(Charsets.UTF_8)
                        setFixedLengthStreamingMode(bytes.size)
                        outputStream.use { it.write(bytes) }
                    }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = if (stream != null) {
                    stream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var totalRead = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            totalRead += n
                            if (totalRead > 4 * 1024 * 1024) {
                                throw IllegalStateException("响应内容过大")
                            }
                            out.write(buf, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                } else {
                    ""
                }
                val ret = JSObject().apply {
                    put("status", status)
                    put("body", body)
                }
                invoke.resolve(ret)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "网络请求失败")
            } finally {
                conn?.disconnect()
            }
        }
    }

    @Command
    fun downloadAndInstallApk(invoke: Invoke) {
        val args = invoke.parseArgs(DownloadApkArgs::class.java)
        thread(name = "maam-update-apk") {
            var conn: HttpURLConnection? = null
            val rawName = args.filename.substringAfterLast('/').substringAfterLast('\\').trim()
            val safeName = rawName
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .trimStart('.')
                .ifEmpty { "update.apk" }
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.part")
            val expectedSha = args.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

            try {
                val canReuse = targetFile.exists() && targetFile.length() > 0L && (
                    (expectedSha != null && computeSha256Hex(targetFile) == expectedSha) ||
                        (expectedSha == null && lastDownloadedUrl == args.url && lastDownloadedPath == targetFile.absolutePath)
                )

                if (!canReuse) {
                    AccountSessionCoordinator.emitUpdateProgress(
                        stage = "downloading",
                        downloaded = 0L,
                        total = null,
                        percent = 0,
                        message = "正在连接下载服务器...",
                    )
                    conn = openHttpConnection(args.url, args.userAgent, null, 60_000)
                    val status = conn.responseCode
                    if (status !in 200..299) {
                        throw IllegalStateException("下载更新包失败（HTTP $status）")
                    }
                    val totalBytes = conn.contentLengthLong.takeIf { it > 0L }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    var lastEmitTime = 0L
                    var lastEmitPercent = -1

                    if (partFile.exists()) {
                        partFile.delete()
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                downloaded += n
                                val pct = totalBytes?.let { ((downloaded * 100L) / it).toInt().coerceIn(0, 100) }
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 150L || (pct != null && pct != lastEmitPercent)) {
                                    lastEmitTime = now
                                    if (pct != null) lastEmitPercent = pct
                                    AccountSessionCoordinator.emitUpdateProgress(
                                        stage = "downloading",
                                        downloaded = downloaded,
                                        total = totalBytes,
                                        percent = pct,
                                        message = "正在下载安装包...",
                                    )
                                }
                            }
                            output.fd.sync()
                        }
                    }

                    AccountSessionCoordinator.emitUpdateProgress(
                        stage = "verifying",
                        downloaded = downloaded,
                        total = totalBytes,
                        percent = 100,
                        message = "正在校验安装包完整性...",
                    )
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expectedSha != null && actualSha != expectedSha) {
                        partFile.delete()
                        throw IllegalStateException("安装包 SHA-256 校验失败，请重试")
                    }

                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (!partFile.renameTo(targetFile)) {
                        FileInputStream(partFile).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        partFile.delete()
                    }

                    updatesDir.listFiles()?.forEach { f ->
                        if (f.name != targetFile.name) {
                            f.delete()
                        }
                    }
                    lastDownloadedUrl = args.url
                    lastDownloadedPath = targetFile.absolutePath
                }

                launchInstallIntentForApk(targetFile, invoke)
            } catch (t: Throwable) {
                if (partFile.exists()) {
                    partFile.delete()
                }
                val err = t.message ?: "下载更新包失败"
                AccountSessionCoordinator.emitUpdateProgress("error", 0L, null, null, err)
                invoke.reject(err)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * 会话式安装：把 APK 流式写入 PackageInstaller session 后 commit。
     * 系统仍会弹一次安装确认页让用户点「安装」（非系统应用省不掉这一步，
     * 这里不承诺静默安装）；能省掉的是每次更新都被支去设置里开「允许安装
     * 未知应用」——该授权开一次永久有效，预检见 checkInstallPermission。
     * 安装结果由 InstallStatusReceiver 接收：用户点确认后安装成功即自动
     * 拉起主界面，不用再点系统的「完成/打开」。
     */
    private fun launchInstallIntentForApk(targetFile: File, invoke: Invoke) {
        activity.runOnUiThread {
            try {
                // 兜底：授权万一在下载中途被撤掉，仍走旧的 needs_permission 返回，
                // 由前端调 open_install_permission_settings 引导用户去开。
                // 正常流程下前端已在下载前调 check_install_permission 预检过，
                // 不应再走到这里。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(permIntent)
                    val msg = "请先开启“允许安装未知来源应用”，开启后返回再次点击安装"
                    AccountSessionCoordinator.emitUpdateProgress(
                        stage = "needs_permission",
                        downloaded = targetFile.length(),
                        total = targetFile.length(),
                        percent = 100,
                        message = msg,
                    )
                    val ret = JSObject().apply {
                        put("status", "needs_permission")
                        put("message", msg)
                    }
                    invoke.resolve(ret)
                    return@runOnUiThread
                }

                val packageInstaller = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                )
                var sessionId = -1
                try {
                    sessionId = packageInstaller.createSession(params)
                } catch (t: Throwable) {
                    throw IllegalStateException("创建安装会话失败：${t.message ?: "未知错误"}")
                }
                val createdSessionId = sessionId
                thread(name = "maam-install-apk") {
                    try {
                        packageInstaller.openSession(createdSessionId).use { session ->
                            session.openWrite("base.apk", 0, targetFile.length()).use { out ->
                                FileInputStream(targetFile).use { input ->
                                    input.copyTo(out)
                                }
                                out.flush()
                                session.fsync(out)
                            }
                            val statusIntent = Intent(activity, InstallStatusReceiver::class.java).apply {
                                action = InstallStatusReceiver.ACTION_INSTALL_STATUS
                            }
                            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    PendingIntent.FLAG_MUTABLE
                                } else {
                                    0
                                }
                            // PackageInstaller 往 status intent 里填安装结果 extras，
                            // Android 12+ 必须用 FLAG_MUTABLE 的 PendingIntent。
                            val statusPending = PendingIntent.getBroadcast(
                                activity,
                                createdSessionId,
                                statusIntent,
                                pendingFlags,
                            )
                            session.commit(statusPending.intentSender)
                        }
                        val msg = "已提交系统安装，请在弹出的安装确认页点「安装」完成更新"
                        AccountSessionCoordinator.emitUpdateProgress(
                            stage = "ready",
                            downloaded = targetFile.length(),
                            total = targetFile.length(),
                            percent = 100,
                            message = msg,
                        )
                        val ret = JSObject().apply {
                            put("status", "installer_launched")
                            put("message", msg)
                        }
                        invoke.resolve(ret)
                    } catch (t: Throwable) {
                        runCatching { packageInstaller.abandonSession(createdSessionId) }
                        val err = t.message ?: "提交系统安装失败"
                        AccountSessionCoordinator.emitUpdateProgress("error", 0L, null, null, err)
                        invoke.reject(err)
                    }
                }
            } catch (t: Throwable) {
                val err = t.message ?: "提交系统安装失败"
                AccountSessionCoordinator.emitUpdateProgress("error", 0L, null, null, err)
                invoke.reject(err)
            }
        }
    }

    /**
     * 前端下载前预检：当前应用是否已被允许安装未知来源应用。
     * Android O 以下没有这项授权，直接返回 true。
     */
    @Command
    fun checkInstallPermission(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }
                val ret = JSObject().apply {
                    put("canInstall", canInstall)
                }
                invoke.resolve(ret)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "检测安装授权失败")
            }
        }
    }

    /**
     * 打开系统「允许安装未知来源应用」设置页（本应用）。
     * 无返回值；用户开完返回 App 后由前端重新调 check_install_permission 确认。
     */
    @Command
    fun openInstallPermissionSettings(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(permIntent)
                invoke.resolve(JSObject())
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "无法打开安装授权设置页")
            }
        }
    }

    @Command
    fun pickAndInstallApk(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                ApkPickerBridge.setCallback { filePath, cancelled, error ->
                    if (error != null) {
                        invoke.reject(error)
                    } else if (cancelled || filePath.isNullOrBlank()) {
                        val ret = JSObject().apply {
                            put("status", "cancelled")
                            put("message", "已取消选择")
                        }
                        invoke.resolve(ret)
                    } else {
                        launchInstallIntentForApk(File(filePath), invoke)
                    }
                }
                val intent = Intent(activity, ApkPickerActivity::class.java)
                activity.startActivity(intent)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "无法打开文件选择器")
            }
        }
    }

    private fun openHttpConnection(
        rawUrl: String,
        userAgent: String,
        accept: String?,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        var currentUrl = URL(rawUrl)
        if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("仅允许 HTTPS 更新地址")
        }
        for (hop in 0 until 10) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                if (userAgent.isNotEmpty()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                if (!accept.isNullOrEmpty()) {
                    setRequestProperty("Accept", accept)
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty()) {
                    throw IllegalStateException("重定向缺少 Location 头")
                }
                val nextUrl = URL(currentUrl, location)
                if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                    throw IllegalStateException("重定向到非 HTTPS 地址已被拒绝")
                }
                currentUrl = nextUrl
                continue
            }
            return conn
        }
        throw IllegalStateException("网络重定向次数过多")
    }

    @Command
    fun scanQrCode(invoke: Invoke) {
        activity.runOnUiThread {
            try {
                QrScannerBridge.setCallback { content, cancelled, error ->
                    if (error != null) {
                        invoke.reject(error)
                    } else {
                        val ret = JSObject().apply {
                            put("content", content ?: "")
                            put("cancelled", cancelled)
                        }
                        invoke.resolve(ret)
                    }
                }
                val intent = Intent(activity, QrScannerActivity::class.java)
                activity.startActivity(intent)
            } catch (t: Throwable) {
                invoke.reject(t.message ?: "无法打开扫码界面")
            }
        }
    }

    @Command
    fun evaluateJavascript(invoke: Invoke) {
        val args = invoke.parseArgs(EvaluateJavascriptArgs::class.java)
        activity.runOnUiThread {
            val settled = AtomicBoolean(false)
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (settled.compareAndSet(false, true)) {
                    invoke.reject("执行 JS 超时（30 秒），页面可能未响应")
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 30_000L)

            fun settleSuccess(raw: String) {
                if (!settled.compareAndSet(false, true)) return
                timeoutHandler.removeCallbacks(timeoutRunnable)
                invoke.resolveObject(raw)
            }

            fun settleError(message: String) {
                if (!settled.compareAndSet(false, true)) return
                timeoutHandler.removeCallbacks(timeoutRunnable)
                invoke.reject(message)
            }

            try {
                if (!AccountSessionCoordinator.isUuidActive(args.uuid)) {
                    settleError("该账号的会话未打开或已切换，请先打开该账号")
                    return@runOnUiThread
                }
                val target = AccountSessionCoordinator.currentActivity()
                if (target == null) {
                    settleError("账号页面不可用，请先打开该账号")
                    return@runOnUiThread
                }
                val dispatched = target.evaluateJavascriptOnMainWebView(args.script) { raw ->
                    activity.runOnUiThread {
                        try {
                            if (raw == null) {
                                settleError("JS 执行未返回结果（脚本错误或页面已销毁）")
                                return@runOnUiThread
                            }
                            if (!AccountSessionCoordinator.isUuidActive(args.uuid)) {
                                settleError("账号会话已切换")
                                return@runOnUiThread
                            }
                            settleSuccess(raw)
                        } catch (t: Throwable) {
                            settleError(t.message ?: "执行 JS 回调异常")
                        }
                    }
                }
                if (!dispatched) {
                    settleError("账号页面 WebView 不可用，请先打开该账号")
                }
            } catch (t: Throwable) {
                settleError(t.message ?: "执行 JS 异常")
            }
        }
    }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
