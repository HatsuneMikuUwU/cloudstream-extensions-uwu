package com.animesail

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    private val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val host = request.url.host

        val currentCookies = savedCookies[host]
        val response = if (currentCookies != null) {
            proceed(request, currentCookies)
        } else {
            chain.proceed(request)
        }

        if (response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES) {
            response.close()
            
            bypassCloudflare(request)?.let {
                Log.d(TAG, "Succeeded bypassing cloudflare for: ${request.url}")
                return@runBlocking it
            }
        }

        return@runBlocking response
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        Log.d(TAG, "Loading webview to solve cloudflare for $url")
        WebViewResolver(
            Regex(".^"), 
            userAgent = null, 
            useOkhttp = false,
            additionalUrls = listOf(Regex("."))
        ).resolveUsingWebView(url) {
            trySolveWithSavedCookies(request)
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return safe {
            val cookieString = CookieManager.getInstance()?.getCookie(request.url.toString())
            if (cookieString != null && (cookieString.contains("cf_clearance") || cookieString.contains("_as_turnstile"))) {
                savedCookies[request.url.host] = parseCookieMap(cookieString)
                true
            } else false
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val wmUserAgent = WebViewResolver.getWebViewUserAgent()
        val headers = request.headers.newBuilder().apply {
            if (wmUserAgent != null) set("user-agent", wmUserAgent)
            
            val mergedCookies = cookies + request.cookies
            val cookieHeader = mergedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            set("cookie", cookieHeader)
        }.build()

        return app.baseClient.newCall(request.newBuilder().headers(headers).build()).await()
    }
}
