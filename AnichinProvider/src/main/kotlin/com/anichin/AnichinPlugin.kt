package com.anichin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class AnichinPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anichin())
    }
}
