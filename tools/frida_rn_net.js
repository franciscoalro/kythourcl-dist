// Hook do cliente HTTP do React Native: toda URL que o app pede.
const emit = (o) => send(JSON.stringify(o));
const seen = new Set();

function note(url, origin) {
  var s = String(url);
  if (s.length < 8 || s.length > 400) return;
  if (s.indexOf('tomato') === -1 && s.indexOf('/v2/') === -1 &&
      s.indexOf('anime') === -1 && s.indexOf('search') === -1) return;
  if (seen.has(s)) return;
  seen.add(s);
  emit({ t: 'url', origin: origin, url: s });
}

Java.perform(function () {

  // 1. okhttp3 Request.Builder: a URL e montada aqui
  try {
    var RB = Java.use('okhttp3.Request$Builder');
    ['url', 'a', 'b'].forEach(function (m) {
      try {
        var f = RB[m].overload('java.lang.String');
        f.implementation = function (u) { note(u, 'reqbuilder.' + m); return f.call(this, u); };
      } catch (e) {}
    });
    emit({ t: 'hook', what: 'okhttp3.Request$Builder' });
  } catch (e) { emit({ t: 'err', what: 'RB', e: String(e) }); }

  // 2. modulo de rede do RN: ve method + url
  try {
    var NM = Java.use('com.facebook.react.modules.network.NetworkingModule');
    var send = NM.sendRequest.overload(
      'com.facebook.react.bridge.ReactApplicationContext', 'java.lang.String',
      'java.lang.String', 'com.facebook.react.bridge.ReadableArray', 'com.facebook.react.bridge.Callback',
      'com.facebook.react.bridge.RequestBodyHandler');
    send.implementation = function (ctx, m, u, h, cb, rh) {
      note(u, 'rn.net ' + String(m));
      return send.call(this, ctx, m, u, h, cb, rh);
    };
    emit({ t: 'hook', what: 'NetworkingModule.sendRequest' });
  } catch (e) { emit({ t: 'err', what: 'NM', e: String(e) }); }

  // 3. construtor de URL generico
  try {
    var RU = Java.use('okhttp3.Request$Builder');
    var b = RU.build.overload();
    b.implementation = function () {
      try { note(this.url().toString(), 'build'); } catch (e) {}
      return b.call(this);
    };
    emit({ t: 'hook', what: 'build' });
  } catch (e) { emit({ t: 'err', what: 'build', e: String(e) }); }

  emit({ t: 'ready' });
});
