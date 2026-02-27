package com.drakor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DrakorProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DrakorProvider())
        registerExtractorAPI(Jeniusplay())
    }
}
