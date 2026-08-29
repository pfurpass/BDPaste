# BDPaste

A Paper plugin that imports models from [bdengine.app](https://bdengine.app/) and places them
**with your crosshair** — instead of pasting enormous `/summon` commands into command blocks.

Runs on **Paper 1.20.4 and newer**, including forks like Purpur — built against
`paper-api 1.20.4` on **Java 17**, and compile-checked against every version from there up to
26.2. Plain Spigot/CraftBukkit will not work; see [Versions](#versions).

[spigotmc](https://www.spigotmc.org/resources/bdpaste.138329/)

---

## Installation

1. Copy `BDPaste-1.0.0.jar` into `plugins/` and start the server.
2. The first start creates `plugins/BDPaste/config.yml` and `plugins/BDPaste/models/`.
3. Put model files into `plugins/BDPaste/models/`.
4. `/bdpaste list` shows what it found.

## Getting a model out of BDEngine

There are **four** ways in — take whichever is least trouble:

| File | Where it comes from in BDEngine | Notes |
|---|---|---|
| `.bdengine` | save / download the project | **recommended**, carries the whole tree |
| `.json` | the raw project JSON | if you already unpacked it |
| `.txt` / `.mcfunction` | "Export to Minecraft" → copy the summon command into a text file | several `summon` lines (Summon #1, #2 …) can simply follow one another |
| `.zip` | "Export Model as a datapack" | carries the model *and* fully baked animation tracks |

On top of that there is the JSON format `/bdpaste import` fetches from block-display.com (see
below) — it lands in the models folder as a `.json` and behaves like any other file afterwards.

The filename without its extension is the model name: `mater.bdengine` → `/bdpaste place mater`.

### Straight from block-display.com

Easiest of all: take the share link off the model page, done.

```bash
/bdpaste import https://bde.gg/b/298796
```

Works with `bde.gg/b/<id>`, `block-display.com/bd/<id>`, a BDEngine editor link
(`bdengine.app/?...&id=<id>`), or just the bare id (`/bdpaste import 298796`).

block-display.com publishes models in two shapes, and which one you get is decided per model by
the site: older ones as finished summon commands, newer ones as an editor project file (a
gzipped `PRJ2` container). BDPaste tries both in turn — you will not notice.
The model name then comes from the page itself, and can be overridden:
`/bdpaste import 298796 gogzilla`.

A direct file link works too:

```bash
/bdpaste import https://example.com/mater.bdengine
```

## Placing

```bash
/bdpaste place mater
```

A real, fully built preview then follows your crosshair (only you can see it):

| Input | Effect |
|---|---|
| Move mouse | position the model |
| Scroll | change the selected value |
| Shift + Scroll | select another value (distance, yaw, pitch, roll, scale, offset X/Y/Z) |
| F (swap hands) | **freeze / unfreeze** — the model stays put and you can walk around it |
| Right click | place it |
| Left click | **change the step size** |
| Shift + Left click | reset the selected value |
| Shift + F | cycle snap: off / pixel / block |
| Q (drop item) | **pause / resume** — step out, build something, come back |
| Shift + Q | cancel |

The action bar also shows the **coordinates** the model currently sits at, the whole time.

**Freezing** is for checking before you accept: press **F** once and the model unhooks from your
crosshair and stays where it is. Now you can walk around it and look at it from every side.
Turning, scaling and the offsets all keep working — only the position is fixed. **F** again lets
it go, right click accepts. The action bar shows `FROZEN`.

The model and the crosshair stay locked together while scaling and turning — the pivot (the
bottom middle of the model, or the BDEngine origin, depending on `placement.pivot`) always sits
exactly on the cursor.

If your hand is empty when you start, BDPaste briefly puts a stick in it and takes it away
afterwards. The reason: vanilla clients send no packet at all when you right click **air** with
an empty hand — without an item you could only ever place while aiming at a block. A slot that
is already occupied is never touched.

The action bar shows the model name, the active value, yaw/pitch/roll, scale and the snap mode
throughout.

### Step size

Every value has its own ladder, which **left click** steps through:

| Value | Steps |
|---|---|
| Yaw / Pitch / Roll | 1° · 5° · 15° · 45° · 90° |
| Offset X/Y/Z | 0.03125 · 0.0625 · 0.125 · 0.25 · 0.5 · 1 blocks |
| Scale | ×1.01 · ×1.02 · ×1.05 · ×1.1 · ×1.25 · ×2 |
| Distance | 0.25 · 0.5 · 1 · 2 · 5 |

The current step size is in the action bar. Arbitrary values work too:

```bash
/bdpaste step 0.01
/bdpaste step 7.5 yaw
```

Lost track? `/bdpaste step` lists every step size, `/bdpaste step reset` puts them back to the
values from `config.yml`.

Step sizes, grid snap and the last selected value are **remembered per player** and live in
`plugins/BDPaste/players.yml` — the next model starts with everything set the way you left it,
server restart included.

### Snap

Three modes, switched with **Shift + F** or `/bdpaste snap [off|pixel|block]`:

| Mode | Grid | What for |
|---|---|---|
| `block` | 1 block | lining up with the build grid |
| `pixel` | 1/16 block | fine adjustment to texture pixels |
| `off` | none | exactly where you point |

On a surface, `pixel` snaps to the pixel grid of the block face and `block` to the block corner.
Free in the air the same grid applies. The grid size for `pixel` is `placement.pixel-snap` in
`config.yml` (default `0.0625` = 1/16).

### Stepping out for a moment

**Q** pauses: the preview stays put, you get your hotbar and your clicks back, and you can place
and break blocks normally to compare. **Q** again (or **Shift + F**, or `/bdpaste resume`)
brings you back. The action bar shows `PAUSED`.

Because Q is what brings you back, you cannot drop items while paused — everything else works.

Cancelling is **Shift + Q** — the destructive action is the one behind the modifier, not the
harmless one.

For exact values instead of the scroll wheel:

```bash
/bdpaste set yaw 90
/bdpaste set scale 0.5
/bdpaste set offset_y 1.5
```

## Animations

Animations come from every source — a link, a `.bdengine` file or a datapack. There is no extra
step:

```bash
/bdpaste import https://block-display.com/bd/299039 fermier
/bdpaste place fermier
/bdpaste animate              # starts the first animation
/bdpaste animate talking      # a particular track
/bdpaste animate talking 0.5  # half speed
/bdpaste animate talking once # play it through once, the end pose stays
/bdpaste animate off
```

The arguments can come in any order: a number is the speed, `once` or `loop` decides whether it
repeats, anything else is an animation name.

`/bdpaste inspect fermier` lists the tracks it carries with their lengths. The names are the
ones from the editor — BDPaste reads them out of `listAnim` in the project file, or out of the
folder names in a datapack.

**How exact this is.** From a datapack BDPaste replays the matrices stored there per tick —
measured over two complete runs, 86 parts, 25 ticks: deviation 0.000000000. From the project
file BDPaste works the same poses out for itself; checked against the datapack of the same
model, the difference stays under **0.0008 pixels** — and that on a model that nests animated
groups five levels deep with a pivot of its own hanging off nearly every one of them.

The key to it is how BDEngine folds a pivot into a node's matrix:

```
transforms = T(pivot_parent) · T(position) · Rx · Ry · Rz · S · T(−pivot_own)
```

At rest the two pivot terms between parent and child cancel each other out — which is why the
static import gets by without them. The moment a group animates they no longer do:
`T(−pivot_own)` is what makes the group turn and scale **around its pivot**, and
`T(pivot_parent)` puts it back into the frame its parent group hands down. Leave the first term
out and every child of a group with a pivot sits offset by exactly that pivot — which is what
used to tear nested models apart.

Only the parts underneath an animated group move — on the propeller the 45 blades turn while the
24 housing parts stand still. The state is saved and picks itself up again after a server
restart, as soon as somebody comes near.

Controlled in `config.yml` under `animation:` — the update interval, a ceiling on how many models
may run at once, and the radius within which anything animates at all.

> Models you placed **before** this version carry no part index yet and cannot be animated.
> One `/bdpaste move` and confirming again is enough.

### Sound

Some models bring a note track with them — BDEngine keeps it under `listSound`, one per
animation. BDPaste plays it along with the animation automatically:

```bash
/bdpaste import https://block-display.com/bd/295020 rat
/bdpaste place rat
/bdpaste animate dance
```

`/bdpaste inspect rat` shows which animation has notes and how many.

A timeline can also consist of **nothing but** notes, without a single keyframe — RUSH E on
block-display is 1071 notes and no keyframes at all. Models like that stand still and play, and
`/bdpaste inspect` writes `(sound only)` after them. The length of the note roll then sets the
loop.

If the roll runs longer than the animation, it sets the pace as well: the movement holds its end
pose until the music is through. That is exactly what the editor shows there too.

The sound comes out **at the model**, so it fades with distance like anything else in the world,
and it runs through the same category as note blocks — meaning players control it with the
jukebox slider, not the master one. Turning it off or down lives in `config.yml`:

```yaml
animation:
  sound: true
  sound-volume: 1.0
```

#### Tempo

The field is called `tick`, but it is **not a tick count — it is an index** into a table of step
sizes. The editor works the same thing out in three places:

```js
needTime = Math.floor(currentTime / (1 === tick ? .5 : tick - 1))          // playback
getSoundStepTicks: 1 -> 1,  2 (default) -> 2,  3 -> 4                       // export, game ticks
schedule delay:    1 -> .05s,  2 -> .1s,  3 -> .2s                          // export, seconds
```

So one step lasts **1, 2 or 4 game ticks**. The interface clamps `tick` to 1..3 when saving, so a
project cannot contain any other value.

Read as "that many ticks" it happens to be right for 1 and 2, and is a third too fast at 3 —
RUSH E ran 85 seconds instead of 114 that way.

#### Duplicate notes

Real rolls contain the same note several times at the same moment: RUSH E has 111 exact
duplicates across 59 steps, five deep in one place. Two identical samples starting in the same
tick at the same spot do not sound like two notes but like one at double the amplitude — they
are in phase, and five of them clip. The editor fires them all and has no limiter, which Web
Audio forgives; the game does not.

BDPaste throws exact duplicates away — same step, same sound, same pitch. A chord is several
*different* notes on one step and is left alone.

#### Pitch

The editor allows notes far outside what Minecraft can play. Its preview runs through Web Audio,
which will play at any rate; the game clamps to 0.5–2.0, which is one octave either way. On the
rat, **49% of the harp notes** fall outside — the range runs from −30 to +15 semitones, nearly
four octaves.

BDEngine's own datapack export writes the raw value regardless:

```
playsound ${note.id} block @a ~ ~ ~ ${round2(note.volume)} ${round3(note.pitch)}
```

So the same half of the notes sound wrong there too, and `/playsound` refuses values above 2.0
outright. There is nothing to copy from.

BDPaste solves it the way Minecraft intends: through the **instrument** rather than the pitch.
Every note block sound covers two octaves, and they sit an octave apart from one another. A note
that is too low goes to a lower sound and rings out **at exactly the written frequency** — just
with a different timbre. That is how note block music has always been arranged.

Which sound that should be cannot be derived from the file, only decided by ear — so it lives in
the config:

```yaml
animation:
  sound-low: bass      # for notes below the playable range
  sound-high: bell     # for notes above it
```

| Offset | Instruments |
|---|---|
| two octaves down | `bass`, `didgeridoo` |
| one octave down | `guitar` |
| one octave up | `flute`, `cow_bell` |
| two octaves up | `bell`, `chime`, `xylophone` |

The offset is known per instrument, so you only have to swap the name — the note stays the same
whichever you pick. `flute` whistles, `bell` rings, `chime` is softer, `xylophone` is wooden.

On the rat that moves 70 of 212 notes to a different instrument, and not one of them shifts in
pitch. Percussion (`hat`, `snare`, `basedrum`) has no octave to shift into and is clamped
instead.

What remains: only **project files** carry notes. A datapack export bakes its sounds into
functions — a different format, which BDPaste does not read.

**Tempo.** A keyframe is not a server tick: BDEngine holds every keyframe for 0.1 seconds — in
the editor as well as in the datapacks it exports (`schedule ... 0.1s` with
`interpolation_duration:2`). BDPaste does the same, two ticks per keyframe. If you want it
different everywhere, change `animation.ticks-per-keyframe` in `config.yml`; for a single model
`/bdpaste animate <name> <speed>` is enough.

## Floating name

Optional — no model gets one unless you ask. Aim at it and:

```bash
/bdpaste label <#ff8800>Shop
/bdpaste label <gradient:#ff0000:#00ff00><bold>BOSS</bold></gradient>
/bdpaste label off
```

The text is **MiniMessage**, so hex colours, gradients, bold, hover — all of it works. Without an
argument the command shows what is currently on it, and what that renders to.

A tag MiniMessage does not recognise is **not rejected** — it stays on screen as plain text.
There is nothing to validate, then; if a colour does not take, you see it straight away in the
confirmation, which writes the rendered result back to you.

The label hangs over **the part that forms the top of the resting model** — on anything
human-shaped, the head. It turns to face whoever is looking, is carried along by `move` and
`replace`, and goes away with the model.

Over the head and not over the middle of the whole model, because the middle is not where you
would think: the farmer holds a pitchfork out to one side, which drags the middle a quarter of a
block away from his head. And certainly not over the stored bounding box — that covers everywhere
the model reaches across its animation, and because the farmer throws a hay bale six blocks, its
centre would be 2.6 blocks away from him in an empty field.

### Adjusting the height

```bash
/bdpaste label raise -0.8
```

Negative lowers it, positive raises it. Stored per model and carried along by `move` and
`replace`.

It is needed because how tall a model looks cannot be worked out exactly: an item display is
bounded by a unit cube, but how much of that cube the item actually fills is up to the item's own
model — a player head uses half of it. The automatic height is therefore an estimate on the high
side, and `raise` corrects it once.

### During the animation

The label follows its part in **all three directions**, not just in height. On models that move
away from the spot that is the whole difference: the rat's head travels 2.3 blocks in one round
of its dance, and a name that only follows the height simply stays behind.

What is tracked is the part's **origin**, not the top of its box, and the distance between the
two is fixed at the start. That is why nothing trembles any more: a display's box is axis
aligned, so it measures taller the moment the part inside it turns — by 0.32 blocks on the
farmer, while his hat only tilts. Reading the height off the box every round passes that
breathing on to the label.

After `animate off` it sits over the resting pose again.

Appearance through `config.yml`:

```yaml
label:
  height: 0.4              # gap above the model
  scale: 1.0
  shadow: true
  see-through: false       # visible through walls
  background: "#40000000"  # #aarrggbb, six digits = opaque, #00000000 = none
  view-range: 1.0
  follow-animation: true
```

## Changing models that are already placed

**Duplicate** — aim at one, get a copy, put it down. The original stays where it is:

```bash
/bdpaste duplicate
```

The copy starts with every value of the original and hangs off your crosshair straight away. You
may copy other people's models — you are not changing them.

**Placing in a row** — after every drop the next copy comes up immediately:

```bash
/bdpaste repeat on
```

That lets you set a whole row without typing a command in between. End it with
`/bdpaste repeat off`, `/bdpaste cancel` or Shift+Q. The action bar shows `repeat`, and the
setting is remembered.

A `move` never repeats — that would quietly turn it into a duplicate.

**Move** — aim at one, pick it up, edit it:

```bash
/bdpaste move
```

The model **stays where it stands** and starts out frozen. So you can go straight to fixing the
rotation, the size or an offset without having to aim at it all over again. Only **F** hands it
back to your crosshair, for when you actually want it somewhere else.

**Every** value comes along: yaw, pitch, roll, scale and offset X/Y/Z.

If you cancel (Q or `/bdpaste cancel`), the model ends up exactly where it was — even if you log
out halfway through, or the server shuts down.

**Replace** — same position, same rotation, same size, different model:

```bash
/bdpaste replace mater
```

This happens straight away, without placement mode. If the model name is wrong nothing happens —
the new one is loaded first, the old one deleted only afterwards.

> Models you placed **before** this version do not know their own filename yet (only the display
> name). On those, `move`/`replace` fails with "No model called …" — one `/bdpaste remove` and
> placing it again sorts it out.

## Every command

Base: `/bdpaste` (aliases `/bde`, `/bdp`)

| Command | Description | Permission |
|---|---|---|
| `list` | available model files | `bdpaste.use` |
| `inspect <model>` | parse the file without spawning: part count, types, size, most common blocks | `bdpaste.use` |
| `place <model>` | start placement mode | `bdpaste.place` |
| `duplicate` | pick up a copy of the model you are aiming at | `bdpaste.place` |
| `repeat [on\|off]` | keep placing after each drop | `bdpaste.use` |
| `freeze` | freeze/unfreeze the preview (same as F) | `bdpaste.use` |
| `snap [off\|pixel\|block]` | switch the grid | `bdpaste.use` |
| `pause` / `resume` | leave placement mode for a moment and come back | `bdpaste.use` |
| `step [number\|reset] [value]` | show, set or reset step sizes | `bdpaste.use` |
| `move` | pick the model you are aiming at back up and put it elsewhere | `bdpaste.place` |
| `replace <model>` | swap the model you are aiming at for another, same spot | `bdpaste.place` |
| `set <value> <number>` | set an exact value (while placing) | `bdpaste.place` |
| `cancel` | abort the placement | `bdpaste.use` |
| `undo` | remove the model placed last | `bdpaste.remove` |
| `remove` | remove the model you are aiming at | `bdpaste.remove` |
| `delete <id>` | remove a model by id | `bdpaste.remove` |
| `animate [name] [speed] [once\|loop\|off]` | play the model's animation | `bdpaste.place` |
| `label <text\|raise <n>\|off>` | floating name over the model, MiniMessage | `bdpaste.place` |
| `command <cmd\|off>` | run a command when the model is right clicked | `bdpaste.place` |
| `info` | details about the model you are aiming at | `bdpaste.use` |
| `near [radius]` | list placed models nearby | `bdpaste.use` |
| `tp <id>` | teleport to a model | `bdpaste.admin` |
| `import <url-or-id> [name]` | fetch from block-display.com or a direct link | `bdpaste.import` |
| `hitbox [on\|off\|sync]` | make a model clickable (for the API) | `bdpaste.admin` |
| `cleanup <radius>` | delete **every** BDPaste display within the radius | `bdpaste.admin` |
| `reload` | reload the config, clear the model cache | `bdpaste.admin` |

Every permission defaults to `op`; `bdpaste.*` covers the lot.

## How models are found and removed

Display entities have no hitbox, so you cannot click one. BDPaste therefore stores the bounding
box in `plugins/BDPaste/placements.yml` when a model is placed, and `/bdpaste remove` ray casts
against those stored boxes. On top of that, every entity carries its model id in its
PersistentDataContainer and the scoreboard tag `bdpaste` — if something is ever orphaned,
`/bdpaste cleanup <radius>` clears it out.

That box is the **resting** model. Not the area it sweeps across its animations: the farmer
throws a hay bale six blocks, so that would be 8.4 blocks wide instead of the 3.0 he actually
stands in.

What an animation throws beyond it stays outside on purpose: you click the farmer, not the bale
that happens to be in mid-air.

Boxes from older versions still carry the old numbers. They are measured again at server start as
soon as their chunk is loaded — by themselves, there is nothing to do. `/bdpaste hitbox sync`
kicks off the same thing by hand.

### Several boxes instead of one big one

An `interaction` entity has only **one** width for both horizontal axes. Its footprint is
therefore always square, whatever shape the model is — and around anything long that is mostly
air. Stitch is 2.57 × 1.12 blocks; a square around him is 2.57 × 2.57, more than twice the area
he takes up.

So a long model gets **several** boxes, laid down its length. Each grows around the parts whose
middle falls into its stretch, so every part lies completely inside exactly one box. A gap in the
middle of a model stays a gap.

Every split up to `max-boxes` is worked out, and the single box is one of the candidates. The
smallest wins — so the boxes can never come out looser than before, only tighter:

```
Model          sweep box    rest box         now   boxes
Fermier           268.51       25.90       17.36   2
rat                12.75        1.73        0.49   2
stitch             17.05       15.78        3.74   3
dance             359.56      292.50       53.61   8
rush-e           3880.83     3880.83      803.08   5
```

(in cubic blocks; all test models together: 4851 → 921)

`interaction.padding` is `0.0` — the box is the model. Higher means more forgiving to aim at, but
it costs more than it looks: 0.15 all round doubles the clickable volume on the rat.

Some models stay big because they are big: RUSH E is a note roll spanning 18.7 blocks, and
`dance` spreads 216 parts over 10.

## Configuration

`config.yml` is commented. The knobs that matter most:

- `max-parts` (default 6000) — ceiling on display entities per model. Every part is a real
  entity, and large models cost real server performance.
- `spawn-per-tick` (250) — turn it down if large models cause a lag spike while placing.
- `placement.pivot` — `CENTER` (default, the bottom middle of the model on the cursor) or
  `ORIGIN` (the BDEngine origin on the cursor, which lines up cleanly with the block grid).
- `placement.surface-reach` (32) — how far the crosshair looks for a surface. Beyond that the
  model floats at `default-distance`.
- `display.view-range` — raise it if large models are hidden too early.
- `display.force-brightness` — overrides the brightness stored in the model.

## Building it yourself

Needs **JDK 17 or newer**:

```bash
mvn clean package
```

Result: `target/BDPaste-1.0.0.jar`.

## For developers

BDPaste ships an API so other plugins can react to placed models — click a model, start an
animation, that sort of thing.

### Hooking in

`plugin.yml`:

```yaml
softdepend: [BDPaste]
```

Fetch it through the services manager:

```java
RegisteredServiceProvider<BdPasteApi> provider =
        Bukkit.getServicesManager().getRegistration(BdPasteApi.class);
BdPasteApi bdpaste = provider == null ? null : provider.getProvider();
```

Keep the null check — your plugin should carry on running on a server without BDPaste.

### Clicks

For a model to be clickable at all it needs a **hitbox**. Display entities have none in vanilla,
and no amount of plugin code changes that. BDPaste therefore puts one or more invisible
`interaction` entities around each model — by default on every newly placed one, controlled by
`interaction.enabled` and `/bdpaste hitbox`.

`/bdpaste hitbox off` is stored on the model and survives a restart. It did not before: the box
was simply put back at the next start.

```java
@EventHandler
public void onClick(BdModelClickEvent event) {
    if (event.getAction() != BdModelClickEvent.Action.RIGHT) return;
    if (!event.getModel().source().equalsIgnoreCase("shop")) return;

    event.setCancelled(true);   // stop BDPaste from acting on the click itself
    openShop(event.getPlayer());
}
```

Without `setCancelled(true)`, BDPaste goes on to do whatever `interaction.right-click` or
`left-click` says in `config.yml`:

| Value | Behaviour |
|---|---|
| `NONE` | nothing, just the event |
| `TOGGLE` | start the animation, or stop it if it is running |
| `CYCLE` | step to the next animation, and past the last one back to standing still |

`CYCLE` is the default for right click. A model with two animations then goes
`labourer` → `talking` → off → `labourer`, without a line of your own code.

### Click commands

A model can carry a command it runs when somebody right clicks it — the sofa you can sit on,
without a line of code:

```bash
/bdpaste command sit
/bdpaste command warp lobby
/bdpaste command off
```

Everything after `command` is taken as typed, so a command with spaces needs no quoting. A
leading slash is dropped if you type one out of habit.

It runs **instead of** whatever `interaction.right-click` says in the config, not as well as it —
a sofa should not also start cycling through its animations. Clearing it with
`/bdpaste command off` brings the configured behaviour back.

It runs **as the player**, so it can do no more than they could type themselves. To hand out
something they have no permission for, prefix it:

```bash
/bdpaste command console: give %player% bread 1
```

That form needs `bdpaste.admin` — the plain form only needs `bdpaste.place`.

| Placeholder | |
|---|---|
| `%player%` `%uuid%` | who clicked |
| `%model%` `%source%` `%id%` | which model, and which placed copy |
| `%world%` `%x%` `%y%` `%z%` | where it stands |

Coordinates are always written with a dot, whatever locale the server runs under — a `1,5` would
otherwise arrive at the command as two arguments.

A model with no hitbox cannot be clicked, so the command never fires. `/bdpaste command` warns
you if that is the case; `/bdpaste hitbox on` fixes it.

### Which animation is running

```java
String now = bdpaste.currentAnimation(model.id()).orElse("nothing");
```

That is the **live state**. After a one-shot it goes empty again, while `model.animationName()`
still names the one started last — useful if you want to know what it did last, but a different
question.

The animations a model carries are listed by `animations(...)`; that has to read the model file
and therefore answers through a callback.

### Once instead of on a loop

`loop = false` plays the animation through once and leaves the model in its end pose — a door
that swings open stays open. The editor does the same: its export without a loop hands over to
`stop_anim` at the end, and that only pauses.

```java
bdpaste.play(model, "open", 1.0, false, ok -> { });
```

`stop(id)` or `/bdpaste animate off` puts it back into its resting pose.

A one-shot is **not** written down as running: it does not start itself again after a server
restart, and `isAnimating` goes `false` once it is through. That is exactly how you can tell it
has finished.

For the built-in click actions, `interaction.loop` in `config.yml` controls this.

### Which model is it?

`Placement` carries three identifiers, and they are easy to mix up:

| | Example | What for |
|---|---|---|
| `source()` | `fermier` | filename in the models folder — for **filtering by kind of model** |
| `model()` | `Fermier` | display name; usually the same, but on older block-display imports it is the title from the page |
| `id()` | `3a97fd42-…` | this one placed copy |

For filtering use `source()` with `equalsIgnoreCase` — `model()` can differ in capitalisation.
`/bdpaste info` shows a model's values while you aim at it.

### When the animation is over

```java
bdpaste.play(model, "open", 1.0, false,
        started -> { },
        reason -> {
            if (reason == BdAnimationEndEvent.Reason.FINISHED) doorIsOpen.add(model.id());
        });
```

The second callback runs **once**, on the main thread, however it turned out:

| `Reason` | |
|---|---|
| `FINISHED` | played through — only with `loop = false`; the model keeps its end pose |
| `STOPPED` | somebody stopped it: `animate off`, `stop()`, a click, or another animation took over |
| `GONE` | the entities are gone — chunk unloaded or model removed |

`GONE` is why there is not just a "done" callback: a one-shot in a chunk that unloads halfway
never finishes, and you would wait forever for a signal that only came on success.

If you would rather hear about **every** animation on the server, including the ones a player
started with `/bdpaste animate`, use the event instead:

```java
@EventHandler
public void onEnd(BdAnimationEndEvent event) {
    if (event.getReason() != BdAnimationEndEvent.Reason.FINISHED) return;
    if (!event.getAnimation().equals("open")) return;
    doorIsOpen.add(event.getModel().id());
}
```

### The other events

| Event | When | Cancellable |
|---|---|---|
| `BdModelClickEvent` | a player clicks a model | yes — suppresses BDPaste's own action |
| `BdAnimationEndEvent` | an animation stops running | no |
| `BdModelPlaceEvent` | a model is standing (also after `move`/`replace`) | no |
| `BdModelRemoveEvent` | a model is about to be removed | yes — it stays where it is |

### What the API can do

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

All main thread. The methods that have to read a model file off the disk (`animations`, `play`,
`place`) take callbacks instead of blocking — the callbacks come back on the main thread, so you
may touch entities inside them. And they always come on a later tick, even for a model name that
does not exist.

Example: switch every farmer nearby to `talking`.

```java
for (Placement model : bdpaste.modelsNear(location, 32)) {
    if (model.source().equalsIgnoreCase("fermier")) {
        bdpaste.play(model, "talking", 1.0, started -> {});
    }
}
```

### Placing models

Since **API version 7** a plugin can put models down by itself, with no player standing there:

```java
bdpaste.place("fermier", spot,
        model -> bdpaste.play(model, "labourer", 1.0, true, started -> {}),
        reason -> sender.sendMessage(reason));
```

`source` is the library name — the filename in the models folder without its extension, the same
thing `/bdpaste place` takes and the same thing `libraryModels()` lists. The long form also takes
`yaw` (degrees clockwise from south) and `scale` (a multiplier on the model's own size, 1 leaves
it alone).

The model then stands like any other: it goes into the registry, survives a restart, is
clickable, and `BdModelPlaceEvent` fires for it. It has **no owner**, so in game only somebody
with `bdpaste.admin` may move or remove it.

Pitch, roll and the fine offsets are deliberately not here: those exist for nudging a preview
into place by eye. Anything placing models from a config file wants a spot and a heading, not
seven numbers.

`failed` is handed a message you can show a player directly — no such model, no world, or not a
single part could be spawned.

`BdPasteApi.VERSION` goes up whenever something here changes in a way that could break a caller.

## Versions

| | |
|---|---|
| **Paper 1.20.4 and newer** | supported, and what the jar is built against |
| **Purpur, Pufferfish** and other Paper forks | supported — they carry the whole Paper API |
| **Spigot, CraftBukkit** | no |
| **Folia** | no |

The jar is compiled against the *oldest* supported API and on Java 17, so its bytecode
references nothing a 1.20.4 server lacks. The same source is compile-checked against 1.20.4,
1.21.1, 1.21.4, 1.21.8, 1.21.11 and 26.2, which is what says the API surface it uses exists at
both ends of that range.

Spigot is out because BDPaste leans on Paper API throughout — Adventure and MiniMessage for the
labels, `getSLF4JLogger()`, and `Bukkit.createProfile` for head textures. That is not a switch to
flip; it would mean shading Adventure and rewriting the head handling.

Anything below 1.20.4 is a harder no: display entities only arrived in 1.19.4, and below that
there is nothing to place at all.

## Known limits

- **Easing curves** on individual keyframes (`curveFunc` in the editor) are ignored — the
  interpolation between two keyframes is linear.
- The preview while placing shows the **resting pose**; animation starts once it is set down.
- **Item displays** are posed from their `item_display` NBT if they have one, otherwise from
  their own name — BDEngine writes it there, as in `player_head[display=none]`. Only a part that
  says nothing either way falls back to `display.item-display-transform` (default `NONE`).
- **Block entities look different depending on the server version.** A bell, a chest, a sign, a
  bed, a banner, a skull, a conduit, a shulker box, a decorated pot and an enchanting table are
  all drawn in two pieces: a plain block model, plus a second piece drawn by code so it can move
  — a bell that swings, a chest lid that opens. A block display only draws the plain model, so up
  to 1.21.x a bell in a model shows just its wooden bar and no gold bell. From 26.x on Mojang
  draws those pieces as ordinary models, so the gold bell appears and a model built around the
  bar alone no longer looks the way it did. Nothing a plugin can influence: the server sends the
  same block either way, and BDEngine's own preview follows the older behaviour. If a model has
  to look identical everywhere, build it out of blocks that are not block entities.
- **`paintTexture`** (painted textures from BDEngine) is ignored.
- Unknown blocks/items from newer or older MC versions are replaced with stone; that is noted in
  the server console once.
