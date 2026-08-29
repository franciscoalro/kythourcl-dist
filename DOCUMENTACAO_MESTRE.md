# 📖 Documentação Mestre do Projeto CloudStream (`kythourcl`)

**Repositório Fonte:** [`https://github.com/franciscoalro/kythourcl`](https://github.com/franciscoalro/kythourcl)  
**Repositório de Distribuição:** [`https://github.com/franciscoalro/kythourcl-dist`](https://github.com/franciscoalro/kythourcl-dist)  
**Total de Plugins:** `7 Provedores Oficiais`  
**Versão Atual:** `v49`  
**Data de Consolidação:** 22/08/2026

---

## 📑 Sumário
1. [Visão Geral e Arquitetura do Projeto](#1-visão-geral-e-arquitetura-do-projeto)
2. [Auditoria Completa dos 7 Provedores](#2-auditoria-completa-dos-7-provedores)
   - [2.1 AnimeFire (`AnimeFire.kt`)](#21-animefire-animefirekt)
   - [2.2 CineVision (`CineVision.kt`)](#22-cinevision-cinevisionkt)
   - [2.3 NetCine (`NetCine.kt`)](#23-netcine-netcinekt)
   - [2.4 Pobreflix (`Pobreflix.kt`)](#24-pobreflix-pobreflixkt)
   - [2.5 RedeCanais (`RedeCanais.kt`)](#25-redecanais-redecanaiskt)
   - [2.6 RedeCanais AF (`RedeCanaisAF.kt`)](#26-redecanais-af-redecanaisafkt)
   - [2.7 TopAnimes (`TopAnimes.kt`)](#27-topanimes-topanimeskt)
3. [Engenharia Reversa e Casos de Estudo](#3-engenharia-reversa-e-casos-de-estudo)
   - [3.1 Descoberta e Extração da RPC do Blogger (`batchexecute`)](#31-descoberta-e-extração-da-rpc-do-blogger-batchexecute)
   - [3.2 Resolução Definitiva do Erro ExoPlayer `2004` (Mecanismo `eaua`)](#32-resolução-definitiva-do-erro-exoplayer-2004-mecanismo-eaua)
   - [3.3 Resolução In-App de Captchas com OCR no NetCine](#33-resolução-in-app-de-captchas-com-ocr-no-netcine)
   - [3.4 Servidor HLS Local de Interoperabilidade (`LocalHlsServer`) no CineVision](#34-servidor-hls-local-de-interoperabilidade-localhlsserver-no-cinevision)
   - [3.5 Integração Reversa com DooPlayer e Alibaba CDN no TopAnimes](#35-integração-reversa-com-dooplayer-e-alibaba-cdn-no-topanimes)
4. [Infraestrutura de Build, Distribuição e CI/CD](#4-infraestrutura-de-build-distribuição-e-cicd)
5. [Guia de Resolução de Problemas (Troubleshooting)](#5-guia-de-resolução-de-problemas-troubleshooting)

---

## 1. Visão Geral e Arquitetura do Projeto

O projeto é construído em cima do **CloudStream 3** (`com.lagradost.cloudstream3`), utilizando **Kotlin Multiplatform/Android Library** com Gradle. Cada provedor implementa a interface abstrata `MainAPI`, sendo empacotado em um arquivo binário `.cs3` com seu respectivo `manifest.json`.

```
┌─────────────────────────────────────────────────────────────┐
│                    CloudStream 3 Client                     │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
       Carrega 7 Plugins               Consulta Catálogo
               │                               │
               ▼                               ▼
    ┌──────────────────────┐        ┌──────────────────────┐
    │  Plugin (.cs3 / DEX) │        │     plugins.json     │
    │  (kythourcl-dist)    │        │  (kythourcl-dist)    │
    └──────────────────────┘        └──────────────────────┘
```

---

## 2. Auditoria Completa dos 7 Provedores

### 2.1 AnimeFire (`AnimeFire.kt`)
* **URL Base:** `https://animefire.io`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Anime, Filmes de Anime, OVAs
* **Pipeline de Extração em Cascata:**
  1. **Blogger RPC (`batchexecute`):** Resolução nativa de tokens do Google Video sem abrir WebViews.
  2. **API JSON CDN AnimeFire:** Consulta a `/video/$slug/$ep` e `lightspeedst.net` com parsing seguro em Jackson.
  3. **Tags DOM:** Leitura direta de `<video src="...">` e `<source src="...">`.
  4. **`loadExtractor`:** Delegação para servidores de terceiros (*Sendvid, Streamwish, Filemoon, Mixdrop*).

### 2.2 CineVision (`CineVision.kt`)
* **URL Base:** `https://www.cinevision.lat`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Filmes, Séries, Animes, Desenhos
* **Destaques:**
  - **`LocalHlsServer` (Socket Local):** Servidor HTTP loopback (`127.0.0.1:port`) que serve manifests `#EXTM3U` dinâmicos diretamente ao ExoPlayer com MIME `application/vnd.apple.mpegurl`.
  - **Prioridade Imediata LoadVid:** Resolução da API de tokens em <300ms.
  - **Suporte Multi-CDN:** MegaEmbed, SuperFlix, PlayerFlix, VidSrc e StreamWish.

### 2.3 NetCine (`NetCine.kt`)
* **URL Base:** `https://nnn1.lat`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Filmes, Séries, Animes
* **Destaques:**
  - **Sanitização de Metadados:** Limpeza de títulos e filtros anti-ruído.
  - **Motor OCR In-App Multi-Key:** Resolução de captchas gráficos com OCR.space e fusão contínua de cookies (`mergeCookies`).

### 2.4 Pobreflix (`Pobreflix.kt`)
* **URL Base:** `https://www.pobreflixtv.locker`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Filmes, Séries, Animes
* **Destaques:**
  - **Busca AJAX Videobox:** Rota assíncrona `index.php?app=videobox&module=video&controller=index&do=buscarContent`.
  - **Extração de Séries:** Endpoint dinâmico `episodesList` com separação por temporada e áudio.
  - **Multi-Servidor `playerData`:** Suporte a múltiplos servidores paralelos.

### 2.5 RedeCanais (`RedeCanais.kt`)
* **URL Base:** `https://www3.redecanais.vip`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Filmes, Séries, Animes, TV Online
* **Destaques:**
  - **Parser Semântico de Séries:** Mapeamento de temporadas e navegação de episódios.
  - **DooPlayer WP-JSON / AJAX:** Resolução dos 7 servidores oficiais do catálogo.
  - **Headers anti-WAF:** `Referer: https://www3.redecanais.vip/` e `Upgrade-Insecure-Requests: 1`.

### 2.6 RedeCanais AF (`RedeCanaisAF.kt`)
* **URL Base:** `https://redecanais.af`
* **Linguagem:** `pt-br`
* **Framework:** **PHP Melody CMS** (Totalmente independente do DooPlay)
* **Tipos de Mídia:** Filmes, Séries, Animes, Desenhos & Cartoons
* **Destaques:**
  - **Módulo 100% Autônomo e Exclusivo:** Sem qualquer dependência ou fallback de outros domínios.
  - **Rotas Canônicas PHP Melody:** `/browse-filmes-lancamentos-videos-{page}-date.html`, `/browse-series-videos-{page}-date.html`, `/search.php?keywords={query}`.
  - **Interceptor Nativo `CloudflareKiller`:** Bypass automático de mitigação e captura de sessão via WebView e `CookieManager` do Android.
  - **Resolução de Vídeo Proprietária:** Extração direta de players PHP Melody (`player3.php?v=...`, `player.php?v=...`, iframes embutidos e streams diretos `.m3u8` / `.mp4`).

### 2.7 TopAnimes (`TopAnimes.kt`)
* **URL Base:** `https://topanimes.net`
* **Linguagem:** `pt-br`
* **Tipos de Mídia:** Animes, Filmes, Donghuas
* **Destaques:**
  - **DooPlayer AJAX Nativo:** Consulta direta a `/wp-admin/admin-ajax.php`.
  - **Alibaba CDN API (`sk-api.alibabacdn.net`):** Força `mode=api2` com labels de qualidade.

---

## 3. Infraestrutura de Build, Distribuição e CI/CD

### 🛡️ Regra de Ouro de Integridade do `plugins.json`:
* O CloudStream valida estritamente o campo `fileHash` (SHA-256) e `fileSize` (em bytes).
* Após compilar qualquer `.cs3`, o script de deploy recalcula o hash e tamanho exatos para todos os **7 plugins** antes de realizar o push para `kythourcl-dist` (`main` e `builds`).
