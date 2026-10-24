# Agentic Guitar Learning Partner

A collection of guitar-input, pitch-detection, and looping experiments built
around Jolt, Carp, Clojure, and Csound. The current focus is a hardware-tested,
guitar-controlled looper that records and replays the **actual stereo PCM signal**
from an FX100 or other CoreAudio input.

Pitch detection controls the application; it never replaces the recorded guitar
with MIDI or a synthesized approximation.

## Current PCM looper

The entry point is [`poc/guitar-audio-looper.jolt`](poc/guitar-audio-looper.jolt).
It provides:

- six equivalent stereo PCM loop slots;
- one active recorder, no overdub;
- octave-independent guitar commands based on C-major scale degrees;
- sample-accurate recording and playback boundaries;
- outer-silence trimming for the first/master loop;
- synchronized later loops and queued cycle-boundary playback;
- spoken prompts mixed by Csound, not played directly by `say`;
- a fixed ANSI dashboard and a line-oriented `--no-tui` mode;
- foreground ownership, Ctrl+C cleanup, and a 600-second OS safety alarm.

### Quick start

Run commands from the repository root:

```bash
# Complete offline suite; does not open an audio device
jolt poc/guitar-audio-looper.jolt --check

# Inspect current CoreAudio metadata; does not open a stream
jolt poc/guitar-audio-looper.jolt --devices

# Offline dashboard preview
jolt poc/guitar-audio-looper.jolt

# Live looper with the full TUI
jolt poc/guitar-audio-looper.jolt --live
```

`--live` is the only mode that opens audio devices. It requires a foreground
terminal. Select the input with the keyboard; the default C-major control profile
is then applied automatically. The finite speech cache is generated before the
audio stream opens, so startup can pause for tens of seconds.

Use `--live --no-tui` only when line-oriented logs are desired. That option
intentionally disables the fixed dashboard.

### Default guitar controls

Notes may be played in **any supported octave**. The note names below identify
scale degrees, not required registers.

Wake phrase:

```text
C D C D
```

After the wake phrase, select a loop or exit:

| Degree | Note | Action |
| ---: | --- | --- |
| 1–6 | C, D, E, F, G, A | Select loop 1–6 |
| 7 | B | Begin confirmed exit |

After selecting a loop, choose an action:

| Degree | Note | Action |
| ---: | --- | --- |
| 1 | C | Start recording |
| 2 | D | Stop recording |
| 3 | E | Start playback |
| 4 | F | Stop playback |
| 5 | G | Erase the selected stopped loop |
| 6–7 | A, B | Reserved |

Erase is a direct command and is published only after the native engine
acknowledges it. Guitar-driven exit retains a held confirmation; keyboard `q`
performs a safe fade and exit. `[d]` toggles pitch debug and `[c]` reopens input
selection when all slots are empty and idle.

The first finalized clip establishes the master cycle. Its leading/trailing
silence is trimmed from bounded 10 ms stereo-RMS summaries at -50 dBFS, retaining
20 ms preroll and 50 ms release tail. Later clips preserve complete master cycles
so trimming cannot destroy synchronization.

### Audio safety

- Start with low volume and preferably headphones.
- Verify the displayed input and output before playing.
- Avoid simultaneous FX100 direct monitoring and software monitoring if it sounds
  doubled or phasey.
- Never route a microphone to speakers where feedback is possible.
- Press Ctrl+C immediately if feedback, runaway level, or unexpected behavior
  occurs.

The loop bus uses 0.65 gain and the FX100 software monitor uses 0.30. Concurrent
loops are normalized before the final ±0.98 limiter.

## Repository layout

```text
.
├── README.md
├── AGENTS.md
├── song.carp                  # canonical Carp arrangement
├── song.jolt                  # canonical Jolt arrangement
└── poc/
    ├── README.md              # detailed POC commands and requirements
    ├── AUDIO_LOOPER_PLAN.md   # PCM-looper design and verification record
    ├── guitar-audio-looper.jolt
    ├── audio_looper/          # policy, DSP, engine, runtime, UI, checks
    └── ...                    # earlier detector and looper experiments
```

All experimental detector/looper sources live under [`poc/`](poc/README.md).
Generated binaries, Csound documents, recordings, prompt assets, and rendered
WAV files are ignored and must not be committed.

## Requirements

The current PCM looper targets macOS and has been tested with Jolt v0.8.10 and
Csound 6.18/API 4.00 using 8-byte `MYFLT`:

```bash
brew install csound libsndfile
brew install jolt-lang/jolt/jolt
```

Apple command-line tools provide `/usr/bin/cc`; macOS `/usr/bin/say` generates
temporary prompt assets. Set `CSOUND_LIBRARY` or `SNDFILE_PREFIX` when those
libraries are installed outside the standard Homebrew/framework locations.

Carp is needed only for the Carp song and Carp POCs. JVM Clojure, Overtone,
SuperCollider, and sc3-plugins are needed only for the corresponding older POCs
listed in [`poc/README.md`](poc/README.md).

## Verification

The PCM looper’s complete offline gate is:

```bash
jolt poc/guitar-audio-looper.jolt --check
```

It covers pure command policy, setup/dashboard behavior, runtime effect dispatch,
sequenced native transport, every recorded stereo PCM sample, arbitrary sample
boundaries, silence trim, six-slot mixing, capacity handling, prompt playback,
and the native `pitchamdf` lifecycle. It opens no audio device.

Static development checks:

```bash
cljstyle check poc/guitar-audio-looper.jolt poc/audio_looper/*.jolt
clj-kondo --cache false --lang clj --lint \
  poc/guitar-audio-looper.jolt poc/audio_looper/*.jolt
```

See [`poc/AUDIO_LOOPER_PLAN.md`](poc/AUDIO_LOOPER_PLAN.md) for the detailed
architecture, invariants, and verification record.

## Canonical Carp/Jolt song comparison

The root-level `song.carp` and `song.jolt` remain a self-contained comparison of
the same two-minute, 32-bar **Fly Me to the Moon** arrangement at 64 BPM. Both
generate a Csound document; Csound performs the DSP, so this compares host-language
data modeling and workflow rather than two audio engines.

```bash
# Live playback
carp -x song.carp
jolt song.jolt

# Offline rendering
SONG_WAV=/tmp/song-carp.wav carp -x song.carp
jolt song.jolt /tmp/song-jolt.wav
```

The aligned score contains 39 roots, 217 chord-tone taps, 64 kicks, and 81 melody
notes: 401 events total. The two embedded orchestras and musical behavior should
remain aligned.

Minimal source checks:

```bash
carp --check song.carp
carp -b song.carp
clj-kondo --cache false --lang clj --lint song.jolt
cljstyle pipe < song.jolt > /tmp/song-formatted.jolt
diff -u song.jolt /tmp/song-formatted.jolt
```

Composition: Bart Howard. Melody/rhythm follows
[this lead-sheet reference](https://github.com/red-jackets-jazzband/website/blob/fa5d9123ad3ecc5a9c597967b9d15e3826beec45/static/songs/fly_me_to_the_moon.abc),
transposed from F to C with ties merged.
