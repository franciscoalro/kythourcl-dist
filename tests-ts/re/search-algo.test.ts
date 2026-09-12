/**
 * RE-03 — Algoritmo de busca verbatim (rc-search-p4.html).
 * Espelha o JS do site linha a linha: normalize sem NFD, índice
 * `TITULO<a href="URL"`, strip <b>, sort por titulo_norm, includes.
 */
import { test, assert } from '../run.js';
import { readText } from '../util.js';

const P4 = '/tmp/rc-search-p4.html';

/** Cópia fiel de `normalize()` do site (sem toLowerCase fora, sem NFD). */
function siteNormalize(str: string): string {
  return str.toLowerCase()
    .replace(/[áàãâä]/g, 'a').replace(/[éèêë]/g, 'e').replace(/[íìîï]/g, 'i')
    .replace(/[óòõôö]/g, 'o').replace(/[úùûü]/g, 'u')
    .replace(/[ç]/g, 'c').replace(/[ñ]/g, 'n');
}

test('normalize do site existe verbatim no dump', () => {
  const h = readText(P4);
  assert(h.includes('function normalize(str)'), 'normalize()');
  assert(h.includes('.replace(/[áàãâä]/g,"a")'), 'mapa a');
  assert(h.includes('.replace(/[ñ]/g,"n")'), 'mapa ñ');
  assert(!h.includes('normalize("NFD")') && !h.includes('normalize(\'NFD\')'), 'site NÃO usa NFD');
});

test('índice usa os dois arquivos + regex ^(.*?)<a href="(.*?)"', () => {
  const h = readText(P4);
  assert(h.includes('final_mapa.txt') && h.includes('final_mapafilmes.txt'), 'dois índices');
  assert(h.includes('/^(.*?)<a href="(.*?)"/i'), 'regex do índice');
});

test('pipeline bruto→titulo→titulo_norm espelhado', () => {
  const line = 'A Captura (Dublado) - 2026 <b>filme</b><a href="/a-captura-dublado-2026-1080p_a707773a0.html"';
  const m = line.match(/^(.*?)<a href="(.*?)"/i);
  assert(m !== null, 'regex casa');
  const bruto = m![1].replace(/<\/?b[^>]*>/gi, '').replace(/-\s*$/, '').trim();
  assert(bruto === 'A Captura (Dublado) - 2026 filme', `bruto=${bruto}`);
  assert(siteNormalize(bruto).includes(siteNormalize('a captura')), 'includes normalizado');
});

test('query via ?keywords= + render em .listagem', () => {
  const h = readText(P4);
  assert(h.includes('params.has("keywords")'), '?keywords=');
  assert(h.includes('document.querySelector(".listagem")'), '.listagem');
  assert(h.includes('titulo_norm.localeCompare'), 'sort por titulo_norm');
});

test('siteNormalize concorda com casos reais', () => {
  assert(siteNormalize('Coração') === 'coracao', 'coração');
  assert(siteNormalize('Capitão América: Guerra Civil').includes('capitao america'), 'capitão');
});
