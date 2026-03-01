package com.animesail

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

@AnyThread
class TurnstileKiller : Interceptor {
    companion object {
        const val TAG = "TurnstileKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        safe {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    private val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if(!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassTurnstile(request)?.let {
                        Log.d(TAG, "Succeeded bypassing Turnstile: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceedWithCookies(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed Turnstile at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return safe { CookieManager.getInstance()?.getCookie(url) }
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("_as_turnstile").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceedWithCookies(request: Request, cookies: Map<String, String>): Response {
        val userAgent = WebViewResolver.getWebViewUserAgent()
        val requestBuilder = request.newBuilder()
        
        userAgent?.let { requestBuilder.header("User-Agent", it) }

        val existingCookies = request.header("Cookie") ?: ""
        val newCookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        
        val finalCookie = if (existingCookies.isNotEmpty()) "$existingCookies; $newCookieString" else newCookieString
        if (finalCookie.isNotEmpty()) {
            requestBuilder.header("Cookie", finalCookie)
        }

        return app.baseClient.newCall(requestBuilder.build()).await()
    }

    private suspend fun bypassTurnstile(request: Request): Response? {
        val url = request.url.toString()

        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve Turnstile for ${request.url}")
            WebViewResolver(
                Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(url) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceedWithCookies(request, cookies)
    }
}
