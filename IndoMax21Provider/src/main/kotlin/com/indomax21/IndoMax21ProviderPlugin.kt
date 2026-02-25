package com.indomax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoMax21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IndoMax21Provider())
    }
}
