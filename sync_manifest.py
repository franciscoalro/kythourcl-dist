#!/usr/bin/env python3
"""Atualiza so a entrada Tomato nos manifests, preservando formatacao.

Reescrever o JSON inteiro com json.dumps reformata o arquivo (indentacao 2 vs 4,
arrays inline) e produz um diff de 310 linhas onde nada mudou de fato. Aqui a
troca e por linha, dentro do bloco Tomato, com guarda que aborta se qualquer
outra entrada divergir do que esta em disco.
"""
import hashlib
import json
import os
import re
import sys

NEEDLE = '"internalName": "Tomato"'
BLOCK = '"name": "Tomato"'


def find_block(lines):
    """Retorna (inicio, fim) do objeto Tomato, ou None."""
    end = next((i for i, l in enumerate(lines) if l.strip() == BLOCK), None)
    if end is None:
        return None
    start = max(i for i in range(end) if lines[i].strip() in ('{', '},') or lines[i].strip().startswith('{'))
    for i in range(end, -1, -1):
        if lines[i].strip() == '{':
            start = i
            break
    return start, end


def patch(path, version, size, digest):
    with open(path, encoding='utf-8') as fh:
        text = fh.read()
    lines = text.split('\n')
    span = find_block(lines)
    if span is None:
        print('FALHA: bloco Tomato nao encontrado em %s' % path)
        return False
    start, end = span
    block = lines[start:end + 1]
    before = list(block)

    subs = [
        # Chave preservada: o padrao casa a chave+aspas e o replacement devolve
        # o grupo de volta. Passear so o valor faz o re.sub descartar as aspas.
        (r'("fileHash":\s*")[^"]*(")', lambda d: r'\g<1>' + d + r'\g<2>'),
        (r'("fileSize":\s*)\d+', lambda _d: r'\g<1>' + str(size)),
        (r'("version":\s*)\d+', lambda _d: r'\g<1>' + str(version)),
    ]
    for pattern, make_repl in subs:
        repl = make_repl(digest)
        hit = False
        for i, line in enumerate(block):
            new, n = re.subn(pattern, repl, line, count=1)
            if n:
                block[i] = new
                hit = True
                break
        if not hit:
            print('FALHA: %s sem chave %s no bloco Tomato' % (path, pattern))
            return False

    if block == before:
        print('%s ja esta em v%s (nada a fazer)' % (path, version))
        return True

    # Guarda: o resto do arquivo precisa continuar identico byte a byte.
    rebuilt = lines[:start] + block + lines[end + 1:]
    if len(rebuilt) != len(lines):
        print('FALHA: contagem de linhas mudou em %s' % path)
        return False
    for i, (a, b) in enumerate(zip(lines, rebuilt)):
        if a != b and not (start <= i <= end):
            print('FALHA: linha %d fora do bloco Tomato mudou em %s' % (i + 1, path))
            return False

    with open(path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(rebuilt))
    print('%s -> v%s (size=%s, %d linhas alteradas)' % (path, version, size, sum(a != b for a, b in zip(lines, rebuilt))))
    return True


def main():
    cs3 = sys.argv[1]
    version = int(sys.argv[2])
    with open(cs3, 'rb') as fh:
        raw = fh.read()
    size = len(raw)
    digest = 'sha256-' + hashlib.sha256(raw).hexdigest()
    print('artefato: %s bytes, %s' % (size, digest))
    ok = True
    for path in ('plugins.json', 'build/plugins.json', 'builds/plugins.json'):
        if not os.path.exists(path):
            print('ausente: %s' % path)
            continue
        ok = patch(path, version, size, digest) and ok
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
