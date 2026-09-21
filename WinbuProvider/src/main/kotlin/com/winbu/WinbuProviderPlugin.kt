package com.winbu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class WinbuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WinbuProvider())
        registerExtractorAPI(FiledonExtractor())
        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(GofileExtractor())
        registerExtractorAPI(BuzzheavierExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(BloggerExtractor())
        registerExtractorAPI(StrP2PExtractor())
    }
}
