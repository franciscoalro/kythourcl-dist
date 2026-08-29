package com.RedeCanaisAF

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainPageRequest
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CloudstreamPlugin
class RedeCanaisAFProvider: Plugin() {
    override fun load(context: Context) {
        val api = RedeCanaisAF()
        registerMainAPI(api)
        
        // Warm-up em segundo plano assim que o CloudStream inicializa
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] Inicializando pré-carregamento silencioso no boot do app...")
                val cookies = CookieManager.getInstance().getCookie(api.mainUrl) ?: ""
                Log.d("RedeCanaisAF-Trace", "[BOOT_WARMUP] Cookies existentes no boot | len=${cookies.length}")
                
                // Pré-aquece o cache de lançamentos
                api.getMainPage(1, MainPageRequest("home", "Lançamentos", false))
                Log.i("RedeCanaisAF-Trace", "[BOOT_WARMUP] Pré-carregamento concluído com sucesso!")
            } catch (e: Throwable) {
                Log.d("RedeCanaisAF-Trace", "[BOOT_WARMUP] Warm-up em background finalizado: ${e.message}")
            }
        }
    }
}
