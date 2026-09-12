/**
 * LIVE-01 — Scraping ao vivo via egresso residencial (túnel SOCKS :19053).
 * Roda fetch cru ATRAVÉS do túnel quando disponível; se o túnel cair,
 * os testes pulam (skip) em vez de falhar — o túnel é efêmero.
 * Trava o comportamento do alvo sob ASN residencial Brisanet.
 */
import { test, assert } from '../run.js';
import { SocksHttp, TUNNEL_UP } from './socks.js';

const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';

function gate(): SocksHttp | null {
  if (!TUNNEL_UP) { console.log('  (skip: túnel :19053 fora do ar)'); return null; }
  return new SocksHttp('127.0.0.1', 19053);
}

async function withRetry<T>(fn: () => Promise<T>, retries = 2): Promise<T> {
  let last: unknown;
  for (let i = 0; i <= retries; i++) {
    try { return await fn(); } catch (e) { last = e; await new Promise(r => setTimeout(r, 1200 * (i + 1))); }
  }
  throw last;
}

test('LIVE browse via Brisanet: 403 challenge (não 1006 = IP não banido)', async () => {
  const s = gate(); if (!s) return;
  const r = await withRetry(() => s.get('https://redecanais.af/browse-filmes-videos-1-date.html', { 'User-Agent': UA }));
  assert(r.status === 403, `browse=${r.status}`);
  const h = r.headers.get('cf-mitigated') ?? '';
  assert(h.includes('challenge'), `mitigated=${h}`);
  assert(!r.body.includes('error code: 1006'), 'Brisanet NÃO toma 1006 (não banido)');
  assert(r.body.includes('Just a moment'), 'challenge managed padrão');
});

test('LIVE robots via Brisanet: 200', async () => {
  const s = gate(); if (!s) return;
  const r = await s.get('https://redecanais.af/robots.txt', { 'User-Agent': UA });
  assert(r.status === 200, `robots=${r.status}`);
});

test('LIVE search via Brisanet: 403 challenge (mesma política)', async () => {
  const s = gate(); if (!s) return;
  const r = await s.get('https://redecanais.af/search.php?keywords=batman', { 'User-Agent': UA });
  assert(r.status === 403, `search=${r.status}`);
});

test('LIVE server.php via Brisanet: 403 (player exige sessão, não é aberto)', async () => {
  const s = gate(); if (!s) return;
  const r = await s.get(
    'https://redecanais.af/player3/server.php?categoria=vod&server=RCFServer3&subfolder=ondemand&vid=CAPTAMRC3LEG',
    { 'User-Agent': UA }
  );
  assert(r.status === 403, `server.php=${r.status}`);
});
