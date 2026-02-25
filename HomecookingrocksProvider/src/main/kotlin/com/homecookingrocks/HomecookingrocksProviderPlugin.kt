package com.homecookingrocks

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HomecookingrocksProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HomecookingrocksProvider())
    }
}
