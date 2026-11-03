/* JJ - painel "Controle do celular" (permissoes, servico em segundo plano) e sincronizacao do gateway com o servico nativo. */
(function () {
  var P = window.JJP;
  if (!P) return;
  var cfgOf = function () { try { return typeof S !== 'undefined' && S ? S : null } catch (e) { return null } };
  function sync() {
    var s = cfgOf(); if (!s) return;
    var gw = s.gateway || s.gw || '', tk = s.token || s.jjToken || '';
    if (gw) { try { P.setConfig(String(gw), String(tk)) } catch (e) {} }
  }
  setInterval(sync, 4000); sync();
  // garante a captura mesmo que o app guarde a URL/token em outro campo: observa as chamadas ao servidor
  var of = window.fetch;
  window.fetch = function (u, o) {
    try {
      var m = String(u).match(/^(https?:\/\/[^\/]+)\/(health|chat|brain|logs|phone)/);
      if (m) {
        var h = o && o.headers, t = '';
        if (h) t = (typeof h.get === 'function') ? (h.get('X-JJ-Token') || '') : (h['X-JJ-Token'] || h['x-jj-token'] || '');
        P.setConfig(m[1], t);
      }
    } catch (e) {}
    return of.apply(this, arguments);
  };

  var css = document.createElement('style');
  css.textContent =
    '#pbtn{position:fixed;left:1.2vw;bottom:3.4vh;z-index:6;width:5.2vh;height:5.2vh;border-radius:50%;border:1px solid var(--c,#00e5ff);background:rgba(2,9,18,.85);color:var(--c,#00e5ff);font-size:2.6vh;padding:0;cursor:pointer}' +
    '#pov{position:fixed;inset:0;z-index:10;background:rgba(2,9,18,.92);display:none;align-items:center;justify-content:center}' +
    '#pov.on{display:flex}#pbox{width:min(720px,94vw);max-height:90vh;overflow:auto;border:1px solid var(--c,#00e5ff);background:rgba(4,18,34,.98);padding:2vh 2vw}' +
    '#pbox h3{color:var(--c,#00e5ff);font:700 2.2vh Orbitron,sans-serif;margin-bottom:1.2vh}' +
    '.pr{display:flex;justify-content:space-between;align-items:center;gap:1vw;padding:1vh 0;border-bottom:1px solid rgba(0,229,255,.18);font-size:2.1vh}' +
    '.pr small{display:block;color:#6f97b0;font-size:1.7vh}.pr button{white-space:nowrap}.ok{color:#3dffb0}.no{color:#ff4d6d}';
  document.head.appendChild(css);

  var btn = document.createElement('button'); btn.id = 'pbtn'; btn.textContent = '\uD83D\uDCF1'; btn.title = 'Controle do celular';
  var ov = document.createElement('div'); ov.id = 'pov';
  ov.innerHTML = '<div id="pbox"><h3>CONTROLE DO CELULAR</h3><div id="prows"></div><div class="pr"><span>Testar agora<small>executa no aparelho</small></span><span><button data-t="status">Status</button> <button data-t="camera">Foto</button> <button data-t="read_screen">Ler tela</button></span></div><pre id="pout" style="white-space:pre-wrap;color:#9fffe0;font-size:1.8vh;max-height:18vh;overflow:auto"></pre><div style="text-align:right;margin-top:1vh"><button id="pclose">Fechar</button></div></div>';
  document.body.appendChild(btn); document.body.appendChild(ov);

  var ROWS = [
    ['perms', 'Permissoes (mic, camera, local, contatos, ligar, SMS)', 'Toque em Conceder e aceite todas', ['camera', 'mic', 'loc', 'contacts', 'call', 'sms'], function () { P.requestPerms() }, 'Conceder'],
    ['service', 'Servico em segundo plano', 'Mantem o JJ ouvindo comandos com a tela apagada', ['service'], function () { var s = JSON.parse(P.status()); if (s.service) P.stop(); else if (P.start() === 'perm') say('Conceda camera e microfone primeiro.') }, 'Ligar/Parar'],
    ['acc', 'Acessibilidade', 'Tocar, rolar, digitar, ler e capturar a tela', ['acc'], function () { P.openSettings('accessibility') }, 'Abrir'],
    ['notif', 'Acesso as notificacoes', 'Ler notificacoes do celular', ['notif'], function () { P.openSettings('notification') }, 'Abrir'],
    ['overlay', 'Sobrepor outros apps', 'Necessario para abrir apps com a tela em segundo plano', ['overlay'], function () { P.openSettings('overlay') }, 'Abrir'],
    ['battery', 'Sem restricao de bateria', 'Evita que o Android pause o JJ', ['battery'], function () { P.openSettings('battery') }, 'Abrir']
  ];
  function say(t) { document.getElementById('pout').textContent = t }
  function render() {
    var s; try { s = JSON.parse(P.status()) } catch (e) { return }
    var h = ROWS.map(function (r, i) {
      var ok = r[3].every(function (k) { return s[k] });
      return '<div class="pr"><span>' + r[1] + ' <b class="' + (ok ? 'ok' : 'no') + '">' + (ok ? 'ATIVO' : 'FALTA') + '</b><small>' + r[2] + '</small></span><button data-r="' + i + '">' + r[5] + '</button></div>';
    }).join('');
    document.getElementById('prows').innerHTML = h;
  }
  ov.addEventListener('click', function (e) {
    var t = e.target; if (t.id === 'pclose' || t === ov) { ov.classList.remove('on'); return }
    if (t.dataset && t.dataset.r) { ROWS[+t.dataset.r][4](); setTimeout(render, 600) }
    if (t.dataset && t.dataset.t) { say('Executando...'); setTimeout(function () { say(P.test(t.dataset.t, '')) }, 50) }
  });
  btn.onclick = function () { render(); ov.classList.add('on') };
  setInterval(function () { if (ov.classList.contains('on')) render() }, 2500);
  document.addEventListener('visibilitychange', function () { if (!document.hidden && ov.classList.contains('on')) render() });
  // liga o servico sozinho se as permissoes ja foram dadas
  setTimeout(function () { try { var s = JSON.parse(P.status()); if (P.auto() && !s.service && s.camera && s.mic) P.start() } catch (e) {} }, 2500);
})();
