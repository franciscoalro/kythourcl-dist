package com.AnimesOrion

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimesOrionProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesOrion())
    }
}
