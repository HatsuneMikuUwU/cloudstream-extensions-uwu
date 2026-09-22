package com.animasu

import android.content.Context
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimasuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimasuProvider())

        // Custom extractors for hosts commonly used by Animasu mirrors.
        registerExtractorAPI(BloggerExtractor())
        registerExtractorAPI(FiledonExtractor())
        registerExtractorAPI(YourUploadExtractor())

        // Extractors already shipped with Cloudstream core for the other
        // mirror hosts seen on the player page (VidHidePro, YourUpload, ...).
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(VidHideHub())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(Mediafire())
    }
}
