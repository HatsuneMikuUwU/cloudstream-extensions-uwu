
package com.kuramanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*
import android.content.Context

@CloudstreamPlugin
class KuramanimeProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KuramanimeProvider())
        registerExtractorAPI(Kuramadrive())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(VidHideHub())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(Acefile())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Mediafire())
    }
}