<div align="center">

# kythourcl

<img src="https://img.shields.io/badge/CloudStream-blue?style=for-the-badge&logo=android" alt="CloudStream">

### NetCine only

</div>

---

## Adicione a Extensão

**Link:** `https://raw.githubusercontent.com/franciscoalro/kythourcl/refs/heads/main/builds/repo.json`

No CloudStream: `Configurações > Extensões > Adicionar Repositório` e cole o link acima.

---

### NetCine
- **Site:** `https://nnn1.lat`
- **Idioma:** `pt-br`
- **Recursos:** Filmes, Séries com captcha automático via `ocr.space` dentro do app

---

### DMCA
Projeto educacional. Nenhum conteúdo hospedado aqui.
## Desenvolvimento local

### Python (testes `loadLinks` + captcha)

O projeto usa `.venv` na raiz (detectado por VSCode/PyCharm). Em WSL com `D:\` (`9p drvfs`) o `pip` falha ao copiar `RECORD` direto em `/mnt/d`; o setup cria o venv em `/tmp` e expõe via symlink `.venv -> /tmp/cloudstream_venv2` (mesmo caminho visto por editores).

```bash
# 1. criar .venv dentro do projeto
python3 -m venv /tmp/cloudstream_venv2
ln -sf /tmp/cloudstream_venv2 .venv
source .venv/bin/activate
pip install requests playwright
# ou com uv
UV_CACHE_DIR=/tmp/uv_cache uv venv --seed .venv  # quando 9p permitir, senão use /tmp + symlink acima
UV_CACHE_DIR=/tmp/uv_cache uv pip install requests playwright

# 2. testar captcha automático (sem digitar)
python loadlinks_series_tester.py  # log em /tmp/loadlinks_series.log, deve ver PNG magic OK 89 50 4E 47 + found=True
python download_matrix.py --headless --ffmpeg  # 7 tentativas OCR 3 keys x 2 engines + tesseract
python load_series.py
```

### Kotlin (gerar `.cs3`)

```bash
export JAVA_HOME=$PWD/.jdk/jdk-17.0.13+11
export GRADLE_USER_HOME=/tmp/gradle_home  # evita chmod 700 em 9p drvfs
./gradlew :NetCine:assembleDebug --stacktrace
./gradlew :NetCine:make --stacktrace
ls -lh NetCine/build/NetCine.cs3 builds/NetCine.cs3
```

Após testes locais verdes, `push main` dispara `.github/workflows/build.yml` que faz `clean make` e commita `builds/NetCine.cs3`.

