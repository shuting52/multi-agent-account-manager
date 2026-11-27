package com.ldjx.accountmanager

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class AccountWebActivity : AppCompatActivity() {

    private lateinit var headerTitleView: TextView
    private lateinit var mainWebContainer: FrameLayout
    private lateinit var loadingView: ProgressBar
    private lateinit var popupOverlayRoot: LinearLayout
    private lateinit var popupDomainLabel: TextView
    private lateinit var popupWebContainer: FrameLayout
    private lateinit var downloadBannerRoot: LinearLayout
    private lateinit var downloadBannerText: TextView
    private lateinit var downloadOpenBtn: Button
    private lateinit var downloadDirBtn: Button

    private var currentSpec: AndroidAccountSessionSpec? = null
    private var mainWebView: WebView? = null
    // 0.2.8：群聊回合后台打开时为 true——onPause 跳过 WebView.onPause()，
    // 否则 JS 定时器会被暂停，回合的轮询/流式回复就停了。
    private var keepWebViewAlive: Boolean = false
    private var committedAuthority: String? = null
    private var popupWebView: WebView? = null
    private var mainInputEnabled: Boolean = false
    private var popupInputEnabled: Boolean = false
    private var closingFromManager: Boolean = false
    private var capturedIdentityHint: String? = null
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var identityProbeRunnable: Runnable? = null
    private var probeAwaitingConfirm: Boolean = false
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val activeSessionGeneration = AtomicLong(1L)
    private val blobLock = Any()
    private val pendingBlobTokens = HashMap<String, BlobInitMeta>()
    private val activeBlobSessions = HashMap<String, BlobTransferSession>()
    private val blobBridge = AndroidBlobDownloadBridge()
    private var downloadBannerHideRunnable: Runnable? = null
    private var lastDownloadedUri: Uri? = null
    private var lastDownloadedMime: String = "*/*"
    private var lastDownloadedFallbackDir: File? = null

    // Cue 登录页加载失败（CloudFront 403 等）专用状态：只用于 cue.im /
    // manus.im 主页面，Muse/Hark 页面不走这套逻辑。
    private lateinit var cueErrorCardRoot: FrameLayout
    private lateinit var cueErrorBodyView: TextView
    private var cueRetryDesktopUaTried: Boolean = false
    private var cueRetryRootTried: Boolean = false
    private var cueRetryInFlight: Boolean = false
    private var cueFailureHandling: Boolean = false
    private var cueErrorShown: Boolean = false
    private var cueFailedUrl: String? = null
    private var cueOriginalUserAgent: String? = null

    private data class BlobInitMeta(
        val generation: Long,
        val defaultName: String,
        val fallbackMime: String,
    )

    private data class BlobTransferSession(
        val token: String,
        val generation: Long,
        val defaultName: String,
        val tempFile: File,
        val stream: FileOutputStream,
        var mimeType: String,
        var hintName: String,
        var totalBytes: Long = 0L,
    )

    private data class SavedDownloadResult(
        val filename: String,
        val mimeType: String,
        val openUri: Uri?,
        val publicDownloads: Boolean,
        val fallbackDir: File?,
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFilePathCallback ?: return@registerForActivityResult
        pendingFilePathCallback = null
        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data
        val clip = data?.clipData
        val uris = if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        }
        callback.onReceiveValue(uris)
    }

    companion object {
        private const val QUOTA_HOOK_INIT_SCRIPT = """
            (() => {
              try {
                if (location.protocol !== 'https:') return;
                if (window !== window.top) return;
                if (window.__maamNetHooked) return;
                window.__maamNetHooked = true;
                const initHost = (location.hostname || '').toLowerCase();
                const isPrimaryHost = initHost === 'muse.ai' || initHost.endsWith('.muse.ai') || initHost === 'app.hark.com' || initHost.endsWith('.hark.com');
                const setQuota = (text, pct) => {
                  if (!text) return;
                  const cleanPct = typeof pct === 'number' && Number.isFinite(pct)
                    ? Math.max(0, Math.min(100, Math.round(pct)))
                    : null;
                  const obj = { text: String(text).slice(0, 48), pct: cleanPct };
                  window.__maamQuota = obj;
                  try { localStorage.setItem('__maam_cached_quota__', JSON.stringify(obj)); } catch (_) {}
                };
                const saveDetail = (detail) => {
                  if (!detail || !detail.planLabel) return;
                  window.__maamQuotaDetail = detail;
                  try { localStorage.setItem('__maam_cached_quota_detail_v2__', JSON.stringify(detail)); } catch (_) {}
                  const rem = typeof detail.planUsedPercent === 'number'
                    ? Math.max(0, Math.min(100, 100 - detail.planUsedPercent))
                    : null;
                  setQuota(detail.planValue || detail.planLabel, rem);
                };
                const formatHarkReset = (iso) => {
                  if (!iso || typeof iso !== 'string') return null;
                  const d = new Date(iso);
                  if (Number.isNaN(d.getTime())) return null;
                  const pad = (n) => String(n).padStart(2, '0');
                  const hhmm = pad(d.getHours()) + ':' + pad(d.getMinutes());
                  const now = new Date();
                  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
                  const targetStart = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
                  const diffDays = Math.round((targetStart - todayStart) / 86400000);
                  if (diffDays === 0) return '今天 ' + hhmm + ' 重置';
                  if (diffDays === 1) return '明天 ' + hhmm + ' 重置';
                  if (d.getFullYear() === now.getFullYear()) {
                    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hhmm + ' 重置';
                  }
                  return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hhmm + ' 重置';
                };
                const mapHarkSub = (node) => {
                  if (!(location.hostname || '').toLowerCase().endsWith('hark.com')) return null;
                  if (!node || typeof node !== 'object') return null;
                  const meters = (node.meters && typeof node.meters === 'object' ? (node.meters.harkTokens || node.meters) : null)
                    || (node.harkTokens && typeof node.harkTokens === 'object' ? node.harkTokens : null)
                    || (('dailyLimit' in node || 'poolLimit' in node) ? node : null);
                  if (meters && typeof meters === 'object' && (typeof meters.dailyLimit === 'number' || typeof meters.poolLimit === 'number')) {
                    const rawPlan = (node.plan && typeof node.plan === 'object' && typeof node.plan.name === 'string' && node.plan.name.trim())
                      || (typeof node.planName === 'string' && node.planName.trim())
                      || (typeof node.plan === 'string' && node.plan.trim())
                      || '';
                    const shortPlan = rawPlan.replace(/^hark\s+/i, '').trim();
                    const hasDaily = typeof meters.dailyLimit === 'number' && meters.dailyLimit > 0 && typeof meters.dailyUsed === 'number';
                    const hasMonthly = typeof meters.poolLimit === 'number' && meters.poolLimit > 0 && typeof meters.poolUsed === 'number';
                    if (hasDaily || hasMonthly) {
                      const dailyPct = hasDaily ? Math.max(0, Math.min(100, Math.round((meters.dailyUsed / meters.dailyLimit) * 100))) : null;
                      const monthlyPct = hasMonthly ? Math.max(0, Math.min(100, Math.round((meters.poolUsed / meters.poolLimit) * 100))) : null;
                      if (hasDaily) {
                        return {
                          planLabel: shortPlan ? (shortPlan + ' · 每日') : '每日上限',
                          planValue: '已使用 ' + dailyPct + '%',
                          planSubtext: formatHarkReset(meters.dailyResetsAt),
                          planUsedPercent: dailyPct,
                          extraLabel: hasMonthly ? '每月上限' : null,
                          extraValue: hasMonthly ? ('已使用 ' + monthlyPct + '%') : null,
                          extraSubtext: hasMonthly ? formatHarkReset(meters.poolResetsAt) : null,
                          extraUsedPercent: monthlyPct
                        };
                      }
                      return {
                        planLabel: shortPlan ? (shortPlan + ' · 每月') : '每月上限',
                        planValue: '已使用 ' + monthlyPct + '%',
                        planSubtext: formatHarkReset(meters.poolResetsAt),
                        planUsedPercent: monthlyPct,
                        extraLabel: null,
                        extraValue: null,
                        extraSubtext: null,
                        extraUsedPercent: null
                      };
                    }
                  }
                  const u = node.usage || node.billing || node.credits || node.subscription || node;
                  if (!u || typeof u !== 'object' || 'page' in u || 'items' in u || 'messages' in u) return null;
                  const rem = u.remaining ?? u.remaining_credits ?? u.remainingCredits ?? u.credits_remaining ?? u.creditsRemaining ?? u.balance ?? u.creditBalance ?? u.left;
                  const tot = u.total ?? u.limit ?? u.total_credits ?? u.totalCredits ?? u.max ?? u.allowance;
                  const used = u.used ?? u.used_credits ?? u.usedCredits ?? u.consumed;
                  const planName = u.plan_name || u.planName || u.plan || node.plan_name || node.planName || node.plan || (typeof node.tier === 'string' ? node.tier : null) || (typeof u.name === 'string' ? u.name : null) || 'Hark 额度';
                  if (typeof rem === 'number' || typeof used === 'number') {
                    let usedPct = null;
                    let planVal = '';
                    if (typeof tot === 'number' && tot > 0) {
                      const actualUsed = typeof used === 'number' ? used : (tot - (typeof rem === 'number' ? rem : 0));
                      usedPct = Math.max(0, Math.min(100, Math.round((actualUsed / tot) * 100)));
                      planVal = '已使用 ' + usedPct + '%';
                    } else if (typeof rem === 'number') {
                      planVal = '剩余 ' + rem;
                    }
                    const resetsAt = u.resets_at ?? u.resetsAt;
                    return {
                      planLabel: String(planName),
                      planValue: planVal,
                      planSubtext: resetsAt ? ('将在 ' + new Date(resetsAt).toLocaleDateString() + ' 重置') : (u.period ? ('周期: ' + u.period) : null),
                      planUsedPercent: usedPct,
                      extraLabel: null,
                      extraValue: null,
                      extraSubtext: null,
                      extraUsedPercent: null
                    };
                  }
                  return null;
                };
                const mapSub = (node) => {
                  if (!node || typeof node !== 'object') return null;
                  const harkRes = mapHarkSub(node);
                  if (harkRes) return harkRes;
                  const s = (node.subscription && typeof node.subscription === 'object' && node.subscription.usage)
                    ? node.subscription
                    : node;
                  const u = s.usage;
                  if (!u || typeof u !== 'object') return null;
                  if (!('state' in u) && !('percentUsed' in u) && !('percent_used' in u) && !('quotaStatus' in u) && !('quota_status' in u)) {
                    return null;
                  }
                  const subType = s.subscriptionType || s.subscription_type || '';
                  const tierName = (s.tier && typeof s.tier.name === 'string' && s.tier.name.trim()) || '';
                  const planLabel = (typeof s.usageRowLabel === 'string' && s.usageRowLabel.trim())
                    || (typeof s.usage_row_label === 'string' && s.usage_row_label.trim())
                    || tierName
                    || (subType === 'PAID' ? '订阅版' : subType === 'COMPLIMENTARY' ? '赠送版' : subType === 'FREE' ? '免费版' : '基础额度');
                  const rawPct = typeof u.percentUsed === 'number' ? u.percentUsed : u.percent_used;
                  const qStatus = u.quotaStatus || u.quota_status || '';
                  let planUsedPercent = null;
                  if (u.state !== 'UNLIMITED' && typeof rawPct === 'number' && Number.isFinite(rawPct)) {
                    planUsedPercent = Math.max(0, Math.min(100, Math.round(rawPct)));
                  } else if (qStatus === 'DEPLETED') {
                    planUsedPercent = 100;
                  }
                  const rawVal = (typeof s.usageRowValueLabel === 'string' && s.usageRowValueLabel.trim())
                    || (typeof s.usage_row_value_label === 'string' && s.usage_row_value_label.trim())
                    || '';
                  const planValue = rawVal
                    || (u.state === 'UNLIMITED' ? '无限额度'
                      : planUsedPercent !== null ? ('已使用 ' + planUsedPercent + '%')
                      : qStatus === 'DEPLETED' ? '已用尽'
                      : qStatus === 'LOW' ? '额度偏低'
                      : '额度充足');
                  let planSubtext = (typeof s.statusSubtitle === 'string' && s.statusSubtitle.trim())
                    || (typeof s.status_subtitle === 'string' && s.status_subtitle.trim())
                    || (typeof s.usageRowFooter === 'string' && s.usageRowFooter.trim())
                    || (typeof s.usage_row_footer === 'string' && s.usage_row_footer.trim())
                    || null;
                  if (!planSubtext) {
                    const rawReset = u.resetsAt ?? u.resets_at;
                    if (typeof rawReset === 'number' && rawReset > 1000000000) {
                      const ms = rawReset > 100000000000 ? rawReset : rawReset * 1000;
                      const d = new Date(ms);
                      if (!Number.isNaN(d.getTime())) {
                        planSubtext = '将在 ' + (d.getMonth() + 1) + '月' + d.getDate() + '日重置';
                      }
                    }
                  }
                  const topupVal = (typeof s.topupRowValueLabel === 'string' && s.topupRowValueLabel.trim())
                    || (typeof s.topup_row_value_label === 'string' && s.topup_row_value_label.trim())
                    || '';
                  const topupBal = typeof s.topupBalance === 'number' ? s.topupBalance : s.topup_balance;
                  const topupTot = typeof s.topupTotal === 'number' ? s.topupTotal : s.topup_total;
                  let extraLabel = null;
                  let extraValue = null;
                  let extraSubtext = null;
                  let extraUsedPercent = null;
                  if (topupVal || (typeof topupTot === 'number' && topupTot > 0)) {
                    extraLabel = (typeof s.topupRowLabel === 'string' && s.topupRowLabel.trim())
                      || (typeof s.topup_row_label === 'string' && s.topup_row_label.trim())
                      || '额外的使用额度';
                    if (typeof topupTot === 'number' && topupTot > 0 && typeof topupBal === 'number') {
                      extraUsedPercent = Math.max(0, Math.min(100, Math.round(((topupTot - topupBal) / topupTot) * 100)));
                    }
                    extraValue = topupVal || (extraUsedPercent !== null ? ('已使用 ' + extraUsedPercent + '%') : null);
                    extraSubtext = '从不过期';
                  }
                  return {
                    planLabel,
                    planValue,
                    planSubtext,
                    planUsedPercent,
                    extraLabel,
                    extraValue,
                    extraSubtext,
                    extraUsedPercent
                  };
                };
                window.__maamMapSub = mapSub;
                window.__maamSaveDetail = saveDetail;
                window.__maamRefreshHarkQuota = async (force) => {
                  if (window.__maamQuotaInflight) return;
                  const now = Date.now();
                  if (!force && window.__maamQuotaFetchedAt && now - window.__maamQuotaFetchedAt < 90000) return;
                  const curHost = (location.hostname || '').toLowerCase();
                  if (curHost !== 'app.hark.com' && !curHost.endsWith('.hark.com')) return;
                  window.__maamQuotaInflight = true;
                  try {
                    const fetchFn = window.__maamOrigFetch || window.fetch;
                    const res = await fetchFn.call(window, '/api/billing/summary', {
                      credentials: 'include',
                      headers: { Accept: 'application/json' }
                    });
                    if (res && res.ok) {
                      const data = await res.json();
                      const mapped = mapHarkSub(data);
                      if (mapped) {
                        window.__maamQuotaFetchedAt = Date.now();
                        saveDetail(mapped);
                      }
                    }
                    if (!window.__maamApiEmail) {
                      try {
                        const profRes = await fetchFn.call(window, '/api/profile', {
                          credentials: 'include',
                          headers: { Accept: 'application/json' }
                        });
                        if (profRes && profRes.ok) {
                          const prof = await profRes.json();
                          if (prof && typeof prof.email === 'string' && prof.email.includes('@')) {
                            window.__maamApiEmail = prof.email.trim().toLowerCase();
                          }
                        }
                      } catch (_) {}
                    }
                  } catch (_) {
                  } finally {
                    window.__maamQuotaInflight = false;
                  }
                };
                const inspectNode = (node, depth, skipSub) => {
                  if (!node || typeof node !== 'object' || depth > 6) return;
                  if (Array.isArray(node)) {
                    for (let i = 0; i < node.length && i < 15; i++) inspectNode(node[i], depth + 1, skipSub);
                    return;
                  }
                  if (typeof node.email === 'string' && /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(node.email.trim())) {
                    window.__maamApiEmail = node.email.trim().toLowerCase();
                  }
                  let nextSkipSub = Boolean(skipSub);
                  if (!nextSkipSub) {
                    const mapped = mapSub(node);
                    if (mapped) {
                      saveDetail(mapped);
                      nextSkipSub = true;
                    }
                  }
                  const remCredits = node.remaining_credits ?? node.credits_remaining ?? node.credit_balance ?? node.quota_remaining;
                  if (typeof remCredits === 'number' && Number.isFinite(remCredits)) {
                    const total = node.total_credits ?? node.monthly_limit ?? node.quota_limit;
                    if (typeof total === 'number' && total > 0) {
                      const pct = Math.max(0, Math.min(100, Math.round((remCredits / total) * 100)));
                      setQuota('剩余 ' + remCredits + '/' + total, pct);
                    } else {
                      setQuota('剩余额度 ' + remCredits, null);
                    }
                  }
                  if (node.code === 'out_of_credits' || node.error === 'rate_limit_exceeded') {
                    setQuota('额度已用尽', 0);
                  }
                  const keys = Object.keys(node);
                  for (let i = 0; i < keys.length && i < 24; i++) {
                    const v = node[keys[i]];
                    if (v && typeof v === 'object') inspectNode(v, depth + 1, nextSkipSub);
                  }
                };
                const inspectText = (txt) => {
                  if (typeof txt !== 'string' || txt.length < 2 || txt.length > 600000) return;
                  if (!/percent_used|percentUsed|quota_status|quotaStatus|usage_row_value_label|usageRowValueLabel|topup_balance|topupBalance|remaining_credits|credit_balance|out_of_credits|UNLIMITED|email|credits|allowance|balance|usage|tokens|billing|subscription|harkTokens|dailyLimit|dailyUsed|poolLimit|poolUsed|dailyResetsAt|poolResetsAt|meters/i.test(txt)) return;
                  try {
                    inspectNode(JSON.parse(txt), 0, false);
                    return;
                  } catch (_) {}
                  const lines = txt.split('\n');
                  for (let i = 0; i < lines.length && i < 80; i++) {
                    const idx = lines[i].indexOf(':');
                    if (idx > 0 && idx < 12) {
                      const rest = lines[i].slice(idx + 1).trim();
                      if (rest.startsWith('{') || rest.startsWith('[')) {
                        try { inspectNode(JSON.parse(rest), 0, false); } catch (_) {}
                      }
                    }
                  }
                };
                if (isPrimaryHost) {
                  const origFetch = window.fetch;
                  if (typeof origFetch === 'function') {
                    window.__maamOrigFetch = origFetch;
                    window.fetch = function(...args) {
                      const reqUrl = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
                      const p = origFetch.apply(this, args);
                      p.then((res) => {
                        try {
                          const ct = (res.headers && res.headers.get('content-type')) || '';
                          if (ct.includes('json') || ct.includes('text')) {
                            res.clone().text().then(inspectText).catch(() => {});
                          }
                          if (reqUrl.includes('/api/messages') && initHost.endsWith('hark.com')) {
                            clearTimeout(window.__maamPostHarkChatTimer);
                            window.__maamPostHarkChatTimer = setTimeout(() => {
                              if (typeof window.__maamRefreshHarkQuota === 'function') {
                                window.__maamRefreshHarkQuota(true);
                              }
                            }, 6000);
                          }
                        } catch (_) {}
                      }).catch(() => {});
                      return p;
                    };
                  }
                  const OrigWS = window.WebSocket;
                  if (typeof OrigWS === 'function') {
                    window.WebSocket = new Proxy(OrigWS, {
                      construct(target, args, newTarget) {
                        const ws = Reflect.construct(target, args, newTarget);
                        ws.addEventListener('message', (ev) => {
                          if (typeof ev.data === 'string') inspectText(ev.data);
                        });
                        return ws;
                      }
                    });
                  }
                }
                if (!window.__maamDownloadNameHooked) {
                  window.__maamDownloadNameHooked = true;
                  document.addEventListener('click', (ev) => {
                    try {
                      const a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
                      if (!a) return;
                      const dl = a.getAttribute('download');
                      const href = a.href || a.getAttribute('href') || '';
                      if (dl !== null || href.startsWith('blob:') || href.startsWith('data:')) {
                        window.__maamLastDownload = { url: href, name: (dl || '').trim(), ts: Date.now() };
                      }
                    } catch (_) {}
                  }, true);
                }
              } catch (_) {}
            })();
        """

        private const val ANDROID_DOWNLOAD_BRIDGE_NAME = "__maamAndroidDownload"
        private const val MAX_BLOB_DOWNLOAD_BYTES = 256L * 1024L * 1024L

        // Cue 登录页自动重试用的桌面版 Chrome UA（只换 UA，不改其他设置）。
        private const val CUE_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val DOWNLOAD_HINT_EXTENSIONS = setOf(
            "pdf", "zip", "rar", "7z", "tar", "gz", "tgz",
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
            "mp4", "mov", "webm", "mkv", "avi", "mp3", "wav", "m4a", "ogg", "flac",
            "csv", "tsv", "json", "txt", "md", "xml", "yaml", "yml",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "apk",
        )

        private fun sanitizeAndroidUserAgent(raw: String): String {
            return raw
                .replace("; wv)", ")")
                .replace(Regex("""Version/\d+(?:\.\d+)*\s+"""), "")
        }

        private fun parseContentDispositionFilename(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val extMatch = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\r\n]+)""").find(header)
            if (extMatch != null) {
                val decoded = runCatching {
                    URLDecoder.decode(extMatch.groupValues[1].trim().trim('"', '\''), "UTF-8")
                }.getOrNull()?.trim()
                if (!decoded.isNullOrEmpty()) return decoded
            }
            val plainMatch = Regex("""filename\s*=\s*(?:"([^"\r\n]+)"|([^;\r\n]+))""", RegexOption.IGNORE_CASE).find(header)
            val raw = plainMatch?.groupValues?.get(1)?.ifEmpty { plainMatch.groupValues[2] }?.trim()
            return raw?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
        }

        private fun sanitizeDownloadFilename(rawName: String?, mimeType: String?): String {
            val cleaned = (rawName ?: "")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("""[\r\n\t\u0000-\u001F]"""), "")
                .replace(Regex("""[:*?"<>|]"""), "_")
                .trim()
                .trimStart('.')
            val base = if (cleaned.isEmpty()) "download_${System.currentTimeMillis()}" else cleaned
            val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val hasExt = base.contains('.') && base.substringAfterLast('.').length in 1..8
            if (!hasExt && cleanMime.isNotEmpty() && cleanMime != "application/octet-stream") {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
                if (!ext.isNullOrEmpty()) {
                    return "$base.$ext"
                }
            }
            return base
        }

        private fun resolveMimeType(filename: String, declaredMime: String?): String {
            val trimmed = declaredMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (trimmed.isNotEmpty() && trimmed != "application/octet-stream") {
                return trimmed
            }
            val ext = filename.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            }
            return trimmed.ifEmpty { "application/octet-stream" }
        }

        private fun shouldInterceptUrlAsDownload(uri: Uri?, isRedirect: Boolean): Boolean {
            if (uri == null) return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == "data") return true
            if (scheme != "https" && scheme != "http") return false
            val queryNames = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
            if (queryNames.any {
                    it.equals("response-content-disposition", ignoreCase = true) ||
                        it.equals("rscd", ignoreCase = true) ||
                        it.equals("download", ignoreCase = true)
                }
            ) {
                return true
            }
            val disp = runCatching { uri.getQueryParameter("response-content-disposition") }.getOrNull().orEmpty()
            if (disp.contains("attachment", ignoreCase = true)) {
                return true
            }
            if (isRedirect) {
                val lastSeg = uri.lastPathSegment.orEmpty().lowercase()
                val ext = lastSeg.substringAfterLast('.', "")
                if (ext in DOWNLOAD_HINT_EXTENSIONS) {
                    return true
                }
            }
            return false
        }

        private fun isSameSiteOrAllowedHost(initialHost: String?, targetHost: String?, allowedHosts: List<String>): Boolean {
            if (initialHost.isNullOrBlank() || targetHost.isNullOrBlank()) return false
            val a = initialHost.lowercase()
            val b = targetHost.lowercase()
            if (a == b) return true
            for (allowed in allowedHosts) {
                if (allowed == "*") continue
                val clean = allowed.lowercase()
                val aMatch = a == clean || a.endsWith(".$clean")
                val bMatch = b == clean || b.endsWith(".$clean")
                if (aMatch && bMatch) return true
            }
            return false
        }

        private const val IDENTITY_PROBE_SCRIPT = QUOTA_HOOK_INIT_SCRIPT + """
            (() => {
              try {
                if (!window.__maamHooked) {
                  window.__maamHooked = true;
                  const saveVal = (el) => {
                    if (!el || typeof el.value !== 'string') return;
                    const v = el.value.trim();
                    if (/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(v)) {
                      window.__maamTyped = v.toLowerCase();
                      try { sessionStorage.setItem('__maam_typed_identity__', window.__maamTyped); } catch (_) {}
                    }
                  };
                  document.addEventListener('input', (e) => saveVal(e.target), true);
                  document.addEventListener('change', (e) => saveVal(e.target), true);
                }
                document.querySelectorAll('input').forEach((el) => {
                  const v = (el.value || '').trim();
                  if (/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(v)) {
                    window.__maamTyped = v.toLowerCase();
                    try { sessionStorage.setItem('__maam_typed_identity__', window.__maamTyped); } catch (_) {}
                  }
                });
                let typed = window.__maamTyped || '';
                try { typed = typed || sessionStorage.getItem('__maam_typed_identity__') || ''; } catch (_) {}
                const host = (location.hostname || '').toLowerCase();
                const path = (location.pathname || '').toLowerCase();
                const isCustom = Boolean(window.__maamIsCustom);
                const customHost = (window.__maamCustomHost || '').toLowerCase();
                const matchesDomain = (h, d) => Boolean(d) && (h === d || h.endsWith('.' + d));
                const baseDomain = (h) => {
                  const parts = (h || '').split('.').filter(Boolean);
                  return parts.length >= 2 ? parts.slice(-2).join('.') : h;
                };
                const customBase = isCustom ? baseDomain(customHost) : '';
                const isPrimary = isCustom
                  ? (matchesDomain(host, customHost) || matchesDomain(host, customBase))
                  : (host === 'muse.ai' || host.endsWith('.muse.ai') || host === 'app.hark.com' || host.endsWith('.hark.com'));
                const isExternalLoginHost =
                  host === 'meta.com' || host.endsWith('.meta.com') ||
                  host === 'facebook.com' || host.endsWith('.facebook.com') ||
                  host === 'appleid.apple.com' ||
                  (isCustom && (
                    matchesDomain(host, 'auth.openai.com') ||
                    matchesDomain(host, 'auth0.openai.com') ||
                    matchesDomain(host, 'accounts.anthropic.com') ||
                    matchesDomain(host, 'accounts.x.ai') ||
                    matchesDomain(host, 'login.microsoftonline.com') ||
                    matchesDomain(host, 'login.live.com') ||
                    matchesDomain(host, 'accounts.google.com')
                  ));
                const makeLoggedOut = () => {
                  window.__maamQuota = null;
                  window.__maamQuotaDetail = null;
                  try {
                    localStorage.removeItem('__maam_cached_quota__');
                    localStorage.removeItem('__maam_cached_quota_detail_v2__');
                  } catch (_) {}
                  return JSON.stringify({ auth: false, loggedOut: true, typed, email: '' });
                };
                const makeLoading = () => JSON.stringify({ auth: false, loggedOut: false, typed, email: '' });
                if (document.readyState === 'loading') {
                  return makeLoading();
                }
                const loginInputs = document.querySelectorAll('input[type="email"], input[type="password"], input[autocomplete="one-time-code"], input[placeholder*="邮箱"], input[placeholder*="手机"], input[placeholder*="email" i], input[placeholder*="phone" i]');
                const hasVisibleLoginInput = () => {
                  for (const inp of loginInputs) {
                    if (inp.disabled || inp.readOnly) continue;
                    const r = inp.getBoundingClientRect();
                    if (r.width > 20 && r.height > 15) return true;
                  }
                  return false;
                };
                const hasChatComposer = Array.from(document.querySelectorAll('textarea, [contenteditable="true"]')).some((el) => {
                  if (el.disabled || el.readOnly) return false;
                  const r = el.getBoundingClientRect();
                  return r.width > 120 && r.height > 20;
                });
                if (isExternalLoginHost) {
                  if (/^\/(login|signin|signup|auth)\b/.test(path) || hasVisibleLoginInput()) {
                    return makeLoggedOut();
                  }
                  return makeLoading();
                }
                if (!isPrimary) {
                  return makeLoading();
                }
                if (/^\/(login|signin|signup|auth)\b/.test(path)) {
                  return makeLoggedOut();
                }
                if (!hasChatComposer && !/^\/(settings|profile|account)\b/.test(path) && hasVisibleLoginInput()) {
                  return makeLoggedOut();
                }
                const bodyText = (document.body && document.body.innerText) || '';
                if (!hasChatComposer && /登录或创建账户|Log in or sign up|Sign in to|登录以继续|通过 Google 继续|Continue with Google|通过 Apple 继续|Continue with Apple/i.test(bodyText)) {
                  return makeLoggedOut();
                }
                if (document.readyState !== 'complete') {
                  return makeLoading();
                }
                const bad = /^(support|privacy|help|legal|press|noreply|no-reply|info|security|contact|abuse|feedback|sales|terms)@/i;
                const re = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g;
                const candidates = [];
                if (window.__maamApiEmail) candidates.push(window.__maamApiEmail);
                document.querySelectorAll('[data-email],[aria-label*="@"],[title*="@"],a[href^="mailto:"]').forEach((el) => {
                  const raw = (el.getAttribute('data-email') || el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('href') || '').replace(/^mailto:/i, '');
                  const m = raw.match(re);
                  if (m) candidates.push(...m);
                });
                try {
                  for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    const val = k ? localStorage.getItem(k) : '';
                    if (val && val.includes('@') && val.length < 4000) {
                      const m = val.match(re);
                      if (m) candidates.push(...m);
                    }
                  }
                } catch (_) {}
                const textMatches = bodyText.match(re);
                if (textMatches) candidates.push(...textMatches);
                const extractAvatar = () => {
                  const list = document.querySelectorAll('[data-hatch-media-owner="avatar-layer"], [data-hatch-avatar-host] img, [data-hatch-avatar-host] video, [class*="hatch-avatar"] img, [class*="avatar" i] img');
                  for (const el of list) {
                    if (el.closest && el.closest('.landing-rebus-avatar-slot')) continue;
                    const r = el.getBoundingClientRect();
                    if (r.width < 16 || r.height < 16 || r.width > 520) continue;
                    try {
                      const sw = el.tagName === 'VIDEO' ? el.videoWidth : el.naturalWidth;
                      const sh = el.tagName === 'VIDEO' ? el.videoHeight : el.naturalHeight;
                      if (!sw || !sh) continue;
                      const c = document.createElement('canvas');
                      c.width = 72; c.height = 72;
                      const ctx = c.getContext('2d');
                      if (!ctx) continue;
                      const side = Math.min(sw, sh);
                      ctx.drawImage(el, (sw - side) / 2, (sh - side) / 2, side, side, 0, 0, 72, 72);
                      const px = ctx.getImageData(0, 0, 72, 72).data;
                      let vis = 0;
                      for (let i = 3; i < px.length; i += 64) { if (px[i] > 12 && ++vis >= 4) break; }
                      if (vis < 4) continue;
                      const d = c.toDataURL('image/webp', 0.85);
                      if (d && d.startsWith('data:image/')) return d;
                    } catch (_) {}
                  }
                  return '';
                };
                if ((host === 'muse.ai' || host.endsWith('.muse.ai')) && !window.__maamQuotaInflight && (!window.__maamQuotaFetchedAt || Date.now() - window.__maamQuotaFetchedAt > 90000)) {
                  const tpGlobal = globalThis.TURBOPACK;
                  if (tpGlobal && !Array.isArray(tpGlobal) && typeof tpGlobal.push === 'function') {
                    window.__maamQuotaInflight = true;
                    (async () => {
                      try {
                        if (!window.__maamTp) {
                          tpGlobal.push(['__maam_init__', 999999991, (ctx) => { window.__maamTp = ctx; }]);
                          tpGlobal.push(['__maam_run__', { otherChunks: [], runtimeModuleIds: [999999991] }]);
                        }
                        const tp = window.__maamTp;
                        if (!tp) return;
                        if (!window.__maamSubActionId && !window.__maamScriptsScanned) {
                          window.__maamScriptsScanned = true;
                          const fetchFn = window.__maamOrigFetch || window.fetch;
                          for (const sc of document.querySelectorAll('script[src*="/_next/static/"]')) {
                            if (!sc.src) continue;
                            try {
                              const txt = await fetchFn.call(window, sc.src, { cache: 'force-cache' }).then((r) => r.text());
                              const mPre = txt.match(/\.A\((\d+)\)\.then\(\(\{preloadHatchSettingsData\b/);
                              if (mPre) window.__maamPreloadModId = parseInt(mPre[1], 10);
                              const mAct = txt.match(/createServerReference\)\("([0-9a-f]{32,64})"[^)]*"fetchSubscriptionAction"\)/);
                              if (mAct) window.__maamSubActionId = mAct[1];
                            } catch (_) {}
                          }
                        }
                        if (window.__maamPreloadModId && !window.__maamSubActionId && typeof tp.A === 'function') {
                          const origPush = tpGlobal.push.bind(tpGlobal);
                          tpGlobal.push = (...items) => {
                            for (const ch of items) {
                              if (!Array.isArray(ch)) continue;
                              for (let i = 0; i < ch.length; i++) {
                                if (typeof ch[i] === 'function') {
                                  const src = String(ch[i]);
                                  const m = src.match(/createServerReference\)\("([0-9a-f]{32,64})"[^)]*"fetchSubscriptionAction"\)/);
                                  if (m) window.__maamSubActionId = m[1];
                                }
                              }
                            }
                            return origPush(...items);
                          };
                          await tp.A(window.__maamPreloadModId).catch(() => {});
                        }
                        let callServerFn = null;
                        if (tp.c && typeof tp.c.values === 'function') {
                          for (const m of tp.c.values()) {
                            if (m && m.exports && typeof m.exports.callServer === 'function') {
                              callServerFn = m.exports.callServer;
                              break;
                            }
                          }
                        }
                        const aid = window.__maamSubActionId || '40e0249eeae158685b6a8d0c3b203f53dd4f2c7d63';
                        if (callServerFn && aid && typeof window.__maamMapSub === 'function') {
                          const res = await callServerFn(aid, [{ includeAgreement: true }]);
                          const mapped = window.__maamMapSub(res);
                          if (mapped) {
                            window.__maamQuotaFetchedAt = Date.now();
                            if (typeof window.__maamSaveDetail === 'function') {
                              window.__maamSaveDetail(mapped);
                            } else {
                              window.__maamQuotaDetail = mapped;
                              try { localStorage.setItem('__maam_cached_quota_detail_v2__', JSON.stringify(mapped)); } catch (_) {}
                            }
                          }
                        }
                      } catch (_) {
                      } finally {
                        window.__maamQuotaInflight = false;
                      }
                    })();
                  }
                }
                if ((host === 'app.hark.com' || host.endsWith('.hark.com')) && typeof window.__maamRefreshHarkQuota === 'function') {
                  window.__maamRefreshHarkQuota(false);
                }
                let qText = isCustom ? '' : '正在同步额度…';
                let qPct = -1;
                let qDetailStr = '';
                let dObj = isCustom ? null : (window.__maamQuotaDetail || null);
                if (!isCustom && !dObj) {
                  try {
                    const cached = localStorage.getItem('__maam_cached_quota_detail_v2__');
                    if (cached) dObj = JSON.parse(cached);
                  } catch (_) {}
                }
                if ((!dObj || !dObj.planLabel) && host.endsWith('hark.com')) {
                  const mDaily = bodyText.match(/(?:已使用每日上限的\s*(\d+(?:\.\d+)?)\s*%|(\d+(?:\.\d+)?)\s*%\s*of\s*daily\s*limit\s*used)/i);
                  const mMonthly = bodyText.match(/(?:已使用每月上限的\s*(\d+(?:\.\d+)?)\s*%|(\d+(?:\.\d+)?)\s*%\s*of\s*monthly\s*limit\s*used)/i);
                  if (mDaily || mMonthly) {
                    const dPct = mDaily ? Math.max(0, Math.min(100, Math.round(parseFloat(mDaily[1] || mDaily[2])))) : null;
                    const mPct = mMonthly ? Math.max(0, Math.min(100, Math.round(parseFloat(mMonthly[1] || mMonthly[2])))) : null;
                    const mDailyReset = bodyText.match(/(?:每日重置|Daily reset)[\s:：·]*([^\n\r]{2,28})/i);
                    const mMonthlyReset = bodyText.match(/(?:每月重置|Monthly reset)[\s:：·]*([^\n\r]{2,28})/i);
                    const fmtDomReset = (m) => {
                      if (!m || !m[1]) return null;
                      const s = m[1].trim();
                      return s ? (s.endsWith('重置') ? s : (s + ' 重置')) : null;
                    };
                    dObj = dPct !== null
                      ? {
                          planLabel: '每日上限',
                          planValue: '已使用 ' + dPct + '%',
                          planSubtext: fmtDomReset(mDailyReset),
                          planUsedPercent: dPct,
                          extraLabel: mPct !== null ? '每月上限' : null,
                          extraValue: mPct !== null ? ('已使用 ' + mPct + '%') : null,
                          extraSubtext: mPct !== null ? fmtDomReset(mMonthlyReset) : null,
                          extraUsedPercent: mPct
                        }
                      : {
                          planLabel: '每月上限',
                          planValue: '已使用 ' + mPct + '%',
                          planSubtext: fmtDomReset(mMonthlyReset),
                          planUsedPercent: mPct,
                          extraLabel: null,
                          extraValue: null,
                          extraSubtext: null,
                          extraUsedPercent: null
                        };
                    if (typeof window.__maamSaveDetail === 'function') window.__maamSaveDetail(dObj);
                  }
                }
                if ((!dObj || !dObj.planLabel) && host.endsWith('hark.com')) {
                  const creditEls = document.querySelectorAll('[class*="credit" i], [class*="usage" i], [data-testid*="credit"], [data-testid*="usage"]');
                  for (const el of creditEls) {
                    const txt = (el.innerText || '').trim();
                    const m = txt.match(/(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)/);
                    if (m) {
                      const cur = parseFloat(m[1]);
                      const max = parseFloat(m[2]);
                      if (max > 0) {
                        const pct = Math.max(0, Math.min(100, Math.round((cur / max) * 100)));
                        dObj = {
                          planLabel: 'Hark 用量',
                          planValue: '已使用 ' + pct + '%',
                          planSubtext: '当前 ' + cur + ' / ' + max,
                          planUsedPercent: pct,
                          extraLabel: null,
                          extraValue: null,
                          extraSubtext: null,
                          extraUsedPercent: null
                        };
                        if (typeof window.__maamSaveDetail === 'function') window.__maamSaveDetail(dObj);
                        break;
                      }
                    }
                  }
                }
                if (!isCustom && dObj && dObj.planLabel) {
                  window.__maamQuotaDetail = dObj;
                  qText = dObj.planValue || dObj.planLabel;
                  if (typeof dObj.planUsedPercent === 'number') qPct = Math.max(0, 100 - dObj.planUsedPercent);
                  qDetailStr = JSON.stringify(dObj);
                } else if (!isCustom) {
                  let qObj = window.__maamQuota || null;
                  if (!qObj) {
                    try {
                      const cachedQ = localStorage.getItem('__maam_cached_quota__');
                      if (cachedQ) qObj = JSON.parse(cachedQ);
                    } catch (_) {}
                  }
                  if (qObj && qObj.text) {
                    window.__maamQuota = qObj;
                    qText = qObj.text;
                    if (typeof qObj.pct === 'number') qPct = qObj.pct;
                  }
                }
                const av = extractAvatar();
                for (const c of candidates) {
                  const clean = c.toLowerCase();
                  if (!bad.test(clean) && !clean.endsWith('@example.com') && !clean.endsWith('@sentry.io')) {
                    return JSON.stringify({ auth: true, loggedOut: false, typed, email: clean, avatar: av, quotaText: qText, quotaPct: qPct, quotaDetail: qDetailStr });
                  }
                }
                return JSON.stringify({ auth: true, loggedOut: false, typed, email: '', avatar: av, quotaText: qText, quotaPct: qPct, quotaDetail: qDetailStr });
              } catch (_) {
                return JSON.stringify({ auth: false, loggedOut: false, typed: '', email: '' });
              }
            })()
        """
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AccountSessionCoordinator.attachActivity(this)
        buildNativeHierarchy()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (popupWebView != null) {
                        destroyPopupWebView()
                        return
                    }
                    returnToAccountList()
                }
            },
        )

        val resolvedSpec = if (savedInstanceState != null) {
            AccountSessionCoordinator.recoverSpecOnActivityRecreate()
        } else {
            AccountSessionCoordinator.currentActiveSpec()
        }

        if (resolvedSpec == null) {
            closingFromManager = true
            finish()
            return
        }

        bindSessionSpec(resolvedSpec)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(Bundle())
    }

    override fun onResume() {
        super.onResume()
        mainWebView?.onResume()
        popupWebView?.onResume()
        if (mainInputEnabled) currentSpec?.let(::startIdentityProbeLoop)
    }

    override fun onPause() {
        stopIdentityProbeLoop()
        // 0.2.8：后台回合不暂停 WebView，否则成员页的 JS 定时器停掉，
        // 回合的发送/轮询/流式回复全断；前台页面照旧暂停。
        if (!keepWebViewAlive) {
            mainWebView?.onPause()
            popupWebView?.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        val spec = currentSpec
        cancelDownloadBannerTimer()
        releaseSessionViews()
        downloadExecutor.shutdownNow()
        AccountSessionCoordinator.detachActivity(this)
        if (!closingFromManager && isFinishing && spec != null) {
            AccountSessionCoordinator.emitActivityClosed(spec.switchSeq, spec.viewInstanceId)
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    /**
     * Cue 登录页打不开时的中文错误卡（盖住 CloudFront 原生 403 白页）。
     * 只在 Cue 页面加载失败时展示；Muse/Hark 页面不受影响。
     * 四个按钮：[刷新] [在浏览器中打开] [导入 Cookie] [换节点后重试]，
     * 其中「换节点后重试」只是文案提示，点它等于刷新。
     */
    private fun buildCueErrorCard(): FrameLayout {
        cueErrorBodyView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#374151"))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            }
        }

        val titleView = TextView(this).apply {
            text = "Cue 登录页打不开"
            setTextColor(Color.parseColor("#111827"))
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fun cueButton(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { onClick() }
            }
        }

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
            }
        }
        firstRow.addView(cueButton("刷新") { retryCueManually() })
        firstRow.addView(cueButton("在浏览器中打开") { openCueLoginInBrowser() })

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            }
        }
        secondRow.addView(cueButton("导入 Cookie") { returnToAccountList() })
        secondRow.addView(cueButton("换节点后重试") { retryCueManually() })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                setMargins(dp(24), dp(24), dp(24), dp(24))
            }
        }
        card.addView(titleView)
        card.addView(cueErrorBodyView)
        card.addView(firstRow)
        card.addView(secondRow)

        cueErrorCardRoot = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        cueErrorCardRoot.addView(card)
        return cueErrorCardRoot
    }

    private fun isCuePageUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(rawUrl).host?.lowercase() }.getOrNull() ?: return false
        return host == "cue.im" || host.endsWith(".cue.im") ||
            host == "manus.im" || host.endsWith(".manus.im")
    }

    /**
     * 只处理 Cue（cue.im / manus.im）主页面的加载失败；Muse/Hark 页面
     * 不进这里，保持原有行为。
     * 403 是 Cue 的 CloudFront 在对方拦了当前网络（常因 VPN 出口 IP），
     * App 侧绕不过去：先自动换桌面版 Chrome UA 重载一次，再试
     * https://cue.im/ 根地址一次，各最多一次；两次都失败才展示错误卡，
     * 写明换个 VPN 节点最可能解决。
     */
    private fun handleCueLoadFailure(
        spec: AndroidAccountSessionSpec,
        failingUrl: String?,
        httpStatus: Int?,
    ) {
        if (currentSpec !== spec) return
        if (!isCuePageUrl(failingUrl)) return
        if (cueErrorShown || cueFailureHandling) return
        val wv = mainWebView ?: return
        cueFailedUrl = failingUrl
        cueFailureHandling = true
        if (!cueRetryDesktopUaTried) {
            cueRetryDesktopUaTried = true
            val ok = runCatching { retryCueWithDesktopUa(failingUrl) }.isSuccess
            if (!ok) {
                cueFailureHandling = false
                restoreCueUserAgent()
                showCueErrorCard(httpStatus)
            }
            return
        }
        if (!cueRetryRootTried) {
            cueRetryRootTried = true
            restoreCueUserAgent()
            val ok = runCatching {
                cueRetryInFlight = true
                wv.loadUrl("https://cue.im/")
            }.isSuccess
            if (!ok) {
                cueFailureHandling = false
                showCueErrorCard(httpStatus)
            }
            return
        }
        restoreCueUserAgent()
        showCueErrorCard(httpStatus)
    }

    private fun retryCueWithDesktopUa(failingUrl: String?) {
        val wv = mainWebView ?: return
        if (cueOriginalUserAgent == null) {
            cueOriginalUserAgent = wv.settings.userAgentString
        }
        wv.settings.userAgentString = CUE_DESKTOP_USER_AGENT
        cueRetryInFlight = true
        val target = failingUrl?.takeIf { it.isNotBlank() } ?: currentSpec?.entryUrl ?: return
        wv.loadUrl(target)
    }

    private fun restoreCueUserAgent() {
        val original = cueOriginalUserAgent ?: return
        cueOriginalUserAgent = null
        runCatching { mainWebView?.settings?.userAgentString = original }
    }

    private fun showCueErrorCard(httpStatus: Int?) {
        cueErrorShown = true
        loadingView.visibility = View.GONE
        // 保持 WebView 不可见，确保 CloudFront 原生错误白页不会露出来。
        mainWebView?.visibility = View.INVISIBLE
        cueErrorBodyView.text = when {
            httpStatus == 403 -> "对方服务器拒绝了当前网络（CloudFront 返回 403），不是 App 拦的。换个 VPN 节点最可能解决。"
            httpStatus != null -> "Cue 登录页返回了 HTTP $httpStatus，对方服务器拒绝了当前网络。换个 VPN 节点最可能解决。"
            else -> "Cue 登录页加载失败，当前网络连不上对方服务器。换个 VPN 节点最可能解决。"
        }
        cueErrorCardRoot.visibility = View.VISIBLE
    }

    private fun hideCueErrorCard() {
        cueErrorShown = false
        cueErrorCardRoot.visibility = View.GONE
    }

    private fun resetCueFailureState() {
        cueRetryDesktopUaTried = false
        cueRetryRootTried = false
        cueRetryInFlight = false
        cueFailureHandling = false
        cueFailedUrl = null
        restoreCueUserAgent()
        if (::cueErrorCardRoot.isInitialized) {
            hideCueErrorCard()
        }
    }

    /** 手动刷新 / 换节点后重试：恢复默认 UA、清掉重试计数，重新加载当前页。 */
    private fun retryCueManually() {
        resetCueFailureState()
        val target = mainWebView ?: return
        if (target.url.isNullOrBlank()) {
            currentSpec?.let { target.loadUrl(it.entryUrl) }
        } else {
            target.reload()
        }
    }

    private fun openCueLoginInBrowser() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://cue.im/login")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNativeHierarchy() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EDEDED"))
            setPadding(dp(10), dp(6), dp(12), dp(6))
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val backBtn = Button(this).apply {
            text = "← 账号"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                if (popupWebView != null) {
                    destroyPopupWebView()
                } else {
                    returnToAccountList()
                }
            }
        }

        headerTitleView = TextView(this).apply {
            text = "正在核验会话…"
            setTextColor(Color.parseColor("#191919"))
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        val refreshBtn = Button(this).apply {
            text = "刷新"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val target = popupWebView ?: mainWebView
                if (target != null) {
                    if (target.url.isNullOrBlank()) {
                        currentSpec?.let { target.loadUrl(it.entryUrl) }
                    } else {
                        target.reload()
                    }
                }
            }
        }

        topBar.addView(backBtn)
        topBar.addView(headerTitleView)
        topBar.addView(refreshBtn)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        loadingView = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(36),
                dp(36),
                Gravity.CENTER,
            )
        }

        mainWebContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        contentFrame.addView(loadingView)
        contentFrame.addView(mainWebContainer)
        contentFrame.addView(buildCueErrorCard())
        column.addView(topBar)
        column.addView(contentFrame)

        popupOverlayRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val popupHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2F5FA"))
            setPadding(dp(12), dp(6), dp(10), dp(6))
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        popupDomainLabel = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#111827"))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        val closePopupBtn = Button(this).apply {
            text = "关闭认证"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                destroyPopupWebView()
            }
        }

        popupHeader.addView(popupDomainLabel)
        popupHeader.addView(closePopupBtn)

        popupWebContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        popupOverlayRoot.addView(popupHeader)
        popupOverlayRoot.addView(popupWebContainer)

        downloadBannerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            }
        }

        downloadBannerText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#111827"))
            textSize = 12.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        downloadOpenBtn = Button(this).apply {
            text = "打开"
            textSize = 12f
            isAllCaps = false
            visibility = View.GONE
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openLastDownloadedFile() }
        }

        downloadDirBtn = Button(this).apply {
            text = "下载目录"
            textSize = 12f
            isAllCaps = false
            visibility = View.GONE
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openDownloadsFolder() }
        }

        val closeBannerBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { hideDownloadBanner() }
        }

        downloadBannerRoot.addView(downloadBannerText)
        downloadBannerRoot.addView(downloadOpenBtn)
        downloadBannerRoot.addView(downloadDirBtn)
        downloadBannerRoot.addView(closeBannerBtn)

        root.addView(column)
        root.addView(popupOverlayRoot)
        root.addView(downloadBannerRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)
            topBar.minimumHeight = dp(52) + sysBars.top
            topBar.setPadding(
                dp(10) + sysBars.left,
                dp(6) + sysBars.top,
                dp(12) + sysBars.right,
                dp(6),
            )
            popupHeader.minimumHeight = dp(48) + sysBars.top
            popupHeader.setPadding(
                dp(12) + sysBars.left,
                dp(6) + sysBars.top,
                dp(10) + sysBars.right,
                dp(6),
            )
            contentFrame.setPadding(sysBars.left, 0, sysBars.right, bottomInset)
            popupWebContainer.setPadding(sysBars.left, 0, sysBars.right, bottomInset)
            val lp = downloadBannerRoot.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(sysBars.left + dp(10), 0, sysBars.right + dp(10), bottomInset + dp(10))
            downloadBannerRoot.layoutParams = lp
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun bindSessionSpec(spec: AndroidAccountSessionSpec) {
        releaseSessionViews(keepPage = true)
        resetCueFailureState()

        currentSpec = spec
        // 0.2.8：后台回合保持 WebView 活跃；前台打开（切账号）时清掉。
        keepWebViewAlive = spec.background
        activeSessionGeneration.incrementAndGet()
        mainInputEnabled = false
        headerTitleView.text = "正在核验会话…"
        loadingView.visibility = View.VISIBLE

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AccountSessionCoordinator.emitSessionFailed(
                spec.switchSeq,
                spec.viewInstanceId,
                "设备系统 WebView 不支持 MULTI_PROFILE",
            )
            closingFromManager = true
            finish()
            return
        }

        val cached = RecentAccountPages.take(this, spec)
        val wv = cached ?: WebView(MutableContextWrapper(applicationContext))
        try {
            if (cached == null) WebViewCompat.setProfile(wv, spec.uuid)
            val actualProfile = WebViewCompat.getProfile(wv).name
            if (actualProfile != spec.uuid) {
                wv.destroy()
                AccountSessionCoordinator.emitSessionFailed(
                    spec.switchSeq,
                    spec.viewInstanceId,
                    "Android WebView Profile 核对不匹配，已阻止加载",
                )
                closingFromManager = true
                finish()
                return
            }
        } catch (t: Throwable) {
            wv.destroy()
            AccountSessionCoordinator.emitSessionFailed(
                spec.switchSeq,
                spec.viewInstanceId,
                "绑定 Android WebView Profile 失败: ${t.message ?: "unknown"}",
            )
            closingFromManager = true
            finish()
            return
        }

        (wv.context as MutableContextWrapper).baseContext = this
        wv.visibility = View.INVISIBLE
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.setOnTouchListener { _, _ -> !mainInputEnabled }

        configureAccountWebView(
            wv = wv,
            spec = spec,
            supportMultipleWindows = true,
            applyInitialSettings = cached == null,
            isPopup = false,
        )

        var mainLoadFailed = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== mainWebView || currentSpec !== spec) return
                // Cue 失败重试中的加载不重置重试计数；其余新导航恢复默认 UA
                // 并清掉错误卡（用户手动刷新也重新获得一次自动重试额度）。
                cueFailureHandling = false
                if (cueRetryInFlight) {
                    cueRetryInFlight = false
                } else {
                    resetCueFailureState()
                }
                mainLoadFailed = false
                committedAuthority = null
                spec.timezoneInitScript?.takeIf { it.isNotBlank() }?.let { tzScript ->
                    view.evaluateJavascript(tzScript, null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === mainWebView && currentSpec === spec && request.isForMainFrame) {
                    mainLoadFailed = true
                    committedAuthority = null
                    handleCueLoadFailure(spec, request.url?.toString(), null)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (view === mainWebView && currentSpec === spec && request.isForMainFrame) {
                    mainLoadFailed = true
                    committedAuthority = null
                    if (errorResponse.statusCode >= 400) {
                        handleCueLoadFailure(spec, request.url?.toString(), errorResponse.statusCode)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = request.url?.scheme?.lowercase() ?: ""
                if (scheme !in setOf("http", "https", "about", "blob", "data")) {
                    return true
                }
                if (!request.isForMainFrame) {
                    return false
                }
                if (!ownsMainView(view, spec)) return true
                val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.isRedirect else false
                return decideMainFrameOverride(view, spec, request.url, isRedirect, isPopup = false)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!ownsMainView(view, spec)) return
                val boundName = try {
                    WebViewCompat.getProfile(view).name
                } catch (ignored: Throwable) {
                    ""
                }
                val uri = Uri.parse(url)
                if (boundName != spec.uuid ||
                    !AccountSessionCoordinator.hostMatchesAllowed(uri, spec.allowedHosts)
                ) {
                    AccountSessionCoordinator.emitSessionFailed(
                        spec.switchSeq,
                        spec.viewInstanceId,
                        "顶层页面来源或资料归属核验未通过，已回退管理页",
                    )
                    closeAndFinishFromManager()
                    return
                }

                committedAuthority = if (mainLoadFailed) null else uri.encodedAuthority
                AccountSessionCoordinator.emitPageCommitVisible(spec, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!ownsMainView(view, spec)) return
                // 0.2.9：后台 WebView 不可见时 onPageCommitVisible 不触发，
                // 页面加载完成信号作为第二就绪信号，Rust 侧同等对待。
                if (url != null && url != "about:blank") {
                    AccountSessionCoordinator.emitPageLoadFinished(spec, url)
                }
                val runnable = identityProbeRunnable
                if (runnable != null && mainInputEnabled &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !probeAwaitingConfirm
                ) {
                    uiHandler.removeCallbacks(runnable)
                    uiHandler.post(runnable)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === mainWebView && currentSpec === spec) {
                    AccountSessionCoordinator.emitSessionFailed(spec.switchSeq, spec.viewInstanceId, "账号页面已被系统回收，请重新打开")
                    closeAndFinishFromManager()
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (!isUserGesture || resultMsg == null || popupWebView != null) {
                    return false
                }
                if (!ownsMainView(view, spec)) return false
                return createBoundAuthPopup(spec, resultMsg)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                handleWebPermissionRequest(request)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (!ownsMainView(webView, spec)) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                cancelPendingFileChooser()
                pendingFilePathCallback = filePathCallback
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val primaryIntent = runCatching {
                    fileChooserParams?.createIntent()?.apply {
                        if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }.getOrNull()
                if (primaryIntent != null && runCatching { fileChooserLauncher.launch(primaryIntent) }.isSuccess) {
                    return true
                }
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                if (runCatching { fileChooserLauncher.launch(fallbackIntent) }.isSuccess) {
                    return true
                }
                cancelPendingFileChooser()
                return false
            }
        }

        mainWebView = wv
        mainWebContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val restoredUrl = cached?.url
        if (restoredUrl == null) {
            wv.loadUrl(spec.entryUrl)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            wv.onResume()
            committedAuthority = Uri.parse(restoredUrl).encodedAuthority
            AccountSessionCoordinator.emitPageCommitVisible(spec, restoredUrl)
        }
    }

    private fun ownsMainView(view: WebView, spec: AndroidAccountSessionSpec) =
        view === mainWebView && currentSpec === spec &&
            AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createBoundAuthPopup(
        spec: AndroidAccountSessionSpec,
        resultMsg: Message,
    ): Boolean {
        val popup = WebView(this)
        try {
            WebViewCompat.setProfile(popup, spec.uuid)
            if (WebViewCompat.getProfile(popup).name != spec.uuid) {
                popup.destroy()
                return false
            }
        } catch (ignored: Throwable) {
            popup.destroy()
            return false
        }

        popupInputEnabled = false
        popup.visibility = View.INVISIBLE
        popup.isFocusable = false
        popup.isFocusableInTouchMode = false
        popup.setOnTouchListener { _, _ -> !popupInputEnabled }

        configureAccountWebView(
            wv = popup,
            spec = spec,
            supportMultipleWindows = false,
            applyInitialSettings = true,
            isPopup = true,
        )

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = request.url?.scheme?.lowercase() ?: ""
                if (scheme !in setOf("http", "https", "about", "blob", "data")) {
                    return true
                }
                if (!request.isForMainFrame) {
                    return false
                }
                if (view !== popupWebView || !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
                    return true
                }
                val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.isRedirect else false
                return decideMainFrameOverride(view, spec, request.url, isRedirect, isPopup = true)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== popupWebView || !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
                    return
                }
                spec.timezoneInitScript?.takeIf { it.isNotBlank() }?.let { tzScript ->
                    view.evaluateJavascript(tzScript, null)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view !== popupWebView || !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
                    return
                }
                if (url == "about:blank") {
                    return
                }
                val boundName = try {
                    WebViewCompat.getProfile(view).name
                } catch (ignored: Throwable) {
                    ""
                }
                val uri = Uri.parse(url)
                if (boundName != spec.uuid ||
                    !AccountSessionCoordinator.hostMatchesAllowed(uri, spec.allowedHosts)
                ) {
                    destroyPopupWebView()
                    return
                }
                val host = uri.host ?: ""
                popupDomainLabel.text = "认证域名 · $host"
                popupOverlayRoot.visibility = View.VISIBLE
                view.visibility = View.VISIBLE
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                popupInputEnabled = true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === popupWebView) destroyPopupWebView()
                return true
            }
        }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                if (window === popupWebView) destroyPopupWebView()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                handleWebPermissionRequest(request)
            }
        }

        popupWebView = popup
        popupDomainLabel.text = ""
        popupOverlayRoot.visibility = View.GONE
        popupWebContainer.removeAllViews()
        popupWebContainer.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
            destroyPopupWebView()
            return false
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    fun revealCommittedSession(switchSeq: Long, viewInstanceId: Long, title: String) {
        val spec = currentSpec ?: return
        if (spec.switchSeq != switchSeq || spec.viewInstanceId != viewInstanceId) {
            return
        }
        val wv = mainWebView ?: return
        headerTitleView.text = title
        loadingView.visibility = View.GONE
        wv.visibility = View.VISIBLE
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        mainInputEnabled = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startIdentityProbeLoop(spec)
    }

    /**
     * 在主 WebView 上执行 JS。必须在主线程调用。
     * 有可用 WebView 时返回 true，原始回调结果（JSON 编码）交给 [callback]；
     * 没有可用 WebView 时返回 false。
     */
    fun evaluateJavascriptOnMainWebView(script: String, callback: (String?) -> Unit): Boolean {
        val view = mainWebView ?: return false
        view.evaluateJavascript(script) { raw -> callback(raw) }
        return true
    }

    private fun stopIdentityProbeLoop() {
        identityProbeRunnable?.let { uiHandler.removeCallbacks(it) }
        identityProbeRunnable = null
        probeAwaitingConfirm = false
    }

    private data class ProbeData(
        val auth: Boolean,
        val loggedOut: Boolean,
        val typed: String,
        val email: String,
        val avatar: String,
        val quotaText: String,
        val quotaPct: Int?,
        val quotaDetailJson: String,
    )

    private fun buildProbeScript(spec: AndroidAccountSessionSpec): String {
        val isCustom = spec.providerId != "muse" && spec.providerId != "hark"
        if (!isCustom) return IDENTITY_PROBE_SCRIPT
        val customHost = runCatching { Uri.parse(spec.entryUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        return "window.__maamIsCustom = true; window.__maamCustomHost = ${JSONObject.quote(customHost)};\n$IDENTITY_PROBE_SCRIPT"
    }

    private fun startIdentityProbeLoop(spec: AndroidAccountSessionSpec) {
        stopIdentityProbeLoop()
        val probeScript = buildProbeScript(spec)
        var consecutiveLoggedOut = 0
        var loggedOutReported = false
        val task = object : Runnable {
            override fun run() {
                if (!AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
                    stopIdentityProbeLoop()
                    return
                }
                val popup = popupWebView
                popup?.evaluateJavascript(probeScript) { raw ->
                    if (identityProbeRunnable !== this || popup !== popupWebView ||
                        !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) return@evaluateJavascript
                    parseProbeOutput(raw)?.let { parsed ->
                        if (parsed.typed.isNotEmpty()) {
                            capturedIdentityHint = parsed.typed
                        }
                    }
                }
                mainWebView?.evaluateJavascript(probeScript) { raw ->
                    if (identityProbeRunnable !== this ||
                        !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) return@evaluateJavascript
                    val parsed = parseProbeOutput(raw)
                    if (parsed != null) {
                        if (parsed.typed.isNotEmpty()) {
                            capturedIdentityHint = parsed.typed
                        }
                        if (parsed.auth) {
                            consecutiveLoggedOut = 0
                            probeAwaitingConfirm = false
                            loggedOutReported = false
                            val resolved = parsed.email.ifEmpty { capturedIdentityHint.orEmpty() }
                            AccountSessionCoordinator.emitIdentityDetected(
                                spec,
                                resolved,
                                parsed.avatar.ifEmpty { null },
                                parsed.quotaText.ifEmpty { null },
                                parsed.quotaPct,
                                parsed.quotaDetailJson.ifEmpty { null },
                            )
                        } else if (parsed.loggedOut) {
                            consecutiveLoggedOut += 1
                            if (consecutiveLoggedOut == 1) {
                                probeAwaitingConfirm = true
                                uiHandler.removeCallbacks(this)
                                uiHandler.postDelayed(this, 350L)
                            } else if (consecutiveLoggedOut >= 2 && !loggedOutReported) {
                                probeAwaitingConfirm = false
                                loggedOutReported = true
                                AccountSessionCoordinator.emitSessionLoggedOut(spec)
                            } else {
                                probeAwaitingConfirm = false
                            }
                        } else {
                            consecutiveLoggedOut = 0
                            probeAwaitingConfirm = false
                        }
                    }
                }
                uiHandler.postDelayed(this, 4500L)
            }
        }
        identityProbeRunnable = task
        uiHandler.postDelayed(task, 250L)
    }

    private fun parseProbeOutput(raw: String?): ProbeData? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: return null
            val obj = JSONObject(decoded)
            val pctRaw = obj.optInt("quotaPct", -1)
            ProbeData(
                auth = obj.optBoolean("auth", false),
                loggedOut = obj.optBoolean("loggedOut", false),
                typed = obj.optString("typed", ""),
                email = obj.optString("email", ""),
                avatar = obj.optString("avatar", ""),
                quotaText = obj.optString("quotaText", ""),
                quotaPct = if (pctRaw in 0..100) pctRaw else null,
                quotaDetailJson = obj.optString("quotaDetail", ""),
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    fun closeAndFinishFromManager(keepPage: Boolean = false) {
        closingFromManager = true
        releaseSessionViews(keepPage)
        if (!isFinishing) {
            finish()
        }
    }

    private fun returnToAccountList() {
        val spec = currentSpec
        closingFromManager = true
        releaseSessionViews(keepPage = true)
        if (spec != null) {
            AccountSessionCoordinator.emitActivityClosed(spec.switchSeq, spec.viewInstanceId)
        }
        finish()
    }

    private fun cancelPendingFileChooser() {
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
    }

    private fun destroyPopupWebView() {
        val popup = popupWebView
        popupWebView = null
        popupInputEnabled = false
        popupOverlayRoot.visibility = View.GONE
        popupDomainLabel.text = ""
        popupWebContainer.removeAllViews()
        popup?.setDownloadListener(null)
        popup?.removeJavascriptInterface(ANDROID_DOWNLOAD_BRIDGE_NAME)
        popup?.destroy()
    }

    private fun releaseSessionViews(keepPage: Boolean = false) {
        val spec = currentSpec
        val view = mainWebView
        val retain = keepPage && mainInputEnabled && popupWebView == null && committedAuthority != null &&
            view?.url?.let(Uri::parse)?.encodedAuthority == committedAuthority
        activeSessionGeneration.incrementAndGet()
        cancelAllBlobTransfers()
        mainWebView = null
        currentSpec = null
        committedAuthority = null
        stopIdentityProbeLoop()
        cancelPendingFileChooser()
        capturedIdentityHint = null
        mainInputEnabled = false
        destroyPopupWebView()
        mainWebContainer.removeAllViews()
        if (view == null) return
        view.setDownloadListener(null)
        view.removeJavascriptInterface(ANDROID_DOWNLOAD_BRIDGE_NAME)
        if (retain && spec != null) {
            view.visibility = View.INVISIBLE
            view.clearFocus()
            view.setOnTouchListener(null)
            view.webChromeClient = null
            RecentAccountPages.park(view, spec)
        } else {
            view.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureAccountWebView(
        wv: WebView,
        spec: AndroidAccountSessionSpec,
        supportMultipleWindows: Boolean,
        applyInitialSettings: Boolean,
        isPopup: Boolean,
    ) {
        runCatching {
            val cm = WebViewCompat.getProfile(wv).cookieManager
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        }
        if (applyInitialSettings) {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(supportMultipleWindows)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val cleanUa = sanitizeAndroidUserAgent(userAgentString)
                if (cleanUa != userAgentString) {
                    userAgentString = cleanUa
                }
            }
            if (!isPopup && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(
                        wv,
                        QUOTA_HOOK_INIT_SCRIPT,
                        setOf(
                            "https://muse.ai",
                            "https://*.muse.ai",
                            "https://app.hark.com",
                            "https://*.hark.com",
                        ),
                    )
                }
            }
            spec.timezoneInitScript?.takeIf { it.isNotBlank() }?.let { tzScript ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            wv,
                            tzScript,
                            setOf("*"),
                        )
                    }
                }
            }
        }
        wv.addJavascriptInterface(blobBridge, ANDROID_DOWNLOAD_BRIDGE_NAME)
        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownloadRequest(wv, spec, url, userAgent, contentDisposition, mimeType, isPopup)
        })
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val protectedOnly = request.resources
            ?.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            ?.toTypedArray()
        if (!protectedOnly.isNullOrEmpty()) {
            request.grant(protectedOnly)
        } else {
            request.deny()
        }
    }

    private fun decideMainFrameOverride(
        view: WebView,
        spec: AndroidAccountSessionSpec,
        uri: Uri,
        isRedirect: Boolean,
        isPopup: Boolean,
    ): Boolean {
        val raw = uri.toString()
        if (isPopup && raw == "about:blank") {
            return false
        }
        if (raw == "about:srcdoc") {
            return false
        }
        if (raw.startsWith("blob:https://", ignoreCase = true)) {
            val innerUri = runCatching { Uri.parse(raw.substring(5)) }.getOrNull()
            val allowed = AccountSessionCoordinator.hostMatchesAllowed(innerUri, spec.allowedHosts)
            if (!allowed && isPopup) destroyPopupWebView()
            return !allowed
        }
        if (AccountSessionCoordinator.isGoogleOAuthHost(uri) && !spec.allowedHosts.any { it == "*" }) {
            if (isPopup) destroyPopupWebView()
            AccountSessionCoordinator.emitExternalAuthUnavailable(
                spec.switchSeq,
                spec.viewInstanceId,
                "Google OAuth 按政策需使用系统浏览器，请使用邮箱/手机号登录，或在账号设置中通过「导入 Cookie」载入登录态",
            )
            return true
        }
        if (shouldInterceptUrlAsDownload(uri, isRedirect)) {
            handleDownloadRequest(
                view = view,
                spec = spec,
                url = raw,
                userAgent = view.settings.userAgentString,
                contentDisposition = null,
                mimeType = null,
                isPopup = isPopup,
            )
            return true
        }
        val allowed = AccountSessionCoordinator.hostMatchesAllowed(uri, spec.allowedHosts)
        if (!allowed && isPopup) {
            destroyPopupWebView()
        }
        return !allowed
    }

    private fun handleDownloadRequest(
        view: WebView,
        spec: AndroidAccountSessionSpec,
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        isPopup: Boolean,
    ) {
        if (url.isNullOrBlank()) return
        if (currentSpec !== spec || !AccountSessionCoordinator.isCurrent(spec.switchSeq, spec.viewInstanceId)) {
            return
        }
        val gen = activeSessionGeneration.get()
        if (isPopup && popupOverlayRoot.visibility != View.VISIBLE && !url.startsWith("blob:", ignoreCase = true)) {
            destroyPopupWebView()
        }
        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                val defaultName = sanitizeDownloadFilename(
                    parseContentDispositionFilename(contentDisposition),
                    mimeType,
                )
                startBlobDownload(view, gen, url, defaultName, mimeType, isPopup)
            }
            url.startsWith("data:", ignoreCase = true) -> {
                startDataDownload(gen, url, contentDisposition, mimeType)
            }
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> {
                val guessed = parseContentDispositionFilename(contentDisposition)
                    ?: runCatching { URLUtil.guessFileName(url, contentDisposition, mimeType) }.getOrNull()
                val initialName = sanitizeDownloadFilename(guessed, mimeType)
                val profileCookies = runCatching {
                    WebViewCompat.getProfile(view).cookieManager
                }.getOrNull()
                val refererUrl = view.url
                val effectiveUa = userAgent?.takeIf { it.isNotBlank() } ?: view.settings.userAgentString
                startHttpDownload(
                    sessionGen = gen,
                    spec = spec,
                    rawUrl = url,
                    initialName = initialName,
                    declaredMime = mimeType,
                    userAgent = effectiveUa,
                    refererUrl = refererUrl,
                    profileCookieManager = profileCookies,
                )
            }
            else -> {
                showDownloadStatus("暂不支持此下载链接协议", canOpen = false, showDir = false, isError = true, autoHide = true)
            }
        }
    }

    private fun startHttpDownload(
        sessionGen: Long,
        spec: AndroidAccountSessionSpec,
        rawUrl: String,
        initialName: String,
        declaredMime: String?,
        userAgent: String,
        refererUrl: String?,
        profileCookieManager: android.webkit.CookieManager?,
    ) {
        showDownloadStatus("正在下载：$initialName…", canOpen = false, showDir = false, isError = false, autoHide = false)
        downloadExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val initialParsed = URL(rawUrl)
                var currentUrl = initialParsed
                var activeConn: HttpURLConnection? = null
                for (hop in 0 until 10) {
                    val scheme = currentUrl.protocol.lowercase()
                    if (scheme != "https" && scheme != "http") {
                        throw IllegalArgumentException("不支持的下载地址协议")
                    }
                    val c = (currentUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = false
                        if (userAgent.isNotEmpty()) setRequestProperty("User-Agent", userAgent)
                        if (isSameSiteOrAllowedHost(initialParsed.host, currentUrl.host, spec.allowedHosts)) {
                            profileCookieManager?.getCookie(currentUrl.toString())
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { setRequestProperty("Cookie", it) }
                            if (!refererUrl.isNullOrBlank() &&
                                isSameSiteOrAllowedHost(initialParsed.host, Uri.parse(refererUrl).host, spec.allowedHosts)
                            ) {
                                setRequestProperty("Referer", refererUrl)
                            }
                        }
                    }
                    val code = c.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 ||
                        code == 308
                    ) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc.isNullOrBlank()) throw IllegalStateException("下载重定向缺少目标地址")
                        currentUrl = URL(currentUrl, loc)
                        continue
                    }
                    activeConn = c
                    break
                }
                conn = activeConn ?: throw IllegalStateException("下载重定向次数过多")
                val status = conn.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("服务器返回错误状态（HTTP $status）")
                }
                val contentDisp = conn.getHeaderField("Content-Disposition")
                val serverMime = conn.contentType ?: declaredMime
                val rawName = parseContentDispositionFilename(contentDisp)
                    ?: runCatching { URLUtil.guessFileName(currentUrl.toString(), contentDisp, serverMime) }.getOrNull()
                    ?: initialName
                val finalName = sanitizeDownloadFilename(rawName, serverMime)
                val finalMime = resolveMimeType(finalName, serverMime)
                val saved = conn.inputStream.use { input ->
                    saveStreamToPublicDownloads(finalName, finalMime) { out ->
                        copyStreamWithCheck(input, out, sessionGen)
                    }
                }
                onDownloadCompleted(sessionGen, saved)
            } catch (t: Throwable) {
                onDownloadFailed(sessionGen, t.message ?: "网络下载失败")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun startDataDownload(
        sessionGen: Long,
        dataUrl: String,
        contentDisposition: String?,
        declaredMime: String?,
    ) {
        val commaIdx = dataUrl.indexOf(',')
        if (commaIdx <= 5) {
            showDownloadStatus("无效的数据下载链接", canOpen = false, showDir = false, isError = true, autoHide = true)
            return
        }
        val meta = dataUrl.substring(5, commaIdx)
        val payload = dataUrl.substring(commaIdx + 1)
        val isBase64 = meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }
        val dataMime = meta.substringBefore(';').trim().ifEmpty { declaredMime ?: "application/octet-stream" }
        val fileName = sanitizeDownloadFilename(parseContentDispositionFilename(contentDisposition), dataMime)
        showDownloadStatus("正在保存：$fileName…", canOpen = false, showDir = false, isError = false, autoHide = false)
        downloadExecutor.execute {
            try {
                val bytes = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                val finalMime = resolveMimeType(fileName, dataMime)
                val saved = saveStreamToPublicDownloads(fileName, finalMime) { out ->
                    out.write(bytes)
                }
                onDownloadCompleted(sessionGen, saved)
            } catch (t: Throwable) {
                onDownloadFailed(sessionGen, t.message ?: "保存数据失败")
            }
        }
    }

    private fun startBlobDownload(
        view: WebView,
        sessionGen: Long,
        blobUrl: String,
        defaultName: String,
        mimeType: String?,
        isPopup: Boolean,
    ) {
        val token = UUID.randomUUID().toString()
        synchronized(blobLock) {
            pendingBlobTokens[token] = BlobInitMeta(
                generation = sessionGen,
                defaultName = defaultName,
                fallbackMime = mimeType?.trim().orEmpty(),
            )
        }
        showDownloadStatus("正在下载：$defaultName…", canOpen = false, showDir = false, isError = false, autoHide = false)
        val quotedToken = JSONObject.quote(token)
        val quotedUrl = JSONObject.quote(blobUrl)
        val js = """
            (() => {
              const token = $quotedToken;
              const blobUrl = $quotedUrl;
              const bridge = window.$ANDROID_DOWNLOAD_BRIDGE_NAME;
              if (!bridge) return;
              (async () => {
                try {
                  const last = window.__maamLastDownload;
                  const hintName = (last && Date.now() - last.ts < 15000 && (!last.url || last.url === blobUrl))
                    ? (last.name || '')
                    : '';
                  const res = await fetch(blobUrl);
                  if (!res.ok) throw new Error('HTTP ' + res.status);
                  const blob = await res.blob();
                  const mime = blob.type || '';
                  bridge.beginBlobDownload(token, hintName, mime);
                  const chunkSize = 384 * 1024;
                  let offset = 0;
                  while (offset < blob.size) {
                    const slice = blob.slice(offset, offset + chunkSize);
                    const buf = await slice.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = '';
                    const step = 0x8000;
                    for (let i = 0; i < bytes.length; i += step) {
                      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
                    }
                    bridge.appendBlobChunk(token, btoa(binary));
                    offset += chunkSize;
                  }
                  bridge.finishBlobDownload(token, ${if (isPopup) "true" else "false"});
                } catch (e) {
                  try {
                    bridge.failBlobDownload(token, (e && e.message) ? String(e.message) : '读取 Blob 文件失败', ${if (isPopup) "true" else "false"});
                  } catch (_) {}
                }
              })();
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class AndroidBlobDownloadBridge {
        @JavascriptInterface
        fun beginBlobDownload(token: String, hintName: String, mimeType: String) {
            val meta = synchronized(blobLock) {
                val m = pendingBlobTokens.remove(token) ?: return
                if (m.generation != activeSessionGeneration.get()) return
                m
            }
            val effectiveMime = mimeType.trim().ifEmpty { meta.fallbackMime }
            val resolvedName = sanitizeDownloadFilename(
                hintName.trim().ifEmpty { meta.defaultName },
                effectiveMime,
            )
            val tmpDir = File(applicationContext.cacheDir, "dl_tmp").apply { mkdirs() }
            val tempFile = File(tmpDir, "blob_${UUID.randomUUID()}.part")
            try {
                val fos = FileOutputStream(tempFile)
                val session = BlobTransferSession(
                    token = token,
                    generation = meta.generation,
                    defaultName = resolvedName,
                    tempFile = tempFile,
                    stream = fos,
                    mimeType = effectiveMime,
                    hintName = hintName.trim(),
                )
                synchronized(blobLock) {
                    if (meta.generation != activeSessionGeneration.get()) {
                        runCatching { fos.close() }
                        tempFile.delete()
                        return
                    }
                    activeBlobSessions[token] = session
                }
                uiHandler.post {
                    if (activeSessionGeneration.get() == meta.generation) {
                        showDownloadStatus("正在下载：$resolvedName…", canOpen = false, showDir = false, isError = false, autoHide = false)
                    }
                }
            } catch (t: Throwable) {
                tempFile.delete()
                onDownloadFailed(meta.generation, t.message ?: "无法创建临时下载文件")
            }
        }

        @JavascriptInterface
        fun appendBlobChunk(token: String, base64Chunk: String) {
            val session = synchronized(blobLock) { activeBlobSessions[token] } ?: return
            if (session.generation != activeSessionGeneration.get()) {
                abortBlobSession(token)
                return
            }
            try {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                synchronized(blobLock) {
                    val active = activeBlobSessions[token] ?: return
                    if (active.generation != activeSessionGeneration.get()) {
                        activeBlobSessions.remove(token)
                        runCatching { active.stream.close() }
                        active.tempFile.delete()
                        return
                    }
                    if (active.totalBytes + bytes.size > MAX_BLOB_DOWNLOAD_BYTES) {
                        activeBlobSessions.remove(token)
                        runCatching { active.stream.close() }
                        active.tempFile.delete()
                        onDownloadFailed(active.generation, "文件超过 256MB 大小限制")
                        return
                    }
                    active.stream.write(bytes)
                    active.totalBytes += bytes.size
                }
            } catch (t: Throwable) {
                abortBlobSession(token)
                onDownloadFailed(session.generation, t.message ?: "写入下载分片失败")
            }
        }

        @JavascriptInterface
        fun finishBlobDownload(token: String, closeHiddenPopup: Boolean) {
            val session = synchronized(blobLock) { activeBlobSessions.remove(token) } ?: return
            runCatching {
                session.stream.flush()
                session.stream.close()
            }
            if (closeHiddenPopup) {
                uiHandler.post {
                    if (popupOverlayRoot.visibility != View.VISIBLE) {
                        destroyPopupWebView()
                    }
                }
            }
            if (session.generation != activeSessionGeneration.get()) {
                session.tempFile.delete()
                return
            }
            val finalName = sanitizeDownloadFilename(
                session.hintName.ifEmpty { session.defaultName },
                session.mimeType,
            )
            val finalMime = resolveMimeType(finalName, session.mimeType)
            downloadExecutor.execute {
                try {
                    val saved = FileInputStream(session.tempFile).use { input ->
                        saveStreamToPublicDownloads(finalName, finalMime) { out ->
                            copyStreamWithCheck(input, out, session.generation)
                        }
                    }
                    onDownloadCompleted(session.generation, saved)
                } catch (t: Throwable) {
                    onDownloadFailed(session.generation, t.message ?: "导出文件失败")
                } finally {
                    session.tempFile.delete()
                }
            }
        }

        @JavascriptInterface
        fun failBlobDownload(token: String, reason: String, closeHiddenPopup: Boolean) {
            val gen = abortBlobSession(token) ?: synchronized(blobLock) {
                pendingBlobTokens.remove(token)?.generation
            }
            if (closeHiddenPopup) {
                uiHandler.post {
                    if (popupOverlayRoot.visibility != View.VISIBLE) {
                        destroyPopupWebView()
                    }
                }
            }
            if (gen != null) {
                onDownloadFailed(gen, reason.ifBlank { "下载失败" })
            }
        }
    }

    private fun abortBlobSession(token: String): Long? {
        val session = synchronized(blobLock) { activeBlobSessions.remove(token) } ?: return null
        runCatching { session.stream.close() }
        session.tempFile.delete()
        return session.generation
    }

    private fun cancelAllBlobTransfers() {
        val sessions = synchronized(blobLock) {
            pendingBlobTokens.clear()
            activeBlobSessions.values.toList().also { activeBlobSessions.clear() }
        }
        for (s in sessions) {
            runCatching { s.stream.close() }
            s.tempFile.delete()
        }
    }

    private fun copyStreamWithCheck(input: InputStream, output: OutputStream, sessionGen: Long) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted || sessionGen != activeSessionGeneration.get()) {
                throw InterruptedException("会话已切换")
            }
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
        }
        output.flush()
    }

    private fun saveStreamToPublicDownloads(
        filename: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): SavedDownloadResult {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建系统下载目录文件")
            try {
                resolver.openOutputStream(itemUri, "w")?.use { out ->
                    writer(out)
                } ?: throw IllegalStateException("无法写入系统下载文件")
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                runCatching { resolver.update(itemUri, doneValues, null, null) }
                val actualName = runCatching {
                    resolver.query(itemUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: filename
                return SavedDownloadResult(
                    filename = actualName,
                    mimeType = mimeType,
                    openUri = itemUri,
                    publicDownloads = true,
                    fallbackDir = null,
                )
            } catch (t: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw t
            }
        }

        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val tryPublic = runCatching {
            if (!publicDir.exists()) publicDir.mkdirs()
            val outFile = createUniqueFile(publicDir, filename)
            try {
                FileOutputStream(outFile).use { out -> writer(out) }
            } catch (t: Throwable) {
                outFile.delete()
                throw t
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(outFile.absolutePath), arrayOf(mimeType), null)
            val uri = runCatching {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
            }.getOrNull()
            SavedDownloadResult(
                filename = outFile.name,
                mimeType = mimeType,
                openUri = uri,
                publicDownloads = true,
                fallbackDir = publicDir,
            )
        }.getOrNull()
        if (tryPublic != null) return tryPublic

        val fallbackDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "Download")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        val fallbackFile = createUniqueFile(fallbackDir, filename)
        try {
            FileOutputStream(fallbackFile).use { out -> writer(out) }
        } catch (t: Throwable) {
            fallbackFile.delete()
            throw t
        }
        return SavedDownloadResult(
            filename = fallbackFile.name,
            mimeType = mimeType,
            openUri = null,
            publicDownloads = false,
            fallbackDir = fallbackDir,
        )
    }

    private fun createUniqueFile(dir: File, filename: String): File {
        var candidate = File(dir, filename)
        if (!candidate.exists()) return candidate
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 1000) {
            candidate = File(dir, "$stem ($counter)$ext")
            counter += 1
        }
        return candidate
    }

    private fun onDownloadCompleted(sessionGen: Long, saved: SavedDownloadResult) {
        uiHandler.post {
            if (isFinishing || isDestroyed || sessionGen != activeSessionGeneration.get()) return@post
            lastDownloadedUri = saved.openUri
            lastDownloadedMime = saved.mimeType
            lastDownloadedFallbackDir = saved.fallbackDir
            val prefix = if (saved.publicDownloads) "已保存到「下载」" else "已保存（应用下载目录）"
            showDownloadStatus(
                text = "$prefix：${saved.filename}",
                canOpen = saved.openUri != null,
                showDir = saved.publicDownloads,
                isError = false,
                autoHide = true,
            )
        }
    }

    private fun onDownloadFailed(sessionGen: Long, reason: String) {
        uiHandler.post {
            if (isFinishing || isDestroyed || sessionGen != activeSessionGeneration.get()) return@post
            lastDownloadedUri = null
            showDownloadStatus(
                text = "下载失败：$reason",
                canOpen = false,
                showDir = false,
                isError = true,
                autoHide = true,
            )
        }
    }

    private fun showDownloadStatus(
        text: String,
        canOpen: Boolean,
        showDir: Boolean,
        isError: Boolean,
        autoHide: Boolean,
    ) {
        cancelDownloadBannerTimer()
        downloadBannerText.text = text
        downloadBannerText.setTextColor(
            Color.parseColor(if (isError) "#B91C1C" else "#111827")
        )
        downloadOpenBtn.visibility = if (canOpen) View.VISIBLE else View.GONE
        downloadDirBtn.visibility = if (showDir) View.VISIBLE else View.GONE
        downloadBannerRoot.visibility = View.VISIBLE
        if (autoHide) {
            val task = Runnable { hideDownloadBanner() }
            downloadBannerHideRunnable = task
            uiHandler.postDelayed(task, 10_000L)
        }
    }

    private fun cancelDownloadBannerTimer() {
        downloadBannerHideRunnable?.let { uiHandler.removeCallbacks(it) }
        downloadBannerHideRunnable = null
    }

    private fun hideDownloadBanner() {
        cancelDownloadBannerTimer()
        downloadBannerRoot.visibility = View.GONE
    }

    private fun openLastDownloadedFile() {
        val uri = lastDownloadedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastDownloadedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val generic = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (runCatching { startActivity(generic) }.isFailure) {
                showDownloadStatus(
                    text = "暂无可直接打开此格式的应用，请点「下载目录」查看",
                    canOpen = false,
                    showDir = true,
                    isError = false,
                    autoHide = true,
                )
            }
        } catch (_: Throwable) {
            openDownloadsFolder()
        }
    }

    private fun openDownloadsFolder() {
        val openedDownloads = runCatching {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }.isSuccess
        if (openedDownloads) return

        val openedDoc = runCatching {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivity(docIntent)
        }.isSuccess
        if (!openedDoc) {
            val hint = lastDownloadedFallbackDir?.absolutePath ?: "系统「文件管理 -> 下载」"
            showDownloadStatus(
                text = "请前往 $hint 查看已下载文件",
                canOpen = lastDownloadedUri != null,
                showDir = false,
                isError = false,
                autoHide = true,
            )
        }
    }
}
