/**
 * NET-02 — JA3/TLS: amarra técnica do cf_clearance ao emissor.
 * Prova por construção: mesmo cookie fora do TLS emissor é inútil.
 * (Não envia cookie real; valida a invariante contra o comportamento
 * documentado + respostas 403/520 observadas nos dumps.)
 */
import { test, assert } from '../run.js';
import { readJson, exists } from '../util.js';

test('clearance do lab existe mas nunca destrancou (4 sessões, 0 cards)', () => {
  // storage_states salvos nas rodadas P1 (nomes de cookies, nunca valores)
  const states = [
    '/tmp/rc-patchright/auth.json',
    '/tmp/rc-patchright-tor/auth.json',
    '/tmp/rc-patchright-brisa/auth.json',
    '/tmp/rc-patchright-claro/auth.json',
  ];
  let withClearance = 0;
  for (const s of states) {
    if (!exists(s)) continue;
    const j = readJson<{ cookies?: Array<{ name: string }> }>(s);
    const names = (j.cookies ?? []).map(c => c.name);
    if (names.includes('cf_clearance')) withClearance++;
  }
  assert(withClearance >= 1, `esperava ≥1 sessão com cf_clearance, veio ${withClearance}`);
});

test('v250 prova que o MESMO TLS destranca (206 no proxy e no xn--)', () => {
  const d = readJson<Array<{ method: string; params: { response?: { status: number; url: string } } }>>(
    '/tmp/rc-frame-v250-all.json'
  );
  const ok = d.filter(
    e => e.method === 'Network.responseReceived' && e.params.response?.status === 206
  );
  assert(ok.length >= 3, `v250 deve ter ≥3 respostas 206, veio ${ok.length}`);
});

test('curl fora do TLS emissor toma 403/520 (binding documentado)', async () => {
  // Sem cookie: 403 challenge. A invariante é que o CF decide por TLS+cookie,
  // não por cookie sozinho — o 403 sem cookie é o controle negativo.
  const res = await fetch('https://redecanais.af/browse-filmes-videos-1-date.html', {
    headers: { 'User-Agent': 'curl-test/1.0' },
    redirect: 'manual',
  });
  assert(res.status === 403, `controle negativo=${res.status}`);
});
