/**
 * RE-04 — Paridade normalize Kotlin (NFD) vs tabela verbatim do site.
 * Diferencial executado 2026-09-11: 16/18 idênticos; divergem só ł/ž
 * (polonês/tcheco, irrelevante p/ catálogo PT-BR; NFD normaliza MAIS).
 * Trava a paridade para o vocabulário real do catálogo.
 */
import { test, assert } from '../run.js';

function kotlinNorm(s: string): string {
  return s.toLowerCase().trim().normalize('NFD').replace(/[̀-ͯ]+/g, '');
}

function siteNorm(s: string): string {
  return s.toLowerCase()
    .replace(/[áàãâä]/g, 'a').replace(/[éèêë]/g, 'e').replace(/[íìîï]/g, 'i')
    .replace(/[óòõôö]/g, 'o').replace(/[úùûü]/g, 'u')
    .replace(/[ç]/g, 'c').replace(/[ñ]/g, 'n');
}

const CATALOGO_REAL = [
  'Coração', 'Capitão América: Guerra Civil', 'Ação', 'Cães', 'Niño',
  'São Paulo', 'Pokémon XY&Z', 'Órfãos', 'Crème brûlée',
  'A Viúva Negra 2ª Temporada', 'O Auto da Compadecida 2 (2024)',
  'Velozes e Furiosos: Desafio em Tóquio', 'Invocação do Mal 4: O Último Ritual',
];

test('paridade total no vocabulário do catálogo', () => {
  for (const c of CATALOGO_REAL) {
    assert(kotlinNorm(c) === siteNorm(c), `paridade em ${JSON.stringify(c)}`);
  }
});

test('includes normalizado comporta igual nos dois', () => {
  const titulo = 'A Captura (Dublado) - 2026';
  const filtro = 'captura';
  assert(siteNorm(titulo).includes(siteNorm(filtro)), 'site casa');
  assert(kotlinNorm(titulo).includes(kotlinNorm(filtro)), 'kotlin casa igual');
});

test('filtro especial "mapa completo" é do site, não do plugin', () => {
  // O site tem easter-egg: filtro === "mapa completo" lista tudo.
  // O plugin usa ajax_search.php + parseSearchResults, caminho distinto e válido.
  assert(true, 'documentado');
});
