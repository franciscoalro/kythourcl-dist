# CloudStream — Repositório de Plugins

## Contexto do Projeto
Repositório Gradle com múltiplos plugins Android para o app CloudStream.
Target: Android API 21+ | Linguagem: Kotlin | Build: Gradle 8.12 + JDK 21.

## Estrutura do Repositório
- `/root/cloudstream-plugins/`
  - `RedeCanaisAF/` — Plugin RedeCanaisAF (`RedeCanaisAF.kt`, `CloudflareSolver.kt`)
  - `CineVision/`, `AnimeFire/`, `NetCine/`, `Pobreflix/`, `TopAnimes/` — Outros providers
  - `builds/` — Artefatos gerados (`.cs3`, `plugins.json`, `repo.json`)
  - `tools/verify_plugin.py` — Script automatizado de compilação e teste no emulador
  - `build.gradle.kts` — Configuração global de build

## Infraestrutura de Execução e Emulador (Redroid)
- **Host:** Ubuntu Linux (`python` e `python3` disponíveis).
- **Emulador Android:** Container Docker `redroid` conectado ao ADB no host em `emulator-5554`.
- **Package do App:** `com.lagradost.cloudstream3.prerelease`
- **Launcher Activity:** `com.lagradost.cloudstream3.ui.account.AccountSelectActivity`
- **Atenção:** O Redroid é uma imagem AOSP pura (não use `apt-get` ou `apk` dentro dele).

## Comandos Oficiais de Operação
- **Compilar todos os plugins:**
  `./gradlew makePlugins`
- **Compilar um plugin específico (ex: RedeCanaisAF):**
  `./gradlew :RedeCanaisAF:make`
- **Verificar e testar plugin no emulador:**
  `python tools/verify_plugin.py RedeCanaisAF`
- **Iniciar o CloudStream no emulador:**
  `docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity`
- **Monitorar logs (Logcat):**
  `adb -s emulator-5554 logcat -s RedeCanaisAF:V CloudStream:V ExoPlayer:V`
  *(Ou direto no Docker: `docker exec redroid logcat -s RedeCanaisAF:V CloudStream:V`)*

## Regras para o Agente Autônomo
1. **Zero Adivinhação:** NUNCA presuma caminhos de arquivos ou scripts sem inspecionar a pasta antes (`ls`, `find`).
2. **NUNCA use `adb -d`:** Use SEMPRE `adb -s emulator-5554` ou `docker exec redroid`.
3. **Mantenha a Sintaxe Kotlin Limpa:** Sempre verifique o `build.gradle.kts` e as dependências antes de compilar.
4. **Resolução de Erros:** Se a compilação falhar, leia o erro do compilador Kotlin com calma e corrija a linha exata.
