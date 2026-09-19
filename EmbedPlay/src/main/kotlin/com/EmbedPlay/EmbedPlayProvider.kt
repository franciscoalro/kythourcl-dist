package com.EmbedPlay

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EmbedPlayProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EmbedPlay())
    }
}
