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

### La barra in ascolto: quando c'è e quando no

`#mini-player` **non è una barra fissa**. Compare solo quando la card della
traccia che stai ascoltando è uscita di vista, e serve a una cosa sola: riportarti
lì. Con la card a schermo sarebbe un secondo tasto play accanto al primo, e i due
si contraddirebbero a vicenda.

Le regole, prese dal prototipo:

- visibile solo se **meno di un terzo** della card è nel viewport
  (`IntersectionObserver` con `threshold: 0.35`);
- il collegamento fra barra e traccia **si spezza** nell'istante preciso in cui
  sei in pausa *e* rivedi la card: a quel punto ti sei già ricongiunto con la
  traccia guardandola, e allontanartene di nuovo non deve far ricomparire niente.
  Se invece sta ancora suonando, rivedere la card la nasconde soltanto;
- il tasto commento della barra **non ha un riquadro suo**: ti porta sulla card e
  apre quella vera (nel prototipo, letteralmente `addBtn.click()`). Una UI in
  meno da tenere allineata in due posti.

Perché questo funzioni, lo `LazyListState` della lista deve essere **lo stesso**
che legge chi decide se mostrare la barra. Vedi errore 20: per un po' non lo era.

**`is-playing` si toglie alla pausa, non al cambio di traccia.** Bordo accent
della card e tasto play pieno vogliono dire "sta suonando adesso", non "è la
traccia selezionata". La posizione del cursore invece resta anche in pausa: è
lì che sei rimasto.

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

### Riproduzione fuori dall'app, e il commento dalla notifica

L'audio deve continuare a schermo spento e con l'app chiusa, come in qualunque
lettore musicale — e mentre continua si deve poter **commentare senza rientrare
nell'app**. È il gesto che rende utile ascoltare in cuffia facendo altro, ed è
quello che il prototipo simula con la finta lock screen.

**Com'è messo insieme.**

| Pezzo | Cosa fa |
|---|---|
| `PlayerCondiviso` | l'unico ExoPlayer, più "cosa sta suonando" (id, titolo, cartella) |
| `ServizioRiproduzione` | `MediaSessionService` di Media3: tiene viva la riproduzione e pubblica la sessione |
| `ProviderNotifica` | costruisce la notifica: titolo, play/pausa, **Commenta** |
| `CommentoRapido` | la schermatina di commento aperta dalla notifica |
| `ComandiNotifica` | play/pausa e chiusura dai tasti della notifica |
| `CommentiDaFuori` | avvisa il ViewModel di un commento nato fuori dall'interfaccia |

**Perché il player è un singleton, che di solito è una cattiva idea.** Un player
dentro il ViewModel muore con la schermata. L'alternativa ortodossa — il player
dentro il servizio, l'interfaccia che ci parla via `MediaController` — vorrebbe
dire riscrivere ogni chiamata in forma asincrona. Con un'istanza sola,
`PlayerMega` comanda come ha sempre fatto e il servizio ci mette sopra la
sessione media. Conseguenza da ricordare: **`PlayerMega` non rilascia il player**
(`scollega()` toglie solo i suoi ascoltatori), perché il servizio lo sta ancora
usando; chiuderlo alla distruzione del ViewModel spegnerebbe l'audio a ogni
rotazione dello schermo.

**Perché una schermatina di commento e non la risposta rapida di Android.**
`RemoteInput` è il modo naturale di rispondere da una notifica, e a prima vista
è quello giusto. Ma avvisa **solo quando l'utente invia**: non esiste modo di
sapere quando ha aperto il campo. Nel prototipo il minutaggio si congela nel
momento in cui premi il tasto commento (`lnCommentSeconds = activePlaybackSeconds`),
non quando invii — e un commento appiccicato al secondo dell'invio finirebbe
sistematicamente **dopo** il punto di cui parla, tanto più quanto più lungo è
quello che scrivi. Con una schermata nostra quel momento si conosce: è
`onCreate`. E ci entra anche il lucchetto della card, che sbloccato fa scorrere
il minutaggio insieme al cursore.

**L'audio non si ferma mai** per commentare. La schermatina legge la posizione,
non tocca il player. Mettere in pausa per essere precisi al secondo sarebbe una
cura peggiore del male.

**Il commento scritto da lì nasce già su Room** (`StatoSync.LOCALE`) e viene
annunciato su `CommentiDaFuori`: se l'app è viva il ViewModel lo raccoglie e la
card si aggiorna; se non lo è, al prossimo avvio arriva dal database. Vale la
seconda regola in cima al documento — il telefono è la fonte di verità, sempre.

**`addSession` non è facoltativo.** `MediaSessionService` registra una sessione
col proprio gestore di notifiche quando `onGetSession` viene chiamato — e quello
succede solo se un `MediaController` si connette. Qui non si connette nessuno:
l'interfaccia comanda il player direttamente. Senza un `addSession(sessione)`
esplicito in `onCreate` il servizio parte, costruisce la sessione e **non mostra
mai niente**, senza nessun errore. Vale la pena tenerlo a mente: gran parte
degli esempi di Media3 danno per scontato il `MediaController`, e omettono il
passaggio perché nel loro caso avviene per caso.

**Play/pausa dalla notifica non passano dal ViewModel.** Agiscono sul player, e
il ViewModel si riallinea da `onIsPlayingChanged` (`allineaAlPlayer`). Quel
metodo non comanda mai il player: se comandasse, un tocco sulla notifica e uno
nell'app si rimbalzerebbero a vicenda. Lo stesso canale copre i tasti delle
cuffie e la telefonata che ruba l'audio.

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
| ⏸ sulla percentuale di una traccia | ferma quella traccia **e** la coda "Scarica tutte", se la traccia era sua |
| ▶ sulla percentuale di una traccia | riprende **solo quella**; la coda resta ferma |
| tasto "Scarica tutte" durante il download | mette in pausa tutto, ma solo in **quella cartella** |
| tasto "Scarica tutte" da fermo a metà | riprende la coda da dove era |

Icona della percentuale, voce del kebab e tasto "Scarica tutte" sono **tre
comandi della stessa cosa**: passano per le stesse funzioni e devono portare allo
stesso stato con un tap solo. I primi due chiamano letteralmente
`cambiaDownload`. Se in futuro qualcuno ne aggiunge un quarto, deve entrare da
lì, non riscrivere lo stato per conto suo — vedi errore 19.

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

Stanno anche in **`firestore.rules`** nella radice del repository, pronte da
incollare in Firebase Console → Firestore Database → Regole. La console resta
la sola copia che conta: il file serve a poterle leggere insieme al codice, e
va tenuto allineato a mano.

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
      allow update: if request.auth != null;  // vedi nota
    }
  }
}
```

> Regole volutamente permissive per un gruppo di 5 persone di fiducia.
> Non adatte a un'app pubblica.

> **`update` era a `false`, e sarebbe stato un guaio.** La riga diceva "i
> commenti non si modificano", ma l'app un commento lo lascia modificare
> eccome — c'è `modificaCommento`, e il riquadro per farlo sta nella card. Con
> la regola originale la modifica sarebbe stata accettata in locale e rifiutata
> da Firestore alla sincronizzazione: il commento corretto sul proprio telefono,
> quello vecchio su quello di tutti gli altri, e nessun errore visibile finché
> qualcuno non avesse confrontato i due schermi. Le regole descrivono cosa fa
> l'app, non cosa avevamo immaginato che facesse.

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

## Prima di provare la sincronizzazione

Due cose vanno fatte **in Firebase Console**, o il tasto Sincronizza fallisce
senza che ci sia niente da correggere nel codice:

1. **Authentication → Sign-in method → Anonymous: abilitato.** È spento di
   default. Senza, `signInAnonymously()` risponde
   `CONFIGURATION_NOT_FOUND` e ogni giro si ferma prima di cominciare.
2. **Firestore Database creato**, e le regole di `firestore.rules` incollate
   nella scheda Regole. Quelle di default scadono dopo trenta giorni e poi
   bloccano tutto.

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

### Rami e push

Il lavoro vive su **`feature/app-implementation`**. È lì che si committa e lì
che si pusha, sempre.

**`main` è la rete di sicurezza, non un ramo di lavoro.** Ci si arriva solo con
una pull request aperta e accettata a mano, quando un blocco è finito *e
provato sul telefono*. Nessun push diretto, nessun merge fatto per abitudine a
fine giro: se il ramo di lavoro si rompe, `main` deve essere ancora il punto
buono a cui tornare — e non lo è se lo abbiamo tenuto allineato per comodità.

Il punto sicuro attuale è `ae64f5f`, l'app che gira sul telefono con l'icona
vera. Da qui in avanti i due rami divergono apposta.

*Perché sta scritto qui:* niente in questo documento ha mai chiesto di pushare
su `main` a ogni giro. È successo che le PR #1 e #2 sono state accettate su
`main` e i due rami sono finiti sullo stesso commit — normale come flusso, ma
per la fase che comincia adesso (Firestore) non va bene.

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
| Room | 2.7.1 | catalog `room` — schema DB alla versione 3 |
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
| Topbar unica per tutte le schermate | ✅ | c'è anche nel Gate; logo o freccia nella stessa casella |
| Home + cartelle + ghost card | ✅ | |
| Folder screen, TrackCard, timeline, commenti, voti | ✅ | tutta l'UI del prototipo |
| Mini player | ✅ | logica di scollegamento inclusa |
| Foglio account + strumenti di test | ✅ | |
| Identità persistente (`appUid`) | ✅ | `EncryptedSharedPreferences` con fallback |
| Riproduzione audio da MEGA | ✅ | **provata**: audio, seek dai commenti, pausa/riprendi |
| Cartelle di prova pre-collegate | 🟡 | due cartelle MEGA **vere** seminate al primo avvio, con commenti e voti finti sopra: `DatiDiProva.kt` |
| Collegamento di una cartella MEGA | ✅ | **provato su una cartella vera**; ricollegare la stessa cartella la ricarica |
| Condivisione da MEGA verso l'app | ✅ | **provata**: da app aperta e chiusa, e due volte con lo stesso link |
| Durata delle tracce da MEGA | 🟡 | arriva al primo play di *quella* traccia; le altre restano `--:--` |
| Nome della cartella letto da MEGA | ✅ | risolto provando tutti i nodi non-file, vedi errore 7 |
| Waveform | 🟡 | equalizzatore animato decorativo, nessun dato reale |
| Archivio locale (Room) | ✅ | **provato**: commenti, stelle e rinomine sopravvivono alla chiusura |
| Elenco profili per il recupero account | ✅ | **provato**: i profili arrivano da Firestore, `ProfiliStore` resta la cache per il Gate offline |
| Firestore | 🟡 | **primo contatto provato**: legge i profili e li scrive. Cartelle, tracce e commenti da provare |
| Firebase Anonymous Auth | ✅ | **provata**: l'accesso passa e le regole lo accettano |
| MEGA HTTP API + crypto | ✅ | elenco, decifratura e scarico dei byte **verificati sul campo** |
| Tasto Sincronizza | 🟡 | MEGA + pull + push + cancellazioni, con l'icona che gira: **tutto da provare** |
| Banner offline | ❌ | c'è il messaggio al gesto che fallisce, non il banner permanente |
| Riproduzione in background + notifica | ✅ | **provata**: icona, cronometro, commento sopra il blocco schermo |
| Traccia precedente/successiva | 🟡 | barra in ascolto + notifica, stessa logica per entrambi: **da provare** |
| Commento dalla notifica | 🟡 | schermatina col lucchetto, minutaggio congelato all'apertura: **da provare** |
| Download reale su disco | ✅ | **provato**: scarica, decifra, suona da locale, si rimuove |
| Pausa e ripresa del download | ✅ | **provata**: riprende da dove era, dai tre comandi |
| Coda unica dei download | 🟡 | un solo consumatore, una traccia alla volta: **da provare** |
| Download con app chiusa | ❌ | vivono in `viewModelScope`; serve un foreground service |
| Riproduzione dal file locale | ✅ | **provata**, anche togliendo il file mentre suona |

Detto in modo diretto, perché sono le domande che vengono naturali:
**il link MEGA collega davvero**, l'audio si sente, **le tracce si scaricano
davvero** e da scaricate suonano dal telefono senza rete; **i commenti non
arrivano ancora su Firestore**, ma restano sul telefono in attesa invece di
sparire.

**Le cartelle finte non ci sono più.** Al loro posto ci sono due cartelle MEGA
vere, collegate da sole al primo avvio, con sopra commenti e voti di un gruppo
immaginario — vedi *Il banco di prova* qui sotto. Le vecchie tracce demo non
avevano un file dietro e suonavano su un timer: provare l'app su quelle voleva
dire non provare niente, e con MEGA funzionante erano diventate un modo per
sbagliarsi.

**Il bug delle tracce che sparivano alla chiusura è chiuso**, e con esso una mia
lettura sbagliata: l'avevo trattato come qualcosa che si sarebbe risolto da sé
con Firestore. Non era così — mancava la persistenza locale, che deve esistere
**prima** e **indipendentemente** da Firestore. Vedi la seconda regola in cima al
documento.

**Stato della build:** verde fino al giro precedente, e l'app gira su un
telefono vero.

**La riproduzione in background con notifica non è ancora stata compilata né
provata**, ed è l'unico pezzo del progetto scritto contro API che non ho potuto
verificare staticamente (`MediaSessionService`, `MediaNotification.Provider`,
`MediaStyleNotificationHelper` di Media3 1.2.0). Tutto il resto è stato
incrociato con il codice esistente prima di essere spinto; questo no. Aspettarsi
un giro di correzioni di compilazione è ragionevole — non è un fallimento, è dove
siamo.

---

### Il banco di prova

`data/DatiDiProva.kt` tiene due link a cartelle MEGA vere. Al primo avvio
vengono collegate da sole, come se le avesse già collegate qualcun altro, e ci
vengono messi sopra commenti, voti e ascolti di un gruppo immaginario (Marco,
Luca, Ale). Serve a non ripartire da zero a ogni prova.

**I commenti si agganciano per posizione, non per nome file.** L'id di una
traccia è il node handle di MEGA, che si conosce solo dopo aver letto la
cartella: in un file di dati non può starci. Quindi `CommentoDiProva.traccia`
è `1` per la prima traccia dell'elenco ordinato, `2` per la seconda, e le
posizioni che non esistono si saltano. Aggiungere o rinominare file su MEGA
non rompe niente.

**Cartelle e tracce del seme vanno su Firestore, i commenti no.** Le prime sono
dati veri — il link MEGA esiste, i file pure — e tenerle fuori lasciava il
database incoerente: bastava ascoltare una traccia perché quella salisse da
sola, con gli ascolti aggiornati, mentre la cartella che la contiene non
c'era. I commenti finti invece nascono `SINCRONIZZATO` e non partono mai:
Marco, che non esiste, resterebbe nella cronologia del gruppo per sempre.

**Il seme scatta una volta sola**, e il segno che è scattato sta in
`DatiDiProvaStore` (SharedPreferences), non fra le cartelle. Se stesse lì,
scollegare una cartella di prova la farebbe tornare al riavvio dopo e lo
scollegamento non si potrebbe più provare. Senza linea non semina e non segna
niente: si riprova al riavvio successivo.

**Il reset è "cancella tutto e risemina", non "cancella il tuo".** È il tasto
rosso del foglio account, *Riparti dai dati di prova*. Non esiste nessun elenco
di righe "mie" contrapposte a righe "del seme", ed è il punto: il seme si
ricostruisce da codice, quindi buttare tutto e riseminare dà lo stesso
risultato di una cancellazione selettiva senza uno stato in più da tenere
allineato e da sbagliare.

> **Il reset cancella solo questo telefono, mai Firestore.** È deliberato:
> quel database è del gruppo, e un tasto di prova che lo svuotasse
> cancellerebbe i commenti di altre quattro persone senza chiedere niente a
> nessuno. Da aspettarsi quindi che profili e commenti già sincronizzati
> **ritornino** al primo Sincronizza dopo il reset — non è un reset mancato, è
> il cloud che fa il suo mestiere. Per svuotare davvero il database si passa
> dalla console Firebase, a mano e guardando cosa si cancella.

> **I link contengono la chiave.** La parte dopo il `#` decifra la cartella:
> chi legge il sorgente può scaricarne l'audio, e il repository è pubblico. È
> una scelta consapevole per questa fase. Quando quelle cartelle non serviranno
> più, **cancellarle da qui non basta**: restano nella storia dei commit, che su
> un repo pubblico resta raggiungibile anche dopo un force-push. L'unica cosa
> che invalida davvero la chiave è **rigenerare il link dalla cartella su
> MEGA**. I posti da ripulire nel codice sono due, entrambi in
> `DatiDiProva.cartelle`.

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

#### 19. Chi riporta il progresso non deve possedere lo stato

*Sintomo:* toccare la percentuale fermava davvero il download, ma la card restava
**verde con l'icona di pausa** invece di diventare grigia con il play. Un secondo
tap la sistemava. Identico dal kebab e dal tasto "Scarica tutte": tutti e tre
richiedevano due tap per mostrare l'effetto di uno.

*Causa:* `Job.cancel()` in Kotlin è **cooperativo** e non interrompe una
`read()` bloccante già partita. Dopo il comando di pausa la lettura da 64 KB in
volo arrivava a termine, scriveva su disco e chiamava `onProgresso(frazione)` —
che scriveva `StatoScaricamento(frazione, inPausa = false)`, **riaccendendo** lo
stato "sta scaricando" un istante dopo che l'utente l'aveva spento. Al secondo
tap la coroutine era morta e nessuno riscriveva più: da qui l'illusione che
servissero due tap.

*Fix:* la callback del progresso aggiorna **solo il numero**
(`corrente.copy(frazione = ...)`) e non tocca mai `inPausa`. Il flag appartiene a
chi dà i comandi.

*Da ricordare:* due cose.
1. `cancel()` chiede, non impone. Fra la richiesta e l'effetto passa del tempo,
   e in quel tempo il codice che si sta cancellando **continua a scrivere**. Se
   quel codice tocca lo stesso stato di chi l'ha cancellato, l'ultimo che scrive
   vince — e non è chi ha premuto.
2. Ogni pezzo di stato deve avere **un solo proprietario**. La frazione è del
   downloader, `inPausa` è dei comandi. Quando il primo scriveva anche il
   secondo, il bug era inevitabile: non era un errore di logica ma di proprietà.

*Corollario, sempre da qui:* icona, kebab e "Scarica tutte" sono **tre comandi
della stessa cosa** e devono passare per le stesse funzioni. Icona e kebab
chiamano già entrambi `cambiaDownload`. Il terzo, `pausaBulk`, agisce su tutta la
coda — ed è stato ristretto alle tracce **della sua cartella**, perché fermare lo
"Scarica tutte" di una cartella non deve fermare un download avviato a mano in
un'altra. Stessa correzione in `pausaDownload`, che metteva in pausa la coda
anche quando la traccia toccata non le apparteneva.

#### 20. Uno stato passato a nessuno resta vuoto per sempre

*Sintomo:* la barra in ascolto stava **sempre** a schermo, anche con la card
della traccia bene in vista, creando due tasti play in competizione. E "tocca per
tornare alla traccia" non scorreva da nessuna parte.

*Causa:* `AppRoot` creava un `LazyListState` e lo usava per calcolare se la card
fosse visibile — ma non lo passava mai a `FolderScreen`, che creava la propria
`LazyColumn` senza `state =`. Quello stato non era attaccato a nessuna lista:
`visibleItemsInfo` restava vuoto per sempre, "card visibile" era sempre falso, e
la barra non aveva mai motivo di sparire. `animateScrollToItem` su una lista
inesistente non faceva niente, in silenzio.

*Fix:* `statoLista` diventa un parametro di `FolderScreen`, e la visibilità si
misura come nel prototipo — più di un terzo dell'altezza della card dentro il
viewport (`threshold: 0.35` dell'IntersectionObserver), non "anche un pixel".

*Da ricordare:* uno stato Compose che non è collegato a nessun componente non
dà errore, non lancia niente, e risponde con valori vuoti perfettamente
plausibili. Quando una logica che dipende da `layoutInfo` "non scatta mai",
prima di studiare la logica va controllato che lo stato sia davvero quello della
lista che si sta guardando.

#### 21. Vincolare la larghezza non è ritagliare, e riempirla non è "quanto serve"

Tre difetti di grafica con la stessa radice: **in Compose i vincoli di layout
non fanno quello che fanno le proprietà CSS che gli assomigliano.**

| Volevo | Avevo scritto | Cosa faceva davvero |
|---|---|---|
| mezza stella (`clip-path: inset(0 50% 0 0)`) | `Box(Modifier.width(6.dp))` con dentro l'icona da 12dp | il vincolo **rimpicciolisce** il figlio: una stellina da 6dp storta, non una stella tagliata a metà |
| menu largo quanto serve (`min-width:190px`) | `widthIn(min = 190.dp)` + voci con `fillMaxWidth()` | dentro un `Popup` il vincolo massimo è **lo schermo**: "riempi la larghezza" prendeva tutto lo schermo |
| alone del cursore (`box-shadow: 0 0 0 6px`) | `Modifier.border(6.dp, …)` | il bordo si disegna **dentro** i propri limiti: mangiava il pallino invece di circondarlo |
| anelli del marker selezionato (due `box-shadow`) | `Modifier.border(2.dp, accent)` | idem: copriva l'iniziale, che è l'unica cosa da leggere |

*Regola che ne esce:* **layout e disegno sono due cose diverse.** `width`,
`fillMaxWidth`, `border` partecipano alla misurazione e vivono dentro i propri
limiti. Ritagli, aloni e anelli che nel CSS escono dal box (`clip-path`,
`box-shadow`, `position:absolute; inset:0`) in Compose si fanno **disegnando** —
`drawWithContent { clipRect { … } }`, `drawBehind { drawCircle(…) }` — perché
il disegno riceve il nodo già impaginato e può uscire dai suoi bordi.
È la stessa lezione dell'errore 17, e ormai è la terza volta: se una cosa deve
stare *sopra*, *sotto* o *fuori* dal contenuto, disegnala.

#### 22. Un popup si chiude e si riapre nello stesso gesto

*Sintomo:* ripremendo i puntini il kebab non si chiudeva, si riapriva.

*Causa:* il tap fuori dal `Popup` fa scattare `onDismissRequest` **e** arriva
comunque al tasto sotto. Chiusura e riapertura, un gesto solo. Nel prototipo il
caso non esiste perché è un `if(is-open){ close; return; }` sullo stesso
handler; qui i due eventi arrivano separati.

*Fix:* si annota quando il popup si è chiuso e si ignora un tap sui puntini
arrivato entro 250 ms.

*Da ricordare:* quando due strati diversi reagiscono allo stesso tocco, non
basta che ognuno faccia la cosa giusta: contano anche l'ordine e il fatto che
sono **due** eventi. Vale anche per la z: nella timeline i marker stanno di
proposito *sopra* la presa del cursore, così un tap su un commento non finisce
in un trascinamento.

#### 23. Il testo di un campo alto non si centra

*Sintomo:* in "Scrivi un commento…" il segnaposto e il testo galleggiavano a
metà altezza del riquadro.

*Causa:* `AppTextField` centrava sempre in verticale. Su un campo a una riga è
l'unica cosa sensata; su uno alto 56dp che accoglie più righe è sbagliato — e
salta anche in su all'arrivo della seconda riga.

*Fix:* con `singleLine = false` l'allineamento passa a `Alignment.Top` /
`TopStart`, che è il comportamento di una `textarea` e di qualunque campo
multiriga di sistema.

*Da ricordare:* non c'è niente da inventare su un comportamento che ha già uno
standard. Se un componente si scosta da quello che fa il resto del mondo, la
domanda giusta è "perché?", non "come lo miglioro?".

#### 24. L'icona dell'app: da SVG a icona adattiva Android

`Logo_RegiStrazio.svg` (nella root del repo) è la fonte: sagoma color crema di
sfondo, poi un musetto — due occhi ad anello scuro con un riflesso bianco
dietro il buco, un'onda scura, e una forma a nastro. Otto `<path>`/`<polygon>`
in tutto, nessun comando d'arco insolito: si trascrivono 1:1 in vector
drawable Android, che capisce la stessa sintassi di `d` (compresi gli archi
`A`) usata da SVG.

**Come si è tradotto, e dove stanno le trappole:**

- **`<polygon>` non esiste nei vector drawable Android.** Solo `<path>`. La
  forma a nastro (un `<polygon points="…">` nell'SVG) è stata riscritta come
  `pathData="M… L… L… … Z"` — stessi punti, stessa forma, sintassi diversa.
- **L'icona adattiva è due livelli, non uno.** `ic_launcher_background.xml`
  tiene il crema; `ic_launcher_foreground.xml` ha il resto, sfondo
  trasparente. È il sistema a comporli e poi ritagliarli con la propria
  maschera (cerchio, squircle, goccia…) a seconda del launcher — per questo
  lo sfondo va tenuto separato dal musetto, non fuso in un'unica immagine.
  Nel livello di sfondo **la sagoma tondeggiante dell'SVG non c'è**: è un
  rettangolo crema pieno. Il bordo lo disegna la maschera del launcher, e
  dove i suoi angoli sono meno arrotondati di quelli della sagoma si
  vedrebbero quattro spicchi trasparenti.
- **L'ordine di disegno dentro il foreground conta.** I due anelli scuri
  degli occhi sono ciascuno un `<path>` con **due sottopercorsi** nello stesso
  `d` (cerchio esterno + cerchio interno, versi opposti): è quello che scava
  il buco al centro dell'anello. Il riflesso bianco sta in un `<path>`
  separato, disegnato **prima** — attraverso il buco dell'anello si vede lui,
  non lo sfondo. Se l'ordine si invertisse, l'anello coprirebbe il riflesso e
  l'occhio perderebbe la lucentezza.
- **Il viewport resta 192×192**, lo stesso dell'SVG originale, invece di
  essere riscalato a 108. Così i numeri dei path si copiano senza
  ricalcolare nulla a mano — `android:width/height="108dp"` fa comunque lo
  scaling giusto in fase di render.
- **Il livello `monochrome`** (icona a tinta unica di Android 13+, quando
  l'utente sceglie icone abbinate al wallpaper) riusa lo stesso
  `ic_launcher_foreground` — è la stessa scelta già presente nel template di
  base di Android Studio: il sistema legge solo il canale alfa e ci applica
  il proprio colore, quindi riusare il livello a colori è corretto e non
  richiede un disegno separato.
- **I fallback raster** (`mipmap-*dpi/ic_launcher(_round).webp`, 48–192px)
  sono stati rigenerati dallo stesso SVG con `cairosvg` + Pillow, uno per
  densità. Con `minSdk 26` — la stessa versione che ha introdotto le icone
  adattive — nessun dispositivo supportato dall'app le userà mai davvero a
  runtime; restano lì per la manciata di strumenti (anteprime IDE, alcune
  schermate di sistema) che ancora guardano al raster invece che al vettore.

*Da ricordare:* un SVG pensato come icona standalone (qui: viewBox 192×192,
sfondo pieno + contenuto quasi a bordo canvas) va quasi sempre spezzato in due
prima di diventare un'icona adattiva — il taglio sfondo/primo piano non è nel
file sorgente, va deciso leggendo cosa nell'immagine "è" lo sfondo.

---

#### 25. Di un'icona adattiva non si vede tutto il viewport

Trascritti i path 1:1, sul telefono il musetto **riempiva tutto il riquadro**,
a filo dei bordi, con l'onda tagliata ai lati. Nessun margine, mentre l'SVG di
partenza ne ha parecchio.

Il viewport di un'icona adattiva non è la parte visibile. Il launcher tiene i
**72/108 centrali** e li ingrandisce a pieno riquadro: un 1,5× che mangia il
quarto esterno del disegno. Path a coordinate piene vuol dire disegno a filo
del bordo — non è un errore di trascrizione, è che mancava lo strato di
scaling che i template di Android Studio hanno già e che copiando i path a
mano si salta.

*Fix:* i path del foreground vanno dentro un `<group>` che li rimpicciolisce
del fattore di ritaglio, attorno al centro del viewport:

```xml
<group android:pivotX="96" android:pivotY="96"
       android:scaleX="0.6667" android:scaleY="0.6667">
```

`0.6667` = 72/108, cioè esattamente il ritaglio: così il quadrato 192×192
dell'SVG coincide con ciò che resta visibile, e i margini tornano quelli
dell'originale (≈14% ai lati, 26% sopra, 19% sotto — il musetto non è
centrato in verticale nemmeno nell'SVG, e quello sbilanciamento va
conservato, non "corretto").

*Da ricordare:* le linee guida parlano anche di una *safe zone* più stretta,
un cerchio da 66/108: è l'area che nessuna maschera taglia mai. Fra 66 e 72
il taglio dipende dalla forma scelta dal launcher. Qui il musetto sta dentro
il cerchio da 66 anche dopo lo scaling — verificato sui punti più esterni,
l'onda e il nastro — quindi 72 è sicuro. Con un disegno che riempie di più gli
angoli conviene scalare a `0.611` (66/108) e non a `0.6667`.

---

#### 26. Una durata a zero ammassa i commenti contro il bordo

Seminando commenti su tracce MEGA mai riprodotte sono finiti tutti impilati
all'estremità destra della timeline. Il ripiego che c'era —
`durataSecondi.coerceAtLeast(1)` — nasce per non dividere per zero, e per
quello funziona; ma con durata 1 ogni marker a 12, 68 o 125 secondi dà una
frazione enorme, che il `coerceIn(0f, 1f)` schiaccia tutta su 1.

Non è un problema del banco di prova, è l'app: **la durata si legge solo
premendo play su quella traccia**, quindi una traccia mai avviata con sopra i
commenti di qualcun altro sarà la norma anche in produzione. Il seme l'ha solo
fatto emergere subito invece che al primo commento arrivato da Firestore.

*Fix:* finché la durata vera non si conosce, stimarla dal commento più
avanzato — la traccia dura almeno quanto il punto di cui qualcuno ha parlato —
con un margine che stacchi l'ultimo marker dal bordo:

```kotlin
val durataSicura = when {
    durataSecondi > 0 -> durataSecondi.toFloat()
    commenti.isEmpty() -> 1f
    else -> commenti.maxOf { it.timestampSecondi }.coerceAtLeast(1f) * 1.15f
}
```

Al primo play la durata vera prende il posto della stima e i marker si
assestano da soli.

*Da ricordare:* un ripiego che evita il crash non è per forza un ripiego che
dà un risultato sensato. `coerceAtLeast(1)` toglieva il NaN e lasciava una
bugia — e una bugia in un dato che finisce in un layout si vede solo quando
qualcuno guarda lo schermo nel caso giusto.

---

#### 27. Saltare a un commento non è ricominciare la traccia

Il sintomo era intermittente e brutto: premendo il chip di un commento mentre
la traccia già suonava, ogni tanto l'audio proseguiva per conto suo, il cursore
si piantava, e una volta è comparso un `coroutine was cancelled` al posto di un
messaggio vero.

`riproduciDa` chiamava `avvia`, che è la strada lunga: butta il flusso, chiede
a MEGA un **indirizzo nuovo**, ricarica tutto. Mezzo secondo per spostarsi di
dieci, sulla funzione che è il motivo per cui l'app esiste. E `avvia` comincia
con `playJob?.cancel()`: se il job precedente stava aspettando l'indirizzo, la
cancellazione arrivava dentro il suo

```kotlin
catch (e: Exception) { fermaRiproduzione(); mostra(spiegaErroreDiRete(e)) }
```

che la scambiava per un guasto di rete e **spegneva la riproduzione appena
partita**. Da qui l'intermittenza: con l'indirizzo già pronto la corsa non si
apriva, ed è per questo che "dopo un po' di prove funzionava".

*Fix, due parti:*

1. Se il player ha già dentro quella traccia, saltare è un `seek` — niente
   rete, niente job da cancellare, nessuna corsa.
2. `CancellationException` va **rilanciata**, mai trattata come errore. In
   Kotlin è una `Exception` come le altre e un `catch` largo se la mangia,
   rompendo la cooperazione fra coroutine. `scaricaUna` lo faceva già giusto;
   il player no.

*Da ricordare:* ogni `catch (e: Exception)` attorno a codice sospendibile è un
posto dove una cancellazione può travestirsi da guasto. Il danno non si vede
dove sta il `catch` — si vede addosso all'operazione **successiva**, quella che
ha causato la cancellazione e che si ritrova spenta da chi stava morendo.

---

#### 28. Un file di regole di backup vuoto vuol dire "salva tutto"

Reinstallando l'app da zero ricompariva un account creato settimane prima, da
solo, senza le cartelle che ai suoi tempi lo accompagnavano.

Non era l'app: era **Auto Backup**. `backup_rules.xml` e
`data_extraction_rules.xml` erano i template di Android Studio, cioè commenti e
nient'altro — e un file di regole senza regole non disattiva il backup,
**acconsente a tutto**. Google rimetteva a posto le SharedPreferences alla
reinstallazione successiva.

Tre ragioni per cui qui il backup fa più danni che comodo:

- `EncryptedSharedPreferences` (dove vive `appUid`) è cifrata con una chiave
  del **Keystore**, che non viene ripristinata. Il file torna indietro
  illeggibile: peggio che assente, perché sembra esserci.
- Il segno "cartelle di prova seminate" sta nelle preferenze, le cartelle in
  Room. Ripristinare le prime senza le seconde lascia l'app convinta di aver
  già seminato, e vuota.
- Room è una copia ricostruibile: le cartelle si riprendono da MEGA.

*Fix:* `<exclude>` esplicito su `sharedpref`, `database` e `file` in entrambi i
file — e in `data_extraction_rules.xml` anche in `<device-transfer>`, non solo
in `<cloud-backup>`: sono due strade diverse per gli stessi dati.

*Da ricordare:* i due file valgono per versioni di Android diverse (≤30 e
31+). Toccarne uno solo fa comportare l'app in un modo su un telefono e in un
altro su quello accanto.

---

#### 29. Un `isPlaying` a false non vuol dire "in pausa"

Il tasto play restava a girare su tracce che stavano suonando — soprattutto
saltando da un commento all'altro, e in modo così frequente sulle tracce
scaricate da far sembrare che l'app ignorasse il file locale e ricaricasse da
MEGA. Tre sintomi, un difetto solo.

`audioAttivo` lo scriveva **solo** il ciclo di `seguiPosizione`. Se quel ciclo
moriva mentre lo stato diceva "in riproduzione", il valore restava congelato a
`false` per sempre: cursore fermo, tasto che gira, audio che va avanti per
conto suo.

A ucciderlo era `allineaAlPlayer`, e la sequenza merita di essere letta tutta,
perché nessuno dei passaggi è sbagliato da solo:

1. Salti a un commento. `riproduciDa` legge lo stato — `inRiproduzione = true`
   — e chiama `player.cerca()`.
2. ExoPlayer, durante il `seek`, emette `isPlaying = false`.
3. `allineaAlPlayer(false)` lo legge come una pausa: **spegne il ciclo** e
   scrive `inRiproduzione = false`.
4. `riproduciDa` prosegue e riscrive `inRiproduzione = true`, dalla copia di
   stato letta al passo 1 — che nel frattempo è invecchiata.
5. Il `seek` finisce, l'audio riparte, arriva `isPlaying = true`.
6. `allineaAlPlayer(true)` trova `inRiproduzione` **già** a `true`, conclude di
   non avere niente da fare (`if (r.inRiproduzione == suona) return`) e non
   riaccende il ciclo.

Fine: lo stato dice tutto a posto, il ciclo è morto, `audioAttivo` non si
muove più.

*Fix, tre parti:*

1. `audioAttivo` viene da `allineaAlPlayer`, che è l'unico posto che sa se il
   suono esce. `seguiPosizione` lo conferma a ogni tick, non lo stabilisce.
2. Distinguere la pausa vera dalla pausa tecnica con **`playWhenReady`**: se il
   player *vuole* suonare ma non suona, sta caricando o finendo un `seek`, e il
   ciclo va lasciato acceso. Il tasto che gira, lì, è la verità.
3. Riaccendere il ciclo guardando **`playJob?.isActive`**, non lo stato. Sono
   due cose diverse e quando divergono è proprio nei casi che rompono.

*Da ricordare:* leggere lo stato in cima a una funzione e riscriverlo in fondo
è sicuro solo se in mezzo non può succedere niente. Qui in mezzo c'era una
chiamata al player, cioè una callback sincrona che quello stesso stato lo
cambiava. E una funzione che decide "è già a posto, non faccio niente"
confrontando un valore che qualcun altro ha appena corretto per un motivo
diverso è il modo più silenzioso per non fare la cosa che serviva.

---

#### 30. Due strade per lo stesso download

Scaricando una traccia a mano e premendo poi "Scarica tutte", quella traccia
partiva **due volte**: due percentuali sulla stessa barra, entrambe che
scrivevano sullo stesso file `.parziale`.

`avviaDownload` la guardia ce l'ha — `if (jobDownload[id]?.isActive == true)
return` — ma la coda non ci passava: chiamava `scaricaUna` direttamente, senza
consultare `jobDownload` né lasciarci il proprio job.

*Fix:* la coda salta le tracce che hanno già un download vivo. Restano quelle
in pausa, che è giusto riprendere.

*Da ricordare:* una guardia contro il doppio avvio vale solo per chi passa
dalla porta dov'è appesa. Quando due strade portano alla stessa operazione, o
la guardia sta nell'operazione, o va ripetuta su tutte le strade.

---

#### 31. Una percentuale senza denominatore, e un file mozzato dato per buono

Due difetti nello stesso punto, e insieme facevano perdere fiducia nella barra
di avanzamento: restava a **zero** per tutto lo scaricamento, e fermandola e
riprendendola saltava di colpo al 50%.

Il totale veniva dal `Content-Length` della risposta:

```kotlin
val totale = if (corpo.contentLength() > 0) corpo.contentLength() + daByte else -1L
```

MEGA non lo manda sempre. Quando manca, `totale` è `-1` e `onProgresso` non
viene **mai** chiamato: il download procedeva benissimo, era il numero a non
esistere. Alla ripresa il `.parziale` valeva già metà file, quel valore
entrava in `daByte`, e la prima percentuale calcolata partiva da lì.

La dimensione, però, la sappiamo **prima di cominciare**: sta nell'elenco della
cartella, in `FileMega.dimensioneByte`. Non c'era solo dove serviva, perché non
veniva conservata. Ora è una colonna di `TracciaEntity` e fa tre lavori:

- **denominatore** della percentuale, indipendente da come viaggia la risposta;
- **percentuale di partenza** letta dal disco invece che dalla memoria — il
  `.parziale` sopravvive alla chiusura dell'app, lo stato no, e questo chiude
  il vecchio debito del download in pausa che ripartiva visivamente da zero;
- **prova che il file è intero.**

L'ultimo era un bug vero e silenzioso: un flusso che si chiude a metà — rete
che cade, server che tronca — non alza nessuna eccezione. La `read()`
restituisce `-1` esattamente come a fine file, e il parziale veniva rinominato
e segnato come scaricato. Un file mozzato con la spunta verde accanto. Adesso
si confronta la lunghezza con quella attesa (AES-CTR non cambia la dimensione,
quindi decifrato e cifrato pesano uguale) e se non torna è un errore, con il
`.parziale` lasciato dov'è per riprendere.

*Da ricordare:* prima di mostrare una percentuale, chiedersi da dove viene il
denominatore e cosa succede quando non c'è. Una barra ferma a zero mentre il
lavoro procede è peggio di nessuna barra: dice che l'app è bloccata, e chi
guarda smette di credere anche a quello che funziona.

---

#### 32. Un timeout di lettura a zero non toglie un limite, toglie un allarme

I download si piantavano a metà — 40%, nessun errore, nessun avanzamento — e
ripartivano solo mettendoli in pausa e riprendendoli a mano.

```kotlin
// Nessun timeout di lettura: un brano lungo su rete lenta impiega
// quanto impiega, e interromperlo a metà non aiuterebbe nessuno.
.readTimeout(0, TimeUnit.SECONDS)
```

Il commento descriveva un problema che non esiste. Il `readTimeout` di OkHttp
non limita la durata del download: limita l'attesa **fra un byte e il
successivo**. Un brano da venti minuti su rete lenta non lo sfiora, purché i
byte continuino ad arrivare. A zero, invece, una connessione morta senza
chiudersi — capita di continuo passando fra celle o su un WiFi debole — lascia
la `read()` appesa per sempre: nessuna eccezione da mostrare, nessun byte da
contare.

*Fix:* 30 secondi. Se per mezzo minuto non arriva niente, è un errore vero, con
il messaggio giusto e il `.parziale` pronto per riprendere.

*Da ricordare:* prima di disattivare un timeout, guardare cosa misura davvero.
Quelli che sembrano "limiti alla durata del lavoro" sono quasi sempre limiti
al **silenzio**, e sono la sola differenza fra un errore e un blocco muto.

---

#### 33. Due strade per la stessa operazione, due mappe diverse

Restavano due tracce in scaricamento insieme, una con l'icona grigia in pausa e
la percentuale che però avanzava.

L'errore 30 era stato chiuso a metà. La coda saltava le tracce già presenti in
`jobDownload`, ma continuava a chiamare `scaricaUna` direttamente **senza
lasciarci il proprio job**. La guardia proteggeva quindi la coda dal tasto
singolo, e non il tasto singolo dalla coda: per `avviaDownload` una traccia che
la coda stava scaricando risultava ferma, e ne partiva una seconda copia.

*Fix:* la coda registra il proprio job in `jobDownload` come fa
`avviaDownload`, con un `async` di cui aspetta il risultato. Una mappa sola, e
le due strade si vedono a vicenda.

*Da ricordare:* quando due strade portano alla stessa operazione, una guardia
su una sola delle due non è mezza protezione — è protezione in una direzione
sola, che è il modo migliore per crederla completa.

---

### Come funziona la sincronizzazione

`SyncManager.sincronizza()` fa quattro cose, e l'ordine non è arbitrario:

1. **MEGA** — rilegge ogni cartella collegata, per scoprire i file nuovi.
2. **Pull** — si prende quello che hanno scritto gli altri.
3. **Push** — manda quello che è stato scritto qui.
4. **Cancellazioni** — propaga quello che è stato tolto qui.

**MEGA per primo**, e qui c'era una trappola. Rileggere una cartella scrive in
Room le tracce nuove come `LOCALE`, cioè da caricare: mettendolo dopo il push,
quelle tracce sarebbero rimaste in coda fino al giro successivo, che a sua
volta ne avrebbe create altre. Il contatore non sarebbe mai tornato a zero, e
il lavoro da fare sarebbe stato creato dalla sincronizzazione stessa.

Per lo stesso motivo `sostituisciTracce` **conserva lo stato delle righe
invariate**: rileggere una cartella senza novità non deve marcare da caricare
cinquanta tracce identiche a quelle già su Firestore. Confronta la riga nuova
costruita *con lo stato vecchio* e, se coincide, non è cambiato niente.

**Il pull non sovrascrive mai il lavoro locale.** Una riga in `LOCALE`,
`ERRORE` o `DA_ELIMINARE` porta qualcosa che il cloud non ha ancora visto:
accettare la versione remota vorrebbe dire buttarla via proprio nel momento in
cui l'utente ha chiesto di salvarla. La regola vive in
`ArchivioLocale.accettaDalCloud` ed è la seconda legge del progetto.

**E non riscrive quello che è già identico.** Prima confrontava solo lo stato:
una riga `SINCRONIZZATO` veniva riscritta con la versione remota anche quando
le due coincidevano, e contata come "ricevuta". Da qui un `8 ricevuti` a ogni
giro, sempre gli stessi otto, che sono i propri documenti tornati indietro.
Non faceva danni ma diceva una cosa falsa, e su un tasto che serve a capire se
sei in pari è tutto quello che conta.

**Il messaggio finale conta documenti, non gesti.** "Inviati: 1 traccia",
"Ricevuti: 2 cartelle, 9 tracce". Mettere una stella e ascoltare la stessa
traccia dieci volte fa **1 traccia**: stella, ascolti, durata e grafico sono
campi *dentro* quel documento, e l'upload lo riscrive intero. Contare i gesti
darebbe un numero più grande e più falso — non c'è nessun lavoro in più da
fare. È la stessa regola del contatore dei pendenti, e per un giro i due
messaggi hanno detto cose diverse: quello di sync sommava tutto in un "1
caricato" che non diceva *cosa*.

**Due cose non passano da Firestore, ed è voluto:**

- **`mioVoto`** — la stella è di questo telefono. Il documento remoto porta solo
  i contatori di gruppo (`votiPieni`, `votiMezzi`); scrivere anche il voto
  personale lo renderebbe quello di tutti. Al pull, `accettaDalCloud` rilegge
  il voto locale e lo rimette al suo posto — senza, ogni sincronizzazione
  cancellerebbe le stelline.
- **Le chiavi AES** dei file MEGA. Su Firestore va `idFileMega`, mai la chiave.

**L'identità è `appUid`, non l'UID di Anonymous Auth.** Quello cambia a ogni
reinstallazione, e usarlo come chiave vorrebbe dire perdere i propri commenti
reinstallando. Anonymous Auth serve solo a far passare le regole, che chiedono
"un utente autenticato qualsiasi".

**Il Gate legge i profili da Firestore ma parte dalla cache.** `ProfiliStore`
non è più il cloud simulato: è l'ultima copia nota dell'elenco. Chi ha appena
reinstallato apre l'app sul Gate e deve riconoscersi in quella lista —
aspettare la rete gliela lascerebbe vuota nel momento peggiore, e senza linea
vuota resterebbe.

**Limiti noti di questo primo giro:**

- Scollegando una cartella, le sue **tracce restano su Firestore**. Sparisce il
  documento cartella e spariscono le righe locali, ma i documenti `tracce/` con
  quel `cartellaId` no. Non danno fastidio — senza la cartella nessuno li
  guarda — e ricollegandola tornano al loro posto, ma sono spazzatura che prima
  o poi va raccolta.
- Il pull **non cancella le tracce sparite dal cloud**, solo i commenti. Una
  traccia tolta da un altro membro resta visibile qui finché non si rilegge la
  cartella da MEGA.
- Nessun `listener` in tempo reale: è una scelta della v1, i commenti non si
  aggiornano da soli mentre l'app è aperta.

---

### Traccia precedente e successiva

I due tasti stanno nella **barra in ascolto** e nella **notifica**, e finiscono
nello stesso punto: `AppViewModel.tracciaSuccessiva()` /
`tracciaPrecedente()`. Nelle card delle singole tracce non ci sono — lì la
traccia ce l'hai già davanti e la tocchi.

**La notifica non decide, chiede.** `ComandiNotifica` riceve il tap e manda una
`Direzione` su `ComandiTraccia`; il ViewModel la raccoglie e salta. Un
`BroadcastReceiver` che leggesse Room per rispondere da sé darebbe una seconda
risposta possibile alla stessa domanda — quale sia la traccia dopo dipende
dalle cartelle collegate, dal loro ordine e dall'ordinamento scelto — e prima o
poi le due divergerebbero: due tasti identici, due comportamenti diversi a
seconda di dove li premi.

**Il passaggio fra cartelle non è codice, è una conseguenza.**
`elencoCompleto()` mette in fila le tracce di tutte le cartelle nell'ordine in
cui si vedono, e da lì "successiva" è **indice + 1**. L'ultima traccia di una
cartella e la prima della successiva sono vicine di posto: non c'è nessun caso
particolare da gestire, e quando si cambia cartella un messaggio ne dice il
nome, perché chi ha lo schermo bloccato non vedrebbe cambiare il titolo.

**Gira su sé stesso**: dopo l'ultima traccia dell'ultima cartella si torna alla
prima della prima. Fermarsi in fondo lascerebbe un tasto che ogni tanto non fa
niente senza dire perché, e su una notifica — dove non si vede a che punto
della libreria si è — sembrerebbe rotto.

> **Se l'app non è in memoria, i tasti della notifica non fanno niente.** Chi
> ascolta la richiesta è il ViewModel. In pratica il caso non si presenta: la
> notifica esiste finché esiste il servizio, e Android che uccide il processo
> porta via entrambi. Se un domani dovranno funzionare a processo morto, la
> strada non è duplicare la logica nel receiver — è farla vivere sotto il
> ViewModel, dove il servizio possa raggiungerla.

**Domanda aperta:** a fine traccia la riproduzione **si ferma**, non passa alla
successiva. È il comportamento di prima e non l'ho cambiato, perché su
registrazioni di prove può essere voluto — si ascolta una take e ci si vuole
pensare, non farsi portare avanti. Ora che la navigazione esiste, il
concatenamento automatico sarebbe due righe in `player.onFine`.

---

### Come funzionano i download

**C'è una coda sola, e ci passa tutto.** Il tasto sulla singola traccia e lo
"Scarica tutte" non scaricano: **accodano**. A scaricare c'è un solo
consumatore, che prende una traccia alla volta.

Prima erano due meccanismi paralleli — `avviaDownload` con la sua mappa di job,
`avviaBulk` con la sua coda — ognuno con il proprio stato e le proprie regole
di pausa. Separatamente funzionavano. Ogni guaio nasceva dove si toccavano, e
ogni rattoppo ne scopriva un altro: gli errori 30 e 33 sono lo stesso difetto
trovato due volte. Domande come *"se fermo una singola si ferma anche la
coda?"* non avevano risposta perché il codice non ne aveva una — dipendeva da
chi arrivava primo.

Con una coda sola quelle domande hanno una risposta per costruzione:

| Gesto | Cosa succede |
|---|---|
| Scarica su una traccia | va in fondo alla fila |
| Pausa su quella in corso | si ferma **solo quella**, la coda passa alla prossima |
| Pausa su una in attesa | esce dalla fila, resta il parziale |
| "Scarica tutte" | accoda tutte le mancanti della cartella |
| "Scarica tutte" mentre qualcosa gira | diventa **"Ferma tutte"** e svuota la fila |
| Errore di rete su una traccia | quella va in pausa; se è "senza linea" si ferma anche il resto, che fallirebbe uguale |

**Una alla volta**, come fanno Spotify, YouTube Music e Pocket Casts. Su una
linea lenta dieci download in parallelo si dividono la banda e finiscono tutti
tardi, mentre in fila la prima traccia è ascoltabile quasi subito. In più MEGA
è un servizio pubblico: molte connessioni insieme sono il modo migliore per
farsi rallentare.

**Tre fasi, non due:** `ATTESA`, `CORSO`, `PAUSA`. "In attesa" prima non
esisteva, e una traccia in fila si presentava identica a una che stava
scaricando — con la percentuale ferma, perché nessuno la stava toccando. Era
metà della confusione. Ora solo la traccia in `CORSO` è in accent; le altre
sono spente, e il tasto in cima dice "Ferma tutte".

**Ogni fase si legge in parole, non solo dal colore.** La card scrive
`40%` mentre scarica, `In coda · 40%` mentre aspetta il turno, `In pausa · 40%`
quando è ferma. Il numero da solo non distingueva una traccia che sarebbe
ripartita da sola da una che aspettava un tocco: erano la stessa percentuale
grigia. Lo zero non si scrive mai — "In coda · 0%" farebbe pensare a un
download partito e piantato.

**Dove si vede la coda:** la nuvoletta col numero (`IndicatoreCoda`) sta in due
posti, addosso alla cosa di cui parla — accanto al tasto "Scarica tutte" dentro
la cartella, e sulla riga della cartella in Home. In topbar c'era stata per un
giro, ma da lì diceva solo "sta scaricando qualcosa, da qualche parte": chi la
vedeva doveva ancora cercare *dove*, e con più cartelle collegate è una ricerca
vera.

**La fase appartiene a chi dà i comandi, non a chi riporta i byte.**
`onProgresso` aggiorna solo il numero: `cancel()` è cooperativo e non
interrompe una `read()` già in volo, quindi dopo una pausa arriva ancora un
aggiornamento, e se quello riscrivesse la fase rimetterebbe la traccia in
"sta scaricando" un istante dopo che l'utente l'ha fermata.

**Il worker si spegne quando la coda è vuota** e viene risvegliato da chi
accoda. Fra il controllo "coda vuota" e l'azzeramento di `workerCoda` non c'è
nessuna sospensione: `accoda` non può infilarsi in mezzo e trovare un worker
che sta per morire. Regge perché entrambi girano sul dispatcher principale —
spostarne uno su un thread di fondo riaprirebbe quella finestra.

**Quello che ancora manca:** i download vivono in `viewModelScope`, quindi se
Android chiude l'app si fermano. La topbar mostra una nuvoletta finché
qualcosa è in coda, ma è un indicatore in-app: serve un foreground service con
notifica di progresso perché sopravvivano davvero.

---

#### 34. `derivedStateOf` evita le ricomposizioni, non i calcoli

Scorrere una cartella con molte tracce scattava. Il colpevole era il blocco
che decide se la barra in ascolto va mostrata:

```kotlin
val cardVisibile by remember {
    derivedStateOf {
        val tracce = state.tracce.filter { ... }.ordinate(state.ordinamento)
        val indice = tracce.indexOfFirst { it.id == id }
        val info = listaTracce.layoutInfo          // <- cambia a ogni frame
        ...
    }
}
```

`derivedStateOf` serve a non far ricomporre nessuno quando il **risultato** non
cambia, e quello lo faceva benissimo: `cardVisibile` è un booleano che si muove
di rado. Ma il **corpo** si rivaluta ogni volta che cambia una sua dipendenza,
e lì dentro c'era `layoutInfo`, che durante lo scorrimento cambia a ogni frame.
Sessanta filtri e sessanta ordinamenti completi al secondo, per rispondere sì o
no.

*Fix:* fuori dal blocco tutto ciò che non dipende dallo scorrimento — la lista
filtrata e ordinata, e la ricerca dell'indice — in `remember` con le loro
chiavi vere (le tracce, l'ordinamento, la traccia in ascolto). Dentro resta
solo il confronto con `layoutInfo`. La stessa lista serve anche a
`FolderScreen`, che la ricalcolava per conto suo a ogni tick del cursore.

*Attenzione alla chiave:* `remember(indiceInAscolto) { derivedStateOf { … } }`.
Il valore estratto è un `Int` normale, non uno stato osservabile: senza chiave
il blocco lo cattura una volta sola e continua a guardare la posizione della
prima traccia ascoltata per sempre.

*Da ricordare:* un `derivedStateOf` che legge `layoutInfo`, `scrollState` o
qualsiasi cosa cambi a ogni frame è un pezzo di codice che gira a 60 Hz. Dentro
ci va solo quello che deve girare a 60 Hz.

**Non bastava.** Con cinque tracce lo scorrimento tornava fluido, con dieci no:
segno che il costo era **per card**, non per lista. Il secondo colpevole era
`AppIcon`:

```kotlin
val parsed = remember(spec) {
    spec.paths.map { it to PathParser().parsePathString(it.d).toPath() }
}
```

`remember` vive quanto il posto del composable nella composizione, e in una
`LazyColumn` quel posto viene buttato appena la card esce dallo schermo.
Scorrendo, ogni card che rientrava riparsava **tutte** le sue icone — play,
stella, kebab, nuvola — da stringa SVG a `Path`, ognuna daccapo.

*Fix:* una mappa a livello di file, popolata alla prima richiesta. Le icone
sono costanti dichiarate in `AppIcons` e non cambiano mai, quindi i `Path` si
costruiscono una volta per tutta la vita dell'app invece di una volta per
apparizione. Non serve sincronizzarla: la composizione gira sul thread
principale.

*Da ricordare:* `remember` in una lista pigra non è una cache, è una memoria
che dura quanto la visibilità. Per qualcosa di costoso e immutabile — path
parsati, regex compilate, formatter — serve un posto che viva più a lungo del
composable.

**Nemmeno quello bastava**, e il colpevole vero era il più grosso dei tre.
`EqualizerBackground` impaginava **una barra per composable**, con `EQ_BARS =
36`, e dava a ciascuna la sua `animateDpAsState`. Trentasei nodi di layout e
trentasei animazioni **per card**, moltiplicati per le card a schermo: con
dieci tracce sono trecentosessanta animazioni registrate. E si pagavano anche
a riposo, perché `animateDpAsState` esiste comunque, ferma o no — motivo per
cui lo scatto si sentiva pure senza niente in riproduzione.

*Fix:* un `Canvas` solo. Le stesse barre diventano trentasei `drawRoundRect`
in fase di **draw**: nessun nodo, nessuna misurazione, nessuna ricomposizione.
L'animazione è una sola — un avanzamento da 0 a 1 fra le altezze di prima e
quelle nuove, che il disegno interpola. Muovendo solo la fase di draw, Compose
non ricompone e non riesegue il layout di niente.

*Da ricordare:* qualunque cosa si ripeta **per elemento dentro una card** va
contata due volte, perché le card in una lista sono già un moltiplicatore. Un
composable per barra sembra ragionevole finché non lo si moltiplica per 36 e
poi per 10. Se una cosa è solo disegno, disegnala.

**E la vera risposta era un'altra: era una build di debug.** In release lo
scorrimento è liscio, senza un solo scatto — sulla stessa cartella, sullo
stesso telefono, con lo stesso codice. Una build di debug non ha R8 e ha la
strumentazione di Compose accesa, che traccia ogni composizione: sulle liste
la differenza non è del dieci per cento, è di un ordine di grandezza. I
fotogrammi da 330 ms misurati in debug non esistevano per nessun utente.

*Da ricordare, ed è la parte che costa:* **prima di ottimizzare, misurare
dove conta.** Qui sono stati fatti tre giri di ottimizzazione — sensati, tutti
su costi veri — inseguendo un problema che nella build che la gente usa non
c'era. Nessuno dei tre era sbagliato e nessuno peggiorava l'interfaccia, ma
l'ordine giusto era: provare in release, e solo se scatta anche lì mettersi a
cercare. Un profilo preso sulla build sbagliata risponde con precisione alla
domanda sbagliata.

Il misuratore è rimasto (`MisuratoreScatti`, tag `RegiStrazioScatti`) e resta
utile — ma i suoi numeri vanno letti sapendo che sono numeri di debug, cioè
buoni per confrontare *prima e dopo una modifica*, non per decidere se c'è un
problema.

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
dall'HTML, non si importa un file. **L'unica eccezione è il logo**
(`res/drawable/ic_logo.xml`, da `Logo_NoBackground.svg`): si tinge come le
altre, ma il suo viewBox non è quadrato — 136,87 × 104,32 — e `AppIconSpec`
di viewBox ne tiene uno solo, dando per scontato che le icone siano quadrate.
Vero per i glifi del prototipo, non per un marchio più largo che alto.

**La prima casella della topbar non è mai vuota.** Dentro una cartella ospita
la freccia indietro, in Home e nel Gate il logo — sempre 40dp, così il titolo
parte dalla stessa ascissa e la barra non si "rimonta" cambiando schermata. Chi
aggiunge una schermata non deve nascondere la casella: deve decidere cosa ci
mette dentro.

**Il logo in topbar è tinto, non a colori.** Prende `textSecondary`, lo stesso
`tint` della freccia che sostituisce: nello stesso punto della barra due
disegni di peso diverso si notano passando da una schermata all'altra, ed è
il salto che quella casella esiste per evitare. In più segue il tema scuro da
sé, senza una seconda versione del file da tenere allineata. Il logo **a
colori** resta quello dell'icona dell'app (`Logo_RegiStrazio.svg` →
`ic_launcher_*`): sono due file diversi e vanno tenuti tali, uno ha lo sfondo
crema e l'altro no.

**Cose che il prototipo fa e che Compose non ha già pronte** — sono già
risolte, non riprogettarle:
- bordo tratteggiato → `drawBehind` + `PathEffect.dashPathEffect`
  (`HomeScreen.kt`), perché non esiste un `border-style: dashed`
- mezza stella → `clipToBounds` su un contenitore da 6dp sopra la stella piena
- comparsa/scomparsa del mini player → logica `barraCollegata` in `AppRoot.kt`,
  che replica lo "scollegamento" del prototipo

**Il tasto play ha tre stati, non due.** `inRiproduzione` è l'intenzione
(play premuto), `audioAttivo` è il suono che esce davvero: fra i due c'è il
tempo di chiedere l'indirizzo a MEGA e riempire il buffer. Il cerchio resta
scarico e gira finché non parte il suono, e solo allora si riempie di accent.
Chi aggiunge un comando di riproduzione deve guardare `audioAttivo`, non
`inRiproduzione`, o tornerà a promettere un audio che non c'è. Nel mini player
la distinzione non serve: lì la traccia è già caricata e play/pausa sono
istantanei.

**Scollegare una cartella spetta a chi l'ha collegata.** Non è un gesto
locale: toglie la cartella a tutto il gruppo. Il foglio account elenca quindi
solo le cartelle con `aggiuntoDa` uguale al proprio `appUid` — più quelle con
`aggiuntoDa` vuoto, che nessuno rivendica e che nascondere renderebbe
irremovibili.

**Il passaggio da streaming a file locale avviene alla prima interruzione.** Se
il download finisce mentre la traccia suona, l'audio non si interrompe per
rimettere lo stesso brano da un'altra sorgente. Ma le interruzioni che ci sono
già si sfruttano tutte: la pausa, il salto a un commento e il trascinamento del
cursore ripartono dal file. `caricataDaFile` ricorda da dove il player stava
leggendo **quando ha cominciato**: non basta guardare `fileLocali`, che dice
solo se il file c'è adesso, ed è la differenza fra i due a dire se conviene
ricaricare. Il trascinamento è sicuro perché `onSposta` scatta una volta sola,
al rilascio del dito — durante il movimento non arriva niente.

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
- ~~**Un download in pausa non sopravvive alla chiusura dell'app.**~~ Chiuso
  con l'errore 31: `dimensioneByte` è in `TracciaEntity`, e la percentuale di
  partenza si legge dal `.parziale` sul disco invece che dallo stato in memoria.
- **Versioni sparse.** Room, Media3, OkHttp, Gson, coroutines e security-crypto
  sono ancora scritte a mano in `app/build.gradle.kts` invece che nel catalog.
- **`applicationId` è ancora `com.example.registrazio`**, il default di Android
  Studio. Va cambiato **prima** di qualunque pubblicazione, e cambiarlo dopo
  aver configurato Firebase richiede di rigenerare `google-services.json`.
- **`security-crypto` è una alpha.** `1.1.0-alpha06` regge l'identità utente,
  che è la cosa più delicata dell'app. Da tenere d'occhio.
- **La durata si sa solo premendo play.** È la radice degli errori 26 e del
  salto dei marker quando la scala si assesta: fino al primo play la posizione
  dei commenti è una stima. Due strade per chiuderla davvero, in ordine di
  costo: **(a)** stimarla dalla dimensione del file — che ora è già in
  `TracciaEntity.dimensioneByte`, quindi non costa più niente prenderla, ma
  resta una stima, perché il bitrate non lo conosciamo; **(b)** scaricare i
  primi kilobyte e leggere l'header audio — AES-CTR permette di decifrare un
  intervallo qualsiasi, quindi è fattibile, e darebbe il numero **vero** senza
  riprodurre niente. La (b) è la risposta giusta, la (a) un tampone.
- **Il banco di prova dichiara ascolti e commenti ma non le durate**, e le due
  cose non stanno insieme: se qualcuno ha commentato al minuto 2, quella
  traccia l'ha ascoltata, e la sua durata sarebbe già su Firestore. Si chiude
  scrivendo la durata nota accanto ai voti in `DatiDiProva`, che però va
  misurata a mano una volta per traccia.

---

## Riferimenti

- Prototipo UI: `prova-app-v3-integrata.html` (spec grafica 1:1)
- Logo a colori (icona dell'app): `Logo_RegiStrazio.svg`
- Logo monocromo (topbar): `Logo_NoBackground.svg`
- Repository: `https://github.com/DrFonzarelli/RegiStrazio`
- Firebase Console: `https://console.firebase.google.com`
- MEGA API (community docs): `https://mega.py.readthedocs.io/en/latest/api.html`
- ExoPlayer Media3: `https://developer.android.com/guide/topics/media/exoplayer`
- Android Auto Backup: `https://developer.android.com/guide/topics/data/autobackup`
