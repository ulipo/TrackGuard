# Pubblicare TrackGuard su GitHub

1. Crea su GitHub un nuovo repository vuoto chiamato `TrackGuard` (senza README, `.gitignore` o licenza iniziali).
2. Estrai questo archivio e apri un terminale nella cartella `TrackGuard`.
3. Inizializza il repository e pubblica il primo commit:

```bash
git init -b main
git add .
git commit -m "Initial TrackGuard Android app"
git remote add origin https://github.com/UTENTE/TrackGuard.git
git push -u origin main
```

Sostituisci `UTENTE` con il tuo username GitHub. Per i push successivi sarà sufficiente `git add`, `git commit` e `git push`.

## Build automatica

Il workflow `.github/workflows/android.yml` parte automaticamente a ogni push su qualsiasi branch e a ogni pull request. Compila `app-debug.apk` e lo carica come artifact della run GitHub Actions per 30 giorni.

Per scaricare l'APK: repository GitHub → **Actions** → seleziona la run completata → sezione **Artifacts** → `TrackGuard-debug-<commit SHA>`.

Non sono necessari secrets o token aggiuntivi per la build. Il workflow produce un APK debug non firmato per la distribuzione Play Store.
