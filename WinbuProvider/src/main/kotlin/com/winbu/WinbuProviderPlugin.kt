package com.winbu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class WinbuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WinbuProvider())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Vidhidepre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Gdriveplayerto())
        registerExtractorAPI(Playerngefilm21())
        registerExtractorAPI(Winbustrp2p())
        registerExtractorAPI(Xshotcok())
        registerExtractorAPI(Dsvplay())
    }
}
