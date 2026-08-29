# 📚 Descobertas Funcionais e Soluções Validadas

Documento de registro contínuo com todas as técnicas, análises de tráfego (HAR), engenharia reversa e soluções testadas e comprovadas no projeto.

---

## 1. 🎬 Mecanismo de Vídeo do AnimeFire (Blogger / GoogleVideo)

### 📌 Contexto
Muitos episódios (como os dublados recentes e clássicos) utilizam o player incorporado do Blogger em vez da CDN local (`lightspeedst.net`).

### 🔎 Descoberta via HAR (`HARR.txt`)
* O HTML da página do episódio carrega um iframe:
  ```html
  <iframe src="https://www.blogger.com/video.g?token=AD6v5dxOZYt31GHE7dvbpM3n3N-..."></iframe>
  ```
* O player web faz uma requisição RPC para resolver os links do GoogleVideo.

### ✅ Solução Funcional (Extrator Nativo em Kotlin)
1. **Endpoint RPC do Blogger:**
   - **URL:** `https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&hl=pt-BR`
   - **Método:** `POST`
   - **Headers:**
     - `Content-Type: application/x-www-form-urlencoded;charset=UTF-8`
     - `Referer: https://www.blogger.com/`
     - `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...`
   - **Body (`f.req`):**
     ```json
     [[["WcwnYd","[\"<TOKEN>\",null,0]",null,"generic"]]]
     ```

2. **Parsing da Resposta:**
   - Extrair o bloco `WcwnYd`.
   - Realizar unescape de `\"`, `\\`, `\u003d` (=) e `\u0026` (&).
   - Capturar os links diretos `googlevideo.com/videoplayback?...` e seus `itags`:
     - `itag 18`: 360p (SD)
     - `itag 22`: 720p (HD)
     - `itag 37`: 1080p (FHD)

---

## 2. 🛡️ Solução para o Erro do ExoPlayer: `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)`

### 🔴 O Problema
Ao tentar reproduzir a URL do GoogleVideo diretamente, o CloudStream acusava:
```
ERROR_CODE_IO_BAD_HTTP_STATUS (2004) - Source error (HTTP 403 Forbidden)
```

### 🔬 Causa Raiz e Mecanismo `eaua`
1. O endpoint `batchexecute` do Blogger gera a URL do GoogleVideo contendo a assinatura do User-Agent no parâmetro `&eaua=...`.
2. Se a requisição de streaming (feita pelo ExoPlayer) enviar um User-Agent diferente do usado no `batchexecute` (ou se enviar um `Referer` inválido), o Google bloqueia com **HTTP 403 (BAD_HTTP_STATUS 2004)**.
3. Se um cabeçalho `Referer` for enviado durante a reprodução, o GoogleVideo rejeita a conexão. O streaming direto requer apenas o `User-Agent` idêntico e sem referer.

### ✅ Solução Funcional
Configurar os cabeçalhos com o User-Agent idêntico e sem referer no `newExtractorLink`:
```kotlin
val browserUa = BROWSER_HEADERS["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
newExtractorLink(
    source = name,
    name = "AnimeFire Blogger ($label)",
    url = streamUrl,
    type = ExtractorLinkType.VIDEO
) {
    this.headers = mapOf(
        "User-Agent" to browserUa,
        "Accept" to "*/*"
    )
    this.quality = qualityInt
}
```
* **Resultado:** Status `200 OK` / `206 Partial Content` em requisições diretas e de Range (ExoPlayer), permitindo reprodução e seek instantâneo.

---

## 3. 🚀 Fluxo de Deploy e Distribuição do CloudStream

### 📌 Estrutura dos Repositórios
* **Repositório Fonte:** `https://github.com/franciscoalro/kythourcl.git` (código-fonte Kotlin e workflows de build).
* **Repositório de Distribuição:** `https://github.com/franciscoalro/kythourcl-dist.git` (binários `.cs3` e catálogo `plugins.json`).

### ⚙️ Como o CloudStream gerencia Atualizações
1. O CloudStream baixa o arquivo `plugins.json` de `kythourcl-dist`.
2. Compara o campo `"version"` com a versão instalada no aplicativo.
3. Para disparar uma atualização para os usuários:
   - Incrementar `version` no `build.gradle.kts`.
   - Gerar o novo binário (`./gradlew :AnimeFire:make`).
   - Atualizar a entrada do plugin no `plugins.json` com a nova `version` e tamanho em bytes (`fileSize`).
   - Fazer o commit e push para o repositório `kythourcl-dist`.

---

## 4. 🗂️ Matriz de Extração do Provedor AnimeFire

| Tipo de Mídia / Player | Mecanismo de Resolução | Status |
| :--- | :--- | :---: |
| **Blogger / GoogleVideo** | Extração RPC nativa `batchexecute` com headers de navegador | ✅ 100% Funcional |
| **CDN Nativa AnimeFire** | API JSON `/video/$slug/$ep` ou `data-video-src` (`lightspeedst.net`) | ✅ Funcional |
| **DOM Direct `<video>`** | Leitura direta de tags `<source src="...">` ou `<video src="...">` | ✅ Funcional |
| **Players Terceiros** | Delegado via `loadExtractor` (Sendvid, Streamwish, Filemoon, Mixdrop) | ✅ Funcional |
| **Páginas com Proteção** | `WebViewResolver` (utilizado apenas como último fallback) | ⚠️ Fallback |

---

## 5. 🧩 Template Pronto para Novos Provedores (Reaproveitamento)

Para adicionar suporte ao Blogger em qualquer outro provedor CloudStream, basta copiar e colar a função abaixo:

```kotlin
private suspend fun extractBlogger(
    token: String,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (token.isBlank()) return false
    var extracted = false
    try {
        val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val rpcUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&hl=pt-BR"
        val reqPayload = """[[["WcwnYd","[\"$token\",null,0]",null,"generic"]]]"""
        
        val response = app.post(
            rpcUrl,
            headers = mapOf(
                "User-Agent" to browserUa,
                "Referer" to "https://www.blogger.com/",
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"
            ),
            data = mapOf("f.req" to reqPayload)
        )

        val text = response.text
        val jsonArrayMatch = Regex("""\[\["wrb\.fr","WcwnYd","(.*?)",null,null,null,"generic"\]\]""").find(text)
        val rawData = jsonArrayMatch?.groupValues?.getOrNull(1) ?: text

        val unescaped = rawData
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")

        val streamRegex = Regex("""\["(https:[^"]+googlevideo\.com[^"]+)",\s*\[(\d+)\]\]""")
        val matches = streamRegex.findAll(unescaped).toList()

        for (m in matches) {
            var streamUrl = m.groupValues[1]
            val itag = m.groupValues[2].toIntOrNull() ?: 22

            if (streamUrl.contains("\\u")) {
                streamUrl = streamUrl.replace("\\u003d", "=").replace("\\u0026", "&")
            }

            val (qualityInt, label) = when (itag) {
                37 -> Qualities.P1080.value to "1080p (FHD)"
                22 -> Qualities.P720.value to "720p (HD)"
                18 -> Qualities.P360.value to "360p (SD)"
                else -> Qualities.P720.value to "HD"
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName Blogger ($label)",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "User-Agent" to browserUa,
                        "Accept" to "*/*"
                    )
                    this.quality = qualityInt
                }
            )
            extracted = true
        }
    } catch (_: Exception) {}
    return extracted
}
```

---

## 6. ⚠️ Checklist e Armadilhas Evitadas (Gotchas)

1. **Nunca misturar User-Agents:** O User-Agent enviado para a RPC do Blogger (`batchexecute`) **DEVE** ser exatamente o mesmo enviado no `ExtractorLink` para o streaming do GoogleVideo. Caso contrário, a assinatura `&eaua=` falha e retorna HTTP 403.
2. **Não enviar `Referer` no stream do vídeo:** Ao reproduzir arquivos diretos do GoogleVideo (`videoplayback`), o cabeçalho `Referer` faz com que o servidor do Google rejeite a requisição. Apenas o `User-Agent` correto é necessário.
3. **Regex do Token do Blogger:** O token do Blogger pode aparecer em:
   - `iframe[src*='blogger.com/video.g?token=...']`
   - Parâmetros embutidos no JavaScript: `blogger.com/video.g?token=([A-Za-z0-9_-]+)`
4. **Validação Rápida via ADB:**
   - Use `adb logcat -d -t 200` para capturar os logs imediatamente após testar uma reprodução no celular.

