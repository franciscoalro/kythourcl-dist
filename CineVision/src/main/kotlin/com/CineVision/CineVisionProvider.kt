package com.CineVision

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineVisionProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVision())
    }
}
