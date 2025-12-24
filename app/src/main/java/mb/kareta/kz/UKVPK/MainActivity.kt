package mb.kareta.kz.UKVPK

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.socket.client.IO
import io.socket.client.Socket
import mb.kareta.kz.UKVPK.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        const val WEB_URL: String = "https://mb.kareta.kz/"
        const val ALLOWED_HOST: String = "mb.kareta.kz"

        const val SOCKET_ORIGIN: String = "https://mb.kareta.kz"
        const val SOCKET_PATH: String = "/ws/socket.io"
    }

    private lateinit var web: WebView
    private var socket: Socket? = null
    private var ui: Handler = Handler(Looper.getMainLooper())
    private var lastCookie: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding=ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        web = binding.wvContainer

        val cm: CookieManager = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, false)

        val settings: WebSettings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri: Uri? = request.url
                val host: String? = uri?.host

                return host == null || !ALLOWED_HOST.equals(host, ignoreCase = true)
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
            }

            override fun onPageFinished(
                view: WebView, url: String
            ) {
                CookieManager.getInstance().flush()
                view.evaluateJavascript(
                    "window.__NATIVE_READY__ && window.__NATIVE_READY__('android');",
                    null
                )
                refreshSocketIfCookieChanged()
            }
        }

        web.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        web.loadUrl(WEB_URL)

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) {
                web.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSocket()
    }

    // ========== Socket.IO ==========
    /**
    не знаю, на сколько хорошо chat gpt сработал, пока проверяю
    а так, ошибок не видно, и не должно быть видно
    но будет ли все работать вопрос
     */

    private fun initSocketIoWithCookie(cookie: String) {
        try {
            val opts = IO.Options().apply {
                transports = arrayOf("websocket")
                path = SOCKET_PATH

                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 500
                reconnectionDelayMax = 10_000

                if (cookie.isNotBlank()) {
                    extraHeaders = mapOf(
                        "Cookie" to Collections.singletonList(cookie)
                    )
                }
            }

            socket = IO.socket(SOCKET_ORIGIN, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                emitToWeb("ws.open", null)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                emitToWeb("ws.close", safeJson("reason", "disconnect"))
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                emitToWeb(
                    "ws.error",
                    safeJson("error", args.firstOrNull()?.toString() ?: "")
                )
            }

            /**

            в джава скрипт есть метод EVENT_RECONNECT_ATTEMPT
            но в котлине не найден
            по этому было решено закоментрировать

            я не знаю насколько нужна функция, по этому сохранил

            socket?.on(Socket.EVENT_RECONNECT_ATTEMPT) { args ->
            emitToWeb(
            "ws.reconnect_attempt",
            safeJson("n", args.firstOrNull()?.toString() ?: "")
            )
            }
             **/

            socket?.on("message") { args ->
                val payload = JSONObject()
                try {
                    payload.put("event", "message")
                    payload.put("data", args.firstOrNull()?.toString() ?: "")
                } catch (_: Exception) { }
                emitToWeb("ws.message", payload)
            }

            socket?.connect()

        } catch (e: URISyntaxException) {
            emitToWeb("ws.error", safeJson("error", "Bad URI: ${e.message}"))
        }
    }

    private fun closeSocket() {
        try {
            socket?.off()
            socket?.disconnect()
            socket?.close()
            socket = null
        } catch (_: Exception) {
        }
    }

    private fun refreshSocketIfCookieChanged() {
        var now = CookieManager.getInstance().getCookie(WEB_URL)
        if (now == null) now = ""

        if (now != lastCookie) {
            lastCookie = now
            closeSocket()
            initSocketIoWithCookie(lastCookie)
        }
    }

    // ========== Web -> Native bridge ==========
    inner class AndroidBridge {

        @JavascriptInterface
        fun emit(eventName: String, jsonPayload: String) {
            try {
                val data: Any = try {
                    JSONObject(jsonPayload)
                } catch (_: Exception) {
                    jsonPayload
                }

                socket?.emit(eventName, data)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun socketStatus(): String {
            val connected = socket?.connected() == true
            return """{"connected":$connected}"""
        }

        @JavascriptInterface
        fun authReady() {
            CookieManager.getInstance().flush()
            refreshSocketIfCookieChanged()
        }
    }

    // ========== Native -> Web ==========
    private fun emitToWeb(type: String, payload: JSONObject?) {
        try {
            val env = JSONObject()
            env.put("type", type)
            env.put("ts", System.currentTimeMillis())
            env.put("payload", payload ?: JSONObject())

            val js = """
                window.NativeSocket__onEvent && 
                window.NativeSocket__onEvent(${JSONObject.quote(env.toString())});
            """.trimIndent()

            ui.post {
                web.evaluateJavascript(js, null)
            }
        } catch (_: Exception) { }
    }

    private fun safeJson(key: String, value: String): JSONObject {
        val obj = JSONObject()
        try {
            obj.put(key, value)
        } catch (_: Exception) { }
        return obj
    }
}