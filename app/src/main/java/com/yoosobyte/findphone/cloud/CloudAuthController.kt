package com.yoosobyte.findphone.cloud

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.isVisible
import com.yoosobyte.findphone.R
import com.yoosobyte.findphone.data.Account
import com.yoosobyte.findphone.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在应用内打开 OPPO 云服务官方登录页，登录后从 WebView 的 Cookie 中提取会话。
 *
 * 不直接调用账号密码登录 API 的原因：官方登录会按风控弹出滑块、短信或新设备验证，
 * 只有走官方页面才能可靠地完成这些流程。账号密码会被自动填到官方页面的输入框里，
 * 验证码等仍需用户手动完成。
 *
 * 登录成功后需要保存两样东西：`cloud.oppo.com` 的 Cookie（含 `TOKENKEY`）
 * 和 `TOKENKEY` 本身。查找手机接口的 `ctoken` 由 `TOKENKEY` 本地算出，不单独保存。
 */
class CloudAuthController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val onAuthorized: (accountId: Long, session: OppoSession) -> Unit,
    private val onDismissed: () -> Unit,
    private val onAutomationStarted: () -> Unit = {},
    private val onAutomationFinished: () -> Unit = {},
    private val onPageError: () -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())
    private val webView: WebView = binding.authWebView
    private var webUserAgent: String = ""

    @Volatile
    private var currentAccount: Account? = null
    private var overlayShown = false
    private var captureScheduled = false
    private var finishing = false
    private var languagePrepared = false
    private var languageAttempts = 0
    private var automationStarted = false
    private var tokenKeyFirstSeenAt = 0L

    private val cookiePoll = object : Runnable {
        override fun run() {
            if (!overlayShown || finishing) return
            if (!captureScheduled) {
                val cloudCookie = readCloudCookies()
                val tokenKey = CookieHeader.value(cloudCookie, "TOKENKEY")
                    ?: CookieHeader.value(readFindCookies(), "TOKENKEY")
                if (!tokenKey.isNullOrBlank()) {
                    if (tokenKeyFirstSeenAt == 0L) tokenKeyFirstSeenAt = System.currentTimeMillis()
                    val accountCookie = CookieHeader.value(cloudCookie, "OCLOUD_ACCOUNTNAME_COOKIE")
                    val hasLogin = CookieHeader.value(cloudCookie, "HASLOGIN")
                    val settled = System.currentTimeMillis() - tokenKeyFirstSeenAt > SETTLE_DELAY_MS
                    val confirmed = !accountCookie.isNullOrBlank() ||
                        hasLogin.equals("YES", ignoreCase = true) ||
                        settled
                    if (confirmed) beginCapture(manual = false)
                }
            }
            if (overlayShown && !finishing) {
                handler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
            }
        }
    }

    init {
        configureWebView()
        binding.authCloseButton.setOnClickListener { close() }
        binding.authDoneButton.setOnClickListener { beginCapture(manual = true) }
    }

    val isShowing: Boolean
        get() = overlayShown

    fun start(account: Account) {
        if (overlayShown) close()
        currentAccount = account
        captureScheduled = false
        finishing = false
        languagePrepared = false
        languageAttempts = 0
        automationStarted = false
        tokenKeyFirstSeenAt = 0L
        overlayShown = true

        binding.authSubtitle.text = activity.getString(R.string.auth_subtitle, account.displayName)
        binding.authFooterHint.setText(R.string.auth_loading)
        binding.authDoneButton.isEnabled = true
        binding.authProgress.isVisible = true
        binding.authOverlay.alpha = 0f
        binding.authOverlay.visibility = View.VISIBLE
        binding.authOverlay.animate().alpha(1f).setDuration(160L).start()

        handler.removeCallbacksAndMessages(null)

        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearHistory()
            webView.clearFormData()
            webView.loadUrl(
                LOGIN_URL,
                mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
            )
            handler.postDelayed(cookiePoll, COOKIE_POLL_INTERVAL_MS)
        }
    }

    fun close() {
        if (!overlayShown) return
        currentAccount = null
        captureScheduled = false
        hideOverlay()
        if (!finishing) onDismissed()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        currentAccount = null
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeJavascriptInterface("FindPhoneAutomation")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (error: Exception) {
            Log.w(TAG, "destroy webview: ${error.javaClass.simpleName}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webUserAgent = USER_AGENT
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = webUserAgent
        }
        // OPPO 官方登录页只有浅色样式，禁止 WebView 跟随系统深色做算法反色。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onAutomationStarted() {
                    handler.post {
                        if (!automationStarted) {
                            automationStarted = true
                            this@CloudAuthController.onAutomationStarted()
                        }
                    }
                }

                @JavascriptInterface
                fun onAutomationFinished() {
                    handler.post { this@CloudAuthController.onAutomationFinished() }
                }

                @JavascriptInterface
                fun tapLogin(x: Double, y: Double, viewportWidth: Double, viewportHeight: Double) {
                    handler.post {
                        dispatchLoginTap(x, y, viewportWidth, viewportHeight)
                    }
                }
            },
            "FindPhoneAutomation",
        )
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setBackgroundColor(Color.WHITE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!overlayShown) return
                binding.authProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!overlayShown || finishing) return
                binding.authProgress.isVisible = false
                binding.authFooterHint.setText(R.string.auth_footer_hint)
                val account = currentAccount ?: return
                val host = url?.let { Uri.parse(it).host?.lowercase() }

                // OPPO 登录页优先读 sessionStorage 里的 ocloud_lang。
                // 先把它设成 zh-cn 再 reload 一次，避免整条登录/同步流程走英文（global）分支。
                if (!languagePrepared && host == "cloud.oppo.com") {
                    if (languageAttempts >= 2) {
                        // reload 没有生效时不再循环，直接继续。
                        languagePrepared = true
                        injectAutoFill(view, account)
                        return
                    }
                    languageAttempts++
                    view?.evaluateJavascript(LANGUAGE_SCRIPT) { result ->
                        if (result?.contains("reload") == true) return@evaluateJavascript
                        languagePrepared = true
                        if (overlayShown && !finishing) injectAutoFill(view, account)
                    }
                    return
                }
                injectAutoFill(view, account)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "about") return false
                if (uri.scheme != "http" && uri.scheme != "https") return true
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (!overlayShown || request?.isForMainFrame != true) return
                onPageError()
                binding.authProgress.isVisible = false
                binding.authFooterHint.setText(R.string.auth_page_error)
                Toast.makeText(activity, R.string.auth_page_error, Toast.LENGTH_SHORT).show()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val account = currentAccount ?: return null
                if (!request.method.equals("GET", ignoreCase = true)) return null
                if (!isAccountLoginFrameUrl(url)) return null
                return interceptLoginFrame(url, account)
            }
        }
    }

    /**
     * 账号 SDK 的登录浮层是一个全屏 iframe，地址形如
     * `https://id.heytap.com/identity/web/v1/authn/popper-redirect?...`。
     * 拦截它的 HTML 并注入自动填充脚本。
     */
    private fun isAccountLoginFrameUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (LOGIN_FRAME_HOSTS.none { host == it || host.endsWith(".$it") }) return false
        return url.contains("/identity/web/", ignoreCase = true) ||
            url.contains("cloud_login", ignoreCase = true)
    }

    private fun interceptLoginFrame(url: String, account: Account): WebResourceResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("User-Agent", webUserAgent)
                setRequestProperty("Referer", "$CLOUD_ORIGIN/")
                setRequestProperty("Origin", CLOUD_ORIGIN)
                CookieManager.getInstance().getCookie(url)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            if (connection.responseCode !in 200..299) return null
            val setCookies = connection.headerFields
                .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                .values
                .flatten()
                .filterNotNull()
            if (setCookies.isNotEmpty()) {
                val cookieManager = CookieManager.getInstance()
                setCookies.forEach { cookieManager.setCookie(url, it) }
                cookieManager.flush()
            }
            val html = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            if (!html.contains("<html", ignoreCase = true)) return null
            if (html.length > MAX_INTERCEPT_BYTES) return null
            // 去掉 CSP meta，保证注入的自动填充脚本能执行；其余内容保持原样。
            val sanitized = html.replace(
                Regex(
                    "(?is)<meta[^>]+http-equiv\\s*=\\s*[\"']?Content-Security-Policy[\"']?[^>]*>",
                ),
                "",
            )
            val injection = "<script>${buildAutoFillScript(account.account, account.password)}</script>"
            val closingBody = sanitized.lastIndexOf("</body>", ignoreCase = true)
            val modified = if (closingBody >= 0) {
                sanitized.substring(0, closingBody) + injection + sanitized.substring(closingBody)
            } else {
                sanitized + injection
            }
            WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                modified.byteInputStream(Charsets.UTF_8),
            )
        } catch (error: Exception) {
            Log.w(TAG, "intercept login frame failed: ${error.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun injectAutoFill(view: WebView?, account: Account) {
        view?.evaluateJavascript(
            buildAutoFillScript(account.account, account.password),
            null,
        )
    }

    private fun dispatchLoginTap(x: Double, y: Double, viewportWidth: Double, viewportHeight: Double) {
        if (!overlayShown || finishing || viewportWidth <= 0 || viewportHeight <= 0) return
        val width = webView.width.toFloat()
        val height = webView.height.toFloat()
        if (width <= 1f || height <= 1f) return

        val px = (x / viewportWidth * width).toFloat().coerceIn(1f, width - 1f)
        val py = (y / viewportHeight * height).toFloat().coerceIn(1f, height - 1f)
        webView.requestFocus()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            px,
            py,
            0,
        )
        webView.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            if (!overlayShown || finishing) return@postDelayed
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(
                upTime,
                upTime,
                MotionEvent.ACTION_UP,
                px,
                py,
                0,
            )
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 60L)
    }

    private fun buildFillOnlyScript(account: String, password: String): String {
        val safeAccount = jsString(account)
        val safePassword = jsString(password)
        return """
            (function () {
              var ACCOUNT = $safeAccount;
              var PASSWORD = $safePassword;
              var tick = 0;
              var lastUser = null;
              var lastPass = null;
              function visible(el) {
                var rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              }
              function setValue(el, value) {
                try {
                  var proto = el.tagName === 'TEXTAREA'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype;
                  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                  setter.call(el, value);
                  ['input', 'change', 'blur'].forEach(function (name) {
                    el.dispatchEvent(new Event(name, { bubbles: true }));
                  });
                } catch (error) {
                  try {
                    el.value = value;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                  } catch (ignored) {}
                }
              }
              function fill() {
                tick++;
                if (tick > 120) { clearInterval(timer); return; }
                var inputs = document.querySelectorAll('input');
                var user = null;
                var pass = null;
                for (var i = 0; i < inputs.length; i++) {
                  var el = inputs[i];
                  var type = (el.type || '').toLowerCase();
                  if (type === 'hidden' || type === 'checkbox' || type === 'radio' || !visible(el)) continue;
                  if (type === 'password') {
                    if (!pass) pass = el;
                  } else if (!user && (type === 'text' || type === 'tel' || type === 'email' || type === 'number')) {
                    user = el;
                  }
                }
                if (user && (user !== lastUser || !user.value)) {
                  if (user.value !== ACCOUNT) setValue(user, ACCOUNT);
                  lastUser = user;
                }
                if (pass && (pass !== lastPass || !pass.value)) {
                  if (pass.value !== PASSWORD) setValue(pass, PASSWORD);
                  lastPass = pass;
                }
              }
              var timer = setInterval(fill, 500);
              fill();
            })();
        """.trimIndent()
    }

    private fun buildAutoFillScript(account: String, password: String): String {
        val safeAccount = jsString(account)
        val safePassword = jsString(password)
        return """
            (function () {
              var ACCOUNT = $safeAccount;
              var PASSWORD = $safePassword;
              var submitted = false;
              var tick = 0;
              var filledUser = false;
              var filledPass = false;
              var switchedToPassword = false;
              var clickedNext = false;
              var lastUser = null;
              var lastPass = null;
              var entryAttempts = 0;
              var entryLastClickAt = 0;
              var startedNotified = false;
              var vueLoginVm = null;
              var consentClicks = 0;
              var termsClicks = 0;
              function notifyStarted() {
                try {
                  if (window.FindPhoneAutomation) {
                    window.FindPhoneAutomation.onAutomationStarted();
                  }
                } catch (error) {}
              }
              function notifyFinished() {
                try {
                  if (window.FindPhoneAutomation) {
                    window.FindPhoneAutomation.onAutomationFinished();
                  }
                } catch (error) {}
              }
              function setValue(el, value) {
                try {
                  var proto = el.tagName === 'TEXTAREA'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype;
                  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                  setter.call(el, value);
                  ['input', 'change', 'blur'].forEach(function (name) {
                    el.dispatchEvent(new Event(name, { bubbles: true }));
                  });
                } catch (error) {
                  try {
                    el.value = value;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                  } catch (ignored) {}
                }
              }
              function visible(el) {
                var rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              }
              function findButton() {
                var nodes = document.querySelectorAll('button, a, [role="button"]');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (!visible(el) || el.disabled) continue;
                  var text = (el.innerText || el.textContent || '').replace(/\s+/g, '');
                  if (!text || text.length > 10) continue;
                  if (/^(登录|立即登录|登入|登錄|下一步|继续|Sign ?in|Log ?in|Next)$/i.test(text)) {
                    return el;
                  }
                }
                return null;
              }
              function clickElement(el) {
                try {
                  var target = el.closest
                    ? (el.closest('button, a, [role="button"]') || el)
                    : el;
                  target.click();
                } catch (error) {
                  try { el.click(); } catch (ignored) {}
                }
              }
              function nativeTap(el) {
                try {
                  var rect = el.getBoundingClientRect();
                  if (window.FindPhoneAutomation && window.FindPhoneAutomation.tapLogin) {
                    window.FindPhoneAutomation.tapLogin(
                      rect.left + rect.width / 2,
                      rect.top + rect.height / 2,
                      window.innerWidth,
                      window.innerHeight
                    );
                    return true;
                  }
                } catch (error) {}
                return false;
              }
              function elementText(el) {
                var text = (el.innerText || el.textContent || '').replace(/\s+/g, '');
                if (!text && el.getAttribute) {
                  text = (el.getAttribute('aria-label') || el.getAttribute('title') ||
                    el.getAttribute('alt') || el.getAttribute('data-title') || '')
                    .replace(/\s+/g, '');
                }
                if (!text && el.value) text = String(el.value).replace(/\s+/g, '');
                return text;
              }
              function findByText(pattern, maxLength) {
                var groups = ['button, a, [role="button"]', 'div, span'];
                for (var g = 0; g < groups.length; g++) {
                  var nodes = document.querySelectorAll(groups[g]);
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (!visible(el) || el.disabled) continue;
                    var text = elementText(el);
                    if (!text || text.length > maxLength) continue;
                    if (pattern.test(text)) return el;
                  }
                }
                return null;
              }
              function findSmallest(pattern, maxLength) {
                var groups = ['button, a, [role="button"]', 'div, span'];
                var best = null;
                var bestArea = Number.MAX_VALUE;
                for (var g = 0; g < groups.length; g++) {
                  var nodes = document.querySelectorAll(groups[g]);
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (!visible(el) || el.disabled) continue;
                    var text = elementText(el);
                    if (!text || text.length > maxLength || !pattern.test(text)) continue;
                    var rect = el.getBoundingClientRect();
                    var area = rect.width * rect.height;
                    if (area < bestArea) {
                      best = el;
                      bestArea = area;
                    }
                  }
                }
                return best;
              }
              function findCookieConsent() {
                return findSmallest(
                  /^(AgreeandClose|AcceptandClose|AcceptAll|同意并关闭|同意并关闭提示|同意并接受)$/i,
                  14
                );
              }
              function findTermsAgree() {
                return findSmallest(
                  /^(Agreeandcontinue|Agreeandproceed|Acceptandcontinue|同意并继续|同意并进入|同意并授权|同意并登录|同意并接受)$/i,
                  24
                );
              }
              function findEntry() {
                return findSmallest(
                  /^(Sign ?in|Signin|Signin\/Signup|SigninorSignup|Log ?in|Login|LoginAccount|登录|登录账号|登录帐号|登录账户|账号登录|立即登录|登入|登錄|登录\/注册|注册\/登录|登录或注册)$/i,
                  24
                );
              }
              function sdkReady(vm) {
                try {
                  var data = vm['${'$'}data'];
                  if (data && Object.prototype.hasOwnProperty.call(data, 'accountClientSdk')) {
                    return !!data.accountClientSdk;
                  }
                  if (Object.prototype.hasOwnProperty.call(vm, 'accountClientSdk')) {
                    return !!vm.accountClientSdk;
                  }
                } catch (error) {}
                return true;
              }
              function findVueLoginVm() {
                try {
                  if (!document.querySelector('.wrapper-login')) return null;
                  var nodes = document.querySelectorAll('*');
                  for (var i = 0; i < nodes.length; i++) {
                    var inst = nodes[i].__vue__;
                    if (!inst || typeof inst.loginClick !== 'function') continue;
                    var data = inst['${'$'}data'];
                    if (data && Object.prototype.hasOwnProperty.call(data, 'accountClientSdk')) {
                      return inst;
                    }
                  }
                } catch (error) {}
                return null;
              }
              function triggerVueLogin() {
                try {
                  if (!vueLoginVm) vueLoginVm = findVueLoginVm();
                  if (!vueLoginVm) return 'missing';
                  if (!sdkReady(vueLoginVm)) return 'wait';
                  vueLoginVm.loginClick();
                  return 'clicked';
                } catch (error) {
                  return 'missing';
                }
              }
              function vueSdkState() {
                try {
                  if (!vueLoginVm) vueLoginVm = findVueLoginVm();
                  if (!vueLoginVm) return 'missing';
                  return sdkReady(vueLoginVm) ? 'ready' : 'wait';
                } catch (error) {
                  return 'missing';
                }
              }
              function fill() {
                if (submitted) return;
                tick++;
                if (tick > 120) { notifyFinished(); clearInterval(timer); return; }
                // 1. 先点网页顶部的「同意并关闭」Cookie 提示。
                var consent = findCookieConsent();
                if (consent && consentClicks < 3) {
                  consentClicks++;
                  console.log('FindPhone auth: click cookie consent');
                  clickElement(consent);
                  return;
                }
                var inputs = document.querySelectorAll('input');
                var user = null;
                var pass = null;
                var hasVisibleInput = false;
                for (var i = 0; i < inputs.length; i++) {
                  var el = inputs[i];
                  var type = (el.type || '').toLowerCase();
                  if (type === 'hidden' || type === 'checkbox' || type === 'radio' || !visible(el)) continue;
                  hasVisibleInput = true;
                  if (type === 'password') {
                    if (!pass) pass = el;
                  } else if (!user && (type === 'text' || type === 'tel' || type === 'email' || type === 'number')) {
                    user = el;
                  }
                }
                if (!startedNotified && (location.pathname.indexOf('/identity/') >= 0 || pass)) {
                  startedNotified = true;
                  notifyStarted();
                }
                // 2. 账号协议 / 隐私保护弹窗优先级最高，先点「同意并继续」，
                //    不能让它后面的登录按钮先被点到。
                var terms = findTermsAgree();
                if (terms && termsClicks < 5) {
                  termsClicks++;
                  console.log('FindPhone auth: click terms agree');
                  clickElement(terms);
                  return;
                }
                var needEntry = !hasVisibleInput || location.hostname === 'cloud.oppo.com';
                if (needEntry && entryAttempts < 6 && tick > 4 &&
                    location.pathname.indexOf('/identity/') === -1 &&
                    Date.now() - entryLastClickAt > 2000) {
                  // 3. Cookie 关闭后再点「登录账号 / Sign in」打开账号登录页。
                  // 真机上账号 SDK 初始化可能比页面加载慢，第一次点击可能只弹出初始化失败，
                  // 所以隔 2 秒重试，直到页面跳走或成功打开登录浮层。
                  var entry = findEntry();
                  var sdkState = vueSdkState();
                  if (sdkState === 'wait') {
                    if (tick === 8) {
                      console.log('FindPhone auth: waiting account sdk');
                    }
                    return;
                  }
                  if (entry && entryAttempts < 2) {
                    entryAttempts++;
                    entryLastClickAt = Date.now();
                    if (entryAttempts === 1 && nativeTap(entry)) {
                      console.log('FindPhone auth: native tap login entry #' + entryAttempts);
                      return;
                    }
                    console.log('FindPhone auth: click login entry #' + entryAttempts);
                    clickElement(entry);
                    return;
                  }
                  if (sdkState === 'ready' && vueLoginVm) {
                    entryAttempts++;
                    entryLastClickAt = Date.now();
                    console.log('FindPhone auth: call vue loginClick #' + entryAttempts);
                    vueLoginVm.loginClick();
                    return;
                  }
                  if (entry) {
                    entryAttempts++;
                    entryLastClickAt = Date.now();
                    console.log('FindPhone auth: click login entry #' + entryAttempts);
                    clickElement(entry);
                    return;
                  }
                  if (tick === 8) {
                    console.log('FindPhone auth: no login entry, host=' + location.hostname +
                      ', path=' + location.pathname + ', inputs=' + hasVisibleInput);
                  }
                }
                if (user && (user !== lastUser || !user.value)) {
                  if (user.value !== ACCOUNT) setValue(user, ACCOUNT);
                  lastUser = user;
                  filledUser = true;
                }
                if (pass && (pass !== lastPass || !pass.value)) {
                  if (pass.value !== PASSWORD) setValue(pass, PASSWORD);
                  lastPass = pass;
                  filledPass = true;
                }
                if (!pass && !switchedToPassword && tick > 4) {
                  switchedToPassword = true;
                  var tabs = document.querySelectorAll('a, button, [role="tab"], div, span');
                  for (var j = 0; j < tabs.length; j++) {
                    var tab = tabs[j];
                    if (!visible(tab)) continue;
                    var label = (tab.innerText || tab.textContent || '').replace(/\s+/g, '');
                    if (/^(账号密码登录|密码登录|账号登录|密码登陆)$/.test(label)) {
                      try { tab.click(); } catch (error) {}
                      return;
                    }
                  }
                }
                if (!pass && filledUser && user && user.value && !clickedNext && tick > 6) {
                  clickedNext = true;
                  var next = findButton();
                  if (next) {
                    try { next.click(); } catch (error) {}
                    return;
                  }
                }
                // 4. 账号密码都填好后提交登录。
                if (tick < 6 || !filledPass || !filledUser || !pass.value || !user.value) return;
                var button = findButton();
                if (!button) return;
                notifyFinished();
                submitted = true;
                clearInterval(timer);
                try {
                  button.disabled = false;
                  button.removeAttribute('disabled');
                  button.click();
                } catch (error) {}
              }
              var timer = setInterval(fill, 500);
              fill();
            })();
        """.trimIndent()
    }

    private fun beginCapture(manual: Boolean) {
        val account = currentAccount ?: return
        if (captureScheduled) return
        captureScheduled = true
        handler.removeCallbacks(cookiePoll)
        binding.authFooterHint.setText(R.string.auth_syncing)
        binding.authProgress.isVisible = true
        binding.authDoneButton.isEnabled = false
        attemptCapture(account.id, attempt = 0, manual = manual)
    }

    private fun attemptCapture(accountId: Long, attempt: Int, manual: Boolean) {
        if (currentAccount?.id != accountId) return
        val cookieManager = CookieManager.getInstance()
        val cloudCookie = readCloudCookies()
        val mergedCookie = CookieHeader.mergeAll(
            readFindCookies(),
            cloudCookie,
        )
        val tokenKey = (
            CookieHeader.value(cloudCookie, "TOKENKEY")
                ?: CookieHeader.value(mergedCookie, "TOKENKEY")
            ).orEmpty()

        if (tokenKey.isNotBlank()) {
            val session = buildSession(mergedCookie, tokenKey)
            if (session != null) {
                cookieManager.flush()
                finishWithSession(accountId, session)
                return
            }
        }

        if (attempt < MAX_CAPTURE_ATTEMPTS) {
            handler.postDelayed(
                { attemptCapture(accountId, attempt + 1, manual) },
                CAPTURE_RETRY_DELAY_MS,
            )
            return
        }

        captureScheduled = false
        Log.w(TAG, "capture failed: TOKENKEY cookie not found")
        binding.authProgress.isVisible = false
        binding.authDoneButton.isEnabled = true
        binding.authFooterHint.setText(R.string.auth_no_cookie)
        if (manual) {
            Toast.makeText(activity, R.string.auth_no_cookie, Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed(cookiePoll, COOKIE_POLL_INTERVAL_MS)
    }

    private fun buildSession(mergedCookie: String, tokenKey: String): OppoSession? {
        if (mergedCookie.isBlank() || tokenKey.isBlank()) return null
        return OppoSession(cookie = mergedCookie, tokenKey = tokenKey)
    }

    private fun finishWithSession(accountId: Long, session: OppoSession) {
        val account = currentAccount ?: return
        if (account.id != accountId) return
        Log.i(
            TAG,
            "session captured: cookies=${session.cookie.split(';').size}, " +
                "tokenKeyLength=${session.tokenKey.length}",
        )
        finishing = true
        currentAccount = null
        captureScheduled = false
        hideOverlay()
        onAuthorized(accountId, session)
    }

    private fun hideOverlay() {
        handler.removeCallbacksAndMessages(null)
        overlayShown = false
        binding.authProgress.isVisible = false
        binding.authOverlay.animate().cancel()
        binding.authOverlay.visibility = View.GONE
        binding.authOverlay.alpha = 1f
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    private fun readCloudCookies(): String {
        val manager = CookieManager.getInstance()
        return CookieHeader.mergeAll(
            manager.getCookie("$CLOUD_ORIGIN/"),
            manager.getCookie("$CLOUD_ORIGIN/login.html"),
            manager.getCookie("$CLOUD_ORIGIN/pagemodule.html"),
            manager.getCookie("$CLOUD_ORIGIN/index.html"),
        )
    }

    private fun readFindCookies(): String {
        val manager = CookieManager.getInstance()
        return CookieHeader.mergeAll(
            manager.getCookie("$FIND_ORIGIN/"),
            manager.getCookie("$FIND_ORIGIN/console/web/v1/postNotification"),
        )
    }

    private fun jsString(value: String): String {
        return JSONObject.quote(value)
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    companion object {
        private const val TAG = "CloudAuthController"
        private const val CLOUD_ORIGIN = "https://cloud.oppo.com"
        private const val FIND_ORIGIN = "https://find.oppo.com"
        private const val LOGIN_URL = "$CLOUD_ORIGIN/login.html"
        private const val MAX_CAPTURE_ATTEMPTS = 8
        private const val CAPTURE_RETRY_DELAY_MS = 700L
        private const val COOKIE_POLL_INTERVAL_MS = 700L
        private const val SETTLE_DELAY_MS = 2_500L
        private const val MAX_INTERCEPT_BYTES = 3_000_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val LANGUAGE_SCRIPT = """
            (function () {
              try {
                if (sessionStorage.getItem('ocloud_lang') === 'zh-cn' ||
                    (window.locate && String(window.locate).toLowerCase().indexOf('zh') === 0)) {
                  return 'ok';
                }
                sessionStorage.setItem('ocloud_lang', 'zh-cn');
                location.reload();
                return 'reload';
              } catch (error) {
                return 'error';
              }
            })();
        """.trimIndent()

        private val LOGIN_FRAME_HOSTS = setOf(
            "id.heytap.com",
            "id.oppo.com",
            "id.oneplus.com",
        )
    }
}
