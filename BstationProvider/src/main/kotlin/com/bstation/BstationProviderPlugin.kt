package com.bstation

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BstationProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        BstationProvider.context = context
        registerMainAPI(BstationProvider())
    }
}
