/**
 * NET-01 — Fingerprint de rede do canônico via fetch cru (sem browser).
 * Trava: challenge em rotas doc, 200 em robots.txt, header cf-mitigated,
 * 1006 na raiz sob ASN marcado, TLS 1.3 Cloudflare.
 */
import { test, assert } from '../run.js';

const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';

async function probe(path: string): Promise<{ code: number; mitigated: string; server: string; bodyHead: string }> {
  const res = await fetch(`https://redecanais.af${path}`, {
    headers: { 'User-Agent': UA, Accept: 'text/html,*/*;q=0.8' },
    redirect: 'manual',
  });
  const body = await res.text().catch(() => '');
  return {
    code: res.status,
    mitigated: res.headers.get('cf-mitigated') ?? '',
    server: res.headers.get('server') ?? '',
    bodyHead: body.slice(0, 120),
  };
}

test('robots.txt é 200 (rota limpa, sem challenge) — MAS com mitigated!', async () => {
  // DESCOBERTA RE: cf-mitigated: challenge vem em TODA resposta do CF,
  // inclusive 200. O header sozinho NÃO prova challenge — só vale com code≠200.
  // (Isso refinou o P0-1: ver teste xcheck mitigated-gating.)
  const r = await probe('/robots.txt');
  assert(r.code === 200, `robots=${r.code}`);
});

test('browse retorna 403 + cf-mitigated: challenge', async () => {
  const r = await probe('/browse-filmes-videos-1-date.html');
  assert(r.code === 403, `browse=${r.code}`);
  assert(r.mitigated.includes('challenge'), `mitigated=${r.mitigated}`);
  assert(r.server === 'cloudflare', `server=${r.server}`);
});

test('corpo do challenge é "Just a moment..." (não 1006 necessariamente)', async () => {
  const r = await probe('/browse-filmes-videos-1-date.html');
  const isChallenge = r.bodyHead.includes('Just a moment') || r.bodyHead.includes('error code: 1006');
  assert(isChallenge, `head=${r.bodyHead.slice(0, 60)}`);
});

test('search.php também desafia (mesma política)', async () => {
  const r = await probe('/search.php?keywords=batman');
  assert(r.code === 403 && r.mitigated.includes('challenge'), `search=${r.code}/${r.mitigated}`);
});
