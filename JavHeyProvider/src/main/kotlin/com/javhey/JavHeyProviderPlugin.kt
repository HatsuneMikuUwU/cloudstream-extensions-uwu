package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // <--- PENTING: Ini class induknya
import android.content.Context

@CloudstreamPlugin
class JavHeyProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavHeyProvider())
    }
}
