# Receitas de shell e Python
- Processo longo: `nohup cmd > log.txt 2>&1 &` e depois `tail -n 30 log.txt`.
- Ver e matar processo: `ps aux | grep nome`, `kill PID`.
- Baixar: `curl -L -o arq URL`; descompactar: `unzip` ou `tar xzf`.
- Espaço: `du -sh *`, `df -h`.
- Dependências: `pip install -q pacote`; ambientes: `python -m venv .venv`.
- JSON no shell: `jq`; no Python: `json.load`.
