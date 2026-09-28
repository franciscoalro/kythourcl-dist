// Mapa as classes reais do app RN: qual modulo faz a rede?
const emit = (o) => send(JSON.stringify(o));

Java.perform(function () {
  const all = Java.enumerateLoadedClassesSync().map(String);

  const groups = {
    tomato: all.filter(function (n) { return n.indexOf('tomato') !== -1; }),
    react: all.filter(function (n) { return n.indexOf('react') !== -1; }),
    fb: all.filter(function (n) { return n.indexOf('facebook') !== -1; }),
  };
  for (const k in groups) {
    emit({ t: 'grp', k: k, n: groups[k].length, list: groups[k].slice(0, 40) });
  }

  // candidatos a cliente HTTP
  const http = all.filter(function (n) {
    return /okhttp|cronet|volley|HttpURLConnection|NetworkModule|NetworkingModule|URLRequest/i.test(n);
  });
  emit({ t: 'http', n: http.length, list: http.slice(0, 30) });

  // atividades do app
  const act = all.filter(function (n) { return /Activity|Fragment|Application/.test(n) && n.indexOf('android.') !== 0; });
  emit({ t: 'act', list: act.slice(0, 25) });

  emit({ t: 'ready' });
});
