"""JJ runner: sobe o servidor, abre o tunel Cloudflare e publica o Gateway no vault (GitHub).
Usado pelo botao "Ligar JJ" do app (script enviado ao Kaggle) e pela celula 2 do notebook.
Configuracao por variaveis de ambiente: GITHUB_TOKEN, GROQ_API_KEY, JJ_TOKEN, GITHUB_USER,
VAULT_NAME, JJ_TZ, JJ_IDLE_MIN (minutos sem pedidos ate desligar; 90 por padrao).
Quando o servidor encerra (ociosidade ou /shutdown) este script termina e libera a sessao do Kaggle."""
import os, sys, re, time, secrets, subprocess, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(HERE)
W = os.environ.get("JJ_HOME") or ("/kaggle/working" if os.path.isdir("/kaggle/working") else os.getcwd())
os.makedirs(W, exist_ok=True)
os.chdir(W)

GH = os.environ.get("GITHUB_TOKEN", "")
GROQ = os.environ.get("GROQ_API_KEY", "")
USER = os.environ.get("GITHUB_USER", "")
VAULT_NAME = os.environ.get("VAULT_NAME") or "JJ-Vault"
TOKEN = os.environ.get("JJ_TOKEN") or secrets.token_urlsafe(18)
PORT = os.environ.get("PORT", "8000")
VAULT = os.environ.get("JJ_VAULT", W + "/JJ_Vault")


def say(*a):
    print(*a, flush=True)


def sh(cmd, cwd=None):
    p = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).replace(GH or "\0", "***").strip()


def publish(text):
    """Grava Gateway.md no vault e envia ao GitHub (com pull --rebase para evitar conflito)."""
    if not (GH and USER and os.path.isdir(VAULT + "/.git")):
        return False
    open(VAULT + "/Gateway.md", "w", encoding="utf-8").write(text)
    sh('git config user.email jj@jj.local; git config user.name JJ', VAULT)
    for _ in range(5):
        sh('git add -A; git commit -qm "gateway"', VAULT)
        sh("git pull -q --rebase --autostash origin main", VAULT)
        rc, out = sh("git push -q -u origin main", VAULT)
        if rc == 0:
            return True
        time.sleep(3)
    say("Aviso: nao consegui enviar Gateway.md:", out[:200])
    return False


def main():
    say("[runner] instalando dependencias...")
    sh("pip -q install fastapi uvicorn requests ddgs trafilatura beautifulsoup4 scikit-learn pypdf tzdata fastembed numpy")

    env = dict(os.environ, JJ_TOKEN=TOKEN, JJ_HOME=W, PORT=PORT, GITHUB_TOKEN=GH, GROQ_API_KEY=GROQ,
               VAULT_REPO=f"{USER}/{VAULT_NAME}" if USER else "", JJ_TZ=os.environ.get("JJ_TZ", "America/Sao_Paulo"),
               JJ_IDLE_MIN=os.environ.get("JJ_IDLE_MIN", "90"))
    server = subprocess.Popen([sys.executable, HERE + "/jj_server.py"], env=env,
                              stdout=open("server.log", "w"), stderr=subprocess.STDOUT)

    if not os.path.exists("cloudflared"):
        sh("wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O cloudflared && chmod +x cloudflared")
    tun = subprocess.Popen(["./cloudflared", "tunnel", "--url", f"http://localhost:{PORT}", "--no-autoupdate"],
                           stdout=open("tunnel.log", "w"), stderr=subprocess.STDOUT)
    url = None
    for _ in range(90):
        time.sleep(1)
        m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", open("tunnel.log").read())
        if m:
            url = m.group(0)
            break
    if not url:
        say("[runner] tunel nao subiu. tunnel.log:\n" + open("tunnel.log").read()[-800:])
        server.terminate(); tun.terminate()
        sys.exit(1)

    # espera o servidor (o vault e preparado antes de ele abrir a porta)
    for _ in range(150):
        try:
            req = urllib.request.Request(f"http://localhost:{PORT}/health", headers={"X-JJ-Token": TOKEN})
            urllib.request.urlopen(req, timeout=3)
            break
        except Exception:
            if server.poll() is not None:
                say("[runner] servidor caiu ao iniciar. server.log:\n" + open("server.log").read()[-1500:])
                tun.terminate()
                sys.exit(1)
            time.sleep(2)

    say("\n=== JJ ONLINE ===")
    say("Gateway:", url)
    if os.environ.get("JJ_PRINT_TOKEN") == "1":
        say("Token  :", TOKEN)
    say("Desliga sozinho apos", os.environ.get("JJ_IDLE_MIN", "90"), "min sem pedidos.")
    ok = publish(f"# Gateway do JJ\n\nAtualizado: {time.strftime('%Y-%m-%d %H:%M')}\n\n{url}\n")
    say("Gateway publicado no vault." if ok else "Gateway NAO publicado (cole manualmente no app).")

    while server.poll() is None and tun.poll() is None:
        time.sleep(5)

    say("[runner] encerrando...", "(servidor parou)" if server.poll() is not None else "(tunel caiu)")
    for p in (server, tun):
        try:
            p.terminate()
        except Exception:
            pass
    publish(f"# Gateway do JJ\n\nOFFLINE desde {time.strftime('%Y-%m-%d %H:%M')}\n")


if __name__ == "__main__":
    main()
