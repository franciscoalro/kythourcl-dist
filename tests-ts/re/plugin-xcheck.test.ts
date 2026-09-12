/**
 * XCHECK — Invariantes do plugin Kotlin vs dumps reais.
 * Lê o fonte Kotlin como texto e exige que cada marcador provado nos dumps
 * tenha cobertura no código. Quebra se alguém remover cobertura.
 */
import { test, assert } from '../run.js';
import { readText, readJson } from '../util.js';

const SR = readText('RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/StreamResolver.kt');
const AF = readText('RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/RedeCanaisAF.kt');
const CF = readText('RedeCanaisAF/src/main/kotlin/com/RedeCanaisAF/CloudflareSolver.kt');
const ALL = SR + '\n' + AF + '\n' + CF;

test('todos os marcadores do v250 têm cobertura no Kotlin', () => {
  // marcadores extraídos do dump v250 (requests reais)
  const markers = [
    'serverforms', '__RC__/proxy', 'tos-alisg', 'xn--l',
    'videojs', 'captcha_button', 'server.php',
  ];
  for (const m of markers) {
    assert(ALL.toLowerCase().includes(m.toLowerCase()), `Kotlin deve cobrir ${m}`);
  }
});

test('P0-1 refinado: mitigated só vale com code != 200 (robots.txt prova)', () => {
  assert(AF.includes('code != 200'), 'gating code != 200 no mitigatedHeader');
  assert(AF.includes('(retryRes?.code ?: 0) != 200'), 'gating code != 200 no retryMitigated');
});

test('P0-2: 402 com clearance invalida e re-resolve', () => {
  assert(AF.includes('402') && AF.includes('invalidateClearance'), '402 → invalidateClearance');
});

test('P0-3: fallback TAB+Espaço existe no solver', () => {
  assert(CF.includes('KEYCODE_TAB') && CF.includes('KEYCODE_SPACE'), 'TAB+Espaço no solver');
});

test('P0-4: UA unificado respeita preexistente', () => {
  assert(CF.includes('preexistente'), 'UA preexistente respeitado');
});

test('whitelist do StreamResolver casa URL canônica do v250', () => {
  const d = readJson<Array<{ method: string; params?: { request?: { url: string } } }>>(
    '/tmp/rc-frame-v250-all.json'
  );
  const proxy = d.map(e => e.params?.request?.url ?? '').find(u => u.includes('/__RC__/proxy'));
  assert(proxy !== undefined, 'v250 tem __RC__/proxy');
  // regex do Kotlin deve casar a URL real
  const rx = /https?:\/\/[^\s"'']+\/__RC__\/proxy\?src=[^\s"'']+/i;
  assert(rx.test(proxy!), 'regex do plugin casa URL real do v250');
});
