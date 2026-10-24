# Agentic guitar learning partner — agent instructions

## Repository layout and scope

Read `README.md` first.

- `song.carp` and `song.jolt` at the repository root are the canonical,
  self-contained Carp/Jolt implementations of the same two-minute arrangement.
- `poc/` contains every experimental detector, looper, comparison script, and
  the hardened guitar-controlled PCM looper. Keep new experiments there rather
  than adding more scripts or generated artifacts at the repository root.
- `poc/guitar-audio-looper.jolt` is the entry point for the current six-slot PCM
  looper; its separated implementation modules live in `poc/audio_looper/`.
- `poc/AUDIO_LOOPER_PLAN.md` is the looper design and verification record.
- Do not depend on or edit the old sibling Jolt checkout.
- Do not add services, daemons, controllers, REPL/nREPL workflows, dependency
  manifests, build tasks, or a generic benchmark/test framework unless explicitly
  requested. There are no `bb play/stop/status/test` tasks.

## Canonical Carp/Jolt song comparison

Each canonical song source must remain self-contained, including its Csound
orchestra. Prefer idiomatic language features rather than artificial line-for-line
similarity.

Carp has static types and ownership/borrowing. Keep meaningful `Chord`/`Note`
types and collection transformations instead of flat positional data, unsafe
indexing, or hand-written accumulator loops. Its copy/ownership APIs are not
Clojure-style structurally shared persistent collections.

Jolt runs Clojure on Scheme, not the JVM. Keep vectors and sequence operations;
do not assume arbitrary Java interop works. Neither canonical implementation
uses a REPL.

Both canonical sources generate a Csound document and send it to a foreground
child process. Csound, not Carp/Jolt, performs the DSP. Do not claim this compares
two audio engines. Keep the embedded orchestras identical; duplication is
intentional so each file runs independently.

Preserve the fixed 64 BPM, amplitude 0.18, complete 32-bar form, and 120-second
score ending: 39 roots, 217 taps, 64 kicks, 81 melody notes (401 total).
Preserve pitch, duration, pan, the quiet guide melody (0.2 post-drive gain),
foreground taps (2.0 post-drive gain), root vibrato, and syncopated kicks.
Update both canonical versions together if musical behavior changes.

## POC organization and behavior

- Keep POC source paths and usage examples under `poc/`; update both root
  `README.md` and `poc/README.md` when an entry point moves or changes.
- Keep generated WAV/AIFF files, Carp binaries, generated `guitar-loop.csd`,
  temporary C, and rendered output out of git. Use `/tmp` for ad-hoc verification
  whenever possible.
- The PCM looper must record/replay actual stereo guitar PCM. Pitch is command
  input only; never replace the guitar with MIDI or synthesis.
- Preserve six equivalent slots, one recorder, no overdub, bounded sequenced
  transport, foreground ownership, Ctrl+C cleanup, and the 600-second OS alarm.
- Keep command policy, DSP, voice assets, runtime transport, setup, and terminal
  rendering separated and independently checkable.
- `--no-tui` intentionally selects line-oriented output. Use live mode without
  that flag when validating the fixed ANSI dashboard.

## Audio safety

Do not open live audio unless the user explicitly requests playback or live
validation. Metadata enumeration and offline rendering are safe; `--live` is the
only PCM-looper mode that may open devices.

Use offline rendering for canonical development:

```bash
SONG_WAV=/tmp/song-carp.wav carp -x song.carp
jolt song.jolt /tmp/song-jolt.wav
```

Use offline checks for the PCM looper:

```bash
jolt poc/guitar-audio-looper.jolt --check
```

Playback is foreground-only; Ctrl+C stops it. Keep both score-time endings and
independent OS wall-clock alarms. Never introduce a daemon or leave an audio,
Csound, `say`, or SuperCollider process running after testing.

For a stop request, interrupt only the foreground process group owned by the
requested playback and verify its engine process exited before claiming silence.
Do not use stale controller commands, `killall`, or broad `pkill`; never signal
unrelated audio processes.

## Verification

Canonical sources:

- Carp: `carp --check song.carp` and `carp -b song.carp`.
- Jolt: `clj-kondo --cache false --lang clj --lint song.jolt`, then use
  `cljstyle pipe` as documented in `README.md`. Do not apply Clojure tools to Carp.
- For musical/process changes, compare score events and identical orchestras,
  verify all 401 notes and the ending, render both without clipping, and test
  Ctrl+C plus engine-failure propagation.
- Allow six-significant-digit Carp rounding; never treat random pluck variation
  as a score mismatch or require byte-identical WAVs.

PCM looper and other POCs:

- Run `clj-kondo` and `cljstyle check` on changed Jolt sources.
- Run `jolt poc/guitar-audio-looper.jolt --check` for looper changes; this must
  not open an audio device.
- Compile-check `poc/audio_looper/diagnostics.c` with the pinned Csound and
  libsndfile headers when native integration changes.
- For process/lifecycle changes, verify signal cleanup, terminal restoration,
  no stale owned process, and accurate nonzero failure exits.
- Only request another user hardware pass after every relevant offline gate passes.

Use temporary files for ad-hoc checks and renders. Do not add a framework solely
for verification.
