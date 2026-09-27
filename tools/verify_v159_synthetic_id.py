#!/usr/bin/env python3
"""
v159 -- verifica a logica do guard de ID sintetico em loadLinks.

Reproduz exatamente o codigo Kotlin:

    val epId = data.trim()
    if (epId.isEmpty() || epId.any { !it.isDigit() }) return false

e contrasta com o comportamento antigo (Regex pegando o primeiro bloco de digitos)
para provar que o bug existia e que o guard o corrige.

Casos:
  - ID de episodio real (puro)      -> deve PASSAR (chega ao /stream)
  - Placeholder offline "7115_0"    -> deve REJEITAR, e NAO virar 7115
  - Placeholder offline "1089_0"    -> deve REJEITAR, e NAO virar 1089
  - data vazio / espaco             -> deve REJEITAR
  - Nao numerico                    -> deve REJEITAR
"""
import re

# ---- comportamento ANTIGO (v157/v158) ----
def ep_id_OLD(data: str):
    m = re.search(r"(\d+)", data)
    return m.group(0) if m else data

# ---- comportamento NOVO (v159) ----
def guard_NEW(data: str):
    """Retorna o epId se utilizavel, ou None se o loadLinks deve retornar false."""
    ep_id = data.strip()
    if not ep_id:
        return None
    if any(not ch.isdigit() for ch in ep_id):
        return None
    return ep_id


CASES = [
    # (data, deve_passar, descricao)
    ("36937",  True,  "episodio real: ID puro numerico"),
    ("36928",  True,  "episodio real: ID puro numerico"),
    ("7115_0", False, "placeholder offline do anime 7115"),
    ("1089_0", False, "placeholder offline do anime 1089"),
    ("1279_0", False, "placeholder offline do anime 1279"),
    ("1179_0", False, "placeholder offline do anime 1179"),
    ("6881_0", False, "placeholder offline do anime 6881"),
    ("",       False, "data vazia"),
    ("   ",    False, "data so com espacos"),
    ("abc",    False, "data nao numerica"),
    ("36937a", False, "numero com letra grudada"),
]

def main():
    print("=" * 74)
    print("v159 -- guard de ID sintetico (loadLinks)")
    print("=" * 74)
    print(f"{'data':<12} {'ANTIGO (epId)':<16} {'NOVO':<10} {'esperado':<10} ok")
    print("-" * 74)

    failures = 0
    for data, should_pass, desc in CASES:
        old = ep_id_OLD(data)
        new = guard_NEW(data)
        new_pass = new is not None
        ok = (new_pass == should_pass)
        if not ok:
            failures += 1
        print(f"{data!r:<12} {old!r:<16} "
              f"{'passa' if new_pass else 'rejeita':<10} "
              f"{'passa' if should_pass else 'rejeita':<10} "
              f"{'OK' if ok else 'FALHOU'}   {desc}")

    print("-" * 74)

    # o ponto critico: nenhum placeholder pode virar um episode_id usavel
    print("\nDeteccao do bug original:")
    bug_found = 0
    for data, should_pass, desc in CASES:
        if "_" in data:
            old = ep_id_OLD(data)
            new = guard_NEW(data)
            if new is not None:
                bug_found += 1
            print(f"  data={data!r:<12} ANTIGO usava episode_id={old!r:<8} "
                  f"-> NOVO: {'rejeita (correto)' if new is None else 'AINDA USA ' + str(new)}")

    print("\n" + "=" * 74)
    if failures == 0 and bug_found == 0:
        print(f"RESULTADO: {len(CASES)}/{len(CASES)} casos OK, "
              f"nenhum placeholder sobrevive ao guard. BUG CORRIGIDO.")
        return 0
    print(f"RESULTADO: {failures} falha(s), {bug_found} placeholder(s) sobrevivendo. BUG AINDA PRESENTE.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
