

package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NimegamiPlugin : Plugin() {
    override fun load(context: Context) {
    
        registerMainAPI(Nimegami())
        registerExtractorAPI(DlganExtractor())
        registerExtractorAPI(BerkasDriveExtractor())
    }
}
