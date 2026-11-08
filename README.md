# TrackGuard — Android

TrackGuard è un'app Android nativa che controlla la distanza della posizione GPS da una traccia GPX e avvisa quando si esce dal percorso. Non visualizza mappe e non richiede connessione Internet durante l'uso: può essere usata insieme a CoMaps.

## Interfaccia

L’interfaccia usa componenti Material 3 e una palette ispirata a Nord (grigio ghiaccio, blu e accenti verde/rosso). Il comando di monitoraggio è un unico pulsante circolare: **▶** avvia il servizio, **■** lo ferma.

## Funzioni

- Importazione di file `.gpx` tramite il selettore documenti Android.
- Lettura di tracce (`trk` / `trkseg`) e percorsi (`rte`), inclusi segmenti separati.
- Distanza calcolata dal segmento più vicino della traccia, non solo dai waypoint.
- Soglia di allarme configurabile da 10 a 300 metri (predefinita: 30 m).
- Rientro considerato acquisito entro il 65% della soglia (con minimo 5 m), per evitare continui lampeggi dell'allarme.
- Notifica sintetica di deviazione: distanza, direzione relativa e direzione assoluta.
- Direzione relativa ricavata dal course-over-ground GNSS quando velocità e qualità del fix sono sufficienti. In caso contrario viene mostrata la direzione assoluta.
- Ripetizione dell'avviso dopo 2 minuti oppure quando la distanza aumenta di almeno 20 metri rispetto all'ultimo avviso.
- Notifica di servizio persistente durante il monitoraggio; l'avviso fuori traccia viene rimosso al rientro.
- Servizio foreground per continuare il monitoraggio con schermo spento.

## Requisiti

- Android 8.0 (API 26) o successivo.
- Android Studio recente con Android SDK Platform 35.
- JDK 17 e Gradle 8.9 per la build da riga di comando.
- Connessione Internet durante la build per scaricare plugin e dipendenze Gradle. L'app in uso non usa Internet.

## Compilazione locale

Aprire la cartella `TrackGuard` in Android Studio e attendere la sincronizzazione Gradle. Installare Android SDK Platform 35 e Build Tools 35.0.0 se richiesti.

Da terminale, con Gradle 8.9 installato:

```bash
gradle --no-daemon :app:assembleDebug
```

L'APK debug viene generato in:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Il workflow `.github/workflows/android.yml` usa action aggiornate compatibili con Node.js 24 (`checkout@v6`, `setup-java@v6`, `setup-android@v4`, `setup-gradle@v6`, `upload-artifact@v7`) e compila automaticamente l'APK debug:

- a ogni `push` su qualsiasi branch;
- a ogni `pull_request`;
- manualmente da **Actions → Android APK → Run workflow**.

Al termine di ogni build riuscita, l'APK è disponibile come artifact chiamato `TrackGuard-debug-<commit SHA>` nella pagina della relativa esecuzione GitHub Actions. L'artifact resta disponibile per 30 giorni. Non è richiesta alcuna chiave di firma: si tratta di un APK debug, adatto a test e installazione manuale, non a una pubblicazione Play Store.

## Primo avvio

1. Installare l'APK e concedere l'autorizzazione alla posizione precisa e alle notifiche.
2. Selezionare il file GPX.
3. Impostare la soglia e avviare il monitoraggio.
4. Lasciare attiva la notifica `TRACCIA ATTIVA`.
5. Per fermare il controllo, toccare il pulsante quadrato **■** nella schermata principale. Il pulsante circolare passa da **▶** (avvio) a **■** (arresto) quando il servizio è attivo.

Per alcuni telefoni è necessario consentire l'attività in background / l'uso non limitato della batteria per TrackGuard. Il nome dell'impostazione varia in base al produttore. Le notifiche Android standard vengono inoltrate ai dispositivi indossabili secondo le impostazioni della smart band.

## Logica di direzione

Il bearing del fix GNSS viene considerato valido in movimento (velocità almeno 0,8 m/s) e con accuratezza adeguata. Viene filtrato per attenuare le oscillazioni. La direzione verso il punto più vicino della traccia viene confrontata con il course GNSS e tradotta in AVANTI / DESTRA / DIETRO DESTRA / DIETRO / DIETRO SINISTRA / SINISTRA. La direzione assoluta a 8 settori e in gradi viene comunque sempre mostrata.

## Note e limiti

- Il GPS dipende dalla ricezione satellitare e dalla gestione energetica del telefono. Il servizio mantiene un wakelock parziale durante il monitoraggio: questo aumenta il consumo della batteria. I fix con accuratezza dichiarata peggiore di 50 m vengono ignorati.
- La direzione relativa è basata sulla direzione di spostamento sul terreno, non sull'orientamento del corpo. Da fermi o camminando molto lentamente viene mostrata solo la direzione assoluta.
- La compatibilità effettiva della visualizzazione su smart band dipende dal modello e dalle sue impostazioni; non sono usati SDK proprietari.
- Prima di affidarsi all'app in escursione, provarla su un percorso noto e verificare che il produttore del telefono non termini il servizio in background.

## Identificativi

- Nome app: `TrackGuard`
- Application ID: `it.ulipo.trackguard`
- Versione: `1.0.0` (prototipo iniziale)


### Impostazioni notifiche

L'intervallo di ripetizione dell'avviso fuori traccia è configurabile nell'app: 30 secondi, 1, 2, 3, 5 o 10 minuti (predefinito 2 minuti). Un allontanamento di almeno altri 20 metri rispetto all'ultimo avviso genera una ripetizione immediata.

Formato breve della notifica: `FUORI TRACCIA - 35m`, `vai verso DESTRA` (solo se la direzione relativa GNSS è disponibile), `SE 134°`.
