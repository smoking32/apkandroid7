package com.xtreambuscador

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * La interfaz (HTML/JS de la versión 23) corre dentro de un WebView transparente.
 * El video lo reproduce ExoPlayer, que va DEBAJO del WebView.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private lateinit var assetLoader: WebViewAssetLoader
    private var player: ExoPlayer? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var durMs: Long = 0L
    private var playing = false

    @Volatile
    private var typing = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playing) {
                val d = p.duration
                durMs = if (d > 0) d else 0L
                js("XB.time(" + p.currentPosition + ")")
                ui.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.visibility = View.GONE
        root.addView(
            playerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        webView = WebView(this)
        webView.setBackgroundColor(Color.TRANSPARENT)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == "appassets.androidplatform.net") {
                    val path = uri.path ?: ""
                    if (path == "/__proxy") {
                        return proxy(uri.getQueryParameter("u"))
                    }
                    if (path == "/assets/cfg.js") {
                        val f = File(getExternalFilesDir(null), "cfg.js")
                        if (f.exists()) {
                            return WebResourceResponse("application/javascript", "utf-8", FileInputStream(f))
                        }
                    }
                }
                return assetLoader.shouldInterceptRequest(uri)
            }
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // ---------------------------------------------------------------- teclas
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = mapKey(e.keyCode)
        if (code != 0 && (!typing || e.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                js("App.key($code)")
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** Convierte las teclas de Android a los códigos que entiende la interfaz web. */
    private fun mapKey(k: Int): Int {
        return when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> 38
            KeyEvent.KEYCODE_DPAD_DOWN -> 40
            KeyEvent.KEYCODE_DPAD_LEFT -> 37
            KeyEvent.KEYCODE_DPAD_RIGHT -> 39
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> 8
            KeyEvent.KEYCODE_PROG_RED -> 82
            KeyEvent.KEYCODE_PROG_GREEN -> 71
            KeyEvent.KEYCODE_PROG_YELLOW -> 89
            KeyEvent.KEYCODE_PROG_BLUE -> 66
            KeyEvent.KEYCODE_INFO -> 73
            KeyEvent.KEYCODE_MENU -> 84
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> 80
            KeyEvent.KEYCODE_MEDIA_STOP -> 83
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 76
            KeyEvent.KEYCODE_MEDIA_REWIND -> 75
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> 33
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 34
            KeyEvent.KEYCODE_LAST_CHANNEL -> 81
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + (k - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 48 + (k - KeyEvent.KEYCODE_NUMPAD_0)
            else -> 0
        }
    }

    // ------------------------------------------------------------ reproductor
    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        // buffers pequeños = el canal arranca rápido
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1500, 3000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                    js("XB.bs()")
                } else if (state == Player.STATE_READY) {
                    val d = p.duration
                    durMs = if (d > 0) d else 0L
                    js("XB.bc();XB.info()")
                } else if (state == Player.STATE_ENDED) {
                    js("XB.end()")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                js("XB.err()")
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun startPlay(url: String) {
        val p = ensurePlayer()
        playerView.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true
        playing = true
        durMs = 0L
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 1000)
    }

    private fun stopPlay() {
        playing = false
        ui.removeCallbacks(ticker)
        val p = player
        if (p != null) {
            p.stop()
            p.clearMediaItems()
        }
        playerView.visibility = View.GONE
        durMs = 0L
    }

    // ------------------------------------------------------- puente con la web
    inner class Bridge {
        @JavascriptInterface
        fun play(url: String) {
            runOnUiThread { startPlay(url) }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { stopPlay() }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread { player?.pause() }
        }

        @JavascriptInterface
        fun resume() {
            runOnUiThread { player?.play() }
        }

        @JavascriptInterface
        fun seekBy(ms: Int) {
            runOnUiThread {
                val p = player
                if (p != null) {
                    var t = p.currentPosition + ms
                    if (t < 0L) t = 0L
                    val d = p.duration
                    if (d > 0 && t > d) t = d
                    p.seekTo(t)
                }
            }
        }

        @JavascriptInterface
        fun getDuration(): Long {
            return durMs
        }

        @JavascriptInterface
        fun vol(dir: Int): Int {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (dir > 0) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            } else if (dir < 0) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            return if (max > 0) cur * 100 / max else 0
        }

        @JavascriptInterface
        fun paste(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@MainActivity).toString()
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
        }

        /** true en celulares y tabletas (con pantalla táctil y sin modo TV). */
        @JavascriptInterface
        fun touchMode(): Boolean {
            val pm = packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }

        /** La web avisa cuando el usuario escribe: se muestra o se oculta el teclado en pantalla. */
        @JavascriptInterface
        fun typing(on: Boolean) {
            typing = on
            runOnUiThread {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (on) {
                    webView.requestFocus()
                    imm.showSoftInput(webView, 0)
                } else {
                    imm.hideSoftInputFromWindow(webView.windowToken, 0)
                }
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }
    }

    // ------------------------------------------------------------- red (proxy)
    /**
     * La web pide las direcciones http(s) a través de /__proxy?u=... para evitar el bloqueo CORS,
     * seguir redirecciones http -> https y descomprimir guías .gz.
     */
    private fun proxy(target: String?): WebResourceResponse {
        if (target.isNullOrBlank()) return errResp(400, "Bad Request")
        return try {
            var url = URL(target)
            var hops = 0
            var conn = openConn(url)
            var code = conn.responseCode
            while (code in 300..399 && hops < 6) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return errResp(502, "Bad Gateway")
                url = URL(url, loc)
                hops++
                conn = openConn(url)
                code = conn.responseCode
            }
            val raw: InputStream =
                if (code < 400) conn.inputStream else (conn.errorStream ?: ByteArrayInputStream(ByteArray(0)))
            val bis = BufferedInputStream(raw, 16384)
            bis.mark(2)
            val b1 = bis.read()
            val b2 = bis.read()
            bis.reset()
            val gz = b1 == 0x1f && b2 == 0x8b
            val data: InputStream = if (gz) GZIPInputStream(bis) else bis
            val ct = conn.contentType?.substringBefore(';')?.trim() ?: ""
            val mime = if (gz || ct.isEmpty()) "text/plain" else ct
            val msg = conn.responseMessage
            val reason = if (msg.isNullOrBlank()) "OK" else msg
            val status = if (code in 100..599 && code !in 300..399) code else 502
            WebResourceResponse(mime, "utf-8", status, reason, CORS, data)
        } catch (e: Exception) {
            errResp(502, "Bad Gateway")
        }
    }

    private fun openConn(u: URL): HttpURLConnection {
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun errResp(code: Int, reason: String): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", code, reason, CORS, ByteArrayInputStream(ByteArray(0)))
    }

    // --------------------------------------------------------------- varios
    private fun js(code: String) {
        webView.evaluateJavascript("try{" + code + "}catch(e){}", null)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (playing) player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        player?.release()
        player = null
        webView.destroy()
    }

    companion object {
        private const val UA = "VLC/3.0.20 LibVLC/3.0.20"
        private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    }
}
