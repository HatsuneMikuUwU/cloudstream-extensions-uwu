package com.anichin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinProviderPlugin: Plugin() {
    override fun load(context: Context) {
        AnichinProvider.context = context
        registerMainAPI(AnichinProvider())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Rpmvid())
        registerExtractorAPI(Turbovidhls())
    }
}
