# Registro de medicao — origem Tomato, 2026-09-28

Todas as medidas usam o mesmo header do plugin (Bearer lido do fonte, valor
nunca impresso) e o mesmo User-Agent do app. Duas rotas de saida:

- DIRETO: IP 167.233.60.72 (este servidor de teste)
- WARP: SOCKS5 127.0.0.1:40000, saida 104.28.207.215

## 1. Taxa de sucesso do /stream (Tarefa 1)

`tools/probe_stream_rate.py --n 20 --interval 3`, 40 requisicoes.

| rota | tentativas | 200 | 500 | taxa |
|---|---|---|---|---|
| via WARP | 20 | 20 | 0 | **100%** |
| direto | 20 | 0 | 20 | **0%** |

Confirma com uma segunda medicao independente de 12 rodadas
(par rota-falsa / /feed / /stream): 12/12 com (404, 200, 200).

O /stream **nao esta morto**. Quando o IP nao esta bloqueado, responde 200 de
primeira, com latencia de 0.13s a 0.34s.

## 2. De onde vem o 500 (Tarefa 0)

Mesma rota (`GET /v2/anime/episode/6412/stream`), mesmo token, duas saidas:

```
direto: HTTP 500  Server: cloudflare  Content-Type: text/html; charset=utf-8
        corpo: "Internal Server Error"
WARP  : HTTP 403  Server: cloudflare  Content-Type: application/json
        corpo: {"status":false,"message":"authentication failed","status_code":403}
```

O 500 e' **pagina de erro do Cloudflare**, servida no edge antes do backend. O
backend responde JSON; nao produz esse HTML. Logo o 500 sem WARP e' bloqueio de
IP no edge, e o dispatch nunca rodou porque nunca chegou nele.

## 3. Matriz de camada (Tarefa 0)

`tools/probe_layer_matrix.py`:

| | rota inexistente | /v2/anime/1921 | OPTIONS /v2/anime/1921 |
|---|---|---|---|
| via WARP | 404 | 403 | 204 |
| IP bloqueado | 500 | 500 | 204 |

Duas conclusoes:

1. Numa origem VIVA a rota inexistente devolve **404**, nunca 500. A v158 estava
   certa em que origem morta da 500, mas errada em achar que 500 e' prova:
   o 500 do IP bloqueado e' indistinguivel.
2. **OPTIONS devolve 204 nos dois casos**, entao nao serve de discriminante. Era a
   unica alternativa pensada, e foi medida: descartada.

## 4. Token (Tarefa 4)

O header `Authorization: Bearer` e' **obrigatorio**. Mesma rota, mesma sessao:
200 com o Bearer, 403 `authentication failed` sem ele. Nao existe publicacao
sem token que continue funcionando. A saida real do token do artefato e'
rotacao do lado da origem, que depende de login no aplicativo (hCaptcha).

## 5. Paginacao de temporada (Tarefa 3)

`tools/probe_season_pagination.py` na janela em que a origem respondeu:

| season | total declarado | pagina 0 trouxe |
|---|---|---|
| 555 (Dublado Season I) | 25 | 25 |
| 214 (Dublado Season II) | 12 | 12 |
| 215 (Dublado Season III) | 22 | 22 |
| 216 (Final Season) | — | 500 na janela |

Estrutura real (corrige a suposicao do plano): `GET /v2/anime/{id}` devolve
`anime_seasons:[{season_id, season_name, season_number, season_dubbed}]` e
**nao traz contagem de episodios**. O total so aparece em
`POST /season/{id}/episodes`, no campo `episodes`.

**Paginacao multi-pagina NAO foi exercitada.** A season 555 tem exatamente 25,
igual ao tamanho de pagina observado, o que sugere corte do servidor em 25 — mas
e' hipotese, nao contrato, e nao ha season conhecida com total maior que 25 para
testar. A v163 esta correta para o caso de uma pagina, que e' o que existe hoje.

## 6. Janela de bloqueio que fechou a sessao

Depois das medicoes acima, as rotas passaram a responder 500 em 20/20
(season 555 paginas 0 e 1) e depois 500 tambem via WARP, inclusive
`/v2/animes/feed`, `/v2/anime/1089` e `/stream`. Persistiu por mais de 180s de
reavaliacao. O corpo continuava sendo a pagina HTML do Cloudflare
(`Server: cloudflare`, `Content-Type: text/html`), ou seja, bloqueio de edge,
nao rota quebrada.

Isso confirma o ponto central da v164: **o 5xx isolado nao permite concluir que a
origem morreu**, e a origem logo voltou a 200. Cortar o retry nesse estado seria
entregar "nenhum link" ao usuario durante janelas em que a API responderia.
