package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * v122 — Proxy local de stream via WebView.
 *
 * Por que existe: a URL real do vídeo (__RC__/proxy?src=<p12-common-sign...>) é TLS-bound
 * (JA3 fingerprint). O cf_clearance do Cloudflare só vale para o TLS do navegador que o
 * emitiu — curl/OkHttp/VLC/ExoPlayer direto recebem 520. Provado via browser-harness:
 * o elemento <video> no contexto da página obtém 206 (1.9MB streamed), fetch externo = 520.
 *
 * Solução: o WebView do app carrega server.php (mesma sessão cf_clearance+RCSESS), clica
 * no recap (.captcha_button), captura a URL __RC__/proxy que o player usa, e um ServerSocket
 * local (127.0.0.1) serve essa URL ao ExoPlayer fazendo fetch chunk-a-chunk DENTRO do
 * contexto JS do WebView (credentials: include + Range) — mesmo TLS, mesmo fingerprint.
 */
object WebViewStreamProxy {
    private const val TAG = "RedeCanaisAF-Trace"
    // v123: 512KB por fetch (era 256KB) — decode via FileReader.readAsDataURL é nativo e rápido,
    // então chunks maiores reduzem round-trips sem custo de CPU no JS
    private const val CHUNK_SIZE = 512 * 1024
    private const val CAPTURE_TIMEOUT_MS = 45000L
    private const val CHUNK_FETCH_TIMEOUT_S = 20L
    private const val POLL_INTERVAL_MS = 100L
    private const val CLICK_RETRY_MS = 250L
    private const val DIRECT_FALLBACK_MS = 1500L
    private const val RELOAD_FIRST_MS = 8000L
    private const val RELOAD_INTERVAL_MS = 4000L
    private const val MAX_RELOADS = 3
    private const val DUD_CLICK_MS = 9000L
    private val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    @Volatile private var webView: WebView? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var streamUrl: String? = null
    @Volatile private var isServing = false

    /**
     * Captura o stream real (__RC__/proxy) abrindo server.php no WebView e clicando no recap.
     * Retorna a URL local http://127.0.0.1:<porta>/stream.mp4 que o ExoPlayer deve reproduzir.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureAndServe(serverPhpUrl: String): String? {
        shutdown() // limpa estado anterior
        // Poster fetches have already completed before playback. Release their helper
        // renderer so the full-size hardware player WebView does not overlap it.
        LocalImageProxy.shutdownHelper()

        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[PROXY] Activity indisponível")
            return null
        }

        Log.i(TAG, "[PROXY] Iniciando captura WebView para $serverPhpUrl")

        val captured = AtomicBoolean(false)
        val pageReady = AtomicBoolean(false)
        val clickDone = AtomicBoolean(false)
        val captureHolder = AtomicBoolean(false)
        var wv: WebView? = null
        val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val userAgent = CloudflareSolver.lastUserAgent
                    ?: WebViewResolver.webViewUserAgent
                    ?: MOBILE_UA

                // v230: MATCH_PARENT invisível (alpha 0.01, atrás do conteúdo) +
                // HARDWARE durante a captura — dois motivos provados no diag:
                // (a) o bundle (videojs/ima3) pode exigir pipeline de mídia real
                // para montar o __RC__/proxy; GONE+SOFTWARE congelava o player
                // (v229: btn+rcFn presentes 45s sem nenhuma chamada de API);
                // (b) com layout 1x1 o getBoundingClientRect retorna coords fora
                // dos limites da view (ex: 60.0,0.5) e o dispatchTouchEvent é
                // descartado — com viewport real as coords caem dentro da view.
                // Vida curta (destruído no shutdown ao capturar/timeout) — 1 WebView
                // transiente não pressiona o LMK como os permanentes da v228.
                val view = WebView(activity).apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 0.01f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isLongClickable = false
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = userAgent
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null)
                        }

                        // v232: captura console.log do hook de fetch/XHR/submit do body
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                            try {
                                val m = msg?.message().orEmpty()
                                if (m.contains("[HOOK]", true) || m.contains("Uncaught", true) ||
                                    m.contains("Failed to load", true) || m.contains("Error", true)) {
                                    Log.i(TAG, "[HOOK_JS] $m @ ${msg?.sourceId()?.take(80)}:${msg?.lineNumber()}")
                                }
                            } catch (_: Throwable) {}
                            return super.onConsoleMessage(msg)
                        }
                    }

                    // v232e: hook total — intercepta TODO fluxo cliente->servidor->meio
                    // (fetch/XHR/submit/click/video) e expõe ao Kotlin via window.__rc*
                    // para diagnóstico do túnel Redemovel/RCIP. Anteriormente só logava
                    // bodies com __RC__/proxy (perdia serverforms vazio len=52 que indica
                    // túnel caído). Agora registra 100% dos bodies em __rcBodies.
                    val hookJs = """(function(){
                        if(window.__rcHook) return;
                        window.__rcHook=true;
                        try{
                          window.__rcCaptured='';
                          window.__rcCapturedRawLen=0;
                          window.__rcLastFetchUrl='';
                          window.__rcLastFetchBody='';
                          window.__rcLastFetchCt='';
                          window.__rcLastFetchStatus=0;
                          window.__rcBodies=[];
                          window.__rcFetchCount=0;
                          const ofetch=window.fetch;
                          if(ofetch) window.fetch=function(u,o){
                            const urlStr=String(u).slice(0,400);
                            window.__rcLastFetchUrl=urlStr;
                            try{ console.log('[HOOK] fetch '+urlStr+' opts='+JSON.stringify(o||{}).slice(0,250)+' cookies='+document.cookie.slice(0,200)); }catch(_){}
                            const p=ofetch.apply(this, arguments);
                            try{
                              p.then(function(r){
                                try{
                                  const status=r.status, loc=r.headers.get('location')||r.headers.get('Location')||'';
                                  const ct=r.headers.get('content-type')||'';
                                  window.__rcLastFetchStatus=status;
                                  window.__rcLastFetchCt=ct;
                                  console.log('[HOOK] fetch-resp '+status+' ct='+ct.slice(0,60)+' loc='+String(loc).slice(0,250)+' url='+urlStr.slice(0,200));
                                  // v234: only inspect small metadata responses. Cloning every
                                  // fetch used to materialize media/chunks as text and amplify
                                  // WebView native memory during playback.
                                  const contentLength=parseInt(r.headers.get('content-length')||'0',10)||0;
                                  const isMetadata=urlStr.indexOf('serverforms.api')>=0||urlStr.indexOf('dt.api')>=0;
                                  // Stream capture itself is done by shouldInterceptRequest;
                                  // cloning arbitrary text documents is unnecessary here.
                                  if(!isMetadata||contentLength>1048576) return;
                                  const cl=r.clone();
                                  cl.text().then(function(t){
                                    if(t.length>1048576){ console.log('[HOOK] fetch-body skipped oversized metadata len='+t.length); return; }
                                    window.__rcFetchCount++;
                                    window.__rcLastFetchBody=t;
                                    try{ window.__rcBodies.push({url:urlStr, ct:ct, status:status, body:t.slice(0,3000)}); if(window.__rcBodies.length>20) window.__rcBodies.shift(); }catch(_){}
                                    const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('tos-alisg')>=0||t.indexOf('container=videos')>=0||t.indexOf('.m3u8')>=0||t.indexOf('.mp4')>=0||t.indexOf('https://')>=0;
                                    const hasLoc=t.indexOf('location')>=0||t.indexOf('src=')>=0;
                                    // v232e: SEMPRE loga body (antes só proxy||<3k) — crítico p/ serverforms vazio 204 []
                                    const preview=t.slice(0,900).replace(/\n/g,' ').replace(/\r/g,'');
                                    console.log('[HOOK] fetch-body #'+window.__rcFetchCount+' len='+t.length+' ct='+ct.slice(0,40)+' proxy='+hasProxy+' url='+urlStr.slice(0,120)+' preview='+preview);
                                    // diagnóstico túnel: serverforms com [] = backend sem stream
                                    if(urlStr.indexOf('serverforms')>=0 || urlStr.indexOf('dt.api')>=0){
                                      console.log('[HOOK] serverforms payload len='+t.length+' body='+t.slice(0,1200).replace(/\n/g,' '));
                                      try{
                                        const j=JSON.parse(t);
                                        console.log('[HOOK] serverforms JSON keys='+Object.keys(j).join(',')+' vals='+JSON.stringify(j).slice(0,900));
                                        // detecta túnel caído: array vazio + code 204
                                        if(j.e18b73c9 && Array.isArray(j.e18b73c9) && j.e18b73c9.length===0){
                                          console.log('[HOOK] TUNEL_VAZIO serverforms retornou e18b73c9=[] (código interno 204) — origem não determinada; pode ser sessão, disponibilidade ou política do servidor');
                                        }
                                        if(j.c4a0f6) console.log('[HOOK] c4a0f6 len='+(String(j.c4a0f6).length)+' preview='+String(j.c4a0f6).slice(0,150));
                                      }catch(e){ console.log('[HOOK] serverforms JSON parse err '+e.message); }
                                    }
                                    try{
                                      let found='';
                                      const m=t.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                      if(m) found=m[0];
                                      if(!found){
                                        const m2=t.match(/https?:\/\/[^\s"'\\]*tos-alisg[^\s"'\\]*/i);
                                        if(m2) found=m2[0];
                                      }
                                      if(!found){
                                        const m3=t.match(/https?:\/\/[^\s"'\\]*\/proxy\?container=[^\s"'\\]*/i);
                                        if(m3) found=m3[0];
                                      }
                                      if(!found){
                                        const m4=t.match(/https?:\/\/[^\s"'\\]*__RC__[^\s"'\\]*/i);
                                        if(m4) found=m4[0];
                                      }
                                      if(!found){
                                        try{
                                          const j=JSON.parse(t);
                                          const vals=JSON.stringify(j);
                                          const m5=vals.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                          if(m5) found=m5[0];
                                          if(!found){
                                            const m6=vals.match(/https?:\\\/\\\/[^\s"'\\]{20,}/);
                                            if(m6) found=m6[0].replace(/\\\//g,'/');
                                          }
                                          // fallback: qualquer https longo em JSON (proxy sem extensão)
                                          if(!found && vals.indexOf('https')>=0){
                                            const m7=vals.match(/https?:[^\s"'\\]{15,}/);
                                            if(m7) found=m7[0].replace(/\\\//g,'/');
                                          }
                                          // tenta decodificar base64 se houver
                                          if(!found){
                                            for(const k of Object.keys(j)){
                                              const v=j[k];
                                              if(typeof v==='string' && v.length>20){
                                                try{ const dec=atob(v); if(dec.indexOf('http')>=0) { found=dec.match(/https?:\/\/[^\s"'\\]+/)?.[0]||''; if(found) break; } }catch(_){}
                                              }
                                            }
                                          }
                                        }catch(_){}
                                      }
                                      if(found){
                                        window.__rcCaptured=found;
                                        window.__rcCapturedRawLen=t.length;
                                        console.log('[HOOK] captured stream '+found.slice(0,320));
                                      } else if(hasProxy){
                                        window.__rcCaptured=t.slice(0,800);
                                        console.log('[HOOK] captured raw proxy body len='+t.length);
                                      }
                                      // sempre guarda último body p/ Kotlin poll (mesmo sem proxy)
                                      window.__rcLastFetchBody=t.slice(0,3000);
                                    }catch(e){ console.log('[HOOK] capture-err '+e.message); }
                                  }).catch(function(e){ console.log('[HOOK] fetch-body-err '+e.message); });
                                }catch(e){ console.log('[HOOK] fetch-resp-err '+e.message); }
                                return r;
                              }).catch(function(e){ console.log('[HOOK] fetch-reject '+e.message+' url='+urlStr.slice(0,200)); });
                            }catch(_){}
                            return p;
                          };
                          const oOpen=XMLHttpRequest.prototype.open;
                          XMLHttpRequest.prototype.open=function(m,u){
                            try{ console.log('[HOOK] XHR open '+m+' '+String(u).slice(0,260)); }catch(_){}
                            return oOpen.apply(this, arguments);
                          };
                          const oSend=XMLHttpRequest.prototype.send;
                          XMLHttpRequest.prototype.send=function(b){
                            try{ console.log('[HOOK] XHR send '+(b?String(b).slice(0,300):'-')); }catch(_){}
                            // espia resposta XHR
                            try{
                              const xhr=this;
                              const prev=xhr.onload;
                              const onLoad=function(){
                                try{
                                  const t=xhr.responseText||'';
                                  const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('.m3u8')>=0;
                                  console.log('[HOOK] XHR-resp status='+xhr.status+' len='+t.length+' proxy='+hasProxy+' preview='+t.slice(0,400).replace(/\n/g,' '));
                                }catch(e){ console.log('[HOOK] XHR-resp-err '+e.message); }
                                if(prev) return prev.apply(this, arguments);
                              };
                              if(xhr.addEventListener) xhr.addEventListener('load', onLoad, false); else xhr.onload=onLoad;
                            }catch(_){}
                            return oSend.apply(this, arguments);
                          };
                          document.addEventListener('submit', function(e){
                            try{ console.log('[HOOK] submit action='+(e.target&&e.target.action||'')+' method='+(e.target&&e.target.method||'')); }catch(_){}
                          }, true);
                          window.addEventListener('error', function(e){ try{ console.log('[HOOK] window-error '+e.message+' @'+(e.filename||'').slice(0,80)+':'+e.lineno); }catch(_){} }, true);
                          window.addEventListener('unhandledrejection', function(e){ try{ console.log('[HOOK] unhandledrejection '+(e.reason&&e.reason.message||String(e.reason)).slice(0,300)); }catch(_){} }, true);
                          // v232: intercepta navegação (o form action="?" sem preventDefault
                          // causa GET server.php? e o reload perde o handler do botão).
                          window.addEventListener('beforeunload', function(){ try{ console.log('[HOOK] beforeunload href='+location.href.slice(0,120)); }catch(_){} }, true);
                          document.addEventListener('click', function(e){
                            try{
                              const t=e.target;
                              console.log('[HOOK] click tag='+(t&&t.tagName||'')+' id='+(t&&t.id||'')+' class='+(t&&t.className||'').toString().slice(0,60)+' prevented='+e.defaultPrevented+' trusted='+e.isTrusted);
                            }catch(_){}
                          }, true);
                          // v232c: loga criação de <video> / <source> e mutações do #player
                          try{
                            const obs=new MutationObserver(function(muts){
                              muts.forEach(function(m){
                                m.addedNodes.forEach(function(n){
                                  try{
                                    const tag=n.tagName||'';
                                    const src=n.src||n.currentSrc||'';
                                    console.log('[HOOK] dom-add tag='+tag+' src='+String(src).slice(0,200)+' html='+String(n.outerHTML||'').slice(0,300).replace(/\n/g,' '));
                                  }catch(_){}
                                });
                                if(m.type==='attributes' && m.target.tagName==='VIDEO'){
                                  try{ console.log('[HOOK] video-attr '+m.attributeName+'='+String(m.target.getAttribute(m.attributeName)).slice(0,200)); }catch(_){}
                                }
                              });
                            });
                            obs.observe(document.documentElement||document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src','currentSrc']});
                            console.log('[HOOK] mutation-observer installed');
                          }catch(e){ console.log('[HOOK] observer-err '+e.message); }
                          try{
                            const origCreate=document.createElement.bind(document);
                            document.createElement=function(tag){
                              const el=origCreate(tag);
                              if(String(tag).toLowerCase()==='video' || String(tag).toLowerCase()==='source'){
                                console.log('[HOOK] createElement '+tag);
                                try{
                                  const desc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src')||Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype,'src');
                                  if(desc&&desc.set){
                                    let origSet=desc.set;
                                    Object.defineProperty(el,'src',{set:function(v){ console.log('[HOOK] video.src set '+String(v).slice(0,300)); return origSet.call(this,v); }, get:desc.get, configurable:true});
                                  }
                                }catch(_){}
                              }
                              return el;
                            };
                          }catch(e){ console.log('[HOOK] createElement-hook-err '+e.message); }
                          console.log('[HOOK] installed href='+location.href.slice(0,120));
                        }catch(e){ console.log('[HOOK] install-err '+e.message); }
                    })();""".trimIndent()
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            Log.w(TAG, "[CF_WV] onRenderProcessGone seguro acionado (didCrash=${detail?.didCrash()})")
                            try {
                                (view?.parent as? ViewGroup)?.removeView(view)
                                view?.destroy()
                            } catch (_: Throwable) {}
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                            try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                            try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                            pageReady.set(true)
                            Log.d(TAG, "[PROXY] onPageFinished url=$finishedUrl")
                        }

                        // v229e: deixa a navegação pós-click ACONTECER (não contém) mas
                        // marca para reemitir a URL original se a query se perder —
                        // provado 12:55: click -> server.php? (contido = player congelado,
                        // só bundle.js+jquery.js, nenhuma API, 45s em vão). Sem contenção,
                        // o fluxo segue (server.php? -> sessão -> player real); o reload
                        // com query params (v229) recupera se cair em 520.
                        // (Contain removido; onPageFinished com query vazia só loga.)
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString().orEmpty()
                            if (u.contains("server.php?", true) && captured.get().not()) {
                                Log.i(TAG, "[PROXY] navegação pós-click liberada: $u")
                            }
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val u = request.url.toString()
                            // v232: loga TODA navegação/requisição do server.php (não só .api)
                            // — o body script pode fazer GET a server.php?action=... sem sufixo .api.
                            if (!u.contains("google", true) && !u.contains("disqus", true) && !u.contains("facebook", true) && !u.contains("gstatic", true)) {
                                // evita spam de fonte/css mas mantém XHR/fetch visíveis
                                val isApi = u.contains(".api", true) || u.contains("query", true) ||
                                    u.contains("bundle", true) || u.contains("serverforms", true) ||
                                    u.contains("dt.", true) || u.contains("getvid", true) ||
                                    u.contains("getlink", true) || u.contains("redirect", true) ||
                                    u.contains("server.php", true) || u.contains("__RC__", true) ||
                                    u.contains("proxy", true) || u.contains(".m3u8", true) || u.contains(".mp4", true)
                                if (isApi || request.method != "GET" || u.contains("player3", true)) {
                                    Log.i(TAG, "[PROXY_REQ] ${request.method} ${if (request.isForMainFrame) "MAIN " else ""}${u.take(320)}")
                                }
                            }
                                   val isMediaStream = (u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true) ||
                                u.contains("p12-common-sign", true) || u.contains("xn--l", true) ||
                                u.contains("neosoro.gq", true) || u.contains("tos-alisg", true) ||
                                u.contains("/proxy?container=", true) || u.contains("container=videos", true) ||
                                (u.contains(".mp4", true) && !u.contains(".jpg") && !u.contains(".png")) ||
                                u.contains(".m3u8", true) || u.contains("/ondemand/", true) || u.contains("/videos/", true)) &&
                                !u.contains("disqus", true) && !u.contains("chatango", true) && !u.contains("google", true)

                            if (isMediaStream && !captured.get()) {
                                streamUrl = u
                                captured.set(true)
                                captureHolder.set(true)
                                Log.i(TAG, "[PROXY] Stream capturado via shouldInterceptRequest: ${u.take(180)}")
                                try {
                                    val ctx2 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                    ctx2?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(u) }
                                } catch (_: Throwable) {}
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                    }
                }
                wv = view
                webView = view
                rootLayout.addView(view, 0)
                view.loadUrl(serverPhpUrl)
            } catch (e: Throwable) {
                Log.e(TAG, "[PROXY] Erro ao criar WebView: ${e.message}")
            }
        }

        // ===== Loop de captura v123: clica no recap assim que o DOM estiver pronto =====
        // v123: não espera mais onPageFinished inteiro — o botão .captcha_button existe antes.
        // Poll 200ms, fallbacks por tempo real (3s/6s/12s) em vez de System.currentTimeMillis() % N.
        var reloadCount = 0
        var lastClickMs = 0L
        var lastReloadCheckMs = 0L
        var lastDirectFallbackMs = 0L
        var clickDoneAtMs = 0L
        // v229c: player montado (btn+rcPreloadPlayer visíveis no diag) — enquanto true,
        // nenhum reload: o bundle precisa de >10s após o click para montar o __RC__/proxy.
        val playerMounted = AtomicBoolean(false)
        // v125: o fallback direto (rcPreloadPlayer manual) roda NO MÁXIMO uma vez por ciclo —
        // chamadas repetidas a cada 3s podem reiniciar a montagem do player (serverforms.api)
        var directFallbackDone = false
        val startMs = System.currentTimeMillis()
        // v126: orçamento por tentativa cortado p/ 45s — o framework cancela loadLinks em
        // ~120s (TimeoutCancellationException), então 2 tentativas (RCServer01 + RCFServer2)
        // precisam caber em 90s. Antigo 120s por tentativa abortava antes do fallback.
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            while (!captured.get()) {
                delay(POLL_INTERVAL_MS) // v123: 200ms (era 500ms)
                val now = System.currentTimeMillis()
                val wvNow = wv ?: continue

                // 1) Clica no recap assim que o DOM estiver pronto (não espera onPageFinished).
                // v123: retry a cada CLICK_RETRY_MS até o click ser efetivo ('click') —
                // se rodar antes do DOM existir, retorna 'wait' e tenta de novo.
                // v125: espera window.rcPreloadPlayer existir ANTES de clicar (como o
                // browser-harness da Fase 51 fazia) — se o script do player ainda não
                // carregou, o handler do botão não existe e o clique não dispara nada.
                // v229d: NUNCA chama window.rcPreloadPlayer() diretamente — o diag provou
                // que a chamada direta rejeita com "Error: 7a2f" (~250ms depois) e envenena
                // o estado interno do player (nunca mais monta o __RC__/proxy). Só o
                // handler do próprio botão sabe os args/contexto certos (token recap).
                if (!clickDone.get() && captured.get().not() && now - lastClickMs >= CLICK_RETRY_MS) {
                    lastClickMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    const cfIframe = document.querySelector("iframe[src*='challenges.cloudflare.com']");
                                    if (cfIframe) {
                                        const rect = cfIframe.getBoundingClientRect();
                                        if (rect.width > 0 && rect.height > 0) {
                                            return 'cf:' + (rect.left + 35) + ':' + (rect.top + rect.height/2);
                                        }
                                    }
                                    const b = document.getElementById('submit') || document.querySelector('.captcha_button');
                                    const hasFn = (typeof window.rcPreloadPlayer === 'function');
                                    if (!b || !hasFn) return 'wait';
                                    // v230: retorna coordenadas do botão para toque REAL via
                                    // dispatchTouchEvent — b.click() sintético não aciona o
                                    // handler do bundle (v229: 45s sem API após click).
                                    // v231: validação de visibilidade — offsetParent!==null,
                                    // rect dentro do viewport, w/h > 0 e scrollIntoView para
                                    // garantir layout resolvido. WebView MATCH_PARENT dá
                                    // viewport real (1x1 retornava coords fora da view).
                                    if (b.offsetParent === null) return 'hidden';
                                    b.scrollIntoView({block:'center'});
                                    const r = b.getBoundingClientRect();
                                    const cx = r.left + r.width/2, cy = r.top + r.height/2;
                                    if (r.width <= 0 || r.height <= 0) return 'zerosize';
                                    if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return 'offscreen:' + Math.round(cx) + ':' + Math.round(cy);
                                    return 'tap:' + cx + ':' + cy;
                                })();""".trimIndent()
                            ) { res ->
                                val r = res?.removeSurrounding("\"")
                                Log.i(TAG, "[PROXY] click recap -> $r")
                                // v230: toque REAL (DOWN+UP) nas coordenadas — serve tanto
                                // para o Turnstile (cf:x:y) quanto para o botão recap
                                // (tap:x:y). b.click() sintético não aciona o handler.
                                if (r?.startsWith("cf:") == true || r?.startsWith("tap:") == true) {
                                    val parts = r.split(":")
                                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: 50f
                                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: 50f
                                    val downTime = SystemClock.uptimeMillis()
                                    // v231: validação — coords CSS precisam cair dentro da
                                    // view nativa (coords fora = layout 1x1/scaling; o
                                    // dispatchTouchEvent seria descartado em silêncio).
                                    val vw = wvNow.width.toFloat()
                                    val vh = wvNow.height.toFloat()
                                    val scale = wvNow.scale
                                    val vx = x * scale
                                    val vy = y * scale
                                    if (vx < 0 || vy < 0 || vx > vw || vy > vh) {
                                        Log.w(TAG, "[PROXY] toque fora da view: css=($x,$y) scale=$scale view=(${vw}x$vh) — aguardando layout")
                                        lastClickMs = now // retry: não marca clickDone
                                    } else {
                                        Log.i(TAG, "[PROXY] toque válido: css=($x,$y) -> view=($vx,$vy) view=(${vw}x$vh)")
                                        // Sequência DOWN -> MOVE -> UP (gesto de toque real,
                                        // não tap instantâneo — o bundle pode validar movimento)
                                        val props = arrayOf(
                                            MotionEvent.PointerProperties().apply {
                                                id = 0
                                                toolType = MotionEvent.TOOL_TYPE_FINGER
                                            }
                                        )
                                        val coordsDown = arrayOf(
                                            MotionEvent.PointerCoords().apply {
                                                this.x = vx
                                                this.y = vy
                                                pressure = 1f
                                                size = 1f
                                            }
                                        )
                                        val eventDown = MotionEvent.obtain(
                                            downTime, downTime,
                                            MotionEvent.ACTION_DOWN, 1,
                                            props, coordsDown,
                                            0, 0, 1f, 1f, 0, 0, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventDown)
                                        eventDown.recycle()

                                        val coordsMove = arrayOf(
                                            MotionEvent.PointerCoords().apply {
                                                this.x = vx + 1f
                                                this.y = vy + 1f
                                                pressure = 1f
                                                size = 1f
                                            }
                                        )
                                        val eventMove = MotionEvent.obtain(
                                            downTime, downTime + 40,
                                            MotionEvent.ACTION_MOVE, 1,
                                            props, coordsMove,
                                            0, 0, 1f, 1f, 0, 0, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventMove)
                                        eventMove.recycle()

                                        val eventUp = MotionEvent.obtain(
                                            downTime, downTime + 80,
                                            MotionEvent.ACTION_UP,
                                            vx + 0.5f, vy + 0.5f,
                                            0f, 0f, 0, 1.0f, 1.0f, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventUp)
                                        eventUp.recycle()
                                    }
                                    if (r.startsWith("cf:")) {
                                        Log.i(TAG, "[PROXY] Turnstile checkbox clicado no server.php em ($x, $y)")
                                    } else {
                                        Log.i(TAG, "[PROXY] toque real no recap em ($x, $y)")
                                        clickDone.set(true)
                                        clickDoneAtMs = now
                                        // v231: prova do pós-click — 2s após o toque, espelha
                                        // o innerHTML do <body> na RAM (o outerHTML espelhado
                                        // no PROXY_STATE traz só <head>; os scripts recriam
                                        // o body — player, video, __RC__/proxy — via JS).
                                        wvNow.postDelayed({
                                            try {
                                                wvNow.evaluateJavascript(
                                                    """(function() {
                                                        try {
                                                            const b = document.body ? document.body.innerHTML : '';
                                                            const v = document.querySelector('video');
                                                            const vsrc = v ? (v.currentSrc || v.src || '') : '';
                                                            const inl = Array.from(document.querySelectorAll('script:not([src])')).map(s => s.textContent || '').join('\n');
                                                            // v232: fatiado em 3 partes de ~60KB (limite do
                                                            // evaluateJavascript/JNI estoura em ~120KB e
                                                            // trunca o retorno — o inline full tem ~290KB).
                                                            const p1 = inl.substring(0,60000), p2 = inl.substring(60000,120000), p3 = inl.substring(120000,180000);
                                                            return 'BODY len=' + b.length + ' video=' + (vsrc.substring(0,100) || 'none') + ' INLINE len=' + inl.length + ' ||BODYHTML||' + b.substring(0,20000) + '||P1||' + p1 + '||P2||' + p2 + '||P3||' + p3;
                                                        } catch(e) { return 'BODY ERR ' + e.message; }
                                                    })();""".trimIndent()
                                                ) { res2 ->
                                                    try {
                                                        val c2 = res2?.removeSurrounding("\"").orEmpty()
                                                        Log.i(TAG, "[PROXY_POSTCLICK] " + c2.substringBefore("||BODYHTML||").take(200))
                                                        fun unesc(s: String) = s.replace("\\u003C", "<").replace("\\u003E", ">")
                                                            .replace("\\\"", "\"").replace("\\n", "\n")
                                                        val bodyHtml = unesc(c2.substringAfter("||BODYHTML||", "").substringBefore("||INLINE||", ""))
                                                        if (bodyHtml.length > 200) {
                                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl + "#body", bodyHtml)
                                                        }
                                                        // v232: remonta o inline fatiado (P1+P2+P3) e grava
                                                        // em partes (mirror tem threshold mínimo).
                                                        val p1 = unesc(c2.substringAfter("||P1||", "").substringBefore("||P2||", ""))
                                                        val p2 = unesc(c2.substringAfter("||P2||", "").substringBefore("||P3||", ""))
                                                        val p3 = unesc(c2.substringAfter("||P3||", ""))
                                                        Log.i(TAG, "[PROXY_POSTCLICK] fatias inline: p1=${p1.length} p2=${p2.length} p3=${p3.length}")
                                                        if (p1.length > 5000) {
                                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl + "#inline", p1 + p2 + p3)
                                                        }
                                                    } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }, 2000)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 2) Fallback: re-deriva as coordenadas do botão e re-toca 3s após
                // o toque inicial, se nada capturou. v229d: sem chamada direta
                // rcPreloadPlayer (Error: 7a2f); v230: sem b.click() sintético.
                // v231: validação de viewport + scrollIntoView, mesma do toque inicial.
                if (clickDone.get() && !captured.get() && !directFallbackDone && now - lastClickMs >= DIRECT_FALLBACK_MS && now - lastDirectFallbackMs >= DIRECT_FALLBACK_MS) {
                    lastDirectFallbackMs = now
                    directFallbackDone = true
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    const b = document.getElementById('submit') || document.querySelector('.captcha_button');
                                    if (!b || b.offsetParent === null) return 'no-btn';
                                    b.scrollIntoView({block:'center'});
                                    const r = b.getBoundingClientRect();
                                    const cx = r.left + r.width/2, cy = r.top + r.height/2;
                                    if (r.width <= 0 || r.height <= 0) return 'zerosize';
                                    if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return 'offscreen';
                                    return 'retap:' + cx + ':' + cy;
                                })();""".trimIndent()
                            ) { res ->
                                val rr = res?.removeSurrounding("\"").orEmpty()
                                if (rr.startsWith("retap:")) {
                                    val parts = rr.split(":")
                                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                                    val downTime = SystemClock.uptimeMillis()
                                    val vw = wvNow.width.toFloat()
                                    val vh = wvNow.height.toFloat()
                                    val scale = wvNow.scale
                                    val vx = x * scale
                                    val vy = y * scale
                                    if (vx < 0 || vy < 0 || vx > vw || vy > vh) {
                                        Log.w(TAG, "[PROXY] fallback fora da view: css=($x,$y) view=(${vw}x$vh)")
                                    } else {
                                        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, vx, vy, 0.85f, 0.85f, 0, 1.0f, 1.0f, 0, 0)
                                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(down)
                                        down.recycle()
                                        val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, vx + 0.5f, vy + 0.5f, 0f, 0f, 0, 1.0f, 1.0f, 0, 0)
                                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(up)
                                        up.recycle()
                                        Log.i(TAG, "[PROXY] recap re-tocado (fallback) css=($x,$y) view=($vx,$vy)")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 3) Poll via performance entries & DOM vídeo (fallback não-bloqueante)
                // v229: sonda de estado do player a cada ~5s (alimenta playerMounted).
                // v232e: sonda 100% dos fetch bodies (inclui serverforms vazio len=52)
                // para diagnóstico de túnel Redemovel caído — usa 2 polls simples com unescape.
                if (!captured.get()) {
                    val elapsed = now - startMs
                    val isDiagTick = elapsed > 0 && (elapsed / 5000L) != ((elapsed - POLL_INTERVAL_MS) / 5000L)
                    // fast-path: hook capturou https via fetch body
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript("""(function(){try{return window.__rcCaptured||'';}catch(e){return '';}})();""".trimIndent()) { r ->
                                val f = r?.removeSurrounding("\"").orEmpty()
                                    .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"")
                                if (f.isNotBlank() && f != "null" && f.length > 10 && !captured.get()) {
                                    val isUrl = f.contains("http", true) || f.contains("__RC__", true) || f.contains("tos-alisg", true) || f.contains("/proxy", true)
                                    if (isUrl || f.startsWith("http")) {
                                        streamUrl = f
                                        captured.set(true)
                                        captureHolder.set(true)
                                        Log.i(TAG, "[PROXY] Stream capturado via __rcCaptured: ${f.take(250)}")
                                        try {
                                            val ctx = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                            ctx?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(f) }
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    // v232e: diagnóstico de túnel — loga último fetch body mesmo sem proxy
                    // (serverforms len=52 [] indica backend sem stream / RCIP expirado)
                    if (isDiagTick) {
                        withContext(Dispatchers.Main) {
                            try {
                                wvNow.evaluateJavascript("""(function(){
                                    try{
                                      const u=window.__rcLastFetchUrl||'';
                                      const b=(window.__rcLastFetchBody||'').slice(0,1200);
                                      const s=window.__rcLastFetchStatus||0;
                                      const ct=window.__rcLastFetchCt||'';
                                      const ck=document.cookie.slice(0,300);
                                      return 'url='+u.slice(0,180)+' | st='+s+' ct='+ct.slice(0,30)+' | ck='+ck+' | body='+b.replace(/\n/g,' ');
                                    }catch(e){return 'err '+e.message;}
                                })();""".trimIndent()) { r ->
                                    val s = r?.removeSurrounding("\"").orEmpty()
                                        .replace("\\u003C","<").replace("\\u003E",">").replace("\\\"","\"").replace("\\n"," ")
                                    Log.i(TAG, "[PROXY_DIAG] t=${elapsed}ms $s")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    try {
                                        const entries = performance.getEntriesByType('resource');
                                        for (let i = entries.length - 1; i >= 0; i--) {
                                            const n = entries[i].name;
                                            if (n.indexOf('__RC__/proxy') >= 0 || n.indexOf('/proxy?src=') >= 0 ||
                                                n.indexOf('tos-alisg') >= 0 || n.indexOf('xn--l') >= 0 ||
                                                n.indexOf('neosoro.gq') >= 0 || n.indexOf('container=videos') >= 0 ||
                                                n.indexOf('.mp4') >= 0 || n.indexOf('.m3u8') >= 0 ||
                                                n.indexOf('/ondemand/') >= 0 || n.indexOf('/videos/') >= 0) {
                                                if (n.indexOf('disqus') < 0 && n.indexOf('chatango') < 0 && n.indexOf('google') < 0) {
                                                    return n;
                                                }
                                            }
                                        }
                                    } catch (_) {}
                                    const v = document.querySelector('video');
                                    if (v) {
                                        const s = v.currentSrc || v.src || '';
                                        if (s && s.indexOf('blob:') < 0 && s.indexOf('disqus') < 0) return s;
                                    }
                                    return '';
                                })();""".trimIndent()
                            ) { res ->
                                val found = res?.removeSurrounding("\"").orEmpty()
                                if (found.isNotBlank() && found != "null" && !captured.get()) {
                                    streamUrl = found
                                    captured.set(true)
                                    captureHolder.set(true)
                                    Log.i(TAG, "[PROXY] Stream capturado via evaluateJavascript: ${found.take(180)}")
                                    try {
                                        val ctx3 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                        ctx3?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(found) }
                                    } catch (_: Throwable) {}
                                }
                            }
                            // v229: sonda de estado do player a cada ~5s — alimenta
                            // playerMounted (bloqueia reload enquanto o bundle monta).
                            // v231: também espelha o DOM do server.php na RAM do solver
                            // (o solver nunca captura server.php — só o detalhe — então
                            // o SERVERPHP_HTML tinha dumped=null; com o espelho o
                            // StreamResolver inspeciona forms/scripts do player real).
                            if (isDiagTick) {
                                wvNow.evaluateJavascript(
                                    """(function() {
                                        try {
                                            const btn = document.getElementById('submit') || document.querySelector('.captcha_button');
                                            const btnVisible = btn ? (btn.offsetParent !== null) : false;
                                            const rcFn = (typeof window.rcPreloadPlayer === 'function');
                                            const v = document.querySelector('video');
                                            const vSrc = v ? ((v.currentSrc || v.src || '').substring(0,80)) : '';
                                            const html = document.documentElement ? document.documentElement.outerHTML : '';
                                            return 'player btn=' + btnVisible + ' | rcFn=' + rcFn + ' | video=' + (vSrc || 'none') + ' | title=' + document.title.substring(0,50) + ' ||HTML||' + html.substring(0,60000);
                                        } catch(e) { return 'player ERR ' + e.message; }
                                    })();""".trimIndent()
                                ) { res ->
                                    val clean = res?.removeSurrounding("\"").orEmpty()
                                    val state = clean.substringBefore("||HTML||")
                                    Log.i(TAG, "[PROXY_STATE] t=${elapsed}ms $state")
                                    // player montado = btn visível + rcPreloadPlayer function —
                                    // enquanto montado, o retry NÃO recarrega (mata a montagem).
                                    playerMounted.set(state.contains("btn=true") && state.contains("rcFn=true"))
                                    // espelho do DOM na RAM (1x por ciclo basta)
                                    try {
                                        val htmlPart = clean.substringAfter("||HTML||", "")
                                            .replace("\\u003C", "<").replace("\\u003E", ">")
                                            .replace("\\\"", "\"").replace("\\n", "\n")
                                        if (htmlPart.length > 5000 && !captured.get()) {
                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl, htmlPart)
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 4) Retry com reload se a página não montou o player.
                // v229: recarrega a URL ORIGINAL com query params (vid/server/subfolder) —
                // wv.reload() após submit do recap navegava para "server.php?" vazio,
                // que retorna 520 e destrói a sessão válida (diag v229 provou).
                // v229c: NÃO recarrega enquanto o player está montado (btn+rcPreloadPlayer
                // presentes) — o bundle leva >10s para montar o __RC__/proxy após o click
                // e o reload cego matava a montagem em andamento (diag 12:08 provou:
                // player montado aos 15s, morto pelo reload #2).
                val clickAge = if (clickDoneAtMs == 0L) Long.MAX_VALUE else now - clickDoneAtMs
                val neverClicked = clickDoneAtMs == 0L
                // v229c: stale só vale se o click foi há pouco E o player sumiu;
                // player montado (btn+rcFn) invalida o stale — dá tempo ao bundle.
                val staleClick = clickDone.get() && clickAge in DUD_CLICK_MS..15000L && playerMounted.get().not()
                if (!captured.get() &&
                    (neverClicked || staleClick) &&
                    now - startMs >= RELOAD_FIRST_MS &&
                    now - lastReloadCheckMs >= RELOAD_INTERVAL_MS &&
                    reloadCount < MAX_RELOADS
                ) {
                    lastReloadCheckMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    return document.querySelectorAll('.captcha_button, #submit').length > 0
                                        || typeof window.rcPreloadPlayer === 'function';
                                })();""".trimIndent()
                            ) { res ->
                                val hasCaptcha = res?.contains("true") == true
                                if (staleClick || !hasCaptcha) {
                                    reloadCount++
                                    if (staleClick) {
                                        Log.i(TAG, "[PROXY] click sem efeito -> reload #$reloadCount p/ sessão fresca")
                                    } else {
                                        Log.i(TAG, "[PROXY] captcha ausente -> reload #$reloadCount para renovar RCIP/RCSESS")
                                    }
                                    clickDone.set(false)
                                    clickDoneAtMs = 0L
                                    // v229: preserva query params — reload() nu perdia vid/server
                                    try { wvNow.loadUrl(serverPhpUrl) } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            true
        }

        val finalUrl = streamUrl
        if (finalUrl.isNullOrBlank()) {
            Log.w(TAG, "[PROXY] Falha: nenhuma URL __RC__/proxy capturada em $CAPTURE_TIMEOUT_MS ms")
            shutdown()
            return null
        }

        // Pausa o vídeo no WebView (não deixar o player consumir banda em paralelo)
        withContext(Dispatchers.Main) {
            try {
                wv?.evaluateJavascript(
                    """(function() { const v = document.querySelector('video'); if (v) { try { v.pause(); } catch (_) {} } })();""".trimIndent(),
                    null
                )
            } catch (_: Throwable) {}
        }

        Log.i(TAG, "[PROXY] Captura OK: $finalUrl")
        try {
            val ctx4 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
            ctx4?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(finalUrl) }
            java.io.File("/sdcard/redecanais_af_last_stream_url.txt").writeText(finalUrl)
        } catch (_: Throwable) {}
        return startLocalServer(finalUrl)
    }

    /**
     * Inicia o ServerSocket local que o ExoPlayer consome (http://127.0.0.1:porta/stream.mp4).
     */
    private fun startLocalServer(targetUrl: String): String? {
        return try {
            val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            isServing = true
            val port = server.localPort

            thread(isDaemon = true, name = "RCProxy-Accept") {
                while (isServing && !server.isClosed) {
                    try {
                        val client = server.accept()
                        thread(isDaemon = true, name = "RCProxy-Conn") {
                            handleConnection(client, targetUrl)
                        }
                    } catch (e: Exception) {
                        if (isServing) Log.w(TAG, "[PROXY] accept err: ${e.message}")
                        break
                    }
                }
            }

            val local = "http://127.0.0.1:$port/stream.mp4"
            Log.i(TAG, "[PROXY] Servidor local ativo: $local -> $targetUrl")
            local
        } catch (e: Throwable) {
            Log.e(TAG, "[PROXY] Falha ao iniciar servidor local: ${e.message}")
            null
        }
    }

    private fun handleConnection(socket: Socket, targetUrl: String) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("GET") && !requestLine.startsWith("HEAD")) return

                // Parse headers (Range)
                var rangeStart = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    if (line.startsWith("Range:", true)) {
                        val m = Regex("""bytes=(\d+)-(\d*)""").find(line)
                        rangeStart = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }

                val out = sock.getOutputStream()

                // Resposta 206 com total desconhecido (*) — ExoPlayer faz streaming progressivo
                val head =
                    "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes $rangeStart-*/*\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                if (requestLine.startsWith("HEAD")) return

                // v123: prefetch duplo — busca o chunk N+1 em paralelo enquanto serve o N,
                // eliminando a latência de rede/JS entre chunks (sem pausas na reprodução).
                var pos = rangeStart
                var nextChunk = CompletableFuture<ByteArray?>()
                thread(isDaemon = true, name = "RCProxy-Prefetch") {
                    nextChunk.complete(fetchChunk(targetUrl, pos, pos + CHUNK_SIZE - 1))
                }
                while (isServing) {
                    val chunk = try {
                        nextChunk.get(CHUNK_FETCH_TIMEOUT_S, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROXY] timeout prefetch $pos..: ${e.message}")
                        null
                    } ?: break
                    if (chunk.isEmpty()) break // EOF (416)
                    val nextStart = pos + chunk.size
                    // dispara o fetch do próximo chunk ANTES de escrever o atual no socket
                    val next = CompletableFuture<ByteArray?>()
                    thread(isDaemon = true, name = "RCProxy-Prefetch") {
                        next.complete(fetchChunk(targetUrl, nextStart, nextStart + CHUNK_SIZE - 1))
                    }
                    out.write(chunk)
                    out.flush()
                    pos += chunk.size
                    if (chunk.size < CHUNK_SIZE) break // último chunk
                    nextChunk = next
                }
                Log.d(TAG, "[PROXY] conexão servida: range=$rangeStart..$pos")
            }
        } catch (e: Exception) {
            Log.d(TAG, "[PROXY] conexão encerrada: ${e.message}")
        }
    }

    /**
     * Busca um chunk da URL real fazendo fetch() DENTRO do contexto JS do WebView
     * (credentials include + Range) — mesmo TLS/fingerprint do cf_clearance.
     */
    private fun fetchChunk(url: String, start: Long, end: Long): ByteArray? {
        val wv = webView ?: return null
        val future = CompletableFuture<String>()
        try {
            wv.post {
                try {
                    val js = """(async () => {
                        try {
                            const r = await fetch(${JSONObject.quote(url)}, {
                                credentials: 'include',
                                headers: {'Range': 'bytes=${start}-${end}'}
                            });
                            if (r.status === 416) return 'EOF';
                            if (!r.ok) return 'ERR:' + r.status;
                            // v123: decode nativo via FileReader.readAsDataURL (muito mais rápido
                            // que o loop String.fromCharCode + btoa para chunks de 512KB)
                            const buf = await r.arrayBuffer();
                            const blob = new Blob([buf]);
                            const dataUrl = await new Promise((resolve, reject) => {
                                const fr = new FileReader();
                                fr.onload = () => resolve(fr.result);
                                fr.onerror = () => reject(new Error('FR'));
                                fr.readAsDataURL(blob);
                            });
                            const idx = dataUrl.indexOf(',');
                            return idx >= 0 ? dataUrl.substring(idx + 1) : 'ERR:no-data';
                        } catch(e) { return 'ERR:' + e; }
                    })();""".trimIndent()
                    wv.evaluateJavascript(js) { res -> future.complete(res ?: "ERR:null") }
                } catch (e: Throwable) {
                    future.complete("ERR:${e.message}")
                }
            }
        } catch (e: Throwable) {
            return null
        }

        val raw = try {
            future.get(CHUNK_FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "[PROXY] timeout fetch chunk $start..$end: ${e.message}")
            return null
        } ?: return null

        val cleaned = raw.removeSurrounding("\"")
        if (cleaned == "EOF") return ByteArray(0)
        if (cleaned.startsWith("ERR:")) {
            Log.w(TAG, "[PROXY] chunk $start..$end erro: ${cleaned.take(120)}")
            return null
        }
        return try {
            android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            Log.w(TAG, "[PROXY] chunk $start..$end base64 inválido: ${e.message}")
            null
        }
    }

    /** Encerra servidor e WebView (thread-safe, pode ser chamado de qualquer thread). */
    fun shutdown() {
        isServing = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        streamUrl = null
        // Capture ownership before clearing the singleton. Dispatch independently
        // of CommonActivity: the Activity may already be gone during shutdown.
        val wv = synchronized(this) {
            webView.also { webView = null }
        } ?: return
        val cleanup = Runnable {
            runCatching { wv.stopLoading() }
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
                .onSuccess { Log.i(TAG, "[PROXY] WebView destruido no shutdown") }
                .onFailure { Log.w(TAG, "[PROXY] Falha ao destruir WebView", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            Handler(Looper.getMainLooper()).post(cleanup)
        }
    }
}
