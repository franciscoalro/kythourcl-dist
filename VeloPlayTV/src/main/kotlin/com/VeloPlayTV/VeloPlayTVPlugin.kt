package com.VeloPlayTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class VeloPlayTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VeloPlayTV())
    }
}
