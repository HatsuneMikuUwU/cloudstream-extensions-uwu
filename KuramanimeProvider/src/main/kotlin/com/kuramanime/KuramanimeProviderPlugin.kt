
package com.kuramanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KuramanimeProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KuramanimeProvider())
        registerExtractorAPI(Nyomo())
        registerExtractorAPI(Streamhide())
        registerExtractorAPI(Kuramadrive())
        registerExtractorAPI(Lbx())
        registerExtractorAPI(KuramadriveV1())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Dropbox())
        registerExtractorAPI(PikPak())
        registerExtractorAPI(MegaNz())
    }
}