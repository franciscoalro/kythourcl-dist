/**
 * RE-07 — JS do site executável em TS (linguagem de RE = TS).
 * Prova que `tests-ts/re/site-js/` é réplica fiel: roda o runtime real
 * sobre dumps e linhas sintéticas, não só checa presença de string.
 */
import { test, assert } from '../run.js';
import { siteNormalize, siteDecodeHtml, siteParseIndex, siteMergeIndexes } from './site-js/index.js';

test('siteNormalize === lower + tabela (8 casos PT-BR)', () => {
  const cases: Array<[string, string]> = [
    ['João & Maria — ep. 3 (Dublado)', 'joao & maria — ep. 3 (dublado)'],
    ['Não Há Tempo', 'nao ha tempo'],
    ['Coração Açúcar', 'coracao acucar'],
  ];
  for (const [raw, exp] of cases) assert(siteNormalize(raw) === exp, `normalize ${JSON.stringify(raw)}`);
});

test('siteParseIndex replica regex ^(.*?)<a href="(.*?)"', () => {
  const texto = 'A Casa do Dragão S02E05 <b>HD</b><a href="/a-casa-do-dragao-s02e05.html"\n'
    + 'Sem Link Aqui\n'
    + 'Outro - <a href="/outro.html"';
  const rows = siteParseIndex(texto, 'mapa');
  assert(rows.length === 2, `parse ${rows.length}`);
  assert(rows[0].titulo === 'A Casa do Dragão S02E05 HD', `titulo=${rows[0].titulo}`);
  assert(rows[0].url === '/a-casa-do-dragao-s02e05.html', `url=${rows[0].url}`);
});

test('siteDecodeHtml cobre entities do índice', () => {
  assert(siteDecodeHtml('A &amp; B') === 'A & B', '&amp;');
  assert(siteDecodeHtml('x &lt;y&gt;') === 'x <y>', 'lt/gt');
});

test('siteMergeIndexes sort por titulo_norm', () => {
  const a = siteParseIndex('Zebra<a href="/z.html"\n', 'mapa');
  const b = siteParseIndex('Água<a href="/a.html"\n', 'mapafilmes');
  const all = siteMergeIndexes(a, b);
  // Água normaliza para agua, vem antes de zebra
  assert(all[0].titulo.includes('Água'), `sort ${all[0].titulo}`);
});
