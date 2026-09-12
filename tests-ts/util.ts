/** Shared IO helpers for the TS probes (stdlib only). */
import { readFileSync, existsSync } from 'node:fs';

export function readText(path: string): string {
  return readFileSync(path, 'utf8');
}

export function readJson<T = unknown>(path: string): T {
  return JSON.parse(readText(path)) as T;
}

export function exists(path: string): boolean {
  return existsSync(path);
}

/** Find all regex matches with index info. */
export function matches(re: RegExp, text: string): Array<{ m: string; groups: string[]; index: number }> {
  const flags = re.flags.includes('g') ? re.flags : re.flags + 'g';
  const rx = new RegExp(re.source, flags);
  const out: Array<{ m: string; groups: string[]; index: number }> = [];
  let m: RegExpExecArray | null;
  while ((m = rx.exec(text)) !== null) out.push({ m: m[0], groups: m.slice(1), index: m.index });
  return out;
}

/** Decode a URL query string into a map (first value wins). */
export function queryOf(url: string): Map<string, string> {
  const q = url.split('?')[1]?.split('#')[0] ?? '';
  const map = new Map<string, string>();
  for (const part of q.split('&')) {
    if (!part) continue;
    const i = part.indexOf('=');
    const k = decodeURIComponent((i < 0 ? part : part.slice(0, i)).replace(/\+/g, ' '));
    if (!map.has(k)) map.set(k, decodeURIComponent(((i < 0 ? '' : part.slice(i + 1))).replace(/\+/g, ' ')));
  }
  return map;
}
