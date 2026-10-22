# Carp vs Jolt — a single-song POC

Compare [Carp](https://github.com/carp-lang/Carp) and
[Jolt](https://github.com/jolt-lang/jolt) by implementing the same song in two
self-contained files. This is a language comparison, **not** a general guitar
practice application, agent platform, or audio service.

Both play one complete 32-bar chorus of **Fly Me to the Moon** at 64 BPM,
in C with an Am7 opening. Each chorus lasts two minutes.

## Files

- `song.carp` — typed `Chord`/`Note` data, ownership/borrowing, and array
  transformations (`copy-map`, `flat-map`, `enumerated`, `take`).
- `song.jolt` — Clojure vectors, destructuring, and sequence comprehensions.
- `AGENTS.md` — scope, comparison rules, and audio safety instructions.

Each song file includes its own arrangement, embedded Csound orchestra, score
generation, and foreground process launch. Neither depends on the other file or
the former sibling Jolt checkout. Keep them self-contained; do not introduce
shared libraries or a build framework for this POC.

## Requirements

On macOS:

```bash
brew install csound
brew install jolt-lang/jolt/jolt
```

Install Carp using its [installation guide](https://github.com/carp-lang/Carp/blob/master/docs/Install.md),
including its core-library configuration and a C compiler. Both implementations
also use `/usr/bin/perl` for a wall-clock safety alarm and `/dev/stdin` for input;
this POC targets macOS/POSIX, not Windows.

Tested locally with Carp 0.6.0, Jolt v0.8.10, and Csound 6.18.
No JVM, nREPL, Babashka controller, dependency manifest, or running service is
required.

## Play

Set your system volume low first. Run **one version at a time**:

```bash
carp -x song.carp
# Or:
jolt song.jolt
```

Playback stays in the foreground. **Ctrl+C stops playback**; otherwise the song
ends automatically after 120 score seconds. An independent OS alarm caps the
Csound process at 600 wall-clock seconds. Multiple invocations do not stop or
replace one another, so avoid overlapping playback.

Carp puts generated C code and its executable under
`${TMPDIR:-/tmp/}/carp-fly-me-to-moon/`, not in this repository. Jolt runs the
source directly.

## Render without live audio

```bash
SONG_WAV=/tmp/song-carp.wav carp -x song.carp
jolt song.jolt /tmp/song-jolt.wav
```

These commands write WAV files without opening the audio device. Audition the
files with the same player and volume. The pluck synthesis can introduce random
variation: matching scores do not guarantee byte-identical WAVs.

## What is being compared?

```text
Carp / Jolt: arrangement data → Csound document → child-process stdin
Csound:      parse orchestra and score → synthesize → audio device or WAV
```

Csound is a local executable launched on demand, **not a background service**.
Carp uses `popen` and `IO.Raw.fwrite`; Jolt uses `sh ... :in csd`.
The write operation only transports text—Csound interprets that text and performs
the DSP.

Compare readability, data modeling, collection operations, ownership, and the
edit/run workflow. Both use the same synthesizer, so this is **not** a comparison
of native Carp versus Jolt DSP performance or sound quality. Carp compiles before
running; Jolt's command runs source. Do not label their total runtimes as an
equivalent AOT benchmark.

## Correctness and development

Keep the two versions aligned:

| Track | Events |
| --- | ---: |
| Sustained roots | 39 |
| Chord-tone taps | 217 |
| Syncopated kicks | 64 |
| Melody, with ties merged | 81 |
| **Total notes** | **401** |

Preserve pitches, timing, gain, pan, the 32-bar ending, and identical embedded
orchestras. The guide melody uses 0.2 post-overdrive gain; chord-tone taps use 2.0.
Carp prints fewer significant digits, so compare numeric score fields with a
small rounding tolerance rather than requiring identical text.

Minimal checks, all without live audio:

```bash
carp --check song.carp
carp -b song.carp
clj-kondo --cache false --lang clj --lint song.jolt
cljstyle pipe < song.jolt > /tmp/song-formatted.jolt
diff -u song.jolt /tmp/song-formatted.jolt
SONG_WAV=/tmp/song-carp.wav carp -x song.carp
jolt song.jolt /tmp/song-jolt.wav
```

`clj-kondo` and `cljstyle` are optional development tools for the Jolt source,
not runtime dependencies or Carp linters. Before finishing music changes,
compare both scores, check the event counts and two-minute ending, render both
without clipping, and verify foreground interruption and engine-error handling.
Keep verification lightweight; do not rebuild the old test/build framework.

Composition: Bart Howard. Melody/rhythm follows
[this lead-sheet reference](https://github.com/red-jackets-jazzband/website/blob/fa5d9123ad3ecc5a9c597967b9d15e3826beec45/static/songs/fly_me_to_the_moon.abc),
transposed from F to C with ties merged. Straight-eighth playback; no lyrics,
samples, or runtime downloads.
