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
bb play progression               # Fly Me to the Moon arpeggio: four loops at 64 BPM
bb status
bb stop                          # immediate, confirmed silence
```

Use `bb play` to start audio; there is no `bb start` task. Run `bb tasks` to list
available commands.

Full argument order: `bb play KIND ROOT QUALITY OCTAVE BPM ENVELOPE AMP`.
Arguments after `KIND` are optional. The tempo and envelope apply to `guitar`
(the guitar strums every two beats). Pad sounds (`chord`, `mode`, `root`) sustain
until stopped. `progression` plays **Am7 → Dm7 → G7 → Cmaj7 → Fmaj7 →
Bm7♭5 → E7 → Am7**, one four-beat bar per chord for four loops. Each bar
arpeggiates eighth notes (root–third–fifth–seventh–octave–seventh–fifth–third).
The root sustains for 98% of the bar with delayed vibrato; the remaining seven
notes use a separate, percussive right-hand tapping voice without vibrato.
Syncopated kicks land on the **and of beats 1 and 3**, with the second kick
lighter, leaving the root's downbeat clear. At the default 64 BPM, that is two
minutes; its score ends automatically. Set a different BPM with
`bb play progression a minor 3 80`. The progression uses fixed practical chord
voicings, so octave and envelope arguments do not affect it. Guitar has five plucked strings with a five-note voicing; Am is
A2–E3–A3–C4–E4. The default amplitude is 0.18, with a maximum accepted value of
0.5. Each new `play` stops the preceding sound before starting the next.

The electric guitar voice combines a plucked attack with sustained fundamental
and octave layers, soft overdrive, and a 2800 Hz low-pass filter. Vibrato on the
sustained layers starts after 0.35 seconds, reaching full depth over the next
0.25 seconds (5.5 Hz, ±1.5% pitch). Both `guitar` strums and progression roots
use this voice; tapped notes have a shorter decay and no vibrato.

Available chord qualities: `major`, `minor`, `power`, `minor7`, `major7`,
`dominant7`, `sus2`, `sus4`. Modes: `ionian`/`major`, `dorian`, `phrygian`,
`lydian`, `mixolydian`, `aeolian`/`minor`, `locrian`. Roots include sharps and
flats (`c#`, `eb`, etc.).

## Safety and development

```bash
bb stop           # no active PID => no process is signalled
bb status         # verifies the process still exists
bb build          # compile Carp without starting audio
bb test           # verify 10-minute limits via short offline simulations; no live audio
```

The Carp score schedules Csound's own hard stop at 600 seconds. An OS alarm,
installed before launching Csound and preserved across `exec`, also terminates
it after 600 seconds of wall-clock time if its score clock stalls. Each new
`bb play` resets the timer. `bb status` shows the remaining wall-clock time.
`bb stop` uses the PID plus the Csound command line containing this project's
absolute orchestra and score paths to avoid signalling unrelated audio software.
If a Csound process unexpectedly exits, inspect `.state/csound.log`. If the
controller fails, the only project-owned Csound process can be identified with
`ps -ax -o pid,command | grep '/csound/guitar.orc'` and terminated manually.

`src/guitar_score.carp` contains the music theory and generates a Csound score;
`csound/guitar.orc` contains the sound synthesis; `scripts/control.bb` manages
the OS process. Generated files and the owned PID live under `.state/`.
