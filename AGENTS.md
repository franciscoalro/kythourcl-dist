# CloudStream Plugins — Índice do Agente

> Este arquivo é um **índice**, não um documento. Não duplique conteúdo — aponte onde buscar.

## 1. Onboarding (30s para um dev novo)

- **Stack:** Kotlin + Gradle 8.12 + JDK 21 + Android API 21+ + framework CloudStream3.
- **Repo:** `cloudstream-plugins/` — 7 providers. Foco atual: `RedeCanaisAF`. Outros: `CineVision`, `AnimeFire`, `NetCine`, `Pobreflix`, `TopAnimes`, `RedeCanais`.
- **Infra local:** Host Ubuntu + container Docker `redroid` em `emulator-5554`. App `com.lagradost.cloudstream3.prerelease`.

## 2. Mapa — Onde Buscar (Busca Segmentada)

Consulte **apenas** o artefato necessário. Não carregue tudo no contexto.

| Preciso de... | Vá em... |
|---|---|
| Visão geral dos 7 providers, arquitetura e fonte viva (`kythourcl-dist`) | `DOCUMENTACAO_MESTRE.md` |
| RedeCanais — infra, domínios, rotas DooPlay | `DOSSIE_REDECANAIS.md` |
| CineVision — HLS local, DooPlayer | `DOSSIE_CINEVISION.md` |
| Descobertas funcionais / engenharia reversa (eaua, batchexecute, OCR) | `DESCOBERTAS_FUNCIONAIS.md` |
| Diagnóstico / quebras ao vivo do RedeCanaisAF | `DIAGNOSTICO-REDECANAIS.md` |
| Bypass Cloudflare / Turnstile / WAF | `PESQUISA_COMUNIDADE_CLOUDFLARE.md` |
| Plano e tasks de testes ao vivo | `PLANO_TESTES_REDECANAIS_AF.md`, `TASKS_TESTES_AO_VIVO_REDECANAIS.md` |
| Relatório simulação CDP / WebView | `RELATORIO_SIMULACAO_AO_VIVO_CDP.md` |
| Código do provider RedeCanaisAF | `RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/` → `RedeCanaisAF.kt`, `RedeCanaisAFProvider.kt`, `CloudflareSolver.kt`, `StreamResolver.kt` |
| Build e distribuição | `build.gradle.kts`, `settings.gradle.kts`, `gradle-plugin/`, `builds/` |
| Verificação automatizada no emulador | `tools/verify_plugin.py` |

> Pastas de memória/contexto: `tools/`, `scratch/` (scripts efêmeros). Ignore `build/` e `builds/` ao raciocinar — são saída.

## 3. Guias — Antes de Agir (regras de alto nível)

1. **Zero adivinhação:** `ls`/`find` antes de presumir caminho. Nunca invente script ou pasta.
2. **ADB correto:** Sempre `adb -s emulator-5554` ou `docker exec redroid`. Nunca `adb -d`.
3. **Cirúrgico:** Altere só o necessário. Mantenha comentários e logs existentes.
4. **Versão:** Ver `RedeCanaisAF/build.gradle.kts` → `cloudstream { version = N }` (deve bater com `BUILD_VERSION` no código).
5. **Kotlin limpo:** Confira `build.gradle.kts` e dependências antes de compilar.
6. **Sem deploy automático:** Só faça push/deploy com confirmação explícita.

**Anti-padrões (o que NÃO fazer):** NÃO leia dossiês completos (150+ linhas) para task de 1 arquivo · NÃO rode `makePlugins` se só mexeu em 1 provider · NÃO carregue 3+ docs no contexto.

## 4. Sensores — Depois de Agir (valide com ferramentas externas)

Todo change deve passar nos sensores abaixo — não confie só em leitura:

```bash
./gradlew :RedeCanaisAF:make                # mudou só 1 provider
./gradlew makePlugins                        # mudou build.gradle.kts raiz / gradle-plugin
python tools/verify_plugin.py RedeCanaisAF  # precisa validar Cloudflare / smoke no redroid
adb -s emulator-5554 logcat -s RedeCanaisAF:V CloudStream:V ExoPlayer:V
docker exec redroid logcat -s RedeCanaisAF:V CloudStream:V
```

Se falhar: leia o erro do compilador Kotlin na linha exata, corrija e recompile. Não avance com build quebrado.

## 5. Critérios de Sucesso

Uma tarefa só está concluída quando:

- [ ] Build do alvo passa (`:RedeCanaisAF:make` sem erro)
- [ ] `verify_plugin.py` passa no emulador (ou falha documentada com log)
- [ ] Nenhum outro provider quebrou (`makePlugins` se houve mudança global)
- [ ] Versão bumpada e consistente (`build.gradle.kts` ↔ código)
- [ ] Logs/Toasts removidos ou mantidos intencionalmente (sem ruído no logcat)

## 6. Foco — Contexto é Finito

- Este índice deve ficar < 100 linhas. Se precisar de detalhe, linke o doc — não cole.
- Orçamento: max **2 docs + 1 provider** por task. Se precisar de 3º doc, resuma e descarte o 1º.
- Prefira `grep`/`read` segmentado a `cat` de dossiês de 200 linhas.
