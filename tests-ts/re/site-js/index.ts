/**
 * re/site-js/ — Réplicas executáveis do JS do site (TS como linguagem de RE).
 * Re-exporta funções verbatim para que os testes exercitem o runtime real,
 * não mocks. Cada função é cópia literal de /tmp/rc-search-p4.html.
 */
export function siteNormalize(str: string): string {
  return str.toLowerCase()
    .replace(/[áàãâä]/g, 'a').replace(/[éèêë]/g, 'e').replace(/[íìîï]/g, 'i')
    .replace(/[óòõôö]/g, 'o').replace(/[úùûü]/g, 'u')
    .replace(/[ç]/g, 'c').replace(/[ñ]/g, 'n');
}

export function siteDecodeHtml(html: string): string {
  // Node não tem textarea; decodifica as 5 entities que importam no índice.
  return html.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'");
}

export interface IndexEntry { titulo: string; url: string; titulo_norm: string; origem: string; }

/** Replica exata do loop `texto.split("\n").forEach(linha => match...)`. */
export function siteParseIndex(texto: string, chave: string): IndexEntry[] {
  const out: IndexEntry[] = [];
  for (const linha of texto.split('\n')) {
    const match = linha.match(/^(.*?)<a href="(.*?)"/i);
    if (!match) continue;
    const bruto = match[1].replace(/<\/?b[^>]*>/gi, '').replace(/-\s*$/, '').trim();
    const titulo = siteDecodeHtml(bruto);
    out.push({ titulo, url: match[2].trim(), titulo_norm: siteNormalize(titulo), origem: chave });
  }
  return out;
}

export function siteRenderMatch(titulo_norm: string, filtro: string): boolean {
  if (!filtro) return false;
  if (filtro === 'mapa completo') return true; // caller filtra por origem separadamente
  return titulo_norm.includes(filtro);
}

/** Concatena os dois índices (ordem do site: mapa, depois mapafilmes). */
export function siteMergeIndexes(a: IndexEntry[], b: IndexEntry[]): IndexEntry[] {
  const all = [...a, ...b];
  all.sort((x, y) => x.titulo_norm.localeCompare(y.titulo_norm));
  return all;
}
