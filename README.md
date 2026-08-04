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

**Riferimento visivo obbligatorio:** `prova-app-v3-integrata.html`, nella radice
del repository, è il prototipo funzionante dell'intera UI. Claude Code deve usarlo come spec grafica 1:1 —
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
| Download locale         | OkHttp + decifratura AES-CTR (vedi errore 13)    |
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

E una seconda regola, altrettanto vincolante:

> **Il telefono è la fonte di verità, Firestore è la destinazione.** Tutto ciò
> che l'utente fa viene scritto in Room **all'istante**, con lo stato "da
> caricare". Firestore lo vede solo quando qualcuno preme Sincronizza, ed è
> anche l'unico momento in cui si leggono le modifiche degli altri.
>
> Conseguenza da cui non si scappa: **chiudere l'app non può far perdere
> niente**. Nemmeno un commento scritto un secondo prima, nemmeno l'elenco
> tracce di una cartella appena collegata. Se un dato sparisce alla chiusura,
> è un bug, non uno stadio intermedio.

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
| Chiavi AES dei file MEGA | Solo sul telefono | in Room, per poter premere play senza rileggere MEGA. Su Firestore va `idFileMega`, mai la chiave |
| **Copia locale di tutto quanto sopra** | Room, sempre | è da lì che l'app legge; Firestore si tocca solo sincronizzando |

Firestore è un database di documenti, non un archivio di file: metterci dentro
l'audio sarebbe costoso e fuori dal suo scopo. MEGA fa già quel lavoro.

**Attenzione al nome:** la collection Firestore si chiama `tracce/`, ma un
documento lì dentro **non contiene audio**. È un cartellino segnaletico: porta
`idFileMega`, cioè il riferimento con cui andare a prendere il file vero su
MEGA. Quando in questo documento si legge "traccia", il significato dipende dal
contesto — il documento di metadati, oppure il file audio. Non sono la stessa
cosa e non stanno nello stesso posto.

### Come arriva un link nell'app

Tre strade, in ordine di comodità:

1. **Condivisione da MEGA** — "Ottieni link" → "Condividi" → RegiStrazio.
   L'app si apre con la ghost card già compilata. È la via breve.
2. **Tasto MEGA nella ghost card** (☁) — apre l'app MEGA, o il sito se non c'è.
   Serve a chi parte da RegiStrazio e non ha ancora il link.
3. **Incolla a mano** — sempre disponibile.

Due regole che valgono per tutte e tre:

- **Il collegamento non parte mai da solo.** Chiunque può condividere testo
  verso l'app: il link viene messo nel campo e la conferma resta un gesto
  dell'utente. Una cartella che si aggiunge da sé sarebbe difficile perfino da
  spiegare a chi la vede comparire.
- **Il link va pescato dal testo.** MEGA non condivide l'indirizzo nudo ma una
  frase intorno, e passando per una chat può raccogliere punteggiatura. Se ne
  occupa `LinkMega.cercaNelTesto`, coperta da test JVM.

### Il riempimento di avanzamento: dov'è nel prototipo e dove no

Nel prototipo la barra che si riempie esiste **solo** sul tasto "Scarica tutte"
(`.bulk-dl-fill`: `width` da 0 a 100%, `background: var(--accent-soft)`,
`z-index: 0` sotto icona ed etichetta). L'indicatore della traccia singola
(`.dl-indicator`) è **solo un'icona** che compare quando il file c'è: nel
prototipo `downloaded` era un booleano che si girava all'istante, non c'era
niente da attendere.

Ora che il download è reale e dura, la traccia singola mostra la **percentuale**
accanto all'icona — un numero, non una barra. Provata anche la versione con il
riempimento dietro la card, ed è stata tolta: su una card alta e piena di
contenuto quel movimento distrae senza aggiungere niente che il numero non dica
già. Il riempimento resta dov'era nel prototipo, sul tasto "Scarica tutte".

Per lo stesso motivo **la card di una traccia che sta scaricando non cambia
bordo**: il bordo accent vuol dire "questa è la traccia che stai ascoltando", e
si può benissimo scaricarne una mentre se ne ascolta un'altra. Due card accese
per due motivi diversi dicono meno di una accesa per un motivo solo.

L'icona accanto alla percentuale dice **cosa succede se la tocchi**, non in che
stato sei: ⏸ mentre scarica, ▶ quando è ferma. Non è mai una ✕ — non c'è niente
da annullare, il pezzo già scaricato resta.

Quella barra però non deve avanzare **a scatti di una traccia**: con download
veri resterebbe ferma per decine di secondi e poi salterebbe. Somma anche la
frazione dei download in corso, quindi scorre di continuo restando una misura
vera.

**Regola su questo genere di indicatori:** una barra che si riempie promette una
misura. Va usata dove una misura c'è — byte su byte, file su file — e mai dove
si potrebbe solo far scorrere un'animazione a tempo. Per la lettura di una
cartella MEGA, che è una chiamata sola, non c'è niente da misurare: lì non si
mette nessuna barra.

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
@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val tracciaId: String,
    val percorso: String,               // path assoluto in cacheDir/audio/
    val dimensioneByte: Long,
    val scaricatoIl: Long
)
// Nessuno StatoSync: il download è una scelta di questo telefono e non
// riguarda il gruppo, quindi non c'è niente da sincronizzare.

enum class StatoSync { LOCALE, SINCRONIZZATO, DA_ELIMINARE, ERRORE }
```

**Perché questi quattro stati.** `LOCALE` copre sia "creato qui" sia "modificato
qui": l'upload riscrive il documento intero, quindi non serve distinguerli, e
modificare una riga già sincronizzata la riporta semplicemente a `LOCALE`.

`DA_ELIMINARE` esiste perché una riga cancellata **non può sparire subito** se
era già su Firestore: resta in tabella, nascosta all'interfaccia, finché la
sincronizzazione non l'ha tolta anche dall'altra parte. Cancellarla e basta
significherebbe vedersela ricomparire al giro dopo. Una riga mai caricata,
invece, si cancella davvero — non c'è niente da dire a nessuno.

**Il contatore conta documenti, non gesti.** Collegare una cartella da 5 tracce
porta il conteggio a 6: un documento cartella più cinque documenti traccia.
Mettere una stella su una di quelle tracce **non lo fa salire a 7**, perché quella
traccia era già in attesa: quando la sincronizzazione partirà, caricherà lo
stesso identico documento, solo con la stella dentro. Dieci ritocchi allo stesso
commento restano un commento da caricare.

È la cosa giusta da mostrare — è il lavoro che la sincronizzazione dovrà fare —
ma va detta bene: la prima versione scriveva "7 modifiche", che si legge come un
numero di gesti. Ora è spezzato per tipo ("1 cartella, 5 tracce, 1 commento"),
che si spiega da sé.

**Cosa resta fuori dall'archivio.** Le cartelle e le tracce di `DemoData` non
entrano mai in Room: le prime hanno `linkMega` vuoto, le seconde `idFileMega`
vuoto, e sono quei due campi a fare da criterio. Attenzione a leggerlo bene —
il criterio è **"c'è un file MEGA dietro"**, non "l'ho collegata io". Una
cartella collegata da un altro membro e arrivata via Firestore avrà il suo link
e le sue tracce reali, quindi si salverà come qualsiasi altra.

Seminare la demo in archivio "solo per provare" sarebbe una cattiva idea: quelle
righe nascerebbero `LOCALE`, e alla prima sincronizzazione finirebbero su
Firestore, cioè addosso a tutti e cinque, con tracce che non esistono.

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

Flusso reale (`data/remote/ScaricatoreMega.kt`):
1. Richiedere URL temporaneo MEGA per la traccia.
2. Scaricare con OkHttp **decifrando in corsa** (AES-CTR) dentro
   `cacheDir/audio/{tracciaId}.audio.parziale`.
3. A file completo, rinominare in `{tracciaId}.audio` e registrare la riga in
   `Room.download`.
4. La track card passa dalla percentuale all'icona di file locale.

> **Non si usa il `DownloadManager` di sistema**, che pure una versione
> precedente di questo documento suggeriva: non sa niente di AES e salverebbe su
> disco byte cifrati, cioè un file che non suona.

**Pausa e ripresa.** Il download si può fermare e far ripartire da dove era:
- interrompere **non** cancella il `.parziale` — è tutto il senso della ripresa;
- alla ripresa si tronca il parziale al multiplo di 16 più vicino
  (`allineaABlocco`) e si chiede a MEGA solo il resto con `Range: bytes=N-`;
- l'IV di CTR per ripartire da `N` si calcola con `MegaCrypto.ivPerOffset`, la
  stessa funzione che rende possibile il seek in streaming;
- se il server risponde `200` invece di `206` (Range ignorato) il parziale si
  butta e si ricomincia: appendere tutto il file a un parziale lo raddoppierebbe.

**Chi mette in pausa cosa** — è il modello mentale, e va rispettato:

| Gesto | Effetto |
|---|---|
| ⏸ sulla percentuale di una traccia | ferma quella traccia **e** la coda "Scarica tutte", se c'era |
| ▶ sulla percentuale di una traccia | riprende **solo quella**; la coda resta ferma |
| tasto "Scarica tutte" durante il download | mette in pausa tutto |
| tasto "Scarica tutte" da fermo a metà | riprende la coda da dove era |

Il file a metà è **solo sul telefono**: non si rischia di pubblicare un file
troncato, e una traccia scaricata a metà continua a suonare in streaming da MEGA
finché non è completa.

**Eliminazione file locale:** menu "..." → "Rimuovi dal telefono". Elimina il file
fisico e il record Room. Al prossimo play torna lo streaming automaticamente — e
se la traccia era **in ascolto proprio in quel momento**, riparte da MEGA dallo
stesso punto senza che si senta niente (vedi errore 15).

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

### Il messaggio quando manca la linea

Una frase sola, uguale ovunque manchi la rete — play, download, coda "Scarica
tutte" — perché ripetuta identica si riconosce a colpo d'occhio:

> Sei senza rete. L'audio si ascolta da MEGA: scarica le tracce sul telefono
> quando hai linea e poi le hai anche offline.

Vive in `AppViewModel.SENZA_RETE` e la sceglie `spiegaErroreDiRete(e)`.

**Come si riconosce l'assenza di linea:** dal **tipo dell'eccezione**, scendendo
lungo la catena delle cause (`UnknownHostException`, `ConnectException`,
`SocketTimeoutException`, …). Non si legge lo stato della connessione: servirebbe
`ACCESS_NETWORK_STATE`, e comunque una rete "attiva" dietro un portale captive
fallisce esattamente come una assente — l'eccezione dice la verità, lo stato di
sistema no.

Perché questo funzioni, **chi incarta un errore deve conservarne la causa**:
`MegaException` ha un terzo parametro `cause` e `MegaApi` lo passa. Un `catch`
che ributta un messaggio senza la causa originale rende il messaggio offline
impossibile da distinguere da un errore qualsiasi di MEGA.

Stesso motivo per cui `PlayerMega.onErrore` passa il `Throwable` e non una
stringa già formattata: lì dentro non si sa se è la rete o il file, e a scegliere
le parole deve essere chi conosce il contesto.

Quando la coda "Scarica tutte" incontra l'assenza di rete **si ferma e resta in
pausa**, invece di provare le altre quattro tracce e sfilare quattro errori
identici.

### Indicatore offline nell'UI

Banner non bloccante sotto la topbar: *"Offline — dati dell'ultima
sincronizzazione"*. Sparisce quando torna la connessione. Nessun dialog.
**Non ancora implementato**; per ora il messaggio arriva come toast al momento
del gesto che fallisce, che è il momento in cui serve.

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

Fonte di verità: `gradle/libs.versions.toml`
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
| Room | 2.7.1 | catalog `room` |
| Media3 / ExoPlayer | 1.2.0 | hardcoded |
| navigation-compose | 2.7.7 | hardcoded |
| kotlinx-coroutines-android | 1.7.1 | hardcoded |
| OkHttp | 4.12.0 | hardcoded |
| Gson | 2.11.0 | hardcoded |
| security-crypto | 1.1.0-alpha06 | hardcoded |

**SDK e toolchain:** `compileSdk` 36 (minor 1) · `minSdk` 26 · `targetSdk` 36 ·
Java/JVM target 17.

> **Le versioni nel catalogo non sono quelle che girano davvero.** Il catalogo
> dice Kotlin 2.0.21 e KSP 2.0.21-1.0.28, ma i log di build mostrano che vengono
> scaricati `kotlin-compiler-embeddable-2.2.10` e
> `symbol-processing-aa-embeddable-2.2.10-2.0.2`: **AGP 9 porta con sé il proprio
> Kotlin e impone il KSP corrispondente**. Prima di dare la colpa a una versione
> scritta qui, guardare cosa scarica davvero il log — è così che si è capito
> l'errore 10.

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
| Condivisione da MEGA verso l'app | ✅ | **provata**: da app aperta e chiusa, e due volte con lo stesso link |
| Durata delle tracce da MEGA | 🟡 | arriva al primo play di *quella* traccia; le altre restano `--:--` |
| Nome della cartella letto da MEGA | ✅ | risolto provando tutti i nodi non-file, vedi errore 7 |
| Waveform | 🟡 | equalizzatore animato decorativo, nessun dato reale |
| Archivio locale (Room) | ✅ | **provato**: commenti, stelle e rinomine sopravvivono alla chiusura |
| Elenco profili per il recupero account | 🟡 | `ProfiliStore` = SharedPreferences + Gson, unico resto del cloud simulato |
| Firestore | ❌ | dipendenza presente, **mai importata** nel codice |
| Firebase Anonymous Auth | ❌ | mai inizializzata |
| MEGA HTTP API + crypto | ✅ | elenco, decifratura e scarico dei byte **verificati sul campo** |
| Tasto Sincronizza | ❌ | |
| Banner offline | ❌ | c'è il messaggio al gesto che fallisce, non il banner permanente |
| Download reale su disco | ✅ | **provato**: scarica, decifra, suona da locale, si rimuove |
| Pausa e ripresa del download | 🟡 | `.parziale` + `Range`: **compila, da provare sul telefono** |
| Riproduzione dal file locale | ✅ | **provata**, anche togliendo il file mentre suona |

Detto in modo diretto, perché sono le domande che vengono naturali:
**il link MEGA collega davvero**, l'audio si sente, **le tracce si scaricano
davvero** e da scaricate suonano dal telefono senza rete; **i commenti non
arrivano ancora su Firestore**, ma restano sul telefono in attesa invece di
sparire.

**Il bug delle tracce che sparivano alla chiusura è chiuso**, e con esso una mia
lettura sbagliata: l'avevo trattato come qualcosa che si sarebbe risolto da sé
con Firestore. Non era così — mancava la persistenza locale, che deve esistere
**prima** e **indipendentemente** da Firestore. Vedi la seconda regola in cima al
documento.

**Stato della build:** verde, e l'app gira su un telefono vero. L'ultima cosa
non ancora provata sul telefono è la pausa/ripresa dei download, arrivata dopo
l'ultima sessione di prova.

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

#### 10. Room 2.6.1 contro KSP2: `unexpected jvm signature V`

*Sintomo:* `kspDebugKotlin` fallisce con
`java.lang.IllegalStateException: unexpected jvm signature V`, appena si
aggiunge il primo DAO. Nessun riferimento a un file o a una riga.

*Causa:* la `V` è il tipo di ritorno `void`. Il processore di Room 2.6.1 è
precedente a KSP2 e inciampa sulle funzioni `suspend` che non restituiscono
niente — cioè quasi tutte quelle di scrittura di un DAO. Il supporto a KSP2
arriva con Room 2.7.

Il dettaglio che porta alla diagnosi non sta nel messaggio ma nelle righe di
download subito sopra: `symbol-processing-aa-embeddable-2.2.10-2.0.2`. È
KSP **2.2.10**, non il 2.0.21 scritto nel catalogo — AGP 9 impone il proprio.

*Fix:* Room a 2.7.1, spostata nel version catalog. Verificato: `kspDebugKotlin`
e `compileDebugKotlin` passano.

*Da ricordare:* un errore del processore di annotazioni senza file né riga è
quasi sempre un'incompatibilità di versione, non un errore nel codice. E la
versione da guardare è quella che il log **scarica**, non quella che il
catalogo dichiara.

#### 11. Da Android 11 le altre app sono invisibili se non le dichiari

*Sintomo (previsto, non ancora osservato):* il tasto MEGA apre il browser anche
su un telefono che ha l'app MEGA installata.

*Causa:* dalla 11, `getLaunchIntentForPackage` restituisce `null` per qualunque
pacchetto non elencato in `<queries>` nel manifest. Non è un errore, non è un
permesso negato: è la stessa risposta che si otterrebbe se l'app non ci fosse.

*Da ricordare:* se un giorno MEGA cambiasse il nome del pacchetto, il sintomo
sarebbe identico — e non ci sarebbe niente nei log a dirlo. Il ripiego sul
browser fa sì che il caso peggiore resti utilizzabile invece che rotto, ma
nasconde anche il problema: se il tasto apre il browser su un telefono che ha
MEGA, si guarda `<queries>` prima di guardare il codice.

#### 12. Ricaricare una cartella cancellava il lavoro dell'utente

*Sintomo:* dopo aver ricollegato lo stesso link per aggiornare una cartella,
le stelline erano sparite e i commenti avevano perso il loro punto sulla
timeline — c'erano, ma non si vedeva più a che minuto stavano.

*Causa:* le tracce venivano sostituite in blocco con quelle appena lette da
MEGA. Ma **MEGA sa solo tre cose**: come si chiama il file, quanto pesa e il
suo handle. Voti, rinomine, durata, ascolti e punti riascoltati sono roba
nostra, e ricostruire la traccia da capo li buttava via.

I commenti in realtà sopravvivevano — hanno una tabella loro — ma perdevano
il riferimento visivo, perché con `durataSecondi` azzerata la timeline non sa
più dove piazzare il marker. Una causa sola, due sintomi diversi.

*Fix:* la traccia esistente si riconosce dall'handle, che non cambia mai, e si
conserva. Da MEGA si riprende solo il titolo, e nemmeno quello se era stato
rinominato a mano.

*Da ricordare:* è **lo stesso errore** già fatto col nome della cartella, in
un altro punto del codice. La regola generale: quando si rilegge una sorgente
esterna, tutto ciò che quella sorgente non conosce va conservato, non
ricostruito. Vale per MEGA oggi e varrà per Firestore domani.

#### 13. Il `DownloadManager` di sistema non può scaricare da MEGA

*Dove stava scritto male:* questo stesso documento indicava il `DownloadManager`
di Android per il download locale.

*Perché non funziona:* il `DownloadManager` scarica e salva, e basta. Di AES non
sa niente, quindi metterebbe su disco i byte **cifrati** — un file che non
suona. È lo stesso motivo per cui lo streaming ha bisogno di un `DataSource`
suo: i byte di MEGA vanno sempre decifrati da noi.

*Fix:* `ScaricatoreMega` scarica con OkHttp e decifra mentre scrive. Quello che
finisce in `cacheDir/audio/` è un file audio normale, e il ramo di riproduzione
locale non deve sapere niente né di MEGA né di crittografia.

Due dettagli che valgono la pena:

- si scrive su un file `.parziale` e si rinomina solo alla fine. Un download
  interrotto non deve lasciare mezzo brano che al play successivo *sembra*
  completo;
- `cacheDir` e non `filesDir`: se il telefono ha bisogno di spazio è giusto che
  possa buttare l'audio, che si riscarica. Al play si controlla che il file ci
  sia ancora, e se non c'è si torna in streaming senza dire niente a nessuno.

#### 14. Un'azione senza un posto dove viverci non esiste

*Sintomo:* il download si poteva annullare — toccando di nuovo la stessa voce di
menu — ma nessuno poteva scoprirlo: la voce continuava a dire "Scarica in
locale" anche mentre scaricava.

*Da ricordare:* uno stato in più nel codice vuole quasi sempre uno stato in più
nell'interfaccia. Il menu ora ha tre voci al posto di due (scarica / interrompi
/ rimuovi), e la percentuale è diventata toccabile, perché è lì che uno cerca
il modo di fermare la cosa che sta guardando.

#### 15. Cancellare un file che il player sta leggendo

*Sintomo:* "Rimuovi dal locale" durante l'ascolto lasciava la traccia andare
ancora per un po', poi il player si perdeva e diceva di non trovare il file.
Bisognava cambiare traccia e tornare indietro per farla ripartire.

*Causa:* il file veniva cancellato mentre il player lo teneva aperto. Su Android
il descrittore resta valido finché non si chiude — da qui l'audio che continua
per un po' — ma alla prima lettura successiva non c'è più niente.

*Fix:* se la traccia da cui si sta togliendo il file è proprio quella in
ascolto, la riproduzione riparte da MEGA **dallo stesso punto**. Chi ascolta non
se ne accorge.

*Da ricordare:* prima di cancellare qualcosa, chiedersi chi altro lo sta
usando in questo momento.

#### 16. Warning KSP sui source set Kotlin

*Fix applicato:* `android.disallowKotlinSourceSets=false` in `gradle.properties`.

*Da ricordare:* è una **toppa temporanea**. La build lo dichiara esplicitamente
sperimentale e avvisa che il default è ormai `true`. Andrà rimossa quando KSP e
AGP si allineeranno; se un giorno l'opzione sparisce, il warning va risolto
davvero, non silenziato.

#### 17. `fillMaxHeight()` dentro un Box che non ha un'altezza

*Sintomo:* il riempimento del tasto "Scarica tutte" **non si vedeva affatto**.
Non pallido, non parziale: assente. Segnalato due volte prima che lo prendessi
sul serio.

*Causa:* il riempimento era un `Box` figlio con
`Modifier.fillMaxHeight().fillMaxWidth(frazione)`. `fillMaxHeight` significa
"prendi tutta l'altezza *disponibile*", e l'altezza disponibile la fissa il
genitore. Ma il genitore era un `Box` senza altezza propria, che la ricavava dal
contenuto: al momento del calcolo l'altezza disponibile era **zero**. Un
rettangolo largo il giusto e alto zero è invisibile, e il codice sembrava
corretto rileggendolo.

*Fix:* il riempimento si **disegna**, non si impagina —
`.drawBehind { drawRect(accentSoft, size = Size(size.width * frazione, size.height)) }`
sul Box esterno, dopo `.clip()` e `.background()`. `drawBehind` riceve la
dimensione del nodo **già impaginato**, che è esattamente la semantica di
`position:absolute; top:0; bottom:0` del prototipo.

*Da ricordare:* due cose diverse.
1. In Compose, `fillMax*` è una richiesta al genitore, non una promessa. Dentro
   un contenitore che si dimensiona sul contenuto, non vale niente. Se una cosa
   deve stare *dietro* al contenuto e coprirlo tutto, disegnala.
2. Quando qualcuno dice per la seconda volta che una cosa non si vede, il
   problema è mio, non della sua osservazione. La prima volta avevo risposto che
   era "già implementato come nel prototipo" — lo era nel codice, non sullo
   schermo. Il codice che sembra giusto e non si vede è codice sbagliato.

#### 18. Interrompere un download non deve voler dire buttarlo

*Sintomo:* fermare uno scaricamento e riaverlo faceva ripartire tutto da zero. E
mettendo in pausa una traccia dentro "Scarica tutte" la coda continuava per conto
suo, riscaricando a forza quella appena fermata.

*Causa:* "interrompi" era implementato come `cancel()` + cancellazione del file
parziale, e la coda non sapeva niente di quello che succedeva alle singole
tracce.

*Fix:* due pezzi.
- `ScaricatoreMega` **non cancella più il `.parziale`** quando il job viene
  interrotto; alla ripresa tronca a un confine di 16 byte e chiede il resto con
  `Range`. La ripresa è possibile grazie alla stessa proprietà di AES-CTR che
  rende possibile il seek: si può cominciare a decifrare da qualunque punto,
  purché si sappia da quale.
- Lo stato passa da `Float` a `StatoScaricamento(frazione, inPausa)`, e mettere
  in pausa una traccia mette in pausa **anche** la coda. Riprendere dalla traccia
  riprende solo quella; riprendere dal tasto in alto riprende tutto.

*Da ricordare:* "annulla" e "metti in pausa" sembrano la stessa cosa da
programmare e sono l'opposto per chi le usa. Prima di scrivere `cancel()`,
chiedersi se chi tocca quel tasto vuole **rinunciare** o vuole **fermarsi**. Su
un download di decine di megabyte in mobilità la risposta è quasi sempre la
seconda, e va sostenuta anche se costa un file temporaneo in più.

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
- **Un download in pausa non sopravvive alla chiusura dell'app.** Il file
  `.parziale` resta su disco e la ripresa *funziona* — il download successivo
  riparte da lì invece che da zero — ma la percentuale non si rivede: lo stato
  `scaricamenti` vive in memoria. Per mostrarla servirebbe la dimensione totale
  del file, che oggi non è in `TracciaEntity`. Costo: una colonna in più e una
  migrazione. Nel frattempo il comportamento è comunque quello giusto, solo
  silenzioso.
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
