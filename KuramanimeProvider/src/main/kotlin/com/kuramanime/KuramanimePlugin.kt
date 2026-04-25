package com.kuramanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuramanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kuramanime())
        registerExtractorAPI(MyvidplayAz())
        registerExtractorAPI(Kuramavip())
    }
}
