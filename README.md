# Agentic Guitar Learning Partner

A Carp + Csound guitar-practice instrument. **Carp** compiles the note/voicing logic into
`out/guitar-score`; **Csound** synthesizes the sound. There is no nREPL, JVM,
long-lived scheduler, or stale synth state. **Every audio process is capped at
10 minutes**, even if you forget to stop it. `bb stop` terminates the exact
Csound process started by this project and verifies it has exited.

## Install

```bash
brew install csound babashka
# Install Carp from https://github.com/carp-lang/Carp and ensure `carp` is on PATH.
```

This machine already has Carp and Csound installed. Set your system audio volume
low before the first run.

## Play

```bash
bb play                          # Am guitar strum, 64 BPM, 2-second envelope
bb play guitar a minor 2 64 2.0 0.18
bb play guitar d minor 2 80 1.5 0.12
bb play chord a minor 3          # sustained Am chord at A3
bb play root d                   # root + octaves at D2
bb play mode d dorian            # sparse D Dorian voicing
bb play mode c lydian
bb play progression              # Fly Me to the Moon: full 32-bar melody + accompaniment
bb status                        # process status + current accompaniment chord
bb watch                         # follow chord changes of an already-playing progression
bb stop                          # immediate, confirmed silence
```

Use `bb play` to start audio; there is no `bb start` task. Run `bb tasks` to list
available commands.

### Live chord text

Start the song and follow its current accompaniment chord in the same terminal:

```bash
bb play progression && bb watch
```

Example output (intervening changes omitted):

```text
Bar 01/32 | beat 1 | Am7
Bar 02/32 | beat 1 | Dm7
Bar 04/32 | beat 1 | Cmaj7
Bar 04/32 | beat 3 | C7
```

A new line appears at each chord change, including half-bar changes. `beat`
identifies the beat where that chord entered, not a continuously ticking beat
counter. Csound emits these cues on its score clock using the accompaniment's
actual root and chord intervals; there is no separate wall-clock chord schedule.
`bb status` also shows the latest chord while playback is active.

`bb play` still starts audio in the background. `bb watch` only follows existing
progression playback and never starts audio. It detaches when that playback ends,
is stopped, or is replaced by a new `play`. **Ctrl+C in `watch` detaches the text
only; use `bb stop` to stop audio.** The watcher does not hold the controller lock,
so `stop` and `play` remain available from another terminal.

### Playback arguments

Full argument order: `bb play KIND ROOT QUALITY OCTAVE BPM ENVELOPE AMP`.
Arguments after `KIND` are optional. The tempo and envelope apply to `guitar`
(the guitar strums every two beats). Pad sounds (`chord`, `mode`, `root`) sustain
until stopped. `progression` plays **one complete 32-bar, 4/4 chorus of
Fly Me to the Moon**, in C with an Am7 opening: lead melody plus accompaniment,
not four repeats of the first eight bars. It includes half-bar chord changes,
chromatic pickups, phrase rests, and cross-bar ties. The second ending rises to
E5; the final C5 rings across the last two tonic bars.

Each chord change starts a centered root with sustain and delayed vibrato,
holding for 98% of that chord's span. The remaining eighths use the separate,
percussive right-hand tapping voice without vibrato. Tapping sits quieter than
the lead, which is panned slightly right and uses the same sustaining electric
tone. Taps release by the next chord change. Syncopated kicks stay on the
**and of beats 1 and 3**, with the second kick lighter, leaving chord changes
clear. At the default 64 BPM, one complete chorus is two minutes and ends
automatically. Change tempo with `bb play progression a minor 3 80`.

The full form (`/` means a change after two beats):

| Bars | Chords (· separates bars) |
| --- | --- |
| 1–8 | Am7 · Dm7 · G7 · Cmaj7/C7 · Fmaj7 · Bm7♭5 · E7 · Am7/A7 |
| 9–16 | Dm7 · G7 · Cmaj7 · Em7/A7 · Dm7 · G7 · Dm7/Cmaj7 · Bm7♭5/E7 |
| 17–24 | Am7 · Dm7 · G7 · Cmaj7/C7 · Fmaj7 · Bm7♭5 · E7 · Am7/A7 |
| 25–32 | Dm7 · G7 · Em7 · A7 · Dm7 · G7 · Cmaj7 · Cmaj7 |

Melody and harmony data are in `src/fly_me.carp`. The arrangement follows this
[lead-sheet reference](https://github.com/red-jackets-jazzband/website/blob/fa5d9123ad3ecc5a9c597967b9d15e3826beec45/static/songs/fly_me_to_the_moon.abc),
transposed from F to C with ties merged into single notes. Composition: Bart
Howard. Playback is straight-eighth, not a swing performance; no lyrics, samples,
or runtime downloads are used.

The progression uses fixed melody and practical chord voicings, so octave and
envelope arguments do not affect it. Guitar strums use five strings; Am is
A2–E3–A3–C4–E4. The default amplitude is 0.18, with a maximum accepted value of
0.5. Each new `play` stops the preceding sound before starting the next.

The electric guitar voice combines a plucked attack with sustained fundamental
and octave layers, soft overdrive, and a 2800 Hz low-pass filter. Vibrato on the
sustained layers starts after 0.35 seconds, reaching full depth over the next
0.25 seconds (5.5 Hz, ±1.5% pitch). Both `guitar` strums and progression roots
use this voice, as does the lead melody. Attack and release scale down for short
notes; tapped notes have a shorter decay and no vibrato.

Available chord qualities: `major`, `minor`, `power`, `minor7`, `major7`,
`dominant7`, `sus2`, `sus4`. Modes: `ionian`/`major`, `dorian`, `phrygian`,
`lydian`, `mixolydian`, `aeolian`/`minor`, `locrian`. Roots include sharps and
flats (`c#`, `eb`, etc.).

## Safety and development

```bash
bb stop           # no active PID => no process is signalled
bb status         # verifies the process still exists
bb build          # compile Carp without starting audio
bb test           # verify full form, chord output, renders and safety limits; no live audio
```

Practice scores schedule Csound's own hard stop at 600 seconds; the full song
stops after 32 bars (120 seconds at 64 BPM, at most 384 at the slowest accepted
tempo). An OS alarm,
installed before launching Csound and preserved across `exec`, also terminates
it after 600 seconds of wall-clock time if its score clock stalls. Each new
`bb play` resets the timer. `bb status` shows the remaining wall-clock time.
`bb stop` uses the PID plus the Csound command line containing this project's
absolute orchestra and score paths to avoid signalling unrelated audio software.
If a Csound process unexpectedly exits, inspect `.state/csound.log`. If the
controller fails, the only project-owned Csound process can be identified with
`ps -ax -o pid,command | grep '/csound/guitar.orc'` and terminated manually.

`src/guitar_score.carp` contains the music theory and generates a Csound score;
`src/fly_me.carp` holds the complete melody and harmony; `csound/guitar.orc`
contains the sound synthesis; `scripts/control.bb` manages
the OS process. Generated files and the owned PID live under `.state/`.
