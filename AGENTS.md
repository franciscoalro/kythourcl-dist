# CloudStream — RedeCanaisAF Plugin

## Contexto
Plugin Android para o app CloudStream que extrai filmes/séries de `https://www3.redecanais.vip/`.
Linguagem: Kotlin. Build: Gradle. Target: Android API 21+.

## Regras Obrigatórias
- NUNCA faça deploy automático sem confirmação explícita.
- SEMPRE leia o arquivo antes de editar.
- SEMPRE mantenha comentários e logs existentes.
- Seja cirúrgico: altere apenas o necessário.
- Versão atual: BUILD_VERSION = 125. Próxima: 126.

## Arquitetura
- `RedeCanaisAF.kt` — Provider principal: search, getMainPage, loadLinks
- `CloudflareSolver.kt` — Renderizador de páginas com Cloudflare challenge
- `build.gradle.kts` — version deve bater com BUILD_VERSION

## Comandos úteis
- Compilar: `./gradlew RedeCanaisAF:assembleRelease`
- Deploy: `python scratch/deploy_and_verify_v102.py`
- Logcat: `adb -d logcat -s RedeCanaisAF:V CloudStream:V ExoPlayer:V`
