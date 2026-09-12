/**
 * RE-01 — Grafo de rede do player (dump v250 `rc-frame-v250-all.json`).
 * Prova a sequência canônica: server.php → serverforms.api (init) →
 * serverforms.api (resolve) → jquery.videojs.4.5.2.api → __RC__/proxy (206) → xn-- (206).
 */
import { test, assert } from '../run.js';
import { readJson } from '../util.js';

type Ev = { method: string; params: { request?: { method: string; url: string }; response?: { status: number; url: string } } };
const DUMP = '/tmp/rc-frame-v250-all.json';

function reqs(): Array<{ method: string; url: string }> {
  const d = readJson<Ev[]>(DUMP);
  return d.filter(e => e.method === 'Network.requestWillBeSent').map(e => ({
    method: e.params.request?.method ?? '?', url: e.params.request?.url ?? '',
  }));
}

function resps(): Array<{ status: number; url: string }> {
  const d = readJson<Ev[]>(DUMP);
  return d.filter(e => e.method === 'Network.responseReceived').map(e => ({
    status: e.params.response?.status ?? 0, url: e.params.response?.url ?? '',
  }));
}

test('serverforms init usa nonce curto + params do server.php', () => {
  const init = reqs().filter(r => r.url.includes('serverforms.api?a6e91c4f='));
  assert(init.length >= 1, 'esperava init serverforms');
  const u = init[0].url;
  for (const kv of ['71b8d2=RCFServer3', 'e5c39f=ondemand', '0a6d84=CAPTAMRC3LEG']) {
    assert(u.includes(kv), `init deve conter ${kv}`);
  }
});

test('serverforms resolve usa token opaco longo (não reutilizável)', () => {
  const res = reqs().filter(r => /serverforms\.api\?3c91e7a4=/.test(r.url));
  assert(res.length >= 1, 'esperava resolve serverforms');
  const tok = res[0].url.split('3c91e7a4=')[1].split('&')[0];
  assert(tok.length > 40, `token opaco deve ser longo, veio len=${tok.length}`);
});

test('__RC__/proxy responde 206 e encadeia para xn-- 206', () => {
  const r = resps();
  const proxy206 = r.filter(x => x.url.includes('/__RC__/proxy') && x.status === 206);
  assert(proxy206.length >= 1, 'proxy deve dar 206');
  const xn206 = r.filter(x => x.url.includes('tos-alisg') && x.status === 206);
  assert(xn206.length >= 1, 'xn-- deve dar 206');
});

test('não há dooplayer/wp-json/superflix no caminho feliz', () => {
  const all = reqs().map(r => r.url).join('\n').toLowerCase();
  for (const bad of ['wp-json', 'dooplayer', 'superflix', 'megaembed', 'playerflix']) {
    assert(!all.includes(bad), `caminho feliz não deve conter ${bad}`);
  }
});

test('player carrega stack videojs + ima (ads) + vtt + poster', () => {
  const all = reqs().map(r => r.url).join('\n');
  for (const need of ['jquery.videojs.4.5.2.api', 'videojs.ima.js', '.vtt', '.jpg']) {
    assert(all.includes(need), `stack deve conter ${need}`);
  }
});
