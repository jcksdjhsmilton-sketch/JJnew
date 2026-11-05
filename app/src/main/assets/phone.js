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
  window.__jjcfg = window.__jjcfg || {};
  window.fetch = function (u, o) {
    try {
      var m = String(u).match(/^(https?:\/\/[^\/]+)\/(health|chat|brain|logs|phone|mcp)/);
      if (m) {
        var h = o && o.headers, t = '';
        if (h) t = (typeof h.get === 'function') ? (h.get('X-JJ-Token') || '') : (h['X-JJ-Token'] || h['x-jj-token'] || '');
        P.setConfig(m[1], t); window.__jjcfg = { gw: m[1], tok: t };
        if (m[2] === 'chat' && o && typeof o.body === 'string') {      // anexa os conectores MCP a cada conversa
          var raw = localStorage.getItem('jj_mcp');
          if (raw) {
            var arr = JSON.parse(raw);
            if (Array.isArray(arr) && arr.length) {
              var b = JSON.parse(o.body); b.mcp_servers = arr;
              o = Object.assign({}, o, { body: JSON.stringify(b) });
            }
          }
        }
      }
    } catch (e) {}
    return of.call(this, u, o);
  };

  var css = document.createElement('style');
  css.textContent =
    '#pbtn{position:fixed;left:1.2vw;bottom:3.4vh;z-index:6;width:5.2vh;height:5.2vh;border-radius:50%;border:1px solid var(--c,#00e5ff);background:rgba(2,9,18,.85);color:var(--c,#00e5ff);font-size:2.6vh;padding:0;cursor:pointer}' +
    '#pov{position:fixed;inset:0;z-index:10;background:rgba(2,9,18,.92);display:none;align-items:center;justify-content:center}' +
    '#pov.on{display:flex}#pbox{width:min(720px,94vw);max-height:90vh;overflow:auto;border:1px solid var(--c,#00e5ff);background:rgba(4,18,34,.98);padding:2vh 2vw}' +
    '#pbox h3{color:var(--c,#00e5ff);font:700 2.2vh Orbitron,sans-serif;margin-bottom:1.2vh}' +
    '#mov{position:fixed;inset:0;z-index:11;background:rgba(2,9,18,.94);display:none;align-items:center;justify-content:center}#mov.on{display:flex}#mbox{width:min(760px,94vw);max-height:92vh;overflow:auto;border:1px solid var(--c,#00e5ff);background:rgba(4,18,34,.98);padding:2vh 2vw}#mtxt{width:100%;height:34vh;background:#010a14;color:#d4f1ff;border:1px solid rgba(0,229,255,.3);font:1.8vh/1.4 ui-monospace,Consolas,monospace;padding:1vh;resize:none}' +
    '.pr{display:flex;justify-content:space-between;align-items:center;gap:1vw;padding:1vh 0;border-bottom:1px solid rgba(0,229,255,.18);font-size:2.1vh}' +
    '.pr small{display:block;color:#6f97b0;font-size:1.7vh}.pr button{white-space:nowrap}.ok{color:#3dffb0}.no{color:#ff4d6d}';
  document.head.appendChild(css);

  var btn = document.createElement('button'); btn.id = 'pbtn'; btn.textContent = '\uD83D\uDCF1'; btn.title = 'Controle do celular';
  var ov = document.createElement('div'); ov.id = 'pov';
  ov.innerHTML = '<div id="pbox"><h3>CONTROLE DO CELULAR</h3><div id="prows"></div><div class="pr"><span>Testar agora<small>executa no aparelho</small></span><span><button data-t="status">Status</button> <button data-t="camera">Foto</button> <button data-t="read_screen">Ler tela</button></span></div><pre id="pout" style="white-space:pre-wrap;color:#9fffe0;font-size:1.8vh;max-height:18vh;overflow:auto"></pre><div style="text-align:right;margin-top:1vh"><button id="pclose">Fechar</button></div><small style="color:#6f97b0;font-size:1.7vh">Dica: nos recentes, segure o card do JJ e toque no cadeado. Nao deslize o JJ para fechar: o Android desliga a acessibilidade.</small></div>';
  document.body.appendChild(btn); document.body.appendChild(ov);

  var ROWS = [
    ['shz', 'Shizuku (recomendado: configura tudo sozinho)', 'Ligue o Shizuku (depuracao sem fio), toque em Pedir e aceite. Depois toque em Configurar tudo', ['shz_granted'], function () {
      var r = P.shizukuRequest();
      say(r === 'not_installed' ? 'Instale o Shizuku (abri a pagina de download).' : r === 'not_running' ? 'Abri o Shizuku: inicie o servico la e volte aqui.' : r === 'asked' ? 'Aceite o pedido do Shizuku na tela.' : 'Shizuku ja esta liberado.')
    }, 'Pedir'],
    ['shzsetup', 'Configurar tudo com Shizuku', 'Concede permissoes, acessibilidade, notificacoes e bateria de uma vez', null, function () {
      P.shizukuSetup(); say('Configurando...');
      var n = 0, t = setInterval(function () { var r = P.shizukuReport(); say(r); if ((r && r !== 'executando...') || ++n > 40) clearInterval(t) }, 1000)
    }, 'Configurar'],
    ['perms', 'Permissoes (mic, camera, local, contatos, ligar, SMS)', 'Toque em Conceder e aceite todas', ['camera', 'mic', 'loc', 'contacts', 'call', 'sms'], function () { P.requestPerms() }, 'Conceder'],
    ['service', 'Servico em segundo plano', 'Mantem o JJ ouvindo comandos com a tela apagada', ['service'], function () { var s = JSON.parse(P.status()); if (s.service) P.stop(); else if (P.start() === 'perm') say('Conceda camera e microfone primeiro.') }, 'Ligar/Parar'],
    ['acc', 'Acessibilidade', 'Tocar, rolar, digitar, ler e capturar a tela', ['acc'], function () { P.openSettings('accessibility') }, 'Abrir'],
    ['notif', 'Acesso as notificacoes', 'Ler notificacoes do celular', ['notif'], function () { P.openSettings('notification') }, 'Abrir'],
    ['overlay', 'Sobrepor outros apps', 'Necessario para abrir apps com a tela em segundo plano', ['overlay'], function () { P.openSettings('overlay') }, 'Abrir'],
    ['battery', 'Sem restricao de bateria', 'Evita que o Android pause o JJ', ['battery'], function () { P.openSettings('battery') }, 'Abrir'],
    ['vd', 'Tela virtual (apps em segundo plano, invisiveis)', 'Abre apps numa tela escondida para tocar musica e operar sem sair do JJ. Experimental; precisa de Android 11+ e acessibilidade', ['vdisplay'], function () { P.vdisplayToggle() }, 'Ligar/Desligar'],
    ['mcp', 'Conectores MCP', 'Notion, GitHub, e-mail, agenda... (cole a lista em JSON)', null, function () { openMcp() }, 'Editar'],
    ['xpower', 'Xiaomi: economia de bateria do JJ', 'Marque "Sem restricoes". Sem isso o HyperOS mata o JJ e desliga a acessibilidade', null, function () { P.openSettings('powerkeeper') }, 'Abrir'],
    ['xauto', 'Xiaomi: autoinicio', 'Ative o JJ na lista de autoinicio', null, function () { P.openSettings('autostart') }, 'Abrir'],
    ['xperm', 'Xiaomi: permissoes extras', 'Ligue "Exibir pop-ups em segundo plano" e "Tela de bloqueio"', null, function () { P.openSettings('xiaomi_perms') }, 'Abrir']
  ];
  function say(t) { document.getElementById('pout').textContent = t }
  function render() {
    var s; try { s = JSON.parse(P.status()) } catch (e) { return }
    var h = ROWS.map(function (r, i) {
      var ok = r[3] ? r[3].every(function (k) { return s[k] }) : null;
      var tag = ok === null ? '' : ' <b class="' + (ok ? 'ok' : 'no') + '">' + (ok ? 'ATIVO' : (r[0] === 'acc' && s.acc_dead ? 'PAROU - religue' : 'FALTA')) + '</b>';
      return '<div class="pr"><span>' + r[1] + tag + '<small>' + r[2] + '</small></span><button data-r="' + i + '">' + r[5] + '</button></div>';
    }).join('');
    document.getElementById('prows').innerHTML = h;
  }
  ov.addEventListener('click', function (e) {
    var t = e.target; if (t.id === 'pclose' || t === ov) { ov.classList.remove('on'); return }
    if (t.dataset && t.dataset.r) { ROWS[+t.dataset.r][4](); setTimeout(render, 600) }
    if (t.dataset && t.dataset.t) { say('Executando...'); setTimeout(function () { say(P.test(t.dataset.t, '')) }, 50) }
  });
  var mov = document.createElement('div'); mov.id = 'mov';
  mov.innerHTML = '<div id="mbox"><h3 style="color:var(--c,#00e5ff);font:700 2.2vh Orbitron,sans-serif;margin-bottom:1vh">CONECTORES MCP</h3>' +
    '<textarea id="mtxt" spellcheck="false" placeholder=\'[{"name":"github","url":"https://api.githubcopilot.com/mcp/","headers":{"Authorization":"Bearer SEU_TOKEN"}}]\'></textarea>' +
    '<pre id="mout" style="white-space:pre-wrap;color:#9fffe0;font-size:1.8vh;max-height:16vh;overflow:auto;margin:1vh 0"></pre>' +
    '<div style="display:flex;gap:1vw;flex-wrap:wrap"><button id="msave">Salvar e testar</button><button id="mex">Exemplo</button><button id="mclose">Fechar</button></div>' +
    '<small style="color:#6f97b0;font-size:1.7vh">Servidores com URL (HTTP) usam "url" e "headers" (token). Servidores locais usam "command", "args" e "env". A lista fica so neste celular.</small></div>';
  document.body.appendChild(mov);
  function openMcp() { document.getElementById('mtxt').value = localStorage.getItem('jj_mcp') || ''; document.getElementById('mout').textContent = ''; mov.classList.add('on') }
  function mout(t) { document.getElementById('mout').textContent = t }
  function mcpTest(arr) {
    var c = window.__jjcfg || {}, st = cfgOf(), gw = c.gw || (st && st.gateway) || '', tok = c.tok || (st && st.token) || '';
    if (!gw) { mout('Salvo. Sem gateway ainda: abra os ajustes do JJ e configure o servidor.'); return }
    mout('Salvo. Conectando...');
    of.call(window, gw.replace(/\/+$/, '') + '/mcp/reload', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-JJ-Token': tok, 'ngrok-skip-browser-warning': '1' }, body: JSON.stringify({ servers: arr }) })
      .then(function (r) { return r.json() })
      .then(function (j) { mout((j.servers || []).map(function (x) { return (x.connected ? 'OK ' : 'ERRO ') + x.name + ': ' + (x.connected ? x.tools + ' ferramentas' : x.error) }).join('\n') || 'Nenhum servidor na lista.') })
      .catch(function (e) { mout('Salvo, mas o servidor nao respondeu: ' + e.message) });
  }
  document.getElementById('msave').onclick = function () {
    var v = document.getElementById('mtxt').value.trim();
    if (!v) { localStorage.removeItem('jj_mcp'); mout('Conectores removidos.'); return }
    try { var arr = JSON.parse(v); if (!Array.isArray(arr)) throw new Error('precisa ser uma lista [ ... ]'); localStorage.setItem('jj_mcp', JSON.stringify(arr)); mcpTest(arr) }
    catch (e) { mout('JSON invalido: ' + e.message) }
  };
  document.getElementById('mex').onclick = function () {
    document.getElementById('mtxt').value = JSON.stringify([
      { name: 'github', url: 'https://api.githubcopilot.com/mcp/', headers: { Authorization: 'Bearer SEU_TOKEN' } },
      { name: 'arquivos', command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem', '/kaggle/working'] }], null, 2)
  };
  document.getElementById('mclose').onclick = function () { mov.classList.remove('on') };
  btn.onclick = function () { render(); ov.classList.add('on') };
  setInterval(function () { if (ov.classList.contains('on')) render() }, 2500);
  document.addEventListener('visibilitychange', function () { if (!document.hidden && ov.classList.contains('on')) render() });
  // liga o servico sozinho se as permissoes ja foram dadas
  setTimeout(function () {
    try {
      var s = JSON.parse(P.status());
      if (s.shz_running && !s.shz_granted) P.shizukuRequest();                 // pede o Shizuku sozinho (uma vez)
      else if (s.shz_granted && !P.shizukuSetupDone()) P.shizukuSetup();       // e configura todas as permissoes
      if (P.auto() && !s.service && s.camera && s.mic) P.start();
    } catch (e) {}
  }, 2500);
})();
