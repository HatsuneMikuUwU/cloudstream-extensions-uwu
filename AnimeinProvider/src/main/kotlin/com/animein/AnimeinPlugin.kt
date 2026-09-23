package com.animein

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeinPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Animein())
    }
}
