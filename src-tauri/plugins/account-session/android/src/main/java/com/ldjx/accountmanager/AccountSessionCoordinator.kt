package com.ldjx.accountmanager

import android.net.Uri
import app.tauri.plugin.Channel
import app.tauri.plugin.JSObject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

data class AndroidAccountSessionSpec(
    val providerId: String,
    val providerName: String,
    val uuid: String,
    val alias: String,
    val autoAlias: Boolean,
    val entryUrl: String,
    val allowedHosts: List<String>,
    val timezone: String? = null,
    val timezoneInitScript: String? = null,
    val switchSeq: Long,
    val viewInstanceId: Long,
    val isColdStart: Boolean,
    // 0.2.8：群聊回合后台打开，不抢前台；前台切账号为 false。
    val background: Boolean = false,
)

object AccountSessionCoordinator {
    private val lock = Any()
    private val localViewBump = AtomicLong(1_000_000L)

    @Volatile
    private var pluginInitialized: Boolean = false
    private var eventChannel: Channel? = null
    private var latestSwitchSeq: Long = 0L
    private var activeSpec: AndroidAccountSessionSpec? = null
    private var committedSpec: AndroidAccountSessionSpec? = null
    private var activityRef: WeakReference<AccountWebActivity>? = null

    fun setEventChannel(channel: Channel) {
        synchronized(lock) {
            pluginInitialized = true
            eventChannel = channel
        }
    }

    fun attachActivity(activity: AccountWebActivity) {
        synchronized(lock) {
            activityRef = WeakReference(activity)
        }
    }

    fun detachActivity(activity: AccountWebActivity) {
        synchronized(lock) {
            if (activityRef?.get() === activity) {
                activityRef = null
            }
        }
    }

    fun beginSession(spec: AndroidAccountSessionSpec): AccountWebActivity? {
        synchronized(lock) {
            if (spec.switchSeq < latestSwitchSeq) return null
            latestSwitchSeq = spec.switchSeq
            activeSpec = spec
            return activityRef?.get()
        }
    }

    fun currentActiveSpec(): AndroidAccountSessionSpec? {
        synchronized(lock) {
            return activeSpec
        }
    }

    fun currentActivity(): AccountWebActivity? {
        synchronized(lock) {
            return activityRef?.get()
        }
    }

    fun recoverSpecOnActivityRecreate(): AndroidAccountSessionSpec? {
        synchronized(lock) {
            if (!pluginInitialized) {
                return null
            }
            val base = committedSpec ?: return null
            if (latestSwitchSeq != base.switchSeq) {
                emitRecreateRequestedLocked(latestSwitchSeq)
                return null
            }
            val refreshed = base.copy(viewInstanceId = localViewBump.incrementAndGet())
            activeSpec = refreshed
            committedSpec = refreshed
            return refreshed
        }
    }

    fun isCurrent(switchSeq: Long, viewInstanceId: Long): Boolean {
        synchronized(lock) {
            val cur = activeSpec ?: return false
            return latestSwitchSeq == switchSeq &&
                cur.switchSeq == switchSeq &&
                cur.viewInstanceId == viewInstanceId
        }
    }

    fun markCommittedAndReveal(switchSeq: Long, title: String) {
        val targetActivity: AccountWebActivity?
        val specSnapshot: AndroidAccountSessionSpec?
        synchronized(lock) {
            val cur = activeSpec
            if (cur == null || latestSwitchSeq != switchSeq || cur.switchSeq != switchSeq) {
                return
            }
            committedSpec = cur
            specSnapshot = cur
            targetActivity = activityRef?.get()
        }
        if (specSnapshot != null) {
            targetActivity?.revealCommittedSession(
                specSnapshot.switchSeq,
                specSnapshot.viewInstanceId,
                title,
            )
        }
    }

    fun invalidateAndClose(nextSeq: Long): AccountWebActivity? {
        synchronized(lock) {
            if (nextSeq < latestSwitchSeq) return null
            latestSwitchSeq = nextSeq
            activeSpec = null
            return activityRef?.get()
        }
    }

    fun isUuidActive(uuid: String): Boolean {
        synchronized(lock) {
            return activeSpec?.uuid == uuid
        }
    }

    fun hostMatchesAllowed(uri: Uri?, allowedHosts: List<String>): Boolean {
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (allowedHosts.any { it == "*" }) {
            return true
        }
        return allowedHosts.any { allowed ->
            val clean = allowed.lowercase()
            host == clean || host.endsWith(".$clean")
        }
    }

    fun isGoogleOAuthHost(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "accounts.google.com" || host.endsWith(".accounts.google.com")
    }

    fun emitPageCommitVisible(spec: AndroidAccountSessionSpec, url: String) {
        val ch = synchronized(lock) {
            if (!isCurrent(spec.switchSeq, spec.viewInstanceId)) return
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "pageCommitVisible")
            put("providerId", spec.providerId)
            put("uuid", spec.uuid)
            put("switchSeq", spec.switchSeq)
            put("viewInstanceId", spec.viewInstanceId)
            put("url", url)
        }
        ch.send(obj)
    }

    // 0.2.9：与 emitPageCommitVisible 完全同构的第二就绪信号。
    // 后台 WebView 不可见时 onPageCommitVisible 不触发，onPageFinished 兜底。
    fun emitPageLoadFinished(spec: AndroidAccountSessionSpec, url: String) {
        val ch = synchronized(lock) {
            if (!isCurrent(spec.switchSeq, spec.viewInstanceId)) return
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "pageLoadFinished")
            put("providerId", spec.providerId)
            put("uuid", spec.uuid)
            put("switchSeq", spec.switchSeq)
            put("viewInstanceId", spec.viewInstanceId)
            put("url", url)
        }
        ch.send(obj)
    }

    fun emitIdentityDetected(
        spec: AndroidAccountSessionSpec,
        identity: String,
        avatarUrl: String?,
        quotaText: String?,
        quotaPercent: Int?,
        quotaDetailJson: String?,
        referralJson: String?,
    ) {
        val ch = synchronized(lock) {
            if (!isCurrent(spec.switchSeq, spec.viewInstanceId)) return
            if (identity.isNotEmpty() && activeSpec?.autoAlias == true) {
                activeSpec = activeSpec?.copy(autoAlias = false, alias = identity)
                committedSpec = committedSpec?.copy(autoAlias = false, alias = identity)
            }
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "identityDetected")
            put("providerId", spec.providerId)
            put("uuid", spec.uuid)
            put("switchSeq", spec.switchSeq)
            put("viewInstanceId", spec.viewInstanceId)
            put("identity", identity)
            if (!avatarUrl.isNullOrEmpty()) put("avatarUrl", avatarUrl)
            if (!quotaText.isNullOrEmpty()) put("quotaText", quotaText)
            if (quotaPercent != null) put("quotaPercent", quotaPercent)
            if (!quotaDetailJson.isNullOrEmpty()) put("quotaDetailJson", quotaDetailJson)
            if (!referralJson.isNullOrEmpty()) put("referralJson", referralJson)
        }
        ch.send(obj)
    }

    fun emitSessionFailed(switchSeq: Long, viewInstanceId: Long, reason: String) {
        val ch = synchronized(lock) {
            if (activeSpec?.switchSeq == switchSeq && activeSpec?.viewInstanceId == viewInstanceId) {
                activeSpec = null
            }
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "sessionFailed")
            put("switchSeq", switchSeq)
            put("viewInstanceId", viewInstanceId)
            put("reason", reason)
        }
        ch.send(obj)
    }

    fun emitActivityClosed(switchSeq: Long, viewInstanceId: Long) {
        val ch = synchronized(lock) {
            if (activeSpec?.switchSeq == switchSeq && activeSpec?.viewInstanceId == viewInstanceId) {
                activeSpec = null
            }
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "activityClosed")
            put("switchSeq", switchSeq)
            put("viewInstanceId", viewInstanceId)
        }
        ch.send(obj)
    }

    fun emitExternalAuthUnavailable(switchSeq: Long, viewInstanceId: Long, reason: String) {
        val ch = synchronized(lock) {
            if (!isCurrent(switchSeq, viewInstanceId)) return
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "externalAuthUnavailable")
            put("switchSeq", switchSeq)
            put("viewInstanceId", viewInstanceId)
            put("reason", reason)
        }
        ch.send(obj)
    }

    private fun emitRecreateRequestedLocked(previousSwitchSeq: Long) {
        val ch = eventChannel ?: return
        val obj = JSObject().apply {
            put("kind", "recreateRequested")
            put("previousSwitchSeq", previousSwitchSeq)
        }
        ch.send(obj)
    }

    fun emitUpdateProgress(
        stage: String,
        downloaded: Long,
        total: Long?,
        percent: Int?,
        message: String,
    ) {
        val ch = synchronized(lock) { eventChannel } ?: return
        val obj = JSObject().apply {
            put("kind", "updateProgress")
            put("stage", stage)
            put("downloaded", downloaded)
            if (total != null) put("total", total)
            if (percent != null) put("percent", percent)
            put("message", message)
        }
        ch.send(obj)
    }

    fun emitSessionLoggedOut(spec: AndroidAccountSessionSpec) {
        val ch = synchronized(lock) {
            if (!isCurrent(spec.switchSeq, spec.viewInstanceId)) return
            eventChannel
        } ?: return

        val obj = JSObject().apply {
            put("kind", "sessionLoggedOut")
            put("providerId", spec.providerId)
            put("uuid", spec.uuid)
            put("switchSeq", spec.switchSeq)
            put("viewInstanceId", spec.viewInstanceId)
        }
        ch.send(obj)
    }
}
