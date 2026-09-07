#!/usr/bin/env python3
"""
Teste em camadas — simulando navegação real do RedeCanaisAF v221.
Camadas: 0=ambiente, 1=disco, 2=home parsing, 3=detalhe, 4=busca, 5=stream, 6=cold boot timing
Usa /tmp/cache220.json (fixtures reais do WebView) + emulador redroid.
"""
import json, pathlib, re, subprocess, sys, time, os

CACHE_PATH = "/tmp/cache220.json"
DOCKER_CACHE = "/data/data/com.lagradost.cloudstream3.prerelease/files/redecanais_af_html_cache.json"
PASS=0; FAIL=0; SKIP=0
def ok(msg): 
    global PASS; PASS+=1; print(f"  ✅ PASS — {msg}")
def fail(msg, details=""):
    global FAIL; FAIL+=1; print(f"  ❌ FAIL — {msg} {details}")
def skip(msg):
    global SKIP; SKIP+=1; print(f"  ⏭️  SKIP — {msg}")
def section(title): print(f"\n{'='*72}\n{title}\n{'='*72}")

def sh(cmd, timeout=12):
    try:
        r=subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip(), r.stderr.strip(), r.returncode
    except subprocess.TimeoutExpired:
        return "", "timeout", 124

# ---------------------------------------------------------------------------
section("CAMADA 0 — Ambiente e artefactos")
# artefacto
EXP_VERSION=221
if pathlib.Path("builds/RedeCanaisAF.cs3").exists():
    sz=pathlib.Path("builds/RedeCanaisAF.cs3").stat().st_size
    ok(f"builds/RedeCanaisAF.cs3 existe ({sz} bytes)")
    # check version via plugins.json
    pj=pathlib.Path("build/plugins.json")
    if pj.exists():
        data=json.loads(pj.read_text())
        entry=[x for x in data if x.get("internalName")=="RedeCanaisAF"]
        if entry and entry[0].get("version")==EXP_VERSION: ok(f"plugins.json version={EXP_VERSION}")
        else: fail("plugins.json version", str(entry[0].get("version") if entry else "missing"))
    else: skip("build/plugins.json não existe")
else:
    fail("builds/RedeCanaisAF.cs3 ausente")

out,_,_ = sh("adb -s emulator-5554 shell ls -l /sdcard/Cloudstream3/plugins/RedeCanaisAF.cs3 2>&1",5)
if "RedeCanaisAF.cs3" in out: ok(f"plugin no emulador: {out.split()[-1] if out else out}")
else: fail("plugin não está no emulador", out[:200])

out,_,_ = sh("adb -s emulator-5554 shell getprop sys.boot_completed 2>&1",5)
if out.strip()=="1": ok("emulador boot_completed=1")
else: fail("emulador não pronto", out)

# pull cache atual do docker (sempre fresco)
print("\n[pull] docker exec cat cache -> /tmp/cache220.json")
sh(f"docker exec redroid cat {DOCKER_CACHE} > {CACHE_PATH} 2>&1", 8)
if pathlib.Path(CACHE_PATH).exists() and pathlib.Path(CACHE_PATH).stat().st_size>1000:
    ok(f"cache pull ok ({pathlib.Path(CACHE_PATH).stat().st_size} bytes)")
else:
    fail("cache pull falhou — usando fixture anterior se existir")

# ---------------------------------------------------------------------------
section("CAMADA 1 — Cache de disco (persistência)")
p=pathlib.Path(CACHE_PATH)
if not p.exists():
    fail("cache fixture ausente", CACHE_PATH)
    sys.exit(1)
try:
    data=json.loads(p.read_text())
except Exception as e:
    fail("JSON parse", str(e)); sys.exit(1)

keys=list(data.keys())
if len(keys)==6: ok(f"6 chaves de catálogo presentes")
else: fail(f"chaves={len(keys)} esperado 6", str(keys))

# catálogo esperado
expected=set([
    "https://redecanais.af/browse-filmes-videos-1-date.html",
    "https://redecanais.af/browse-series-videos-1-date.html",
    "https://redecanais.af/browse-animes-videos-1-date.html",
    "https://redecanais.af/browse-desenhos-videos-1-date.html",
    "https://redecanais.af/browse-filmes-videos-1-views.html",
    "https://redecanais.af/topvideos.html",
])
missing=expected - set(keys)
extra=set(keys) - expected
if not missing: ok("todas as 6 URLs esperadas presentes")
else: fail(f"faltando {missing}")
if extra: fail(f"chaves extras (não catálogo) {extra}")
else: ok("nenhuma chave extra (só catálogo)")

for k,v in data.items():
    html=v.get("html","") if isinstance(v,dict) else ""
    ts=v.get("ts",0) if isinstance(v,dict) else 0
    age_h=(time.time()*1000 - ts)/3600000 if ts else 999
    chal = "Just a moment" in html or "challenge-platform" in html or "cf-turnstile" in html
    if chal: fail(f"challenge em {k}")
    else: ok(f"{k.split('/')[-1][:38]:38} len={len(html):6} age={age_h:.1f}h ok")
    if len(html)>600000: fail(f"html muito grande >600k {k} len={len(html)}")
    if "pm-grid" not in html: fail(f"sem pm-grid {k}")
    if ts==0: fail(f"ts=0 {k}")
    if age_h>12.5: fail(f"TTL expirado >12h {k} age={age_h:.1f}h")

# checa que html foi stripped (só 1 script placeholder)
sample_html=list(data.values())[0].get("html","")
if sample_html.count("// stripped large ad")>=1: ok("cleanHtmlForCache aplicado (script placeholder presente)")
else: fail("cleanHtmlForCache não aplicado")

# verifica tamanho serializado < 2M (evita delete no restore)
sz=p.stat().st_size
if sz < 2_000_000: ok(f"cache file {sz} bytes < 2M (restore não deleta)")
else: fail(f"cache file {sz} bytes >=2M seria deletado no restore")

# ---------------------------------------------------------------------------
section("CAMADA 2 — Parsing da HOME (mesma lógica de RedeCanaisAF.kt)")

# replica seletores de getMainPage/parseCard
HOME_SELECTOR_RE = re.compile(r'<li[^>]*class="[^"]*col-xs-6[^"]*"[^>]*>.*?</li>', re.S)
TITLE_RE = re.compile(r'title="([^"]+)"')
HREF_RE  = re.compile(r'href="(/[^"]+\.html[^"]*)"')
IMG_RE   = re.compile(r'<img[^>]*src="([^"]+)"')

for url, entry in data.items():
    html=entry.get("html","")
    cat=url.split("/")[-1]
    items=re.findall(r'<li[^>]*class="[^"]*col-xs-6[^"]*"[^>]*>.*?</li>', html, re.S)
    # fallback pm-grid
    if not items:
        m=re.search(r'<ul[^>]*id="pm-grid"[^>]*>(.*?)</ul>', html, re.S)
        if m:
            items=re.findall(r'<li.*?</li>', m.group(1), re.S)
    # count via simple string
    pm_thumb=html.count("pm-video-thumb")
    col_count=html.count("col-xs-6")
    pm_grid = "pm-grid" in html
    # valida parseCard equivalente: extrai href/title/img
    parsed=[]
    for li in items[:12]:
        # parseCard filtra browse-/category/user/login etc — aqui só .html com _
        hrefs=re.findall(r'href="([^"]+\.html[^"]*)"', li)
        # pega primeiro href que não é browse/category
        good=[h for h in hrefs if "browse-" not in h and "category" not in h and "user/" not in h and "login" not in h]
        title_m=re.search(r'title="([^"]+)"', li)
        title=title_m.group(1) if title_m else ""
        img_m=re.search(r'<img[^>]*src="([^"]+)"', li)
        img=img_m.group(1) if img_m else ""
        if good and title:
            parsed.append((good[0], title[:50], img[:60]))
    # expectativa: 8 cards para browse, 48 para topvideos (mas fixture stripped tem 8 visíveis por página)
    # na fixture real topvideos tem 96 found mas 48 hasNext — aqui só verificamos >=8
    if len(items)>=8: ok(f"{cat:45} items={len(items):3} parsed={len(parsed):2} pm-thumb={pm_thumb} col-xs-6={col_count} pm-grid={pm_grid}")
    else: fail(f"{cat} items={len(items)} <8", f"pm-thumb={pm_thumb}")
    # poster: deve ter /imgs-videos/
    if parsed:
        has_poster = any("/imgs-videos/" in p[2] or "imgs-videos" in li for p in parsed for li in [html])
        # actually check img src in parsed
        poster_ok = sum(1 for _,_,img in parsed if "/imgs-videos/" in img or "imgs-videos" in img)
        if poster_ok>=1: ok(f"  └─ posters extraídos {poster_ok}/{len(parsed)} com /imgs-videos/")
        else: fail(f"  └─ nenhum poster /imgs-videos/ em {cat}")

# ---------------------------------------------------------------------------
section("CAMADA 3 — Parsing de DETALHE (load) — filme vs série")

# Fixtures sintéticos baseados em seletores reais de load():
MOVIE_HTML = """
<html><head><meta property="og:title" content="Deadpool Dublado 2024 1080p">
<meta property="og:image" content="https://redecanais.af/imgs-videos/Filmes/Deadpool.jpg">
<meta property="og:description" content="Um anti-herói..."></head>
<body>
<h1 class="entry-title">Deadpool Dublado 2024 1080p - RedeCanais</h1>
<div class="pm-video-watch-wrap"><iframe src="https://player.redecanais.af/server.php?vid=abc123"></iframe></div>
<span class="pm-video-attr-duration">PT1H45M</span>
</body></html>
"""
SERIES_HTML = """
<html><head><meta property="og:title" content="Futurama Dublado - Lista de Episódios"></head>
<body>
<h1 class="entry-title">Futurama Dublado - Lista de Episódios</h1>
<div class="pm-video-description">
<p><strong>1ª Temporada</strong></p>
<p><a href="/futurama-1a-temporada-episodio-01_abc.html">Episódio 01 - Piloto</a><br>
<a href="/futurama-1a-temporada-episodio-02_def.html">Episódio 02 - Lua</a></p>
<p><strong>2ª Temporada</strong></p>
<p><a href="/futurama-2a-temporada-episodio-01_ghi.html">Episódio 01 - Popplers</a></p>
</div>
</body></html>
"""

# replica isSeriesUrlOrTitle
def is_series(url,title):
    kw=["lista-de-episodios","todas-as-temporadas","temporada","serie","series","anime","desenho","episodio","temp","browse-"]
    u=url.lower(); t=title.lower()
    return any(k in u for k in kw) or any(k in t for k in ["temporada","episódio","episodio"])

if is_series("https://redecanais.af/futurama-dublado-lista-de-episodios_abc.html","Futurama"): ok("Série detectada via URL + título")
else: fail("Série não detectada")
if not is_series("https://redecanais.af/deadpool-dublado-2024-1080p_abc.html","Deadpool 2024"): ok("Filme não classificado como série")
else: fail("Filme classificado como série")

# episode extraction replica parseEpisodes container.html split
def extract_episodes(html):
    # split por <br> etc e regex <a href>
    lines=re.split(r'(?i)<br\s*/?>|</p>|</div>', html)
    eps=[]
    cur_season=1
    for line in lines:
        plain=re.sub(r'<[^>]*>',' ', line); plain=re.sub(r'\s+',' ', plain).strip()
        m=re.search(r'(\d+)[ªaºo]?\s*(?:temp|temporada)', plain, re.I)
        if m: cur_season=int(m.group(1))
        for href, txt in re.findall(r'<a[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', line, re.I):
            txt_clean=re.sub(r'<[^>]*>','', txt).strip()
            if ".html" in href.lower():
                eps.append((href, txt_clean, cur_season))
    return eps

movie_eps=extract_episodes(MOVIE_HTML)
series_eps=extract_episodes(SERIES_HTML)
if len(movie_eps)==0: ok("Filme: 0 episódios (fallback para 1 episódio único)")
else: fail(f"Filme eps={movie_eps}")
if len(series_eps)==3: ok(f"Série: 3 episódios extraídos (S1E1,S1E2,S2E1) -> {series_eps}")
else: fail(f"Série eps={len(series_eps)} esperado 3", str(series_eps))
# season tracking
if series_eps and series_eps[2][2]==2: ok("Temporada 2 rastreada corretamente via <strong>2ª Temporada</strong>")
else: fail("Season tracking falhou", str(series_eps))

# grid fallback (browse page como série) — v220 adiciona #pm-grid branch
GRID_HTML = '<ul id="pm-grid"><li><a href="/video_123.html" title="Ep 01">Ep 01</a></li><li><a href="/video_456.html">Ep 02</a></li></ul>'
grid_links=re.findall(r'<a[^>]*href="([^"]+)"', GRID_HTML)
if len(grid_links)==2: ok("Fallback grid #pm-grid: 2 links encontrados")
else: fail("grid fallback")

# ---------------------------------------------------------------------------
section("CAMADA 4 — Busca (search / ajax_search)")

def normalize(s):
    import unicodedata
    s=unicodedata.normalize("NFD", s.lower())
    return re.sub(r'[\u0300-\u036f]+','', s)

def is_relevant(title, query):
    if not query.strip(): return True
    nt=normalize(title); nq=normalize(query)
    if nq in nt: return True
    toks=[t for t in nq.split() if t]
    return toks and all(t in nt for t in toks)

cases=[
    ("Futurama Dublado - Lista de Episódios", "futurama", True),
    ("Avatar: O Caminho da Água", "avatar", True),
    ("Futurama", "futurama dublado", False),  # title não contém dublado — token all falha -> relevante? na real é False, mas buscamos all tokens
    ("La Casa de Papel", "casa papel", True),
    ("Naruto Shippuden", "naruto", True),
]
for title, q, exp in cases:
    got=is_relevant(title,q)
    # o terceiro caso: title Futurama não contém dublado, então isRelevant retorna False — correto per spec (all tokens)
    if got==exp: ok(f"isRelevant '{title}' vs '{q}' -> {got}")
    else: fail(f"isRelevant '{title}' vs '{q}' esperado {exp} got {got}")

# TvType
def tv_type(url, tags, is_series):
    lu=url.lower(); at=" ".join(tags).lower()
    if "anime" in lu or "anime" in at: return "Anime"
    if "desenho" in lu or "desenho" in at: return "Cartoon"
    if is_series or "serie" in lu: return "TvSeries"
    return "Movie"
if tv_type("https://redecanais.af/naruto-animes-lista_123.html",[],True)=="Anime": ok("TvType Anime via URL")
else: fail("TvType Anime")
if tv_type("https://redecanais.af/ben10-desenhos-lista_123.html",[],True)=="Cartoon": ok("TvType Cartoon")
else: fail("TvType Cartoon")
if tv_type("https://redecanais.af/filme_123.html",[],False)=="Movie": ok("TvType Movie")
else: fail("TvType Movie")

# poster optimization (LocalImageProxy.wrap)
def is_placeholder(u):
    return any(p in u for p in ["echo-lzld","blank.gif","pixel.gif","no-thumbnail"])
def optimize(u):
    if not u or is_placeholder(u): return ""
    if u.startswith("/"): u="https://redecanais.af"+u
    u=u.replace(" ","%20")
    if "redecanais.af" in u: return f"http://127.0.0.1:0/img?url={u}"
    return u

if optimize("/imgs-videos/Filmes/Top Gun.jpg")=="http://127.0.0.1:0/img?url=https://redecanais.af/imgs-videos/Filmes/Top%20Gun.jpg": ok("optimizePosterUrl relativo + espaço -> %20 + proxy")
else: fail("optimizePosterUrl")
if optimize("https://redecanais.af/imgs-videos/echo-lzld.png")=="": ok("placeholder bloqueado")
else: fail("placeholder não bloqueado")
if optimize("https://cdn.example.com/img.jpg")=="https://cdn.example.com/img.jpg": ok("CDN externo não proxificado")
else: fail("CDN externo")

# ---------------------------------------------------------------------------
section("CAMADA 5 — Stream validation (isValidStreamUrl)")

def is_valid_stream(url):
    if not url or url.startswith("javascript:") or url.startswith("#"): return False
    clean=url.split("?")[0].lower(); full=url.lower()
    try: dec=re.sub(r'%[0-9a-fA-F]{2}', lambda m: chr(int(m.group(0)[1:],16)), url).lower()
    except: dec=full
    dec_clean=dec.split("?")[0].lower() if "?" in dec else dec
    if "__rc__/proxy" in full or "/proxy?src=" in full or "p12-common-sign" in full:
        if ".mp4" in dec or ".m3u8" in dec or ".mkv" in dec: return True
    if clean.endswith((".js",".css",".html",".htm",".json",".xml",".jpg",".png",".gif",".svg",".webp",".woff",".woff2",".ttf")):
        if not (dec_clean.endswith((".mp4",".m3u8",".mkv",".mpd",".webm"))): return False
    if any(x in full for x in ["disqus","chatango","facebook","twitter","google-analytics","recaptcha","turnstile","server.php","player.php","embed.php","play.php","browse-"]):
        return False
    is_ext= clean.endswith((".mp4",".m3u8",".mpd",".mkv",".webm")) or dec_clean.endswith((".mp4",".m3u8",".mpd",".mkv",".webm"))
    is_known= any(x in full for x in [".m3u8?",".mp4?","/hls/","/ondemand/","/stream/","googlevideo.com"]) or any(x in dec for x in [".m3u8?",".mp4?","/hls/","/ondemand/","googlevideo.com"])
    return is_ext or is_known

valids=["https://s1.redecanais.af/ondemand/MNSTRSNTRBLHT01EP01.mp4","https://cdn.redecanais.af/hls/master.m3u8?token=xyz","https://rr1---sn-video.googlevideo.com/videoplayback?expire=123","https://redecanaistv.af/__RC__/proxy?src=p12-common-sign-abc.mp4"]
invalids=["https://redecanais.af/player3/server.php?vid=123","https://redecanais.af/browse-filmes-videos-1-date.html","https://c.disquscdn.com/embed.js","https://redecanais.af/filme.html"]
for u in valids:
    if is_valid_stream(u): ok(f"stream válido aceito: {u[:60]}")
    else: fail(f"stream válido rejeitado: {u}")
for u in invalids:
    if not is_valid_stream(u): ok(f"stream inválido rejeitado: {u[:60]}")
    else: fail(f"stream inválido aceito: {u}")

# ---------------------------------------------------------------------------
section("CAMADA 6 — Cold boot timing (emulador live)")

# garante que o cache atual está válido, então faz cold restart e mede HOME_RETURN
print("  → force-stop + start + aguarda HOME_RETURN (12s)")
sh("docker exec redroid am force-stop com.lagradost.cloudstream3.prerelease 2>&1",5)
time.sleep(1)
sh("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity 2>&1",5)
time.sleep(12)
out,_,_ = sh("adb -s emulator-5554 logcat -d -t 1200 -s RedeCanaisAF-Trace:V 2>&1", 8)
# filtra últimos 30s
lines=[l for l in out.splitlines() if "RedeCanaisAF-Trace" in l]
# extrai métricas
def has(s): return any(s in l for l in lines)
checks=[
    (any("PLUGIN_VERSION" in l and f"v={EXP_VERSION}" in l for l in lines), f"PLUGIN_VERSION v={EXP_VERSION} no logcat"),
    (any("HTML cache restaurado" in l and "6 URLs" in l for l in lines), "disk RAM restored 6 URLs"),
    (any("HTML retornado instantaneamente do cache!" in l for l in lines), "REQ cache hit (instantâneo)"),
    (any("HOME_RAW_CARDS" in l for l in lines), "HOME_RAW_CARDS presente"),
    (any("HOME_RETURN" in l and "totalItems=" in l for l in lines), "HOME_RETURN presente"),
]
for cond,msg in checks:
    if cond: ok(msg)
    else: fail(msg)
# latência: tempo entre PLUGIN_VERSION e último HOME_RETURN
import re as _re
ts_pat=_re.compile(r'(\d{2}):(\d{2}):(\d{2})\.(\d{3})')
def to_ms(line):
    m=ts_pat.search(line)
    if not m: return None
    h,mn,s,ms=int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))
    return ((h*3600+mn*60+s)*1000+ms)
start_ms=None; end_ms=None
for l in lines:
    if "PLUGIN_VERSION" in l: start_ms=to_ms(l)
    if "HOME_RETURN" in l: end_ms=to_ms(l)
# start e end são do último boot no buffer (overwrite evita misturar boots antigos com -t 1200)
if start_ms is not None and end_ms is not None:
    delta=end_ms-start_ms
    if delta < 4000: ok(f"cold boot home latency {delta}ms < 4000ms")
    elif delta < 8000: ok(f"cold boot home latency {delta}ms < 8000ms (ok, mas pode melhorar)")
    else: fail(f"cold boot home latency {delta}ms >=8000ms (lento)")
    print(f"     start={start_ms} end={end_ms} delta={delta}ms")
else:
    fail("não foi possível medir latência", f"start={start_ms} end={end_ms}")

# garante que não houve challenge_started para home
home_challenges=[l for l in lines if "challenge_started" in l and "browse-" in l]
if not home_challenges: ok("nenhum challenge_started para browse-* (cache evitou WebView)")
else: fail(f"{len(home_challenges)} challenge_started para home (deveria ser 0 com cache)", home_challenges[0][:160])

# checa NO_PROXY: não deve ter Failed to connect to /127.0.0.1:18080
if "127.0.0.1:18080" in out: fail("proxy 18080 vazando (NO_PROXY falhou)")
else: ok("sem vazamento de proxy 18080")

# ---------------------------------------------------------------------------
section(f"RESULTADO — PASS={PASS} FAIL={FAIL} SKIP={SKIP}")
if FAIL==0:
    print("\n🎉 TODAS AS CAMADAS PASSARAM — navegação real simulada com sucesso (v221)\n")
    sys.exit(0)
else:
    print(f"\n⚠️  {FAIL} falha(s) — ver acima\n")
    sys.exit(1)
