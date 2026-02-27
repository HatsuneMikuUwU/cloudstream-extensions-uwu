package com.cinemax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Cinemax21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cinemax21())
        registerExtractorAPI(Jeniusplay())
    }
}
