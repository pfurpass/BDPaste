# BDPaste

Paper-Plugin, das Modelle von [bdengine.app](https://bdengine.app/) importiert und sie
**mit dem Fadenkreuz** in der Welt platziert — statt riesige `/summon`-Befehle in Command-Blöcke zu kopieren.

Läuft auf **Paper 1.20.4 und neuer**, Forks wie Purpur eingeschlossen — gebaut gegen
`paper-api 1.20.4` mit **Java 17** und gegen jede Version von dort bis 26.2 übersetzt geprüft.
Reines Spigot/CraftBukkit funktioniert nicht, siehe [Versionen](#versionen).

[spigotmc](https://www.spigotmc.org/resources/bdpaste.138329/)

---

## Installation

1. `BDPaste-1.0.0.jar` nach `plugins/` kopieren, Server starten.
2. Beim ersten Start entstehen `plugins/BDPaste/config.yml` und `plugins/BDPaste/models/`.
3. Modelldateien in `plugins/BDPaste/models/` legen.
4. `/bdpaste list` zeigt, was gefunden wurde.

## Modell aus BDEngine holen

Es gehen **vier** Wege — such dir den bequemsten aus:

| Datei | Woher in BDEngine | Bemerkung |
|---|---|---|
| `.bdengine` | Projekt speichern / herunterladen | **empfohlen**, enthält den kompletten Baum |
| `.json` | rohes Projekt-JSON | falls schon entpackt |
| `.txt` / `.mcfunction` | „Export to Minecraft“ → Summon-Befehl kopieren, in eine Textdatei einfügen | mehrere `summon`-Zeilen (Summon #1, #2 …) einfach untereinander |
| `.zip` | „Export Model as a datapack“ | enthält Modell *und* fertig gebackene Animationsspuren |

Dazu kommt das JSON-Format, das `/bdpaste import` von block-display.com holt (siehe unten) —
es landet als `.json` im Models-Ordner und ist danach ganz normal nutzbar.

Der Dateiname ohne Endung ist der Modellname: `mater.bdengine` → `/bdpaste place mater`.

### Direkt von block-display.com

Am bequemsten: den Share-Link der Modellseite nehmen, fertig.

```bash
/bdpaste import https://bde.gg/b/298796
```

Funktioniert mit `bde.gg/b/<id>`, `block-display.com/bd/<id>`, dem BDEngine-Editor-Link
(`bdengine.app/?...&id=<id>`) oder einfach der nackten ID (`/bdpaste import 298796`).

block-display.com veröffentlicht Modelle auf zwei Arten, und welche es ist, entscheidet die
Seite pro Modell: ältere als fertige Summon-Befehle, neuere als Editor-Projektdatei
(gzip-komprimierter `PRJ2`-Container). BDPaste probiert beides der Reihe nach durch — du
merkst davon nichts.
Der Modellname kommt dann von der Seite selbst — optional überschreibbar:
`/bdpaste import 298796 gogzilla`.

Ein direkter Datei-Link geht auch:

```bash
/bdpaste import https://example.com/mater.bdengine
```

## Platzieren

```bash
/bdpaste place mater
```

Dann folgt eine echte, vollständige Vorschau deinem Fadenkreuz (nur du siehst sie):

| Eingabe | Wirkung |
|---|---|
| Maus bewegen | Modell positionieren |
| Mausrad | den gerade gewählten Wert ändern |
| Shift + Mausrad | anderen Wert wählen (Distanz, Yaw, Pitch, Roll, Scale, Offset X/Y/Z) |
| F (Hände tauschen) | **einfrieren / lösen** — Modell bleibt stehen, du kannst drumherum laufen |
| Rechtsklick | platzieren |
| Linksklick | **Schrittweite wechseln** |
| Shift + Linksklick | gewählten Wert zurücksetzen |
| Shift + F | Snap umschalten: aus / Pixel / Block |
| Q (Item droppen) | **pausieren / fortsetzen** — kurz raus, was bauen, dann zurück |
| Shift + Q | abbrechen |

Die Actionbar zeigt durchgehend auch die **Koordinaten**, an denen das Modell gerade sitzt.

**Einfrieren** ist zum Prüfen vor dem Akzeptieren: einmal **F**, das Modell hängt sich vom
Fadenkreuz ab und bleibt stehen. Jetzt kannst du drumherum laufen und es dir von allen Seiten
ansehen. Drehen, Skalieren und die Offsets funktionieren dabei weiter — nur die Position ist
fest. Nochmal **F** löst wieder, Rechtsklick akzeptiert. Die Actionbar zeigt `FROZEN`.

Modell und Fadenkreuz bleiben beim Skalieren und Drehen fest verbunden — der Pivot
(je nach `placement.pivot` die Modellmitte unten oder der BDEngine-Nullpunkt) sitzt immer
genau auf dem Cursor.

Hat man beim Start nichts in der Hand, legt BDPaste kurzzeitig einen Stock hinein und nimmt
ihn danach wieder weg. Grund: Vanilla-Clients schicken beim Rechtsklick **in die Luft** mit
leerer Hand gar kein Paket — ohne Item ließe sich nur platzieren, während man auf einen Block
zielt. Ein bereits belegter Slot wird nie angefasst.

Die Actionbar zeigt durchgehend Modellname, aktiven Wert, Yaw/Pitch/Roll, Scale und Snap-Status.

### Schrittweite

Jeder Wert hat eine eigene Leiter, die **Linksklick** durchschaltet:

| Wert | Stufen |
|---|---|
| Yaw / Pitch / Roll | 1° · 5° · 15° · 45° · 90° |
| Offset X/Y/Z | 0.03125 · 0.0625 · 0.125 · 0.25 · 0.5 · 1 Blöcke |
| Scale | ×1.01 · ×1.02 · ×1.05 · ×1.1 · ×1.25 · ×2 |
| Distanz | 0.25 · 0.5 · 1 · 2 · 5 |

Die aktuelle Schrittweite steht in der Actionbar. Beliebige Werte gehen auch:

```bash
/bdpaste step 0.01
/bdpaste step 7.5 yaw
```

Verstellt? `/bdpaste step` listet alle Schrittweiten auf, `/bdpaste step reset` setzt sie
auf die Werte aus der `config.yml` zurück.

Schrittweiten, Grid-Snap und der zuletzt gewählte Wert werden **pro Spieler gemerkt** und
liegen in `plugins/BDPaste/players.yml` — beim nächsten Modell ist alles wieder so eingestellt,
auch nach einem Serverneustart.

### Snap

Drei Stufen, umschaltbar mit **Shift + F** oder `/bdpaste snap [off|pixel|block]`:

| Modus | Raster | wofür |
|---|---|---|
| `block` | 1 Block | am Bauraster ausrichten |
| `pixel` | 1/16 Block | Feinjustierung auf Texturpixel |
| `off` | keins | exakt dorthin, wo du zeigst |

Auf einer Oberfläche rastet `pixel` auf das Pixelraster der Blockfläche, `block` auf die
Blockecke. Frei in der Luft gilt dasselbe Raster. Die Rastergröße für `pixel` steht als
`placement.pixel-snap` in der `config.yml` (Standard `0.0625` = 1/16).

### Kurz rausgehen

**Q** pausiert: die Vorschau bleibt stehen, du bekommst Hotbar und Klicks zurück und kannst
ganz normal Blöcke setzen oder abbauen, um zu vergleichen. Nochmal **Q** (oder **Shift + F**
bzw. `/bdpaste resume`) holt dich zurück. Die Actionbar zeigt `PAUSED`.

Weil Q zurückholt, kannst du während der Pause keine Items wegwerfen — alles andere geht.

Zum Abbrechen ist es jetzt **Shift + Q** — die zerstörerische Aktion liegt hinter dem Modifier,
nicht die harmlose.

Für exakte Werte statt Mausrad:

```bash
/bdpaste set yaw 90
/bdpaste set scale 0.5
/bdpaste set offset_y 1.5
```

## Animationen

Animationen kommen aus jeder Quelle — Link, `.bdengine`-Datei oder Datapack. Ein Extraschritt
ist nicht nötig:

```bash
/bdpaste import https://block-display.com/bd/299039 fermier
/bdpaste place fermier
/bdpaste animate              # startet die erste Animation
/bdpaste animate talking      # eine bestimmte Spur
/bdpaste animate talking 0.5  # halbes Tempo
/bdpaste animate talking once # einmal abspielen, Endpose bleibt stehen
/bdpaste animate off
```

Die Argumente stehen in beliebiger Reihenfolge: eine Zahl ist das Tempo, `once` bzw. `loop`
entscheidet über die Wiederholung, alles andere ist ein Animationsname.

`/bdpaste inspect fermier` listet die enthaltenen Spuren mit Länge auf. Die Namen sind die
aus dem Editor, BDPaste liest sie aus `listAnim` der Projektdatei bzw. aus den Ordnernamen
des Datapacks.

**Wie genau das ist.** Aus einem Datapack spielt BDPaste die dort pro Tick hinterlegten
Matrizen ab — nachgemessen über zwei komplette Durchläufe, 86 Teile, 25 Ticks: Abweichung
0.000000000. Aus der Projektdatei rechnet BDPaste dieselben Posen selbst aus; gegen das
Datapack desselben Modells geprüft bleibt der Unterschied unter **0,0008 Pixel** — und zwar
bei einem Modell, das animierte Gruppen fünf Ebenen tief verschachtelt und an fast jeder
davon einen eigenen Pivot hängen hat.

Der Schlüssel dazu ist, wie BDEngine einen Pivot in die Matrix eines Knotens einrechnet:

```
transforms = T(pivot_Eltern) · T(position) · Rx · Ry · Rz · S · T(−pivot_eigen)
```

Im Ruhezustand heben sich die beiden Pivot-Terme zwischen Eltern und Kind gegenseitig auf —
deshalb kommt der statische Import auch ohne sie aus. Sobald eine Gruppe animiert, tun sie das
nicht mehr: `T(−pivot_eigen)` sorgt dafür, dass die Gruppe **um ihren Pivot** dreht und
skaliert, und `T(pivot_Eltern)` setzt sie in den Rahmen zurück, den ihre Elterngruppe
weitergibt. Fehlt der vordere Term, sitzt jedes Kind einer Gruppe mit Pivot genau um diesen
Pivot versetzt — das ist es, was verschachtelte Modelle früher auseinandergerissen hat.

Animiert werden nur die Teile unter einer animierten Gruppe — beim Propeller drehen sich
die 45 Blätter, die 24 Gehäuseteile bleiben stehen. Der Zustand wird gespeichert und läuft
nach einem Serverneustart von selbst weiter, sobald jemand in die Nähe kommt.

Steuerung in der `config.yml` unter `animation:` — Aktualisierungsintervall, Obergrenze für
gleichzeitig laufende Modelle und der Radius, ab dem überhaupt animiert wird.

> Modelle, die du **vor** dieser Version platziert hast, tragen noch keinen Teil-Index und
> lassen sich nicht animieren. Einmal `/bdpaste move` und neu bestätigen genügt.

### Sound

Manche Modelle bringen eine Notenspur mit — BDEngine legt sie unter `listSound` ab, eine pro
Animation. BDPaste spielt sie beim Animieren automatisch mit:

```bash
/bdpaste import https://block-display.com/bd/295020 rat
/bdpaste place rat
/bdpaste animate dance
```

`/bdpaste inspect rat` zeigt an, welche Animation Noten hat und wie viele.

Eine Zeitleiste kann auch **nur** aus Noten bestehen, ganz ohne Keyframes — RUSH E auf
block-display sind 1071 Noten und kein einziger Keyframe. Solche Modelle stehen still und
spielen, und `/bdpaste inspect` schreibt `(sound only)` dahinter. Die Länge der Notenrolle
bestimmt dann die Schleife.

Läuft die Rolle länger als die Animation, gibt ebenfalls sie den Takt vor: die Bewegung hält
ihre Endpose, bis die Musik durch ist. Genau das zeigt der Editor an der Stelle auch.

Der Ton kommt **am Modell** heraus, verhallt also mit der Entfernung wie alles andere, und
läuft über dieselbe Kategorie wie Notenblöcke — der Spieler regelt ihn also über den
Jukebox-Regler, nicht über Master. Abschalten oder leiser stellen geht in der `config.yml`:

```yaml
animation:
  sound: true
  sound-volume: 1.0
```

#### Tempo

Das Feld heißt `tick`, ist aber **kein Tick-Zähler, sondern ein Index** in eine Tabelle von
Schrittweiten. Der Editor rechnet an drei Stellen dasselbe:

```js
needTime = Math.floor(currentTime / (1 === tick ? .5 : tick - 1))          // Wiedergabe
getSoundStepTicks: 1 -> 1,  2 (default) -> 2,  3 -> 4                       // Export, Game-Ticks
schedule-Verzögerung: 1 -> .05s,  2 -> .1s,  3 -> .2s                       // Export, Sekunden
```

Ein Schritt dauert also **1, 2 oder 4 Game-Ticks**. Die Oberfläche begrenzt `tick` beim Speichern
auf 1..3, andere Werte kann ein Projekt nicht enthalten.

Als „so viele Ticks" gelesen stimmt es zufällig für 1 und 2 und ist bei 3 um ein Drittel zu
schnell — RUSH E lief damit 85 statt 114 Sekunden.

#### Doppelte Noten

Echte Rollen enthalten dieselbe Note mehrfach zur selben Zeit: RUSH E hat 111 exakte Doppel
über 59 Schritte, an einer Stelle fünffach. Zwei identische Samples, die im selben Tick am
selben Ort starten, klingen nicht wie zwei Noten, sondern wie eine mit doppelter Amplitude —
phasengleich, und fünf davon übersteuern. Der Editor feuert sie alle und hat keinen Limiter,
was ihm Web Audio verzeiht; das Spiel nicht.

BDPaste wirft exakte Doppel weg — gleicher Schritt, gleicher Sound, gleiche Tonhöhe. Ein Akkord
sind mehrere *verschiedene* Töne auf einem Schritt und bleibt unangetastet.

#### Tonhöhe

Der Editor lässt Noten weit außerhalb dessen zu, was Minecraft abspielen kann. Seine Vorschau
läuft über Web Audio, das jede Abspielrate mitmacht; das Spiel kappt auf 0,5 bis 2,0, also je
eine Oktave nach oben und unten. Beim Rat liegen **49 % der Harfennoten** außerhalb — der
Bereich geht von −30 bis +15 Halbtönen, fast vier Oktaven.

BDEngines eigener Datapack-Export schreibt trotzdem den Rohwert:

```
playsound ${note.id} block @a ~ ~ ~ ${round2(note.volume)} ${round3(note.pitch)}
```

Damit klingt dort dieselbe Hälfte der Noten falsch, und Werte über 2,0 lehnt `/playsound` sogar
ganz ab. Es gibt also nichts zum Abschauen.

BDPaste löst es so, wie Minecraft es selbst vorsieht: über das **Instrument** statt über die
Tonhöhe. Jeder Notenblock-Sound deckt zwei Oktaven ab, und sie sitzen im Oktavabstand
übereinander. Eine zu tiefe Note geht an einen tieferen Sound und erklingt **exakt auf der
geschriebenen Frequenz** — nur mit anderer Klangfarbe. Genau so ist Notenblock-Musik immer
arrangiert worden.

Welcher Sound das ist, lässt sich nicht aus der Datei ableiten, sondern nur nach Gehör
entscheiden — also steht es in der Config:

```yaml
animation:
  sound-low: bass      # für Noten unterhalb des spielbaren Bereichs
  sound-high: bell     # für Noten darüber
```

| Versatz | Instrumente |
|---|---|
| zwei Oktaven tiefer | `bass`, `didgeridoo` |
| eine Oktave tiefer | `guitar` |
| eine Oktave höher | `flute`, `cow_bell` |
| zwei Oktaven höher | `bell`, `chime`, `xylophone` |

Der Versatz ist pro Instrument bekannt, du musst also nur den Namen tauschen — der Ton bleibt
derselbe, egal welches du nimmst. `flute` pfeift, `bell` klingelt, `chime` ist weicher,
`xylophone` hölzern.

Beim Rat wechseln damit 70 von 212 Noten das Instrument, keine einzige verschiebt sich in der
Tonhöhe. Schlagwerk (`hat`, `snare`, `basedrum`) hat keine Oktave zum Verschieben und wird
gekappt statt versetzt.

Was bleibt: nur **Projektdateien** tragen Noten. Ein Datapack-Export backt seine Sounds in
Funktionen — ein anderes Format, das BDPaste nicht liest.

**Tempo.** Ein Keyframe ist kein Server-Tick: BDEngine hält jeden Keyframe 0,1 Sekunden —
im Editor wie in den Datapacks, die es exportiert (`schedule ... 0.1s` mit
`interpolation_duration:2`). BDPaste macht es genauso, zwei Ticks pro Keyframe. Wer es global
anders will, stellt `animation.ticks-per-keyframe` in der `config.yml` um; für ein einzelnes
Modell reicht `/bdpaste animate <name> <tempo>`.

## Namensschild

Optional — ohne Zutun bekommt kein Modell eines. Anvisieren und:

```bash
/bdpaste label <#ff8800>Shop
/bdpaste label <gradient:#ff0000:#00ff00><bold>BOSS</bold></gradient>
/bdpaste label off
```

Der Text ist **MiniMessage**, also gehen Hex-Farben, Verläufe, Fett, Hover — alles. Ohne
Argument zeigt der Befehl, was gerade dransteht, und wie es gerendert aussieht.

Ein Tag, das MiniMessage nicht kennt, wird **nicht abgelehnt**, sondern bleibt als Text stehen.
Es gibt also nichts zu validieren; wenn eine Farbe nicht greift, siehst du das direkt an der
Bestätigung, die dir das gerenderte Ergebnis zurückschreibt.

Das Schild hängt über **dem Teil, das im Ruhezustand die Oberkante bildet** — bei allem
Menschenähnlichen also der Kopf. Es dreht sich zum Betrachter, wird bei `move` und `replace`
mitgenommen und verschwindet mit dem Modell.

Über dem Kopf und nicht über der Mitte des ganzen Modells, weil die Mitte nicht dort liegt, wo
man sie vermutet: der Farmer hält eine Mistgabel zur Seite, was die Mitte einen Viertelblock von
seinem Kopf wegzieht. Und erst recht nicht über der gespeicherten Bounding-Box — die umfasst
alles, was das Modell im Lauf seiner Animation überstreicht, und weil der Farmer einen Heuballen
sechs Blöcke weit wirft, läge ihr Mittelpunkt 2,6 Blöcke neben ihm auf freiem Feld.

### Höhe nachjustieren

```bash
/bdpaste label raise -0.8
```

Negativ senkt, positiv hebt. Wird pro Modell gespeichert und bei `move` und `replace`
mitgenommen.

Das braucht es, weil sich die Höhe eines Modells nicht exakt ausrechnen lässt: ein
Item-Display ist von einem Einheitswürfel begrenzt, aber wie viel davon das Item wirklich
ausfüllt, entscheidet dessen eigenes Modell — ein Spielerkopf nutzt die Hälfte. Die
automatische Höhe ist also eine Schätzung nach oben, und `raise` korrigiert sie einmal.

### Während der Animation

Das Schild folgt seinem Teil in **alle drei Richtungen**, nicht nur in der Höhe. Das ist bei
Modellen, die sich von der Stelle bewegen, der ganze Unterschied: der Kopf der Ratte legt in
einer Runde ihres Tanzes 2,3 Blöcke zurück, und ein Name, der nur mit der Höhe mitgeht, bleibt
dabei einfach stehen.

Verfolgt wird der **Ursprung** des Teils, nicht die Oberkante seiner Box, und der Abstand
zwischen beiden steht schon beim Start fest. Das ist der Grund, warum nichts mehr zittert: die
Box eines Displays ist achsenparallel, also misst sie sich höher, sobald sich das Teil darin
dreht — beim Farmer um 0,32 Blöcke, während sein Hut sich nur neigt. Wer die Höhe jede Runde neu
an der Box abliest, überträgt dieses Atmen aufs Schild.

Nach `animate off` sitzt es wieder über der Ruhepose.

Aussehen über die `config.yml`:

```yaml
label:
  height: 0.4              # Abstand über dem Modell
  scale: 1.0
  shadow: true
  see-through: false       # durch Wände sichtbar
  background: "#40000000"  # #aarrggbb, sechs Stellen = deckend, #00000000 = keins
  view-range: 1.0
  follow-animation: true
```

## Bereits platzierte Modelle ändern

**Duplizieren** — anvisieren, Kopie bekommen, absetzen. Das Original bleibt, wo es ist:

```bash
/bdpaste duplicate
```

Die Kopie startet mit allen Werten des Originals und hängt sofort am Fadenkreuz. Fremde
Modelle darfst du kopieren — du änderst sie ja nicht.

**Am Stück platzieren** — nach jedem Absetzen kommt sofort die nächste Kopie:

```bash
/bdpaste repeat on
```

Damit setzt du eine ganze Reihe, ohne zwischendurch einen Befehl zu tippen. Beenden mit
`/bdpaste repeat off`, `/bdpaste cancel` oder Shift+Q. Die Actionbar zeigt `repeat`, und die
Einstellung wird gemerkt.

Ein `move` wiederholt nie — sonst würde daraus unbemerkt ein Duplizieren.

**Verschieben** — anvisieren, aufnehmen, bearbeiten:

```bash
/bdpaste move
```

Das Modell **bleibt stehen, wo es steht**, und startet eingefroren. Du kannst also direkt
Drehung, Größe oder Offset nachbessern, ohne alles neu anvisieren zu müssen. Erst **F**
übergibt es wieder an dein Fadenkreuz, wenn du es tatsächlich woanders hin willst.

Mitgenommen werden **alle** Werte: Yaw, Pitch, Roll, Scale und Offset X/Y/Z.

Brichst du ab (Q oder `/bdpaste cancel`), landet das Modell exakt dort wieder, wo es war —
auch wenn du dich mittendrin ausloggst oder der Server heruntergefahren wird.

**Austauschen** — gleiche Position, gleiche Drehung, gleiche Größe, anderes Modell:

```bash
/bdpaste replace mater
```

Das geht sofort, ohne Platzierungsmodus. Ist der Modellname falsch, passiert nichts —
geladen wird zuerst, gelöscht erst danach.

> Modelle, die du **vor** dieser Version platziert hast, kennen ihren Dateinamen noch nicht
> (nur den Anzeigenamen). Bei denen schlägt `move`/`replace` mit „No model called …" fehl —
> einmal `/bdpaste remove` und neu setzen, dann passt es.

## Alle Befehle

Basis: `/bdpaste` (Aliase `/bde`, `/bdp`)

| Befehl | Beschreibung | Permission |
|---|---|---|
| `list` | verfügbare Modelldateien | `bdpaste.use` |
| `inspect <modell>` | Datei parsen ohne zu spawnen: Teileanzahl, Typen, Maße, häufigste Blöcke | `bdpaste.use` |
| `place <modell>` | Platzierungsmodus starten | `bdpaste.place` |
| `duplicate` | Kopie des anvisierten Modells aufnehmen | `bdpaste.place` |
| `repeat [on\|off]` | nach dem Absetzen gleich weiterplatzieren | `bdpaste.use` |
| `freeze` | Vorschau einfrieren/lösen (wie F) | `bdpaste.use` |
| `snap [off\|pixel\|block]` | Raster umschalten | `bdpaste.use` |
| `pause` / `resume` | Platzierungsmodus kurz verlassen und zurückkehren | `bdpaste.use` |
| `step [zahl\|reset] [wert]` | Schrittweiten anzeigen, setzen oder zurücksetzen | `bdpaste.use` |
| `move` | anvisiertes Modell wieder aufnehmen und woanders absetzen | `bdpaste.place` |
| `replace <modell>` | anvisiertes Modell gegen ein anderes tauschen, gleiche Stelle | `bdpaste.place` |
| `set <wert> <zahl>` | exakten Wert setzen (während des Platzierens) | `bdpaste.place` |
| `cancel` | Platzierung abbrechen | `bdpaste.use` |
| `undo` | zuletzt platziertes Modell entfernen | `bdpaste.remove` |
| `remove` | das anvisierte Modell entfernen | `bdpaste.remove` |
| `delete <id>` | Modell per ID entfernen | `bdpaste.remove` |
| `animate [name] [tempo] [once\|loop\|off]` | Animation des Modells abspielen | `bdpaste.place` |
| `label <text\|raise <n>\|off>` | Schwebender Name über dem Modell, MiniMessage | `bdpaste.place` |
| `command <cmd\|off>` | Befehl ausführen, wenn das Modell rechtsgeklickt wird | `bdpaste.place` |
| `info` | Details zum anvisierten Modell | `bdpaste.use` |
| `near [radius]` | platzierte Modelle in der Nähe auflisten | `bdpaste.use` |
| `tp <id>` | zu einem Modell teleportieren | `bdpaste.admin` |
| `import <url-oder-id> [name]` | Von block-display.com oder direktem Link holen | `bdpaste.import` |
| `hitbox [on\|off\|sync]` | Modell anklickbar machen (für die API) | `bdpaste.admin` |
| `cleanup <radius>` | **alle** BDPaste-Displays im Umkreis löschen | `bdpaste.admin` |
| `reload` | Config neu laden, Modell-Cache leeren | `bdpaste.admin` |

Alle Permissions stehen per Default auf `op`; `bdpaste.*` fasst sie zusammen.

## Wie Modelle gefunden und entfernt werden

Display-Entities haben keine Hitbox, man kann sie also nicht anklicken. BDPaste speichert
darum beim Platzieren die Bounding-Box in `plugins/BDPaste/placements.yml` und macht bei
`/bdpaste remove` einen Raycast gegen diese gespeicherten Boxen. Zusätzlich trägt jede
Entity ihre Modell-ID im PersistentDataContainer und den Scoreboard-Tag `bdpaste` — falls
mal etwas verwaist, räumt `/bdpaste cleanup <radius>` auf.

Diese Box ist das **ruhende** Modell. Nicht die Fläche, die es im Lauf seiner Animationen
überstreicht: der Farmer wirft einen Heuballen sechs Blöcke weit, also wäre die 8,4 Blöcke
breit statt der 3,0, in denen er wirklich steht.

Was eine Animation darüber hinauswirft, bleibt bewusst draußen: du klickst den Farmer an,
nicht den Ballen, der gerade durch die Luft fliegt.

Boxen aus älteren Versionen tragen noch die alten Maße. Die werden beim Serverstart neu
vermessen, sobald ihr Chunk geladen ist — von selbst, es ist nichts zu tun. `/bdpaste
hitbox sync` stößt dasselbe von Hand an.

### Mehrere Boxen statt einer großen

Eine `interaction`-Entity kennt nur **eine** Breite für beide horizontalen Achsen. Ihre
Grundfläche ist also immer quadratisch, egal welche Form das Modell hat — und um alles
Längliche herum ist das überwiegend Luft. Stitch ist 2,57 × 1,12 Blöcke; ein Quadrat darum
ist 2,57 × 2,57, also mehr als doppelt so viel Fläche, wie er einnimmt.

Deshalb bekommt ein längliches Modell **mehrere** Boxen, der Länge nach aufgereiht. Jede
wächst um die Teile, deren Mitte in ihren Abschnitt fällt, sodass jedes Teil vollständig in
genau einer Box liegt. Eine Lücke mitten im Modell bleibt dabei eine Lücke.

Durchgerechnet werden alle Aufteilungen bis `max-boxes`, und die einzelne Box tritt mit an.
Gewinnt die kleinste — die Boxen können also nie lockerer werden als vorher, nur enger:

```
Modell         Sweep-Box    Ruhe-Box       jetzt   Boxen
Fermier           268,51       25,90       17,36   2
rat                12,75        1,73        0,49   2
stitch             17,05       15,78        3,74   3
dance             359,56      292,50       53,61   8
rush-e           3880,83     3880,83      803,08   5
```

(in Kubikblöcken, alle Testmodelle zusammen: 4851 → 921)

`interaction.padding` steht auf `0.0` — die Box ist das Modell. Höher heißt großzügiger beim
Zielen, kostet aber mehr, als es aussieht: 0,15 rundherum verdoppelt bei der Ratte das
anklickbare Volumen.

Manche Modelle bleiben groß, weil sie groß sind: RUSH E ist eine Notenrolle über 18,7 Blöcke,
`dance` verteilt 216 Teile über 10.

## Konfiguration

`config.yml` ist kommentiert. Die wichtigsten Stellschrauben:

- `max-parts` (Standard 6000) — Obergrenze für Display-Entities pro Modell. Jedes Teil ist
  eine echte Entity, große Modelle kosten echte Serverleistung.
- `spawn-per-tick` (250) — runterdrehen, wenn große Modelle beim Platzieren einen Lag-Spike geben.
- `placement.pivot` — `CENTER` (Standard, Modellmitte unten auf dem Cursor) oder `ORIGIN`
  (BDEngine-Nullpunkt auf dem Cursor, rastet sauber ins Blockraster).
- `placement.surface-reach` (32) — wie weit das Fadenkreuz nach einer Oberfläche sucht.
  Darüber hinaus schwebt das Modell in `default-distance` Entfernung.
- `display.view-range` — hochsetzen, wenn große Modelle zu früh ausgeblendet werden.
- `display.force-brightness` — überschreibt die im Modell gespeicherte Helligkeit.

## Selbst bauen

Braucht **JDK 17 oder neuer**:

```bash
mvn clean package
```

Ergebnis: `target/BDPaste-1.0.0.jar`.

## Für Entwickler

BDPaste bringt eine API mit, damit andere Plugins auf platzierte Modelle reagieren können —
etwa: Modell anklicken, Animation startet.

### Anbinden

`plugin.yml`:

```yaml
softdepend: [BDPaste]
```

Holen über den Services Manager:

```java
RegisteredServiceProvider<BdPasteApi> provider =
        Bukkit.getServicesManager().getRegistration(BdPasteApi.class);
BdPasteApi bdpaste = provider == null ? null : provider.getProvider();
```

Der Null-Check bleibt — auf einem Server ohne BDPaste soll dein Plugin weiterlaufen.

### Klicks

Damit ein Modell überhaupt anklickbar ist, braucht es eine **Hitbox**. Display-Entities haben
in Vanilla keine, daran lässt sich mit Plugin-Code nichts ändern. BDPaste setzt deshalb pro
Modell eine oder mehrere unsichtbare `interaction`-Entities — standardmäßig bei jedem neu
platzierten Modell, steuerbar über `interaction.enabled` und `/bdpaste hitbox`.

`/bdpaste hitbox off` wird beim Modell gespeichert und übersteht damit einen Neustart. Vorher
tat es das nicht: die Box wurde beim nächsten Start einfach neu gesetzt.

```java
@EventHandler
public void onClick(BdModelClickEvent event) {
    if (event.getAction() != BdModelClickEvent.Action.RIGHT) return;
    if (!event.getModel().source().equalsIgnoreCase("shop")) return;

    event.setCancelled(true);   // BDPaste soll den Klick nicht selbst verwerten
    openShop(event.getPlayer());
}
```

Ohne `setCancelled(true)` macht BDPaste danach noch das, was in der `config.yml` unter
`interaction.right-click` bzw. `left-click` steht:

| Wert | Verhalten |
|---|---|
| `NONE` | nichts, nur das Event |
| `TOGGLE` | Animation starten, bzw. stoppen wenn sie läuft |
| `CYCLE` | zur nächsten Animation weiterschalten, nach der letzten zurück auf Stillstand |

`CYCLE` ist die Voreinstellung für Rechtsklick. Ein Modell mit zwei Animationen geht damit
`labourer` → `talking` → aus → `labourer`, ganz ohne eigenen Code.

### Klick-Kommandos

Ein Modell kann einen Befehl mitbringen, den es beim Rechtsklick ausführt — das Sofa, auf das
man sich setzt, ohne eine Zeile Code:

```bash
/bdpaste command sit
/bdpaste command warp lobby
/bdpaste command off
```

Alles nach `command` wird genommen, wie es dasteht; ein Befehl mit Leerzeichen braucht keine
Anführungszeichen. Ein führender Slash fällt weg, falls du ihn aus Gewohnheit tippst.

Der Befehl läuft **statt** dem, was `interaction.right-click` in der Config sagt, nicht
zusätzlich — ein Sofa soll nicht nebenbei seine Animationen durchschalten. `/bdpaste command off`
gibt das eingestellte Verhalten zurück.

Ausgeführt wird er **als der Spieler**, kann also nichts, was der nicht selbst tippen könnte.
Für alles darüber hinaus:

```bash
/bdpaste command console: give %player% bread 1
```

Diese Form verlangt `bdpaste.admin`, die einfache nur `bdpaste.place`.

| Platzhalter | |
|---|---|
| `%player%` `%uuid%` | wer geklickt hat |
| `%model%` `%source%` `%id%` | welches Modell, und welche gesetzte Kopie |
| `%world%` `%x%` `%y%` `%z%` | wo es steht |

Koordinaten werden immer mit Punkt geschrieben, egal unter welcher Locale der Server läuft —
ein `1,5` käme beim Befehl sonst als zwei Argumente an.

Ohne Hitbox kann niemand klicken, der Befehl feuert also nie. `/bdpaste command` sagt dir das
direkt dazu; `/bdpaste hitbox on` behebt es.

### Welche Animation läuft gerade

```java
String now = bdpaste.currentAnimation(model.id()).orElse("nichts");
```

Das ist der **Live-Zustand**. Nach einem Einmal-Durchlauf wird es wieder leer, während
`model.animationName()` weiterhin die zuletzt gestartete nennt — nützlich, wenn du wissen
willst, was es zuletzt gemacht hat, aber eine andere Frage.

Die vorhandenen Animationen listet `animations(...)` auf; das muss die Modelldatei lesen und
antwortet deshalb per Callback.

### Einmal statt Dauerschleife

`loop = false` spielt die Animation einmal durch und lässt das Modell in der Endpose stehen —
eine Tür, die aufgeht, bleibt offen. Das macht der Editor genauso: sein Export ohne Schleife
übergibt am Ende an `stop_anim`, und das pausiert nur.

```java
bdpaste.play(model, "open", 1.0, false, ok -> { });
```

Zurück in die Ruhepose bringt es `stop(id)` bzw. `/bdpaste animate off`.

Ein Einmal-Durchlauf wird **nicht** als laufend vermerkt: er startet nach einem Serverneustart
nicht von selbst wieder, und `isAnimating` wird `false`, sobald er durch ist. Genau daran
erkennst du, dass er fertig ist.

Für die eingebauten Klick-Aktionen steuert das `interaction.loop` in der `config.yml`.

### Welches Modell ist es?

`Placement` trägt drei Kennungen, und die verwechselt man leicht:

| | Beispiel | wofür |
|---|---|---|
| `source()` | `fermier` | Dateiname im Models-Ordner — zum **Filtern nach Modellart** |
| `model()` | `Fermier` | Anzeigename; meist gleich, bei älteren block-display-Importen der Titel von der Seite |
| `id()` | `3a97fd42-…` | die einzelne platzierte Kopie |

Zum Filtern nimm `source()` und `equalsIgnoreCase` — `model()` kann sich in der Großschreibung
unterscheiden. Welche Werte ein Modell hat, zeigt `/bdpaste info`, während du es anvisierst.

### Wann ist die Animation zu Ende

```java
bdpaste.play(model, "open", 1.0, false,
        started -> { },
        reason -> {
            if (reason == BdAnimationEndEvent.Reason.FINISHED) doorIsOpen.add(model.id());
        });
```

Der zweite Callback läuft **einmal**, auf dem Main-Thread, egal wie es ausgegangen ist:

| `Reason` | |
|---|---|
| `FINISHED` | durchgelaufen — nur bei `loop = false`; das Modell behält die Endpose |
| `STOPPED` | jemand hat gestoppt: `animate off`, `stop()`, ein Klick, oder eine andere Animation hat übernommen |
| `GONE` | die Entities sind weg — Chunk entladen oder Modell entfernt |

`GONE` ist der Grund, warum es nicht nur einen „fertig"-Callback gibt: ein Einmal-Durchlauf in
einem Chunk, der auf halber Strecke entlädt, wird nie fertig, und du würdest ewig auf ein
Signal warten, das nur im Erfolgsfall käme.

Willst du von **jeder** Animation auf dem Server hören, auch von denen, die ein Spieler mit
`/bdpaste animate` gestartet hat, nimm stattdessen das Event:

```java
@EventHandler
public void onEnd(BdAnimationEndEvent event) {
    if (event.getReason() != BdAnimationEndEvent.Reason.FINISHED) return;
    if (!event.getAnimation().equals("open")) return;
    doorIsOpen.add(event.getModel().id());
}
```

### Weitere Events

| Event | Wann | Abbrechbar |
|---|---|---|
| `BdModelClickEvent` | Spieler klickt ein Modell | ja — unterdrückt BDPastes eigene Aktion |
| `BdAnimationEndEvent` | Animation hört auf zu laufen | nein |
| `BdModelPlaceEvent` | Modell steht (auch nach `move`/`replace`) | nein |
| `BdModelRemoveEvent` | Modell soll entfernt werden | ja — es bleibt stehen |

### Was die API kann

```java
List<Placement>       models();
Optional<Placement>   model(UUID id);
List<Placement>       modelsNear(Location center, double radius);
Optional<Placement>   modelLookingAt(Player player, double maxDistance);
List<String>          libraryModels();

void     animations(Placement model, Consumer<List<String>> found, Consumer<String> failed);
boolean  isAnimating(UUID id);
Optional<String> currentAnimation(UUID id);
void     play(Placement model, String animation, double speed, Consumer<Boolean> done);
void     play(Placement model, String animation, double speed, boolean loop, Consumer<Boolean> done);
void     play(Placement model, String animation, double speed, boolean loop,
              Consumer<Boolean> started, Consumer<BdAnimationEndEvent.Reason> ended);
boolean  stop(UUID id);

String   label(Placement model);
boolean  setLabel(Placement model, String miniMessage);

String   clickCommand(Placement model);
boolean  setClickCommand(Placement model, String command);

void     place(String source, Location at,
               Consumer<Placement> placed, Consumer<String> failed);
void     place(String source, Location at, float yaw, float scale,
               Consumer<Placement> placed, Consumer<String> failed);

int      remove(Placement model);
boolean  hasHitbox(Placement model);
boolean  setHitbox(Placement model, boolean enabled);
```

Alles Main-Thread. Die Methoden, die eine Modelldatei von der Platte lesen müssen
(`animations`, `play`, `place`), arbeiten mit Callbacks statt zu blockieren — die Callbacks
kommen auf dem Main-Thread zurück, du darfst darin also Entities anfassen. Und sie kommen
immer in einem späteren Tick, auch bei einem Modellnamen, den es gar nicht gibt.

Beispiel: alle Bauern im Umkreis auf `talking` umschalten.

```java
for (Placement model : bdpaste.modelsNear(location, 32)) {
    if (model.source().equalsIgnoreCase("fermier")) {
        bdpaste.play(model, "talking", 1.0, started -> {});
    }
}
```

### Modelle platzieren

Seit **API-Version 7** kann ein Plugin Modelle selbst setzen — ohne dass ein Spieler
danebensteht:

```java
bdpaste.place("fermier", spot,
        model -> bdpaste.play(model, "labourer", 1.0, true, started -> {}),
        reason -> sender.sendMessage(reason));
```

`source` ist der Bibliotheksname, also der Dateiname im Models-Ordner ohne Endung — dasselbe,
was `/bdpaste place` nimmt und was `libraryModels()` auflistet. Die lange Form nimmt zusätzlich
`yaw` (Grad im Uhrzeigersinn ab Süden) und `scale` (Faktor auf die Modellgröße, 1 = unverändert).

Das Modell steht dann wie jedes andere: es kommt in die Registry, übersteht einen Neustart,
ist anklickbar, und `BdModelPlaceEvent` feuert dafür. Es hat **keinen Besitzer**, also darf es
im Spiel nur jemand mit `bdpaste.admin` verschieben oder entfernen.

Pitch, Roll und die Feinversätze gibt es hier bewusst nicht: die sind zum Zurechtrücken einer
Vorschau nach Augenmaß da. Wer Modelle aus einer Config setzt, will einen Punkt und eine
Blickrichtung, keine sieben Zahlen.

`failed` bekommt einen Text, den du direkt einem Spieler zeigen kannst — kein solches Modell,
keine Welt, oder kein einziges Teil ließ sich spawnen.

`BdPasteApi.VERSION` steigt, sobald sich hier etwas ändert, das Aufrufer brechen könnte.

## Versionen

| | |
|---|---|
| **Paper 1.20.4 und neuer** | unterstützt, und das, wogegen die Jar gebaut wird |
| **Purpur, Pufferfish** und andere Paper-Forks | unterstützt — sie bringen die ganze Paper-API mit |
| **Spigot, CraftBukkit** | nein |
| **Folia** | nein |

Die Jar wird gegen die *älteste* unterstützte API und auf Java 17 kompiliert, damit ihr Bytecode
nichts referenziert, was einem 1.20.4-Server fehlt. Derselbe Quelltext wird gegen 1.20.4, 1.21.1,
1.21.4, 1.21.8, 1.21.11 und 26.2 übersetzt — das ist der Beleg, dass die benutzte API-Oberfläche
an beiden Enden dieses Bereichs existiert.

Spigot fällt raus, weil BDPaste durchgehend auf Paper-API aufbaut — Adventure und MiniMessage für
die Schilder, `getSLF4JLogger()`, und `Bukkit.createProfile` für Kopf-Texturen. Das ist kein
Schalter, den man umlegt; es hieße Adventure einzuschatten und die Kopf-Behandlung neu zu
schreiben.

Alles unter 1.20.4 ist ein härteres Nein: Display-Entities gibt es erst ab 1.19.4, darunter ist
gar nichts zu platzieren.

## Bekannte Grenzen

- **Easing-Kurven** an einzelnen Keyframes (`curveFunc` im Editor) werden ignoriert —
  zwischen zwei Keyframes wird linear interpoliert.
- Die Vorschau beim Platzieren zeigt die **Ruhepose**; animiert wird erst nach dem Absetzen.
- **Item-Displays** bekommen ihren Modus aus dem `item_display`-NBT, sonst aus ihrem eigenen
  Namen — dort schreibt BDEngine ihn hin, etwa `player_head[display=none]`. Nur ein Teil, das zu
  beidem schweigt, fällt auf `display.item-display-transform` zurück (Standard `NONE`).
- **Block-Entities sehen je nach Serverversion anders aus.** Eine Glocke, eine Truhe, ein Schild,
  ein Bett, ein Banner, ein Schädel, ein Conduit, eine Shulkerkiste, ein Zierkrug und ein
  Zaubertisch werden in zwei Teilen gezeichnet: ein einfaches Blockmodell plus ein zweites Stück,
  das von Code gezeichnet wird, damit es sich bewegen kann — eine schwingende Glocke, ein
  öffnender Truhendeckel. Ein Block-Display zeichnet nur das einfache Modell, also zeigt eine
  Glocke bis 1.21.x nur ihren Holzbalken und keinen goldenen Körper. Ab 26.x zeichnet Mojang
  diese Stücke als gewöhnliche Modelle, der goldene Körper erscheint, und ein Modell, das nur mit
  dem Balken gerechnet hat, sieht anders aus. Kein Plugin kann daran etwas ändern: der Server
  schickt in beiden Fällen denselben Block, und BDEngines eigene Vorschau folgt dem älteren
  Verhalten. Soll ein Modell überall gleich aussehen, baue es aus Blöcken, die keine
  Block-Entities sind.
- **`paintTexture`** (bemalte Texturen aus BDEngine) wird ignoriert.
- Unbekannte Blöcke/Items aus neueren oder älteren MC-Versionen werden durch Stein ersetzt;
  das steht dann einmalig in der Server-Konsole.
