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

### Regola di base: dove vive ogni cosa

Prima dello schema, il principio che regge tutto il resto:

> **L'audio non passa mai da Firestore.** Un file audio sta su MEGA, oppure sul
> telefono, oppure in entrambi i posti. Firestore tiene tutto ciò che audio non
> è: chi siamo, quali cartelle sono collegate, i commenti, i voti, i contatori.

| Cosa | Dove vive | Perché |
|---|---|---|
| File audio | MEGA, e sul telefono se scaricato | MEGA è già il posto dove il gruppo tiene le prove |
| Metadati traccia (titolo, durata, waveform, ascolti) | Firestore | leggeri, servono a tutti anche senza scaricare l'audio |
| Commenti | Firestore | sono il motivo per cui l'app esiste |
| Voti a stella | Firestore | devono vedersi fra i membri del gruppo |
| Cartelle collegate e link MEGA | Firestore | così basta che uno colleghi la cartella e la vedono tutti |
| Profili (nome, colore) | Firestore | servono per attribuire i commenti |
| Identità propria (`appUid`) | Solo sul telefono | in `EncryptedSharedPreferences`, mai in rete |
| Elenco dei file scaricati in locale | Solo sul telefono | in Room; è una scelta di questo telefono, non del gruppo |

Firestore è un database di documenti, non un archivio di file: metterci dentro
l'audio sarebbe costoso e fuori dal suo scopo. MEGA fa già quel lavoro.

**Attenzione al nome:** la collection Firestore si chiama `tracce/`, ma un
documento lì dentro **non contiene audio**. È un cartellino segnaletico: porta
`idFileMega`, cioè il riferimento con cui andare a prendere il file vero su
MEGA. Quando in questo documento si legge "traccia", il significato dipende dal
contesto — il documento di metadati, oppure il file audio. Non sono la stessa
cosa e non stanno nello stesso posto.

### Ciclo di vita di una traccia

Come si incastrano i tre pezzi, dall'inizio alla fine:

1. **Collegamento** — qualcuno incolla il link di una cartella MEGA. L'app
   interroga MEGA, si fa dare l'elenco dei file e scrive un documento in
   `tracce/` per ognuno. Il link finisce in `cartelle/`, così anche gli altri
   quattro se la ritrovano senza incollare niente: **una persona sola collega
   la cartella, e da lì in poi la vedono tutti**.

   Gli altri non rileggono MEGA per sapere cosa c'è dentro — quello lo dice
   `tracce/`. MEGA lo contattano solo quando premono play, per farsi dare
   l'indirizzo dei byte di *quel* file.

   Va detto chiaramente: il link contiene la chiave di decrittazione, quindi
   metterlo in `cartelle/` significa che chiunque possa leggere quel documento
   può ascoltare l'audio. Per cinque persone che condividono già la cartella è
   esattamente il comportamento voluto, ma è una scelta, non un dettaglio.
2. **Ascolto in streaming** — al play l'app chiede a MEGA un URL temporaneo per
   quel file e lo passa a ExoPlayer. Niente resta sul telefono. Se l'URL scade a
   metà ascolto se ne chiede un altro e si riprende dallo stesso punto.
3. **Download** — l'utente decide esplicitamente di scaricare. Il file va in
   `cacheDir/audio/` e viene registrato in Room.
4. **Ascolto in locale** — da quel momento il play usa il file sul telefono e
   non tocca la rete. Il passaggio è automatico: decide la presenza del record
   in Room, non l'utente.
5. **Rimozione** — "Rimuovi dal telefono" cancella file e record. Il play
   successivo torna in streaming da solo.

In tutti e cinque i passaggi, commenti e voti stanno su Firestore e non
cambiano: si possono leggere e scrivere anche su una traccia mai scaricata.

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

tracce/{tracciaId}         // SOLO metadati: l'audio sta su MEGA, mai qui
  cartellaId: string
  nomeFile: string
  idFileMega: string       // node handle: con questo si va a prendere il file su MEGA
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
- `g` → URL temporaneo da cui scaricare i byte
- `s` → dimensione file in bytes
- `at` → attributi cifrati (nome file, se non già decriptato)

Questo URL **scade** (tipicamente entro poche ore). Non cacharlo tra sessioni.
Richiederlo fresco ogni volta prima di fare play o avviare un download.

#### 3. I byte che arrivano da quell'URL sono cifrati

> **Questo è il punto in cui è più facile sbagliare.** L'URL del comando `g`
> **non** è un link a un file audio riproducibile. MEGA cifra i file lato client
> e quell'URL serve i byte cifrati così come sono. Puntarci ExoPlayer non
> produce audio, produce rumore.

Ogni file va decifrato con **AES-128-CTR**, usando chiave e nonce ricavati dalla
chiave del nodo (32 byte, decifrata con la chiave della cartella):

```
chiave AES = primi 16 byte  XOR  secondi 16 byte
nonce      = byte 16..23
blocco IV  = nonce (8 byte) || indice del blocco (8 byte, big-endian)
```

CTR è una scelta fortunata: è **seekabile**. Per far partire la decifratura dal
byte `N` non serve leggere tutto quello che viene prima, basta impostare
l'indice del blocco a `N / 16` e scartare i primi `N % 16` byte. È esattamente
ciò che rende possibile lo streaming con la barra di avanzamento trascinabile.

In pratica serve un `DataSource` di Media3 che avvolge quello HTTP e decifra il
flusso mentre scorre, passando a ExoPlayer byte già in chiaro. ExoPlayer non
sa e non deve sapere che dietro c'è MEGA.

Lo stesso vale per il **download locale**: il file che arriva è cifrato e va
decifrato prima di essere salvato in `cacheDir/audio/`. Una volta su disco è un
file audio normale, quindi la riproduzione locale non passa dal `DataSource`
speciale.

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
    → ExoPlayer legge da quell'URL attraverso il DataSource che decifra AES-CTR
```

Non esiste pre-scarico parziale: o si streamma o si usa il file locale.

Nota: il ramo locale legge un file già in chiaro e non usa il DataSource
speciale. Solo il ramo streaming decifra, perché solo lì i byte arrivano
cifrati da MEGA (vedi *I byte che arrivano da quell'URL sono cifrati*).

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
- `com.google.gms.google-services`
- `com.google.devtools.ksp`

**Librerie** (gia presenti nel blocco `dependencies`):
- Compose (via BOM), Material3, Navigation, Media3/ExoPlayer, Coroutines, Lifecycle
- Firebase BOM + Firestore + Auth
- Room runtime + KTX + compiler (via KSP)
- OkHttp + Gson (per MEGA HTTP API)
- security-crypto (per EncryptedSharedPreferences)

> I numeri di versione **non** sono elencati qui: si disallineano appena si
> aggiorna qualcosa. La tabella autorevole è in
> [Memoria delle versioni](#memoria-delle-versioni), tenuta in pari con
> `gradle/libs.versions.toml` e `app/build.gradle.kts`.

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

## Diario di implementazione

Da qui in giù non c'è progetto, c'è **memoria di lavoro**: cosa gira davvero,
cosa è ancora finto, quali errori abbiamo già preso e perché. Serve a non
ridiscutere due volte le stesse cose e a non ripetere gli stessi sbagli.

Le sezioni sopra descrivono il progetto **come dovrà essere**. Queste
descrivono il progetto **com'è adesso**. Quando le due cose divergono, ha
ragione questa parte.

---

### Memoria delle versioni

Aggiornata al commit `dd819f5`. Fonte di verità: `gradle/libs.versions.toml`
e `app/build.gradle.kts` — se modifichi lì, aggiorna anche qui.

| Cosa | Versione | Dove |
|---|---|---|
| Android Gradle Plugin | 9.0.1 | catalog `agp` |
| Kotlin | 2.0.21 | catalog `kotlin` |
| KSP | 2.0.21-1.0.28 | catalog `ksp` |
| google-services | 4.5.0 | catalog `googleServices` |
| Compose BOM | 2024.09.00 | catalog `composeBom` |
| Firebase BOM | 34.17.0 | catalog `firebaseBom` |
| core-ktx | 1.18.0 | catalog `coreKtx` |
| lifecycle (runtime + viewmodel-compose) | 2.10.0 | catalog `lifecycleRuntimeKtx` |
| activity-compose | 1.13.0 | catalog `activityCompose` |
| Room | 2.6.1 | hardcoded in `app/build.gradle.kts` |
| Media3 / ExoPlayer | 1.2.0 | hardcoded |
| navigation-compose | 2.7.7 | hardcoded |
| kotlinx-coroutines-android | 1.7.1 | hardcoded |
| OkHttp | 4.12.0 | hardcoded |
| Gson | 2.11.0 | hardcoded |
| security-crypto | 1.1.0-alpha06 | hardcoded |

**SDK e toolchain:** `compileSdk` 36 (minor 1) · `minSdk` 26 · `targetSdk` 36 ·
Java/JVM target 17.

**Versioni risolte a runtime dalle BOM** (utili quando si cerca documentazione,
perché la BOM non le mostra): Firestore `26.5.0`, Auth `24.2.0`.

---

### Stato reale del lavoro

**Legenda:** ✅ fatto e funzionante · 🟡 c'è ma è una finzione da sostituire ·
❌ non esiste ancora

| Area | Stato | Nota |
|---|---|---|
| Design system (colori, tema chiaro/scuro, icone, raggi) | ✅ | `ui/theme/` |
| Modelli dati (`Utente`, `Cartella`, `Traccia`, `Commento`) | ✅ | `data/model/` |
| Gate / onboarding | ✅ | crea account + recupera profilo |
| Home + cartelle + ghost card | ✅ | |
| Folder screen, TrackCard, timeline, commenti, voti | ✅ | tutta l'UI del prototipo |
| Mini player | ✅ | logica di scollegamento inclusa |
| Foglio account + strumenti di test | ✅ | |
| Identità persistente (`appUid`) | ✅ | `EncryptedSharedPreferences` con fallback |
| Riproduzione audio da MEGA | ✅ | **provata**: audio, seek dai commenti, pausa/riprendi |
| Riproduzione tracce demo | 🟡 | restano sul timer finto: non hanno un file dietro |
| Collegamento di una cartella MEGA | ✅ | **provato su una cartella vera**; ricollegare la stessa cartella la ricarica |
| Durata delle tracce da MEGA | 🟡 | arriva al primo play di *quella* traccia; le altre restano `--:--` |
| Nome della cartella letto da MEGA | ✅ | risolto provando tutti i nodi non-file, vedi errore 7 |
| Waveform | 🟡 | equalizzatore animato decorativo, nessun dato reale |
| Persistenza profili e cartelle | 🟡 | `ProfiliStore` = SharedPreferences + Gson, sta al posto di Firestore |
| Firestore | ❌ | dipendenza presente, **mai importata** nel codice |
| Firebase Anonymous Auth | ❌ | mai inizializzata |
| Room (entity, DAO, database) | ❌ | dipendenza + KSP configurati, **zero classi scritte** |
| MEGA HTTP API + crypto | 🟡 | elenco e decifratura **verificati sul campo**; manca lo scarico dei byte |
| Tasto Sincronizza | ❌ | |
| Banner offline | ❌ | |
| Download reale su disco | ❌ | il tasto cambia solo un'icona: non scarica niente |

**Attenzione:** tutto ciò che è 🟡 o ❌ vive **solo in memoria**. Chiudendo l'app
si perde tutto tranne l'identità e ciò che sta in `ProfiliStore`.

Detto in modo diretto, perché è la domanda che viene naturale fare:
**il link MEGA ora collega davvero** (l'app interroga MEGA e mostra i nomi veri
dei file, decifrati con la chiave del link), ma **i commenti non vanno ancora su
Firestore** (restano in RAM, anche se il toast dice "Commento salvato") e
**il tasto play non produce audio** (avanza un contatore ogni 250 ms).

#### Bug aperto: le tracce delle cartelle collegate non vengono ricaricate

Nell'`init` di `AppViewModel` le cartelle vengono ripristinate da `ProfiliStore`,
le tracce no:

```kotlin
cartelle = DemoData.cartelle + profiliStore.cartelle(),
tracce   = DemoData.tracce   // <- le tracce finte generate al collegamento sono perse
```

*Effetto visibile:* colleghi una cartella, compaiono le tracce lette da MEGA,
chiudi e riapri l'app — la cartella è ancora in elenco ma segna "0 tracce" ed è
vuota dentro.

*Nota:* si risolve quando le tracce arriveranno da Firestore, che è il posto
dove devono stare. Rileggerle da MEGA a ogni avvio sarebbe lento e inutile: MEGA
serve per i byte dell'audio, non come elenco da riscaricare ogni volta. Finché
Firestore non c'è, **questa incoerenza è attesa** — non è un errore nuovo da
investigare.

**Stato della build:** l'ultima build ha superato `kspDebugKotlin`,
`processDebugGoogleServices` e tutto il packaging delle risorse, e si è fermata
in `compileDebugKotlin` su due errori in `GateScreen.kt`. Quegli errori sono
corretti in `dd819f5`, **ma una build completamente verde non è ancora stata
osservata**. Finché non lo è, non dare per scontato che compili.

---

### Errori già incontrati — non ripeterli

#### 1. Gli artefatti Firebase `-ktx` non esistono più dalla BOM 34

*Sintomo:* `Could not find com.google.firebase:firebase-firestore-ktx:` — con i
due punti finali e **nessuna versione dopo**.

*Causa:* dalla BOM 34 i moduli `-ktx` sono stati rimossi e le estensioni Kotlin
sono state assorbite nei moduli principali. La BOM non conosce più quei nomi,
quindi non assegna nessuna versione.

*Fix:* usare `firebase-firestore` e `firebase-auth` senza suffisso.

*Da ricordare:* la versione vuota dopo i due punti è la firma di questo tipo di
problema. Vuol dire "la BOM non gestisce questo artefatto", non "la rete non
funziona" — e vale per qualsiasi BOM, non solo Firebase.

#### 2. Lambda finale che finisce nel parametro sbagliato

*Sintomo:* due errori appaiati sulla stessa riga —
`No value passed for parameter 'onClick'` **e**
`actual type is 'Function0<Unit>', but 'Modifier' was expected`.

*Causa:* in Kotlin `Foo("x") { ... }` mette la lambda nell'**ultimo** parametro.
I bottoni di questo progetto hanno la firma `(testo, onClick, modifier)`, quindi
la graffa finiva in `modifier`.

*Fix:* passare `onClick` per nome: `GateActionButton("x", onClick = { ... })`.

*Da ricordare:* la coppia di errori "parametro mancante + Function0 dove serve
Modifier" è sempre questo. In `Common.kt` `onClick` viene **prima** di
`modifier`: o si passa tutto posizionalmente, o si nomina `onClick`.

#### 3. Moduli `lifecycle` disallineati

*Causa:* `lifecycle-viewmodel-compose` era fissato a 2.7.0 mentre
`lifecycle-runtime-ktx` stava a 2.10.0. Sono moduli diversi, quindi Gradle non
li unifica, e ci si ritrova due `lifecycle-viewmodel` diversi nel classpath.

*Fix:* entrambi dal catalog con lo stesso `version.ref`.

*Da ricordare:* le famiglie di librerie che si versionano in blocco (lifecycle,
Room, Media3, Compose) vanno tenute su **un solo** riferimento di versione.

#### 4. Dipendenze Compose duplicate

*Causa:* le stesse librerie dichiarate sia con versione esplicita sia tramite
BOM. *Fix:* mai scrivere una versione per ciò che la BOM già gestisce.

#### 5. Funzioni private che oscuravano quelle di Compose

*Causa:* helper privati chiamati `remember()` e `borderStroke()` che entravano
in conflitto con le funzioni standard di Compose, con errori di risoluzione
molto poco leggibili.

*Da ricordare:* non riusare nomi del vocabolario Compose (`remember`, `border`,
`clickable`, `background`…) per helper propri.

#### 6. `.idea/` finita nel repository

*Causa:* i file erano già tracciati **prima** che `.gitignore` li escludesse, e
`.gitignore` non ha effetto su ciò che git già segue.

*Fix:* `git rm --cached -r .idea/`, poi commit.

*Da ricordare:* `.gitignore` non è retroattivo. Se un file ignorato compare
ancora nei cambiamenti, va tolto dall'indice a mano.

#### 7. La radice di una cartella condivisa non e' `t = 2`

*Sintomo:* la cartella si collegava ma restava chiamata "Cartella A6kViD"
invece di prendere il nome vero da MEGA.

*Causa:* cercavo il nodo radice fra quelli con `t = 2`. Quel tipo e' la radice
dell'**account** e in una risposta su link pubblico non compare proprio: la
radice della cartella condivisa e' un nodo cartella normale, riconoscibile
perche' il suo `h` coincide con l'id del link.

*Secondo motivo, indipendente:* `ProfiliStore.registraCartella` usciva subito se
la cartella era gia' salvata, quindi il nome non veniva mai aggiornato. Anche
sistemata la radice, al riavvio sarebbe tornato il nome di ripiego.

*Da ricordare:* quando un dato sbagliato **sopravvive a un fix**, i motivi sono
due e vanno cercati entrambi — uno che lo produce e uno che lo conserva.

*Come è finita:* servite tre versioni. Le prime due indovinavano quale fosse il
nodo radice e sbagliavano; la terza ha smesso di indovinare e prova a decifrare
gli attributi di **tutti** i nodi non-file, tenendo il primo che funziona.

Il log dal telefono ha poi detto com'è fatta davvero la radice di una cartella
condivisa, e vale la pena averlo scritto perché è controintuitivo:

- ha **`t = 1`**, come una cartella qualsiasi — `t = 2` è la radice
  dell'*account* e su link pubblico non compare proprio, non essendo loggati
- il suo handle **non è** l'id che sta nel link. Quell'id è un handle di
  *condivisione*, una cosa diversa dal nodo
- **ha un genitore**, quindi nemmeno "il nodo senza `p`" la identifica
- i suoi attributi si decifrano con la chiave presa dal suo campo `k`, come
  quelli di ogni altro nodo

*Da ricordare:* dopo due ipotesi sbagliate di fila, smettere di indovinare e
misurare. Qui sono bastate cinque righe di log per chiudere una cosa che aveva
già bruciato due giri di prove.

#### 8. Il cursore che sfarfallava tornando a inizio traccia

*Sintomo:* trascinando il cursore sulla timeline, questo spariva e ricompariva
di continuo all'inizio della traccia, rendendo impossibile mirare un punto.

*Due cause sovrapposte, come al solito:*

1. **Un anello che si retroalimentava.** La nuova posizione veniva calcolata
   dalla posizione del cursore, che il trascinamento stesso stava aggiornando.
   Il riquadro si sposta insieme al cursore, quindi leggerne la posizione mentre
   lo si trascina si morde la coda.
2. **Un seek per ogni movimento del dito.** Il player ribufferizzava in
   continuazione, e nel frattempo il ciclo di aggiornamento riscriveva la
   posizione con quella del player — vicina a zero proprio perché stava
   ricaricando.

*Fix:* durante il trascinamento comanda il dito. Si somma lo spostamento
(`delta`) invece di ricalcolare dalla posizione, e il seek parte **una sola
volta**, quando si stacca il dito.

*Da ricordare:* quando un gesto muove l'elemento che il gesto stesso sta
misurando, va sempre usata la somma degli spostamenti, mai la posizione
assoluta. E un `pointerInput` legge per sempre i valori della composizione in
cui è nato: quelli che cambiano vanno passati con `rememberUpdatedState`.

#### 9. Warning KSP sui source set Kotlin

*Fix applicato:* `android.disallowKotlinSourceSets=false` in `gradle.properties`.

*Da ricordare:* è una **toppa temporanea**. La build lo dichiara esplicitamente
sperimentale e avvisa che il default è ormai `true`. Andrà rimossa quando KSP e
AGP si allineeranno; se un giorno l'opzione sparisce, il warning va risolto
davvero, non silenziato.

---

### Trappole del progetto da tenere a mente

**`google-services.json` non è nel repository, ed è voluto.** Sta solo in
locale. Conseguenza pratica: **chi clona il repo da zero non riesce a
buildare** finché non se lo procura dalla console Firebase. Non è un bug, ma va
detto a chiunque si aggiunga al progetto.

**I colori non passano da Material3.** Il tema usa `AppColors` con
`CompositionLocalProvider` e si legge con `AppTheme.colors`. Usare
`MaterialTheme.colorScheme` porta a colori che non c'entrano nulla con il
prototipo. Lo schema M3 è tenuto al minimo apposta.

**Le icone sono path SVG del prototipo** interpretati da `PathParser` in
`Icons.kt`, non risorse vettoriali Android. Per aggiungerne una si copia la path
dall'HTML, non si importa un file.

**Cose che il prototipo fa e che Compose non ha già pronte** — sono già
risolte, non riprogettarle:
- bordo tratteggiato → `drawBehind` + `PathEffect.dashPathEffect`
  (`HomeScreen.kt`), perché non esiste un `border-style: dashed`
- mezza stella → `clipToBounds` su un contenitore da 6dp sopra la stella piena
- comparsa/scomparsa del mini player → logica `barraCollegata` in `AppRoot.kt`,
  che replica lo "scollegamento" del prototipo

**La sort bar occupa l'indice 0 della lista.** Ogni volta che si converte la
posizione di una traccia in indice della `LazyColumn` serve `+1`. Sbagliarlo non
dà errore di compilazione: fa scrollare sulla traccia sbagliata.

---

### Debito noto

Cose consapevolmente lasciate indietro, da affrontare quando conviene:

- **Compose BOM da alzare.** La BOM 2024.09.00 fissa `compose-runtime` alla
  linea 1.7, ma la build risolve **1.9.0**, tirato su da lifecycle 2.10.0. Non
  è rotto, però la BOM di fatto non comanda più sul runtime: tanto vale portarla
  a una versione coerente con lifecycle.
- **`navigation-compose` è dichiarata e mai usata.** La navigazione è fatta a
  mano con la sealed interface `Schermata`. O si toglie la dipendenza, o si
  decide di adottarla davvero.
- **Room e OkHttp sono dichiarate e mai usate.** Legittimo finché arrivano le
  fasi che le richiedono; `kspDebugKotlin` intanto gira a vuoto perché non c'è
  nessuna annotazione da processare.
- **Versioni sparse.** Room, Media3, OkHttp, Gson, coroutines e security-crypto
  sono ancora scritte a mano in `app/build.gradle.kts` invece che nel catalog.
- **`applicationId` è ancora `com.example.registrazio`**, il default di Android
  Studio. Va cambiato **prima** di qualunque pubblicazione, e cambiarlo dopo
  aver configurato Firebase richiede di rigenerare `google-services.json`.
- **`security-crypto` è una alpha.** `1.1.0-alpha06` regge l'identità utente,
  che è la cosa più delicata dell'app. Da tenere d'occhio.

---

## Riferimenti

- Prototipo UI: `prova-app-v3-integrata.html` (spec grafica 1:1)
- Repository: `https://github.com/DrFonzarelli/RegiStrazio`
- Firebase Console: `https://console.firebase.google.com`
- MEGA API (community docs): `https://mega.py.readthedocs.io/en/latest/api.html`
- ExoPlayer Media3: `https://developer.android.com/guide/topics/media/exoplayer`
- Android Auto Backup: `https://developer.android.com/guide/topics/data/autobackup`
