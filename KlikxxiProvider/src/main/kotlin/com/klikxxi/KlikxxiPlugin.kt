package com.klikxxi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KlikxxiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Klikxxi())
        registerExtractorAPI(Klixxistrp2p())
        registerExtractorAPI(Klixxiupns())
        registerExtractorAPI(Hglink())  
    }
}
