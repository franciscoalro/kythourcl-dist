package com.RedeCanais

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RedeCanaisProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RedeCanais())
    }
}
