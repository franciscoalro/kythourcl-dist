/**
 * re/proxy_extract.ts — Parser do player (RE-05/06) executável em TS.
 * Replica a lógica de `StreamResolver` / `resolveStreamOrExtractor` que
 * extrai streams de HTML/JS do player. Usado pelos testes e documenta
 * o contrato real das regexes (split custom em `url=`).
 */

export interface ExtractedStream { url: string; kind: 'mp4' | 'm3u8' | 'api' | 'other'; }

const STREAM_PATTERNS: RegExp[] = [
  /["'](https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?)["']/gi,
  /(?:file|source|src|stream|hls|video)\s*[:=]\s*["'](https?:\/\/[^\s"'\\]+)["']/gi,
  /<source[^>]+src=["'](https?:\/\/[^\s"']+)["']/gi,
  /<video[^>]+src=["'](https?:\/\/[^\s"']+)["']/gi,
  /data-cs-video-src=["'](https?:\/\/[^\s"']+)["']/gi,
  /["']((?:https?:\/\/[^\s"'\\]*?)?\/(?:player3\/)?(?:dt\.api|serverforms\.api|query\.api|query\.js|getvid\.php|getlink\.php)[^"'\s\\]*)["']/gi,
  /["']((?:https?:\/\/[^\s"'\\]*?)?\/player3\/redirect\.api\?p=[^"'\s\\]+)["']/gi,
];

/** Extrai candidatos de stream de um HTML/JS bruto (pré-filtro, sem isValid). */
export function rawExtract(html: string): string[] {
  const out = new Set<string>();
  for (const rx of STREAM_PATTERNS) {
    rx.lastIndex = 0;
    for (const m of html.matchAll(rx)) {
      const g = m[1] ?? m[0];
      out.add(g.replace(/\\\//g, '/').replace(/&amp;/g, '&').trim().replace(/["',;]+$/, ''));
    }
  }
  return [...out];
}

/**
 * Parse da cadeia __RC__/proxy → xn--/tos-alisg → mp4 assinado.
 * Retorna {container, refresh, mp4} ou null.
 * Trata o `&` não-encoded em `url=` via split custom.
 */
export function parseProxyChain(proxyUrl: string): { container: string; refresh: string; mp4: string } | null {
  const raw = (() => {
    try { return decodeURIComponent(new URL(proxyUrl).searchParams.get('src') ?? ''); }
    catch { return ''; }
  })();
  if (!raw) return null;
  const m = raw.match(/[?&]container=([^&]+).*?[?&]refresh=([^&]+).*?url=(.*)$/s);
  if (!m) return null;
  return { container: m[1], refresh: m[2], mp4: m[3] };
}

export function decodeRedirectParam(url: string): string | null {
  const p = new URL(url.replace('redecanaistv.af', 'redecanais.af')).searchParams.get('p');
  if (!p) return null;
  try { return atob(p.replace(/-/g, '+').replace(/_/g, '/')); } catch { return null; }
}
