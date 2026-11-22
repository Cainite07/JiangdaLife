package cn.mudlife.app.utils

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.io.File
import java.util.concurrent.Executors
import java.lang.ref.WeakReference

internal object WebResourceCache {
    private const val LIMIT_BYTES = 8L * 1024 * 1024
    private const val CHECK_DELAY_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var latestView: WeakReference<WebView>? = null
    private var checkPending = false

    @androidx.annotation.MainThread
    fun trimIfOversized(view: WebView) {
        latestView = WeakReference(view)
        if (checkPending) return
        checkPending = true
        val cache = File(view.context.cacheDir, "WebView")
        // A short delay lets subresources load after onPageFinished and coalesces redirects.
        main.postDelayed({
            worker.execute {
                val oversized = exceedsBudget(cache)
                main.post {
                    val activeView = latestView?.get()
                    checkPending = false
                    latestView = null
                    if (oversized && activeView?.isAttachedToWindow == true) activeView.clearCache(true)
                }
            }
        }, CHECK_DELAY_MS)
    }

    private fun exceedsBudget(cache: File): Boolean {
        var bytes = 0L
        for (file in cache.walkTopDown().filter { it.isFile }) {
            bytes += file.length()
            if (bytes >= LIMIT_BYTES) return true
        }
        return false
    }
}
