package com.animeindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeIndoProvider())
        registerExtractorAPI(Gdplayer())
        registerExtractorAPI(Xtwap())
    }
}
