# FOLLOWUP_TASK

## TASK_ID
CS-RC-003-F1

## Problema
O Chromium WebView encerra com erro no processo de renderização quando múltiplos WebViews são instanciados sem `onRenderProcessGone`.

## Evidência
`aw_browser_terminator.cc(110) Render process kill wasn't handled by all associated webviews, killing application.`

## Arquivos Relacionados
- `/root/cloudstream-plugins/RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/CloudflareSolver.kt`
- `/root/cloudstream-plugins/RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/WebViewStreamProxy.kt`

## Alteração Necessária
1. Implementar `override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true` em todos os `WebViewClient`.
2. Executar destruição e remoção imediata do WebView do `CloudflareSolver` antes de iniciar o `WebViewStreamProxy`.

## Teste Obrigatório
Executar o Play do episódio e verificar que o app não fecha e o proxy serve os chunks ao ExoPlayer.

