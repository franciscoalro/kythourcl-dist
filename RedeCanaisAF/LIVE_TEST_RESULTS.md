# RedeCanaisAF v233 — validação real no Redroid

Data dos logs Android: 2026-09-09. Dispositivo: `emulator-5554`, container `redroid`, app `com.lagradost.cloudstream3.prerelease`. Sem mocks ou respostas fabricadas.

## Busca

O HTML real de `search.php` contém `.listagem` inicialmente vazia. A página baixa `final_mapa.txt` e `final_mapafilmes.txt` via `Promise.all`; só depois atribui `#search-input.value` e renderiza resultados. A captura anterior aceitava o shell no carregamento da página. O decoder manual também corrompia sequências escapadas do HTML retornado por `evaluateJavascript`.

A correção aguarda o sinal de prontidão observado no site, respeitando o timeout existente, usa JSONTokener para decodificar uma única vez, rejeita shells de busca não renderizados e não reutiliza HTML de outra URL após timeout. Consultas em branco retornam imediatamente. Dumps multi-MB usados na investigação foram removidos; restam diagnósticos resumidos.

Evidências coletadas via `docker exec redroid logcat`:

- 09:24:13: índices HTTP **200**, 771796 e 2986097 bytes; **25003 entradas** processadas.
- `A Captura`: `ready=true`, `rendered=0`, `SEARCH_SUCCESS results=0`. O próprio índice do site não retornou esse título nesta execução; não é prova de falha do parser.
- 09:24:38: consulta `Batman`, `rendered=62`.
- 09:24:40: `SEARCH_STAGE1 raw=62`, `SEARCH_SUCCESS results=62`.
- Hierarquia UI confirmou resultados `A Sombra do Batman`, `As Aventuras do Batman`, `As Novas Aventuras do Batman`.
- Abertura de `A Sombra do Batman` mostrou **26 episódios** no aplicativo.

## Clique no player e captura de stream

Entrada real selecionada na UI:
`https://redecanais.af/a-sombra-do-batman-episodio-01-cacados_a8b6b81b2.html`

Player identificado pelo plugin: `RCFServer2`, `vid=ASMBRDBTMNEP01`.

- 09:25:53.761: `[PROXY] click recap -> tap:320:180`.
- 09:25:53.762: coordenadas CSS `(320,180)` convertidas para a view `(640,360)` de `1280x720`.
- 09:25:53.778: toque Android despachado pelo plugin.
- Requisições reais ao `serverforms.api` após o toque responderam **HTTP 200**.
- 09:25:54.327: payload final `{"9c4e17a2":"71d0b5f8","2fa806d3":204,"e18b73c9":[]}`.
- O fallback de toque repetiu o mesmo resultado vazio.
- 09:26:37.626: `[PROXY] Falha: nenhuma URL __RC__/proxy capturada em 45000 ms`.

Uma captura passiva posterior via Chrome DevTools Protocol (`Network.enable`) registrou a sequência completa do WebView, sem descriptografar, modificar ou fabricar respostas:

1. `server.php` respondeu HTTP 200 e carregou o player.
2. `serverforms.api?a6e91c4f=1...` respondeu HTTP 200 com `a91f0c7e=true`, `d34b8291=true`, timestamp e nonces.
3. A chamada seguinte `serverforms.api?3c91e7a4=...` respondeu HTTP 200 com o código interno `204` e `e18b73c9=[]`.
4. O mesmo ocorreu usando `RCFServer2` e o fallback `RCServer11`.
5. Um segundo conteúdo de controle, `Batman Vs Superman: A Origem da Justiça - 2016` (`vid=BTMNVSPRMNAODJT`), também passou pela inicialização e retornou o mesmo array vazio em duas tentativas.
6. As 12 respostas `serverforms.api` capturadas nos dois testes têm `fromServiceWorker=true`, `serviceWorkerResponseSource=network`, protocolo `h3` e `fromDiskCache=false`. Eventos `Network.requestWillBeSentExtraInfo` de recursos do mesmo player confirmaram cookies ativos no contexto WebView (`cf_clearance`, `RCIP`, `RCSESS`); não há eventos ExtraInfo correlacionados às chamadas de resolução nesta captura. A ausência precisa ser investigada no target do Service Worker, não atribuída ao encerramento do target da página sem evidência. Portanto, não usamos essa observação para afirmar quais cookies acompanharam especificamente cada `serverforms.api`. Valores foram omitidos e os arquivos brutos permanecem locais, modo 0600.
7. Como comparação independente, o WebView Browser Tester abriu o mesmo episódio, completou o Turnstile e exibiu uma etapa “Acesso VIP / Abrir anúncio”. Não prosseguimos por esse fluxo promocional, logo ele não fornece um baseline independente de reprodução.

**Conclusão:** clique, inicialização e chamadas ao serviço estão comprovados com dois conteúdos; URL de vídeo e reprodução ExoPlayer **não comprovadas**. O `204` é um código no JSON, não o status HTTP. A evidência descarta que o plugin tenha simplesmente deixado de acionar o botão ou a API. Ela ainda não distingue indisponibilidade do arquivo, política do servidor, sessão aceita parcialmente, exigência da etapa promocional ou outro estado interno; portanto, não alegamos indisponibilidade global nem uma causa que os dados não provam.

## Limites e reprodução do teste

Os testes comportamentais acima usaram o candidato local com a correção de busca; a versão final 233 inclui a mesma lógica e proteção adicional contra callback após encerramento. Compilar com `./gradlew :RedeCanaisAF:make`, instalar o `.cs3`, reiniciar o app, pesquisar `Batman`, abrir a série e tocar no primeiro episódio. Comparar logs novos, não reaproveitar linhas de execuções anteriores.

A varredura de oito títulos de 2026 não foi concluída nesta rodada; o título de controle permitiu testar o player sem depender de títulos ausentes do índice. Build bem-sucedido no CI não equivale a reprodução validada.

Os logs brutos e capturas ficam locais em `/tmp/rc-live-search.log`, `/tmp/rc-batman.log`, `/tmp/rc-playback-final.log`, `/tmp/rc-playback.png`, `/tmp/rc-player-all.json` e `/tmp/rc-popular-all.json`. Não são publicados porque os eventos DevTools incluem cookies e tokens de sessão.
