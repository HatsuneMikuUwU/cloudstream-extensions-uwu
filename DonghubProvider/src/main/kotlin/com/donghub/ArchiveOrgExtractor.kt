package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor","url = $url")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = INFER_TYPE,
                    {
                        this.referer = referer ?: mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
            )
        }
    }