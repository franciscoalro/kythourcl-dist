package com.RedeCanaisAF

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CloudstreamPlugin
class RedeCanaisAFProvider: Plugin() {
    override fun load(context: Context) {
        val api = RedeCanaisAF()
        registerMainAPI(api)

        // v148: warm-up LEVE — só restaura cf_clearance + html cache de disco (<100ms).
        // v144 chamava getMainPage("home") que usava URL inválida "home-1-date.html" e segurava
        // o interactiveMutex por até 45s, fazendo a home real ficar em "Precarregamento"/loading
        // até o diálogo expirar. Agora o load() retorna instantâneo; a home usa o caminho rápido
        // de disco ou o diálogo serializado normal (primeiro REQ resolve, demais pegam cache ~0ms).
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] restauração rápida (sem rede/WebView)...")
                val restoredClearance = CloudflareSolver.restoreClearanceIfValid(api.mainUrl)
                val restoredCache = CloudflareSolver.restoreDiskCacheIfNeeded()
                Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] ok clearance=$restoredClearance cacheDisco=$restoredCache (load instantâneo)")
            } catch (e: Throwable) {
                Log.d("RedeCanaisAF-Trace", "[BOOT_WARMUP] finalizado: ${e.message}")
            }
        }
    }
}
