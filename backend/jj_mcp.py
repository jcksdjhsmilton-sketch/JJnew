"""JJ - conectores MCP (Model Context Protocol).

Cliente minimo e sincrono, sem dependencias extras:
- HTTP "Streamable HTTP" (servidores remotos, com cabecalhos/Bearer token);
- stdio (servidores locais, ex.: npx/uvx), com variaveis de ambiente.

Configuracao (lista JSON), aceita de 3 fontes que se somam (a ultima vence pelo nome):
  env JJ_MCP_SERVERS, arquivo <HOME>/mcp_servers.json e o campo "mcp_servers" enviado pelo app em cada /chat.
Exemplos:
  {"name": "github", "url": "https://api.githubcopilot.com/mcp/", "headers": {"Authorization": "Bearer ghp_..."}}
  {"name": "fs", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/kaggle/working"]}
"""
import os
import re
import json
import time
import hashlib
import threading
import subprocess
import queue
import unicodedata
import requests

PROTO = "2025-06-18"


def _plain(s):
    return "".join(c for c in unicodedata.normalize("NFD", str(s).lower()) if unicodedata.category(c) != "Mn")


def _parse_event(lines):
    if not lines:
        return None
    try:
        return json.loads("\n".join(lines))
    except Exception:
        return None


class HttpClient:
    def __init__(self, cfg):
        self.url = cfg["url"]
        self.headers = dict(cfg.get("headers") or {})
        self.sid = None
        self.inited = False
        self.id = 0
        self.lock = threading.Lock()

    def _post(self, payload, want_id, timeout):
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        h.update(self.headers)
        if self.sid:
            h["Mcp-Session-Id"] = self.sid
        if self.inited:
            h["MCP-Protocol-Version"] = PROTO
        r = requests.post(self.url, json=payload, headers=h, timeout=timeout, stream=True)
        sid = r.headers.get("Mcp-Session-Id")
        if sid:
            self.sid = sid
        if r.status_code in (202, 204):
            return None
        if r.status_code >= 400:
            raise RuntimeError(f"HTTP {r.status_code}: {r.text[:200]}")
        if "text/event-stream" in r.headers.get("content-type", ""):
            r.encoding = "utf-8"
            data = []
            for raw in r.iter_lines(decode_unicode=True):
                if raw is None:
                    continue
                if raw == "":
                    msg = _parse_event(data)
                    data = []
                    if isinstance(msg, dict) and msg.get("id") == want_id and ("result" in msg or "error" in msg):
                        return msg
                elif raw.startswith("data:"):
                    data.append(raw[5:].lstrip())
            msg = _parse_event(data)
            return msg if isinstance(msg, dict) and msg.get("id") == want_id else None
        j = r.json()
        if isinstance(j, list):
            j = next((m for m in j if isinstance(m, dict) and m.get("id") == want_id), None)
        return j

    def request(self, method, params=None, timeout=60):
        with self.lock:
            self.id += 1
            i = self.id
            msg = self._post({"jsonrpc": "2.0", "id": i, "method": method, "params": params or {}}, i, timeout)
        if msg is None:
            raise RuntimeError("sem resposta do servidor MCP")
        if "error" in msg:
            raise RuntimeError(str((msg["error"] or {}).get("message", msg["error"])))
        return msg.get("result", {})

    def notify(self, method, params=None):
        with self.lock:
            self._post({"jsonrpc": "2.0", "method": method, "params": params or {}}, None, 20)

    def start(self):
        pass

    def initialize(self):
        res = self.request("initialize", {"protocolVersion": PROTO, "capabilities": {}, "clientInfo": {"name": "JJ", "version": "1.0"}}, 40)
        self.inited = True
        self.notify("notifications/initialized")
        return res

    def close(self):
        try:
            if self.sid:
                requests.delete(self.url, headers={"Mcp-Session-Id": self.sid, **self.headers}, timeout=5)
        except Exception:
            pass


class StdioClient:
    def __init__(self, cfg):
        self.cfg = cfg
        self.p = None
        self.pending = {}
        self.id = 0
        self.lock = threading.Lock()
        self.wlock = threading.Lock()

    def start(self):
        env = dict(os.environ)
        env.update({k: str(v) for k, v in (self.cfg.get("env") or {}).items()})
        self.p = subprocess.Popen([self.cfg["command"]] + [str(a) for a in (self.cfg.get("args") or [])],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, bufsize=1, env=env, cwd=self.cfg.get("cwd") or None)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            for line in self.p.stdout:
                try:
                    msg = json.loads(line)
                except Exception:
                    continue
                q = self.pending.get(msg.get("id")) if isinstance(msg, dict) else None
                if q is not None and ("result" in msg or "error" in msg):
                    q.put(msg)
        except Exception:
            pass

    def _send(self, obj):
        with self.wlock:
            self.p.stdin.write(json.dumps(obj) + "\n")
            self.p.stdin.flush()

    def request(self, method, params=None, timeout=60):
        with self.lock:
            self.id += 1
            i = self.id
        q = queue.Queue(1)
        self.pending[i] = q
        try:
            self._send({"jsonrpc": "2.0", "id": i, "method": method, "params": params or {}})
            msg = q.get(timeout=timeout)
        except queue.Empty:
            raise RuntimeError("timeout do servidor MCP")
        finally:
            self.pending.pop(i, None)
        if "error" in msg:
            raise RuntimeError(str((msg["error"] or {}).get("message", msg["error"])))
        return msg.get("result", {})

    def notify(self, method, params=None):
        self._send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def initialize(self):
        res = self.request("initialize", {"protocolVersion": PROTO, "capabilities": {}, "clientInfo": {"name": "JJ", "version": "1.0"}}, 60)
        self.notify("notifications/initialized")
        return res

    def close(self):
        try:
            self.p.terminate()
        except Exception:
            pass


class Server:
    def __init__(self, cfg):
        self.cfg = cfg
        self.name = re.sub(r"[^a-zA-Z0-9_-]", "_", str(cfg.get("name") or "mcp"))[:24]
        self.key = hashlib.sha1(json.dumps(cfg, sort_keys=True).encode()).hexdigest()
        self.client = None
        self.tools = []
        self.connected = False
        self.error = ""
        self.lock = threading.Lock()

    def connect(self):
        with self.lock:
            try:
                if self.client:
                    self.client.close()
                c = StdioClient(self.cfg) if self.cfg.get("command") else HttpClient(self.cfg)
                c.start()
                c.initialize()
                tools, cursor = [], None
                while True:
                    r = c.request("tools/list", {"cursor": cursor} if cursor else {}, 40)
                    tools += r.get("tools", [])
                    cursor = r.get("nextCursor")
                    if not cursor or len(tools) > 300:
                        break
                self.client, self.tools, self.connected, self.error = c, tools, True, ""
            except Exception as e:
                self.connected, self.error = False, f"{type(e).__name__}: {str(e)[:180]}"

    def call(self, tool, args):
        if not self.connected:
            self.connect()
        if not self.connected:
            return f"ERRO: conector '{self.name}' indisponivel ({self.error})"
        for attempt in (1, 2):
            try:
                r = self.client.request("tools/call", {"name": tool, "arguments": args}, 90)
                break
            except Exception as e:
                if attempt == 2:
                    return f"ERRO no conector '{self.name}': {e}"
                self.connect()  # sessao expirada ou processo morto: reconecta uma vez
                if not self.connected:
                    return f"ERRO: conector '{self.name}' caiu ({self.error})"
        parts = []
        for c in r.get("content", []) or []:
            if c.get("type") == "text":
                parts.append(c.get("text", ""))
            elif c.get("type") == "resource":
                parts.append(str((c.get("resource") or {}).get("text", "[recurso]")))
            else:
                parts.append(f"[{c.get('type', 'conteudo')} omitido]")
        if r.get("structuredContent") and not parts:
            parts.append(json.dumps(r["structuredContent"], ensure_ascii=False))
        out = "\n".join(p for p in parts if p).strip() or "(sem conteudo)"
        return ("ERRO do conector: " if r.get("isError") else "") + out


def clean_schema(sch):
    """Esquema aceito pela Groq. Devolve (esquema, embrulhado). Esquemas complexos viram um unico campo 'args' (JSON)."""
    if not isinstance(sch, dict) or sch.get("type") != "object":
        return {"type": "object", "properties": {}}, False
    raw = json.dumps(sch, ensure_ascii=False)
    if any(k in raw for k in ('"$ref"', '"anyOf"', '"oneOf"', '"allOf"')):
        return ({"type": "object", "properties": {"args": {"type": "string", "description": "Argumentos em JSON. Esquema: " + raw[:700]}},
                 "required": ["args"]}, True)
    s = json.loads(raw)
    for k in ("$schema", "additionalProperties", "$defs", "definitions"):
        s.pop(k, None)
    props = s.get("properties") or {}
    for p in props.values():
        if isinstance(p, dict):
            p.pop("additionalProperties", None)
            p.pop("$schema", None)
    s["properties"] = props
    if "required" in s and not isinstance(s["required"], list):
        s.pop("required")
    return s, False


def coerce(args, schema):
    """O modelo as vezes manda numeros/booleanos como texto: ajusta pelo esquema."""
    props = (schema or {}).get("properties", {}) if isinstance(schema, dict) else {}
    out = dict(args)
    for k, v in args.items():
        t = (props.get(k) or {}).get("type")
        try:
            if t == "integer" and isinstance(v, str):
                out[k] = int(float(v))
            elif t == "number" and isinstance(v, str):
                out[k] = float(v)
            elif t == "boolean" and isinstance(v, str):
                out[k] = v.strip().lower() in ("1", "true", "sim", "yes")
            elif t in ("array", "object") and isinstance(v, str):
                out[k] = json.loads(v)
        except Exception:
            pass
    return out


class Manager:
    def __init__(self):
        self.servers = {}
        self.lock = threading.Lock()
        self.base_key = ""

    def _load_base(self, home):
        out = []
        env = os.environ.get("JJ_MCP_SERVERS", "").strip()
        if env:
            try:
                out += json.loads(env)
            except Exception:
                pass
        f = os.path.join(home, "mcp_servers.json")
        if os.path.exists(f):
            try:
                out += json.load(open(f, encoding="utf-8"))
            except Exception:
                pass
        return out

    def configure(self, extra, home):
        """Conecta o que falta (em paralelo, ate 15 s) e remove o que saiu da configuracao. Rapido se nada mudou."""
        cfgs = {}
        for c in self._load_base(home) + list(extra or []):
            if isinstance(c, dict) and (c.get("url") or c.get("command")) and not c.get("disabled"):
                s = Server(c)
                cfgs[s.name] = s
        todo = []
        with self.lock:
            for name, s in cfgs.items():
                cur = self.servers.get(name)
                if cur is None or cur.key != s.key:
                    self.servers[name] = s
                    todo.append(s)
                elif not cur.connected and time.time() - getattr(cur, "last_try", 0) > 120:
                    cur.last_try = time.time()
                    todo.append(cur)
            for name in list(self.servers):
                if name not in cfgs:
                    try:
                        self.servers[name].client and self.servers[name].client.close()
                    except Exception:
                        pass
                    del self.servers[name]
        ths = [threading.Thread(target=s.connect, daemon=True) for s in todo]
        for t in ths:
            t.start()
        end = time.time() + 15
        for t in ths:
            t.join(max(0.1, end - time.time()))

    def status(self):
        return [{"name": s.name, "connected": s.connected, "tools": len(s.tools), "error": s.error,
                 "tool_names": [t.get("name") for t in s.tools][:40]} for s in self.servers.values()]

    def has_tools(self):
        return any(s.connected and s.tools for s in self.servers.values())

    def names(self):
        return list(self.servers)

    def mentions(self, text):
        t = _plain(text)
        return any(re.search(r"\b" + re.escape(_plain(n)) + r"\b", t) for n in self.servers)

    def all_tools(self):
        return [(s, t) for s in self.servers.values() if s.connected for t in s.tools]

    def _score(self, q_words, srv, tool):
        hay = _plain(f"{srv.name} {tool.get('name', '')} {tool.get('description', '')}")
        hay_words = set(re.findall(r"[a-z0-9]{3,}", hay.replace("_", " ")))
        sc = sum(1 for w in q_words if w in hay_words)
        if _plain(srv.name) in q_words:
            sc += 2
        return sc

    def search(self, query, k=8):
        qw = set(re.findall(r"[a-z0-9]{3,}", _plain(query)))
        ranked = sorted(((self._score(qw, s, t), s, t) for s, t in self.all_tools()), key=lambda x: -x[0])
        return [(s, t) for sc, s, t in ranked[:k] if sc > 0 or not qw]

    def select(self, text, limit=6):
        qw = set(re.findall(r"[a-z0-9]{3,}", _plain(text)))
        ranked = sorted(((self._score(qw, s, t), s, t) for s, t in self.all_tools()), key=lambda x: -x[0])
        return [(s, t) for sc, s, t in ranked[:limit] if sc >= 2]

    def call(self, server, tool, args, wrapped=False, schema=None):
        s = self.servers.get(server)
        if not s:
            return f"ERRO: conector '{server}' nao existe. Conectores: {', '.join(self.servers) or 'nenhum'}"
        if wrapped:
            try:
                args = json.loads(args.get("args", "{}")) if isinstance(args.get("args"), str) else args.get("args", {})
            except Exception:
                return "ERRO: 'args' precisa ser um JSON valido"
        elif schema:
            args = coerce(args, schema)
        return s.call(tool, args)


MANAGER = Manager()


def fname(server, tool):
    base = re.sub(r"[^a-zA-Z0-9_-]", "_", f"mcp__{server}__{tool}")
    if len(base) <= 64:
        return base
    return base[:58] + "_" + hashlib.sha1(base.encode()).hexdigest()[:5]
