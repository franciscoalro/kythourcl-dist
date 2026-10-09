package com.lagradost.cloudstream3.plugins

annotation class CloudstreamPlugin

abstract class Plugin {
    abstract fun load(context: Any)
    open fun registerMainAPI(api: Any) {}
    open fun registerExtractorAPI(api: Any) {}
}
