# RegiStrazio — App Android per collettivi musicali

Documentazione tecnica completa per l'implementazione Android.
Questo file è la fonte di verità per Claude Code e per chiunque lavori sul progetto.

**Repository:** `https://github.com/DrFonzarelli/RegiStrazio`
**Package name / applicationId:** `com.example.registrazio`

---

## Contesto

Cinque persone di un collettivo musicale. Registrano idee alle prove, vogliono
ascoltarle e lasciarsi commenti tra una sessione e l'altra. L'app è asincrona
per natura — raramente due persone la usano insieme.

Le cartelle audio esistono già su MEGA (il gruppo le usa già). L'app legge da
MEGA e aggiunge il layer sociale (commenti, reazioni, note) su Firestore.

**Riferimento visivo obbligatorio:** `prova-app-v3-integrata.html` è il prototipo
funzionante dell'intera UI. Claude Code deve usarlo come spec grafica 1:1 —
layout, colori, componenti, animazioni, interazioni sono già tutti definiti lì.
Non reinventare nulla visivamente: tradurre in Kotlin/Compose quello che il
prototipo fa in HTML/JS.

---

## Stack tecnico

| Layer                   | Tecnologia                                      |
|-------------------------|-------------------------------------------------|
| UI                      | Jetpack Compose                                 |
| Audio                   | ExoPlayer (Media3)                              |
| Backend sync            | Firebase Firestore                              |
| Auth                    | Firebase Anonymous Auth                         |
| Storage locale          | Room DB                                         |
| File audio remoti       | MEGA (HTTP API pubblica, vedi sezione dedicata) |
| Download locale         | DownloadManager di sistema Android              |
| Preferenze persistenti  | EncryptedSharedPreferences (Android Keystore)   |

**SDK minima:** Android 8.0 (minSdk 26, copre ~97% dispositivi attivi)
**compileSdk / targetSdk:** 36 (API 36.1)
**Java:** 17

---

## Architettura dei dati

### Firestore — schema completo

```
utenti/{appUid}
  nome: string
  colore: string           // hex, scelto all'onboarding
  creatoIl: timestamp

cartelle/{cartellaId}
  linkMega: string         // link pubblico cartella MEGA (completo, con chiave)
  megaFolderId: string     // id estratto dal link (parte dopo /folder/)
  nome: string             // nome leggibile ("Prove giugno")
  aggiuntoIl: timestamp
  aggiuntoDa: string       // appUid di chi l'ha aggiunta

tracce/{tracciaId}
  cartellaId: string
  nomeFile: string
  idFileMega: string       // node handle del file su MEGA
  durataSecondi: number
  waveformData: array<number>   // ~200 float 0.0–1.0. null finché non calcolata
  ascolti: number               // contatore incrementale
  creatoIl: timestamp

tracce/{tracciaId}/commenti/{commentoId}
  appUid: string
  autoreNome: string       // snapshot al momento della scrittura
  autoreColore: string     // snapshot al momento della scrittura
  timestampSecondi: number // posizione nella traccia (0.0 = inizio)
  testo: string
  creatoIl: timestamp
```

**Note sullo schema:**
- `appUid` è un UUID generato dall'app, distinto dall'UID Firebase Anonymous Auth
  (vedi sezione Identità).
- Nome e colore vengono copiati nel commento come snapshot. Se uno cambia nome
  dopo, i vecchi commenti restano attribuiti correttamente.
- `waveformData` viene calcolata dal primo utente che scarica la traccia e salvata
  su Firestore. Tutti gli altri la scaricano già pronta — nessuno ricalcola.
- I commenti sono append-only con UUID generato in locale — nessun conflitto
  possibile in caso di sync concorrente.
- Non c'è soft delete: l'eliminazione è permanente sia in locale che su Firestore.

### Room DB — persistenza locale

```kotlin
// Commenti scritti ma non ancora sincronizzati
@Entity(tableName = "commenti_pending")
data class CommentoPending(
    @PrimaryKey val id: String,         // UUID generato in locale
    val tracciaId: String,
    val timestampSecondi: Float,        // posizione nella traccia
    val testo: String,
    val appUid: String,
    val autoreNome: String,
    val autoreColore: String,
    val creatoIlLocale: Long,
    val stato: StatoSync                // PENDING | UPLOADING | ERROR
)

// Tracce scaricate in locale
@Entity(tableName = "tracce_download")
data class TracciaDownload(
    @PrimaryKey val tracciaId: String,
    val percorsoLocale: String,         // path assoluto in cacheDir/audio/
    val scaricatoIl: Long,
    val dimensioneBytes: Long
)

enum class StatoSync { PENDING, UPLOADING, ERROR }
```

I commenti pending vivono in Room dal momento in cui vengono scritti fino al
momento in cui il Sincronizza li carica su Firestore con successo. Dopo l'upload,
il record Room viene eliminato — da quel momento il commento esiste solo su Firestore.

---

## Identità utente

### Il problema con Anonymous Auth

Firebase Anonymous Auth genera un **nuovo UID ad ogni reinstall**. Se lo usassimo
come chiave identità, reinstallare l'app significherebbe perdere i propri commenti.

### Soluzione: UUID applicativo (appUid)

L'app genera un UUID proprio (`appUid`) al primo avvio e lo salva in
`EncryptedSharedPreferences`. Questo UUID:
- È la vera chiave identità su Firestore (`utenti/{appUid}`, `commenti.appUid`).
- Sopravvive agli aggiornamenti dell'app.
- Viene incluso nel backup automatico Android (abilitare `android:allowBackup="true"`
  nel manifest + regola di backup che include il file delle preferenze cifrate).
  In molti casi il ripristino da backup recupera l'UUID automaticamente senza
  che l'utente debba fare nulla.
- Firebase Anonymous Auth continua a essere usato in background, solo per
  soddisfare le regole di sicurezza Firestore ("qualsiasi utente autenticato
  può leggere/scrivere"). L'UID Firebase non viene mai usato come chiave dati.

### Flusso primo avvio

All'avvio l'app controlla `EncryptedSharedPreferences`:

**Se `appUid` esiste** → profilo già noto, si va direttamente alla home.

**Se `appUid` non esiste** (primo avvio o backup non ripristinato) → mostra il Gate.

### Gate — schermata di onboarding

Riprodurre esattamente il Gate del prototipo HTML. Ha due percorsi:

**Percorso A — Sono nuovo:**
1. Input nome.
2. Scelta colore avatar (palette di 7 colori, come nel prototipo).
3. Genera nuovo UUID → salva in `EncryptedSharedPreferences` e su Firestore
   (`utenti/{appUid}`).
4. Firebase Anonymous Auth in background (silenzioso).
5. Vai alla home.

**Percorso B — Ho già un account (reinstall o cambio telefono):**
1. Scarica la lista dei profili da Firestore (`utenti/`, ordinati per `creatoIl`).
2. Mostra la lista con nome + pallino colorato per ciascun profilo, identica
   alla UI del prototipo.
3. L'utente riconosce il proprio avatar e lo tocca.
4. L'`appUid` selezionato viene salvato in `EncryptedSharedPreferences`.
5. Vai alla home. Tutti i commenti precedenti tornano "propri" (eliminabili).

> Nessuna verifica di ownership: chiunque potrebbe teoricamente scegliere il
> profilo di un altro. Per 5 persone di un collettivo che si conoscono è
> accettabile per la v1.

---

## Integrazione MEGA — HTTP API pubblica

### Perché HTTP API e non MegaSDK

MegaSDK ufficiale per Android richiede librerie C++ via JNI, build complessa
e distribuzione di `.so` per ogni ABI. Per le due sole operazioni necessarie
(lista file di una cartella pubblica + URL temporaneo per streaming/download)
l'HTTP API pubblica è sufficiente, stabile e mantenuta da anni dalla community.
Il rischio di rottura è basso; in caso basterebbe aggiornare l'app.

### Endpoint MEGA

Tutte le chiamate vanno a `https://g.api.mega.co.nz/cs` come POST JSON.
Ogni richiesta è un array di comandi.

#### 1. Recuperare il contenuto di una cartella pubblica

Il link MEGA ha formato: `https://mega.nz/folder/FOLDER_ID#DECRYPTION_KEY`

Estrarre `FOLDER_ID` (dopo `/folder/`) e `DECRYPTION_KEY` (dopo `#`).

```json
POST https://g.api.mega.co.nz/cs?id=0&n=FOLDER_ID
[{"a":"f","c":1,"ca":1,"r":1}]
```

La risposta contiene l'array `f` di nodi. Ogni nodo file audio ha:
- `h` → node handle (usato come `idFileMega`)
- `t` → tipo (0 = file, 1 = cartella)
- `a` → attributi cifrati con la chiave della cartella (contengono il nome file)
- `s` → dimensione in bytes
- `k` → chiave del file cifrata con la chiave della cartella

Per decriptare il nome file: derivare la chiave della cartella dalla
`DECRYPTION_KEY` nel link (Base64 URL-safe → 16 byte AES) e usarla per
decriptare il campo `a` (JSON con chiave `n` = nome file).

Librerie Kotlin consigliate per la crittografia MEGA: usare `javax.crypto`
(AES-128-ECB per le chiavi, AES-128-CBC con IV zero per gli attributi).
Riferimento implementativo: libreria open source `mega4j` o `megatools` per
capire il protocollo esatto.

#### 2. Ottenere l'URL temporaneo per streaming/download

```json
POST https://g.api.mega.co.nz/cs?id=1&n=FOLDER_ID
[{"a":"g","g":1,"n":"NODE_HANDLE","ssl":2}]
```

La risposta contiene:
- `g` → URL temporaneo per il download/streaming (HTTPS diretto)
- `s` → dimensione file in bytes
- `at` → attributi cifrati (nome file, se non già decriptato)

Questo URL **scade** (tipicamente entro poche ore). Non cacharlo tra sessioni.
Richiederlo fresco ogni volta prima di fare play o avviare un download.

### Flusso sync MEGA (dentro il tasto Sincronizza)

1. Per ogni cartella collegata (`cartelle/` su Firestore), chiamare l'API con
   il `megaFolderId` per ottenere la lista file aggiornata.
2. Confrontare con le tracce già note in Firestore (`tracce/` dove `cartellaId`
   corrisponde).
3. Per ogni file MEGA non ancora presente su Firestore, creare il documento
   traccia (solo metadati: nome, id, durata se ricavabile, timestamp).
   **Non scaricare l'audio** in questa fase.
4. Le nuove tracce compaiono nell'UI immediatamente dopo.

---

## Audio: streaming e download

### Logica di selezione sorgente

```
Al play di una traccia:
  Se esiste record in Room TracceDownload con quel tracciaId
    → ExoPlayer punta al percorsoLocale (nessuna rete)
  Altrimenti
    → Richiedi URL temporaneo MEGA via HTTP API
    → ExoPlayer streamma da quell'URL
```

Non esiste pre-scarico parziale: o si streamma o si usa il file locale.

### Gestione scadenza URL durante lo streaming

Se ExoPlayer riceve errore HTTP 403/401 durante lo streaming (URL scaduto):
1. Richiedere un nuovo URL temporaneo MEGA per lo stesso `idFileMega`.
2. Creare un nuovo MediaItem con il nuovo URL.
3. Chiamare `player.seekTo(posizioneCorrente)` prima di `player.prepare()`.
4. Riprendere la riproduzione.

Implementare questo nel `Player.Listener.onPlayerError`.

### Download locale (azione esplicita dell'utente)

Triggered dal tasto download nella track card o da "Scarica tutte" nella sort bar,
come nel prototipo.

Flusso:
1. Richiedere URL temporaneo MEGA per la traccia.
2. Avviare download con `DownloadManager` di sistema (notifica di progresso nativa).
3. Al completamento (`ACTION_DOWNLOAD_COMPLETE`): spostare il file in
   `context.cacheDir/audio/{tracciaId}.{estensione}` e inserire record in
   `Room.TracceDownload`.
4. Aggiornare UI della track card: icona download → icona file locale.

**Eliminazione file locale:** menu "..." → "Rimuovi dal telefono". Elimina il file
fisico e il record Room. Al prossimo play torna lo streaming automaticamente.

I file scaricati non vengono eliminati automaticamente dall'app.

---

## Waveform

### Strategia ibrida: falsa-consistente → reale on-demand

**Default (sempre disponibile, zero rete, zero elaborazione):**
Generare un array di ~200 valori float usando l'`idFileMega` come seed per un
generatore pseudo-casuale deterministico (es. `java.util.Random(seed)`).
La stessa traccia produce sempre la stessa "waveform" su tutti i dispositivi.
Visivamente credibile, non accurata. Esattamente come nel prototipo.

**Upgrade a waveform reale (dopo il primo download completo):**
Una volta che una traccia è stata scaricata in locale:
1. Processarla in background con `MediaCodec` per decodificare il PCM.
2. Campionare ~200 valori di ampiezza normalizzati 0.0–1.0.
3. Salvare su Firestore in `tracce/{tracciaId}.waveformData`.
4. Tutti gli altri membri scaricheranno la waveform reale al prossimo pull
   e non dovranno calcolarla.

Durante il calcolo, la waveform falsa rimane visibile. Quando i dati reali
arrivano, aggiornare le barre con un'animazione.

Se la waveform reale è già presente su Firestore (calcolata da un altro membro),
scaricarla insieme agli altri metadati — nessun calcolo necessario.

---

## Commenti

### Ciclo di vita

```
[utente scrive] → Room PENDING
                      ↓
              [preme Sincronizza]
                      ↓
              Room UPLOADING → upload Firestore
                      ↓                  ↓
             [successo]           [errore]
         record Room eliminato   Room ERROR
         commento su Firestore   (riprovabile)
```

### Stati nell'UI (come nel prototipo)

- **PENDING**: badge arancione piccolo (punto o icona orologio) accanto al commento.
- **UPLOADING**: badge diventa spinner.
- **ERROR**: badge rosso, tappabile → bottom sheet con "Riprova" / "Elimina".
- **synced**: nessun badge, aspetto normale.

### Log non invasivo dei pending

Il tasto Sincronizza nell'header mostra un contatore discreto quando ci sono
commenti non ancora caricati: `Sincronizza · 3` (pallino o numero piccolo,
non un badge rosso). Tappando sul contatore (non sul tasto principale) si apre
un bottom sheet leggero con la lista: "3 commenti in attesa — Prova giugno (2),
Prove agosto (1)". Niente notifiche, niente badge rosso sull'icona app.

### Timestamp del commento

`timestampSecondi` è la **posizione nella traccia** al momento in cui l'utente
apre la add-box. L'utente può modificarla prima di inviare (come nel prototipo).
`creatoIl` è il timestamp wall-clock della scrittura.

### Eliminazione commenti

Un utente può eliminare solo i propri commenti (dove `appUid` coincide con
quello locale). I commenti synced: eliminazione su Firestore. I commenti
pending: eliminazione da Room. Nessun soft delete.

---

## Tasto Sincronizza — flusso completo

Il tasto Sincronizza fa pull + push insieme, nell'ordine:

1. **Pull da Firestore**: scarica commenti nuovi e metadati tracce aggiornati
   (es. waveform reale calcolata da un altro membro, nuovi ascolti).
2. **Sync MEGA**: per ogni cartella collegata, interroga l'HTTP API MEGA per la
   lista file aggiornata. Crea su Firestore i documenti per le tracce nuove.
   Mostra le nuove tracce nell'UI.
3. **Push da Room**: carica su Firestore tutti i commenti in stato PENDING o
   ERROR. Aggiorna lo stato a UPLOADING durante l'upload. In caso di successo:
   elimina il record Room. In caso di errore: imposta stato ERROR.
4. Se la waveform di una traccia è stata calcolata localmente e non è ancora
   su Firestore, la carica in questa fase.

Il tasto mostra uno spinner durante tutta l'operazione. In caso di errore
parziale: toast discreto "X elementi non sincronizzati" con possibilità di
riprovare dal log pending.

---

## Gestione offline

### Cosa funziona offline

- Ascoltare tracce **già scaricate in locale**.
- Leggere commenti **già sincronizzati** (Firestore SDK con persistenza locale
  abilitata: `FirebaseFirestoreSettings.Builder().setPersistenceEnabled(true)`).
- **Scrivere nuovi commenti** — salvati in Room con stato PENDING.

### Cosa non funziona offline

- Streaming di tracce non scaricate (ExoPlayer non può raggiungere MEGA).
- Vedere commenti nuovi scritti da altri dopo l'ultima sync.
- Premere Sincronizza → mostra messaggio: *"Sei offline. I tuoi commenti
  verranno caricati alla prossima sincronizzazione."*

### Indicatore offline nell'UI

Banner non bloccante sotto la topbar: *"Offline — dati dell'ultima
sincronizzazione"*. Sparisce quando torna la connessione. Nessun dialog.

---

## Regole di sicurezza Firestore

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Qualsiasi utente autenticato (anonymous) può leggere tutto
    match /{document=**} {
      allow read: if request.auth != null;
    }

    match /utenti/{uid} {
      allow write: if request.auth != null;
    }

    match /cartelle/{cartellaId} {
      allow write: if request.auth != null;
    }

    match /tracce/{tracciaId} {
      allow write: if request.auth != null;
    }

    match /tracce/{tracciaId}/commenti/{commentoId} {
      allow create: if request.auth != null;
      allow delete: if request.auth != null;  // owner check lato app
      allow update: if false;                 // i commenti non si modificano
    }
  }
}
```

> Regole volutamente permissive per un gruppo di 5 persone di fiducia.
> Non adatte a un'app pubblica.

---

## Struttura del progetto

```
app/src/main/
  java/com/example/registrazio/
    data/
      firestore/
        FirestoreRepository.kt     // CRUD su utenti, cartelle, tracce, commenti
      room/
        AppDatabase.kt
        CommentiPendingDao.kt
        TracceDownloadDao.kt
      mega/
        MegaApi.kt                 // chiamate HTTP all'API MEGA
        MegaCrypto.kt              // decriptazione attributi e chiavi
      model/
        Utente.kt
        Cartella.kt
        Traccia.kt
        Commento.kt
        StatoSync.kt
    domain/
      sync/
        SyncManager.kt             // orchestrazione pull + push + mega sync
      audio/
        PlayerManager.kt           // ExoPlayer singleton, gestione URL scaduti
        WaveformGenerator.kt       // MediaCodec → array float
      identity/
        IdentityManager.kt         // appUid, onboarding, recupero account
    ui/
      gate/
        GateScreen.kt              // onboarding nuovo / recupera account
      home/
        HomeScreen.kt              // lista cartelle
        FolderCard.kt
        GhostCard.kt               // collega nuovo link MEGA
      folder/
        FolderScreen.kt            // lista tracce
        TrackCard.kt               // card con waveform, play, commenti
        SortBar.kt
      player/
        MiniPlayer.kt
        LockScreenNotification.kt
      account/
        AccountSheet.kt
      theme/
        Theme.kt                   // colori, typography (dal prototipo)
        Color.kt
  res/
    values/strings.xml             // tutte le stringhe in italiano
```

---

## Dipendenze

Il progetto usa il version catalog (`gradle/libs.versions.toml`) per i plugin.
Le dipendenze sono dichiarate in `app/build.gradle.kts`.

**Plugin** (gia configurati in entrambi i `build.gradle.kts` tramite alias):
- `com.google.gms.google-services` (v4.4.2)
- `com.google.devtools.ksp` (v2.0.21-1.0.28)

**Librerie** (gia presenti nel blocco `dependencies`):
- Compose (via BOM), Material3, Navigation, Media3/ExoPlayer, Coroutines, Lifecycle
- Firebase BOM 33.3.0 + Firestore KTX + Auth KTX
- Room runtime + KTX + compiler (via KSP)
- OkHttp 4.12.0 + Gson 2.11.0 (per MEGA HTTP API)
- security-crypto 1.1.0-alpha06 (per EncryptedSharedPreferences)

> **Nota media3:** il progetto ha `media3-exoplayer:1.2.0` e `media3-session:1.2.0`.
> Aggiungere `media3-ui:1.2.0` solo se si usa `PlayerView` XML;
> con Compose puro non è necessario.

---

## Ordine di implementazione consigliato

1. **Setup progetto** — verificare `applicationId` in `app/build.gradle.kts`,
   aggiungere `google-services.json` quando Firebase è configurato,
   aggiungere le dipendenze.
2. **Theme** — tradurre i CSS custom properties del prototipo in `Color.kt` e
   `Theme.kt` Compose, incluso il tema scuro.
3. **Identità e Gate** — `IdentityManager`, `EncryptedSharedPreferences`,
   `GateScreen` (nuovo + recupera account), Firebase Anonymous Auth in background.
4. **UI Home e Folder** — `HomeScreen`, `FolderScreen`, `TrackCard` con dati
   mock statici. Nessun audio, nessuna rete ancora.
5. **Firestore — pull** — `FirestoreRepository` in lettura: cartelle, tracce,
   commenti. Visualizzazione nell'UI con dati reali.
6. **Commenti — scrittura locale** — `AddBox` in Compose, salvataggio in Room,
   badge pending/error nell'UI.
7. **Tasto Sincronizza — push** — upload da Room a Firestore, gestione errori,
   log pending nel bottom sheet.
8. **MEGA HTTP API** — `MegaApi.kt`, `MegaCrypto.kt`, lista file, creazione
   tracce su Firestore.
9. **ExoPlayer — streaming** — `PlayerManager`, play da URL MEGA, mini-player,
   gestione scadenza URL.
10. **Download locale** — `DownloadManager`, `TracceDownload` Room, switch
    automatico locale/streaming.
11. **Waveform** — generazione falsa-deterministica come default, `WaveformGenerator`
    con `MediaCodec` per la versione reale post-download.
12. **Offline robustezza** — test scenari senza rete, banner offline, messaggi chiari.

---

## Cosa non è in scope per la v1

- Notifiche push quando un altro membro commenta.
- Real-time listener (i commenti non si aggiornano da soli mentre sei nell'app).
- Login con account reale (Google, email).
- Allegati e file extra oltre all'audio.
- Controllo accessi granulare lato Firestore.
- Paginazione commenti (si caricano tutti in una volta — per 5 persone va bene).

---

## Cosa manca ancora prima di iniziare a buildare

- [x] `google-services.json` in `/app/` (presente in locale, escluso da .gitignore)
- [x] Plugin `google-services` e `ksp` aggiunti in entrambi i `build.gradle.kts`
- [x] Dipendenze Firebase, Room, OkHttp, Gson, security-crypto aggiunte
- [x] Permessi INTERNET e FOREGROUND_SERVICE nel Manifest

---

## Riferimenti

- Prototipo UI: `prova-app-v3-integrata.html` (spec grafica 1:1)
- Repository: `https://github.com/DrFonzarelli/RegiStrazio`
- Firebase Console: `https://console.firebase.google.com`
- MEGA API (community docs): `https://mega.py.readthedocs.io/en/latest/api.html`
- ExoPlayer Media3: `https://developer.android.com/guide/topics/media/exoplayer`
- Android Auto Backup: `https://developer.android.com/guide/topics/data/autobackup`
