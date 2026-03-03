package com.layarasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class LayarasiaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarasiaProvider())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(BuzzServer())
        registerExtractorAPI(Nunaupns())
        registerExtractorAPI(Nunap2p())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Minochinos())
    }
}
