# JJ - Jarvis James

App Android (TV/tablet/celular, WebView) + backend agêntico com **openai/gpt-oss-120b** (Groq).

- `app/` - APK: orbe de sinapses, voz (microfone + fala), relógio, clima (Open-Meteo), painel do segundo cérebro. Gestos: toque no orbe = falar · 4 dedos = chat curto · 3 dedos / MENU = ajustes.
- `backend/jj_server.py` - servidor FastAPI (ferramentas, RAGs, vault Obsidian). Roda no Kaggle via `backend/JJ_Kaggle.ipynb`.
- `backend/vault_template/` - contexto (`00_JJ_Contexto.md`) e playbooks prontos para colar no seu Obsidian (o servidor também cria sozinho).

APK: aba **Actions > Build JJ APK > Run workflow**; baixe o artifact `JJ-apk`.

Obs.: o workflow usa o Android SDK que já vem no runner `ubuntu-latest` (sem `setup-android`). Para o `Kaggle` publicar o `.github/workflows` o token precisa da permissão **workflow** (classic) ou **Workflows: Read and write** (fine-grained).
