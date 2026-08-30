# TASK

## ID
CS-RC-003

## Objetivo
Executar validação runtime interativa de reprodução de vídeo (L4/L5) no CloudStream (Android Redroid) para títulos do catálogo do RedeCanais AF, garantindo que o ExoPlayer receba e consuma streams diretos sem interferência visual.

## Contexto
- Projeto: /root/cloudstream-plugins/RedeCanaisAF
- Runtime: Android Redroid
- Engine: ExoPlayer / CS3ExoPlayer
- Proxy: WebViewStreamProxy (127.0.0.1)

## Validação Obrigatória
1. Iniciar o app CloudStream.
2. Abrir um título de filme e reproduzir.
3. Coletar logs de interceptação e streaming:
   - `[LOADLINKS_START]`
   - `[PROXY] Iniciando captura WebView`
   - `[PROXY] click recap`
   - `[PROXY] __RC__/proxy capturado` ou `.m3u8` direto
   - `[PROXY_LINK] emitindo proxy local` ou `ExtractorLink`
   - `ExoPlayer` / `MediaCodec`
4. Capturar screenshot comprobatório da reprodução.

## Critério de Conclusão
- Apenas links diretos de mídia (.mp4, .m3u8, proxy local 127.0.0.1) emitidos para o ExoPlayer.
- Zero envio de frames/iframes HTML para o ExoPlayer.
- Registro completo em RESULT.md e REVIEW.md.

