package com.RedeCanaisAF

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.util.Log

@CloudstreamPlugin
class RedeCanaisAFProvider: Plugin() {
    override fun load(context: Context) {
        CloudflareSolver.setAppContext(context)
        val api = RedeCanaisAF()
        registerMainAPI(api)

        // Restaura síncrono ANTES do framework disparar getMainPage — garante hit 0ms no primeiro REQ
        try {
            CloudflareSolver.restoreDiskCacheIfNeeded()
            CloudflareSolver.restoreClearanceIfValid(api.mainUrl)
        } catch (_: Throwable) {}
        // v228: SEM warm-up de rede no boot — o getMainPage silencioso duplicava o
        // tráfego (6 REQs do framework + 1 do warmup disputando o mutex do solver =
        // WebViews extras de 5MB cada). Com host em 91% RAM + LMK ativo, cada WebView
        // extra aproxima o app do signal 9. O framework já busca as 6 categorias.
        Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] skip (v228) — framework busca as 6 categorias direto")
    }
}
