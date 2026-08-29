package com.NetCine

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NetCineProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NetCine())
    }
}
