#!/usr/bin/env node
/** bundle-unwrap.mjs — executa o bundle.js ofuscado em vm para extrair strings decodificadas */
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const js = readFileSync('/tmp/bundle.js', 'utf8');
const ctx = vm.createContext({
  console, Math, String, Array, Object, RegExp, Date, parseInt, parseFloat, isNaN,
  setTimeout, setInterval, clearTimeout, clearInterval,
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
});

// capturar o pool decodificado: interceptar acesso a a0d/a0e
let pool = null;
const probe = `
let __captured = null;
${js.slice(0, 8000)}
__captured = (typeof b0 !== 'undefined' ? b0 : (typeof _0x1234 !== 'undefined' ? _0x1234 : null));
`;
try { vm.runInContext(probe, ctx, { timeout: 5000 }); pool = ctx.__captured; } catch(e) { console.error('probe fail:', e.message.slice(0,200)); }
// fallback: executar tudo e varrer globais
if (!pool) {
  try { vm.runInContext(js, ctx, { timeout: 5000 }); } catch(e) { /* bundle espera DOM, vai falhar */ }
  for (const k of Object.keys(ctx)) {
    const v = ctx[k];
    if (Array.isArray(v) && v.length > 100 && typeof v[0] === 'string') {
      console.log(`array global ${k}: ${v.length} items, sample:`, v.slice(0,3).map(s=>s.slice(0,40)));
      if (!pool) pool = v;
    }
  }
}
if (pool) console.log('POOL captured elsewhere');
// varrer literais já visíveis sem execução (fetch, server.php, etc)
const needles = ['serverforms','dt.api','redirect.api','__RC__','/proxy','redecanaistv','neosoro','tos-alisg','container=videos','query','a0d','a0e'];
for (const n of needles) {
  const c = (js.match(new RegExp(n.replace('.','\\.'), 'g'))||[]).length;
  if (c) console.log(`${n}: ${c}x`);
}
