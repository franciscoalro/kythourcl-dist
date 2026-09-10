// Minimal build file

// v251: browser-harness provou frame idêntico toca (ready=4, __RC__/proxy 206) no WebViewBrowserTester
// com service_worker ativo; no plugin Android o reuse era stub (só loadUrl) e o jar ficava
// no-video. Primeira variante deve criar o único WebView com clients/hooks completos; SW habilitado.
cloudstream {
    version = 251
}
