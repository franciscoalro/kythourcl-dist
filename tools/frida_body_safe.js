// Captura o corpo JSON no momento em que ele e escrito no socket.
// writeTo e seguro para hookar; build() nao e (reentrante, derruba o processo).
const emit = (o) => send(JSON.stringify(o));
const current = { url: null, verb: null };

Java.perform(function () {
  // Request.url() e um getter simples: seguro para ler.
  // em Frida 17 nao existe 'orig': chama-se o original pelo overload sobrescrito.
  try {
    var R = Java.use('okhttp3.Request');
    var u = R.url.overload();
    u.implementation = function () {
      var r = u.call(this);
      try { current.url = String(r.toString()); } catch (e) {}
      return r;
    };
    var m = R.method.overload();
    m.implementation = function () {
      var r = m.call(this);
      try { current.verb = String(r); } catch (e) {}
      return r;
    };
    emit({ t: 'hook', what: 'Request.url/method' });
  } catch (e) { emit({ t: 'err', what: 'R', e: String(e) }); }

  // RequestBody.writeTo: le o corpo e DEIXA o original escrever
  try {
    var RB = Java.use('okhttp3.RequestBody');
    var w = RB.writeTo.overload('okio.BufferedSink');
    w.implementation = function (sink) {
      try {
        var u = current.url || '';
        if (u.indexOf('/v2/content/') !== -1) {
          var buf = Java.use('okio.Buffer');
          var b2 = buf.$new();
          this.writeTo(b2);          // escreve numa memoria, sem tocar no sink real
          var txt = String(b2.readUtf8());
          emit({ t: 'body', verb: current.verb, url: u,
                 bytes: txt.length, body: txt.slice(0, 700) });
        }
      } catch (e) { emit({ t: 'err', what: 'wb', e: String(e) }); }
      return w.call(this, sink);      // escreve de verdade
    };
    emit({ t: 'hook', what: 'RequestBody.writeTo' });
  } catch (e) { emit({ t: 'err', what: 'RB', e: String(e) }); }

  emit({ t: 'ready' });
});
