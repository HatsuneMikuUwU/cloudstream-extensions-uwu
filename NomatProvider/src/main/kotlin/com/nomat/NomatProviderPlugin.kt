package com.nomat

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NomatProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NomatProvider())
        registerExtractorAPI(Hydrax())
    }
}
