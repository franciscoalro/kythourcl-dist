#!/usr/bin/env python3
"""
Valida a logica da deteccao de origem morta (isOriginDead) da v158
contra os estados reais de origem medidos.

Estados cobertos:
  A) origem NO AR  -> rota inexistente devolve 404  => deve considerar VIVA
  B) origem FORA   -> catch-all 500 em tudo        => deve considerar MORTA
  C) rota viva mas 401/403 (credencial)            => dispatch rodou, VIVA
  D) timeout / erro de rede                       => MORTA
  E) origem viva, /feed 500 mas rota inexistente 404 => VIVA (o ganho da v158)

Este script replica exatamente o `when` de isOriginDead() do Kotlin.
"""
import sys

def is_origin_dead(probe_code, probe_raises=False):
    """Replica do when(res.code) em isOriginDead()."""
    try:
        if probe_raises:
            raise TimeoutError("timeout")
        code = probe_code
        if code in (404, 410):
            return False          # rota respondeu 404 de verdade: origem VIVA
        if 500 <= code <= 599:
            return True           # 500 em rota inexistente: origem MORTA
        if code in (401, 403):
            return False          # autenticacao falhou, mas o dispatch rodou
        return False
    except Exception:
        return True               # timeout/erro de rede = indisponivel

# (nome, code_ou_raises, esperado_morto, justificativa)
CASES = [
    ("A) origem no ar, 404 na rota inexistente", 404, False,
     "404 real = dispatch rodou = origem viva"),
    ("A2) 410 na rota inexistente",              410, False,
     "410 = Gone, mesma semantica de rota ausente"),
    ("B) origem fora, catch-all 500",            500, True,
     "500 em rota INEXISTENTE = origem estruturalmente morta"),
    ("B2) 502 catch-all",                        502, True,
     "qualquer 5xx em rota inexistente = morta"),
    ("B3) 503 catch-all",                        503, True,
     "qualquer 5xx em rota inexistente = morta"),
    ("C) sem token, 403 (dispatch rodou)",       403, False,
     "403 = autenticacao falhou mas a app respondeu; origem viva"),
    ("C2) 401",                                  401, False,
     "401 = credencial, origem viva"),
    ("D) timeout de rede",                       None, True,
     "excecao = indisponivel"),
    ("E) /feed 500 MAS origem de pe (404)",      404, False,
     "o ganho da v158: nao corta retry quando so o /feed falhou"),
    ("F) 200 em rota inexistente (edge mock)",   200, False,
     "200 e estranho, mas nao e 5xx: nao declara morte"),
]

print("=" * 78)
print("VERIFICACAO isOriginDead() -- v158")
print("=" * 78)
fails = 0
for name, code, expected, why in CASES:
    got = is_origin_dead(code, probe_raises=(code is None))
    ok = "PASS" if got == expected else "FAIL"
    if got != expected:
        fails += 1
    print(f"[{ok}] {name}")
    print(f"       esperado={expected}  obtido={got}  -- {why}")
print("=" * 78)
if fails:
    print(f"FALHOU: {fails} caso(s)")
    sys.exit(1)
print(f"OK: {len(CASES)} casos, logica da sonda confere com o comportamento medido")
sys.exit(0)
