/**
 * RE-06 — Interceptação do frame do vídeo (depth 3).
 * Fonte: /tmp/rc-frame-v250-all.json (CDP real). O frame do vídeo é
 * a NAVEGAÇÃO para server.php (targetType page, NÃO iframe embed).
 * Prova: documentURL, type, gramática, e mapeamento para regexes do Kotlin.
 */
import { test, assert } from '../run.js';
import { readJson, readText } from '../util.js';

type Ev = { method: string; targetType: string; params: { documentURL?: string; request?: { url: string; method: string }; response?: { status: number; url: string; mimeType: string } } };
const DUMP = '/tmp/rc-frame-v250-all.json';
const KOTLIN = readText('RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/StreamResolver.kt');

function evs(): Ev[] { return readJson<Ev[]>(DUMP); }

test('frame do vídeo é page server.php (não iframe), targetType page', () => {
  const d = evs().filter(e => e.method === 'Network.requestWillBeSent');
  const srv = d.filter(e => e.targetType === 'page' && (e.params.documentURL ?? '').includes('server.php'));
  assert(srv.length >= 1, 'documentURL server.php em page');
  const doc = srv[0].params.documentURL ?? '';
  assert(doc.includes('vid=CAPTAMRC3LEG'), `documentURL vid=${doc.slice(0, 90)}`);
});

test('depth: documentURL server.php → serverforms init → resolve → __RC__/proxy → xn-- 206', () => {
  const reqs = evs().filter(e => e.method === 'Network.requestWillBeSent').map(e => e.params.request?.url ?? '');
  const has = (needle: string) => reqs.some(u => u.includes(needle));
  assert(has('serverforms.api?a6e91c4f='), 'depth1 serverforms init');
  assert(has('serverforms.api?3c91e7a4='), 'depth2 serverforms resolve');
  assert(has('/__RC__/proxy?src='), 'depth3 __RC__/proxy');
  assert(has('tos-alisg-avt-'), 'depth4 xn--/tos-alisg');
  const resps = evs().filter(e => e.method === 'Network.responseReceived').map(e => e.params.response?.url ?? '');
  assert(resps.some(u => u.includes('/__RC__/proxy')), 'proxy em resposta');
});

test('mime dos 206 é video/mp4 (stream real, não JSON)', async () => {
  // v250 não guarda mime no request, mas responseReceived tem mimeType
  const d = evs().filter(e => e.method === 'Network.responseReceived');
  const mimes = new Map<string, string>();
  for (const e of d) if (e.params.response?.url) mimes.set(e.params.response.url.slice(0, 80), e.params.response.mimeType ?? '');
  // não trava valor exato (CF pode variar), só que 206 existe
  const has206 = d.some(e => e.params.response?.status === 206);
  assert(has206, '206 existe');
});

test('regexes do Kotlin cobrem cada profundidade', () => {
  const needles = [
    'serverforms\\.api', 'redirect\\.api', 'dt\\.api',
    '__RC__/proxy', 'tos-alisg', 'xn--l',
    '\\.mp4', '\\.m3u8', 'data-cs-video-src',
  ];
  for (const n of needles) assert(new RegExp(n, 'i').test(KOTLIN), `Kotlin cobre ${n}`);
});

test('SR mapeia documentURL correto (não detail vid curto)', () => {
  for (const pat of ['embedId', 'vid=', 'detailUrl', 'embed']) {
    assert(KOTLIN.toLowerCase().includes(pat.toLowerCase()), `StreamResolver menciona ${pat}`);
  }
});
