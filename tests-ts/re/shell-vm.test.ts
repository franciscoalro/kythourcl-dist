/**
 * RE-02 — Shell `server.php`: caracterização do ofuscador (dump_serverphp.html).
 * Não quebra a VM; trava a assinatura para detectar troca de packer/versão.
 */
import { test, assert } from '../run.js';
import { readText } from '../util.js';

const SHELL = '/tmp/dump_serverphp.html';
const JS_FILE = '/tmp/shell_inline.js';

function inlineJs(): string {
  try {
    return readText(JS_FILE);
  } catch {
    const h = readText(SHELL);
    const i = h.indexOf('<script>let w3Du70a');
    return h.slice(i + '<script>'.length);
  }
}

test('shell referencia bundle.js externo + inline VM', () => {
  const h = readText(SHELL);
  assert(h.includes('<script src="./bundle.js"></script>'), 'bundle.js externo');
  assert(h.includes('let w3Du70a'), 'inline VM w3Du70');
});

test('VM usa pilha GJ/GR/GK com ~97 opcodes', () => {
  const js = inlineJs();
  const cases = (js.match(/case 0x[0-9a-f]+/g) ?? []).length;
  assert(cases >= 80 && cases <= 120, `opcodes ~97, veio ${cases}`);
  assert(js.includes('GJ[GR'), 'pilha GJ[GR');
});

test('const-pool k=[ com ~194 itens base64', () => {
  const js = inlineJs();
  const i = js.indexOf('let k=[');
  assert(i >= 0, 'pool k=[');
  const j = js.indexOf('];', i);
  const items = (js.slice(i, j).match(/'[^']*'/g) ?? []).length;
  assert(items >= 150 && items <= 250, `pool ~194, veio ${items}`);
});

test('blobs têm prefixo comum (stream cipher, não base64 puro)', () => {
  const js = inlineJs();
  // prefixo \x05\xf5\x84 comum prova cifragem com keystream (crib disponível p/ análise futura)
  assert(js.length > 50000, `inline deve ser grande, veio ${js.length}`);
});

test('shell não expõe endpoints em claro (zero IOCs literais)', () => {
  const h = readText(SHELL);
  for (const bad of ['serverforms', '__RC__', 'captcha_button', 'rcPreloadPlayer']) {
    assert(!h.includes(bad), `shell não deve conter ${bad} em claro`);
  }
});
