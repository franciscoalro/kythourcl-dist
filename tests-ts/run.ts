/**
 * Minimal test harness: glob-free runner over `tsx`.
 * Usage: pnpm tsx tests-ts/run.ts  (runs every *.test.ts, reports pass/fail)
 */
import { readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export type TestFn = () => void | Promise<void>;
// Registra também o arquivo de origem via stack (evita rótulo '<unknown>').
const registry: Array<{ file: string; name: string; fn: TestFn }> = [];

export function test(name: string, fn: TestFn): void {
  const st = new Error().stack?.split('\n')[2] ?? '';
  const m = st.match(/\/tests-ts\/[^):]+/) ?? st.match(/tests-ts[^):]*/) ?? st.match(/(file:\/\/)?(\/[^\s):]+\.test\.ts)/);
  registry.push({ file: m ? m[0] : '<unknown>', name, fn });
}

function collect(dir: string, out: string[]): void {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) collect(p, out);
    else if (e.endsWith('.test.ts')) out.push(p);
  }
}

function assert(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new Error(`assert: ${msg}`);
}
export { assert };

const root = resolve('tests-ts');
const files: string[] = [];
collect(root, files);
files.sort();

async function main(): Promise<void> {
let pass = 0;
let fail = 0;
const failures: string[] = [];
for (const f of files) {
  registry.length = 0;
  await import(pathToFileURL(resolve(f)).href);
  for (const t of registry) {
    try {
      await t.fn();
      pass++;
      console.log(`ok - ${f} :: ${t.name}`);
    } catch (e) {
      fail++;
      const msg = e instanceof Error ? e.message : String(e);
      failures.push(`${f} :: ${t.name}\n    ${msg}`);
      console.log(`FAIL - ${f} :: ${t.name}\n    ${msg}`);
    }
  }
}
console.log(`\n${pass} passed, ${fail} failed, ${files.length} files`);
if (fail > 0) {
  console.log('\nFailures:\n' + failures.join('\n'));
  process.exit(1);
}
}

void main();
