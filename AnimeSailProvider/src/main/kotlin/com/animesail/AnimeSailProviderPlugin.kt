package com.animesail

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeSailProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSailProvider())
        registerExtractorAPI(AnimeSailPixeldrainExtractor())
        registerExtractorAPI(AnimeSailBuzzheavierExtractor())
        registerExtractorAPI(AnimeSailAbyssExtractor())
        registerExtractorAPI(AnimeSailFiledonExtractor())
        registerExtractorAPI(AnimeSailDoplyExtractor())
        registerExtractorAPI(AnimeSailVikingFileExtractor())
        registerExtractorAPI(AnimeSailMixdropCloneExtractor())
        registerExtractorAPI(AnimeSailAceFileExtractor())
    }
}