package com.CineGato

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineGatoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineGato())
    }
}