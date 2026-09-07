package com.RedeCanaisAF

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainPageRequest
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // Warm-up assíncrono: só loga e pré-aquece se realmente vazio (evita wave WebView duplicado)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val ramCount = CloudflareSolver.capturedCount()
                val restored = ramCount > 0
                if (restored) {
                    Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] disk RAM restored count=$ramCount — catálogo já em memória")
                } else {
                    Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] no disk hit — cf_clearance restored check done")
                    Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] cache vazio — disparando getMainPage silencioso")
                    api.getMainPage(1, MainPageRequest("Filmes Lançamentos", "${api.mainUrl}/browse-filmes-videos-1-date.html", false))
                }
                Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] done in ${System.currentTimeMillis()-now}ms")
            } catch (e: Throwable) {
                Log.d("RedeCanaisAF-Trace", "[BOOT_WARMUP] warmup skip: ${e.message}")
            }
        }
    }
}
