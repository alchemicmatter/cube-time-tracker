# Cube Time Tracker

Un cubo fisico (o qualsiasi oggetto con un tag NFC per faccia) per avviare e
fermare timer di progetti diversi appoggiandolo sul telefono. Nessun account,
nessun server, nessun dato che lascia il dispositivo.

## Come funziona

1. Ogni faccia del cubo ha un tag NFC (anche riciclato: bobine filamento,
   vecchie carte, badge dismessi — basta che il telefono ne legga l'UID).
2. Alla prima scansione di un tag mai visto, l'app chiede a quale progetto
   associarlo. Non si scrive mai nulla sul tag: si legge solo il suo
   identificativo di fabbrica (UID).
3. Alle scansioni successive, appoggiare il cubo avvia il timer sul progetto
   associato a quella faccia, chiudendo automaticamente un eventuale timer
   già aperto su un altro progetto. Riappoggiare la stessa faccia ferma il
   timer.
4. Tutti i dati (progetti, sessioni, mappature tag) restano in un database
   SQLite locale (via Room) dentro l'app. L'export CSV è manuale e va sempre
   condiviso a scelta dell'utente (email, Drive personale, ecc.).

## Stato del progetto

Scheletro funzionante iniziale:
- Modello dati (Project, TagMapping, TimeSession) con Room
- Lettura NFC via foreground dispatch (solo UID, nessuna scrittura)
- Logica di toggle start/stop/switch tra progetti
- Export CSV locale
- Build automatica via GitHub Actions (vedi `.github/workflows/build.yml`)

Da completare (vedi Roadmap):
- UI di gestione progetti e associazione tag (schermata di setup)
- Schermata report/statistiche
- File STL del cubo stampabile
- Guida iOS via Apple Shortcuts

## Come ottenere l'APK

Ogni push su `main` builda automaticamente un APK di debug tramite GitHub
Actions. Vai nella scheda "Actions" del repository, apri l'ultima esecuzione
del workflow "Build APK", e scarica l'artifact `cube-time-tracker-debug-apk`.

## Stack tecnico

- Kotlin + Jetpack Compose
- Room (persistenza locale)
- Android NFC API (`NfcAdapter`, foreground dispatch)
- Nessuna dipendenza di rete: `usesCleartextTraffic="false"`, nessun permesso Internet nel manifest

## Hardware suggerito

- Cubo stampato in 3D (STL in `/hardware`, in arrivo)
- 6 tag NFC qualsiasi (NTAG213/215 nuovi consigliati per semplicità,
  ma va bene qualunque tag 13.56MHz che il telefono riesca a leggere)

## Licenza

Codice: MIT. Design hardware (quando pubblicato): CC-BY-SA.

## Privacy by design

Questo progetto non ha backend, non raccoglie telemetria, non richiede
account. Tutti i dati restano sul dispositivo dell'utente.
