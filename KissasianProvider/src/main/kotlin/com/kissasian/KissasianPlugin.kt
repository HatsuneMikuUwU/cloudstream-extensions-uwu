package com.kissasian

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class KissasianPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kissasian())
        registerExtractorAPI(Strcloud())
        registerExtractorAPI(Myvidplay()) 
        registerExtractorAPI(Justplay())
        registerExtractorAPI(ByseSX())
        registerExtractorAPI(Streamtape2())
    }
}
