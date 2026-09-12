/**
 * RE-05 — Gramática do fluxo serverforms → __RC__/proxy → xn--/tos-alisg → mp4.
 * Fonte: /tmp/rc-frame-v250-all.json (URLs reais, sessão histórica).
 * Trava cada camada para detectar mudança de contrato do player.
 */
import { test, assert } from '../run.js';
import { readJson, queryOf } from '../util.js';

type Ev = { method: string; params: { request?: { url: string }; response?: { status: number; url: string } } };
const DUMP = '/tmp/rc-frame-v250-all.json';

function reqUrls(): string[] {
  const d = readJson<Ev[]>(DUMP);
  return d.filter(e => e.method === 'Network.requestWillBeSent').map(e => e.params?.request?.url ?? '');
}

const B64URL = /^[A-Za-z0-9_-]+$/;

test('init tem 5 params: 4 fixos + nonce 7e3a91 (48 chars b64url)', () => {
  const init = reqUrls().find(u => u.includes('serverforms.api?a6e91c4f='));
  assert(init !== undefined, 'init existe');
  const q = queryOf(init!);
  for (const [k, v] of [['a6e91c4f', '1'], ['71b8d2', 'RCFServer3'], ['e5c39f', 'ondemand'], ['0a6d84', 'CAPTAMRC3LEG']]) {
    assert(q.get(k) === v, `init ${k}=${v}`);
  }
  const nonce = q.get('7e3a91') ?? '';
  assert(nonce.length === 48 && B64URL.test(nonce), `nonce 7e3a91 b64url/48, veio ${nonce.length}`);
});

test('resolve repete o nonce 7e3a91 com OUTRO valor (sessão, não fixo)', () => {
  const urls = reqUrls().filter(u => u.includes('serverforms.api?3c91e7a4='));
  assert(urls.length >= 1, 'resolve existe');
  const q = queryOf(urls[0]);
  const nonce = q.get('7e3a91') ?? '';
  assert(nonce.length === 48 && B64URL.test(nonce), 'resolve tem nonce próprio');
  const init = reqUrls().find(u => u.includes('serverforms.api?a6e91c4f='))!;
  assert(q.get('7e3a91') !== queryOf(init).get('7e3a91'), 'nonce do resolve ≠ nonce do init');
});

test('proxy src decodifica em 3 camadas até mp4 assinado', () => {
  const p = reqUrls().find(u => u.includes('/__RC__/proxy'));
  assert(p !== undefined, 'proxy existe');
  const src = decodeURIComponent(queryOf(p!).get('src') ?? '');
  assert(src.includes('tos-alisg-avt-'), `camada2 tos-alisg: ${src.slice(0, 80)}`);
  const q2 = queryOf(src);
  assert(q2.get('container') === 'videos', 'container=videos');
  assert(q2.get('refresh') === '31536000', 'refresh=31536000 (1 ano)');
  // ACHADO RE: o `url=` interno NÃO é encoded — o `&` de `&nu3z…=` quebra
  // parse query padrão. O player faz split custom (tudo após `url=`).
  // Espelhamos: substring após `url=` em vez de queryOf.
  const inner = src.includes('url=') ? src.split('url=').slice(1).join('url=') : '';
  assert(/\.mp4\?sv=\d+&[A-Za-z0-9_.%-]+=\d+-[A-Za-z0-9_.%-]+/.test(inner), `mp4 assinado: ${inner.slice(0, 90)}`);
});

test('mp4 final carrega vid + servidor + timestamp (não reutilizável)', () => {
  const p = reqUrls().find(u => u.includes('/__RC__/proxy'))!;
  const src = decodeURIComponent(queryOf(p).get('src') ?? '');
  const inner = src.includes('url=') ? src.split('url=').slice(1).join('url=') : '';
  assert(inner.includes('CAPTAMRC3LEG'), 'vid no mp4');
  assert(inner.includes('RCFServer3'), 'servidor no mp4');
});
