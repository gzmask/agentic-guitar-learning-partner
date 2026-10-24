# Agentic guitar learning partner — agent instructions

## Goal and repository layout

Read `README.md` first.

The production focus is the guitar-controlled stereo PCM looper:

- `guitar-audio-looper.jolt` is the main entry point.
- `audio_looper/` contains its separated command policy, note reference, Csound
  DSP, embedded-engine transport, voice assets, runtime adapter, setup, dashboard,
  terminal integration, and offline checks.
- `AUDIO_LOOPER_PLAN.md` is the main design and verification record.
- `poc/` contains all previous experiments, including the Carp/Jolt song
  comparison, older pitch detectors, and note-based loopers.
- `poc/README.md` documents those historical POCs.

Do not depend on or edit the old sibling Jolt checkout. Do not add services,
daemons, controllers, REPL/nREPL workflows, dependency manifests, build tasks,
or a generic benchmark framework unless explicitly requested. There are no
`bb play/stop/status/test` tasks.

## Main PCM looper invariants

- Record and replay the actual stereo guitar PCM. Pitch is command input only;
  never convert the recorded performance to MIDI or resynthesize it.
- Preserve six equivalent slots, one active recorder, no overdub, and no silent
  overwrite of an existing clip.
- Keep command policy, note-event behavior, DSP, native transport, voice assets,
  setup, and terminal rendering separated and independently testable.
- Csound owns real-time work: CoreAudio, `pitchamdf`, sample clock, PCM tables,
  playback, fades, prompts, and mixing. Jolt must not perform per-block DSP.
- Preserve bounded sequenced host/native transport, matching operation IDs,
  fail-closed gaps, and publication only after native acknowledgement.
- Keep foreground ownership, Ctrl+C cleanup, terminal restoration, and the
  independent 600-second OS wall-clock alarm.
- `--live` is the only mode that may open an audio device. `--no-tui` intentionally
  selects line-oriented output; omit it when checking the fixed ANSI dashboard.
- Input selection is the only mandatory keyboard setup for FX100. The default
  C-major guitar command profile is octave-independent.
- Erase is a direct selected-slot command; guitar exit retains confirmation.
- The first/master clip may trim outer silence with documented preroll/tail.
  Later synchronized clips must preserve complete master-cycle phase.

## Previous POCs

Keep previous experiments under `poc/`; do not move them back to the repository
root. Keep generated WAV/AIFF files, Carp binaries, generated `guitar-loop.csd`,
temporary C, prompt assets, and rendered output out of git.

`poc/song.carp` and `poc/song.jolt` are the aligned historical language-comparison
POC. Each remains self-contained, including its Csound orchestra. If changing
that arrangement, preserve the fixed 64 BPM, amplitude 0.18, complete 32-bar
form, and 120-second ending: 39 roots, 217 taps, 64 kicks, 81 melody notes (401
total). Preserve pitch, duration, pan, guide gain 0.2, tap gain 2.0, root vibrato,
and syncopated kicks; update both versions together.

Carp code should retain meaningful `Chord`/`Note` types and ownership-aware
collection transformations. Jolt runs Clojure on Scheme, not the JVM; keep
vectors and sequence operations and do not assume arbitrary Java interop.

## Audio safety

Do not open live audio unless the user explicitly requests playback or live
validation. Metadata enumeration and offline rendering are safe.

Main looper offline gate:

```bash
jolt guitar-audio-looper.jolt --check
```

Historical song rendering:

```bash
SONG_WAV=/tmp/song-carp.wav carp -x poc/song.carp
jolt poc/song.jolt /tmp/song-jolt.wav
```

Playback is foreground-only; Ctrl+C stops it. Never introduce a daemon or leave
an audio, Csound, `say`, or SuperCollider process running after testing.

For a stop request, interrupt only the foreground process group owned by the
requested playback and verify its engine process exited before claiming silence.
Do not use stale controller commands, `killall`, or broad `pkill`; never signal
unrelated audio processes.

## Verification

For main-looper changes:

- Run `clj-kondo` and `cljstyle check` on changed Jolt sources.
- Run `jolt guitar-audio-looper.jolt --check`; it must not open an audio device.
- Compile-check `audio_looper/diagnostics.c` with the pinned Csound and libsndfile
  headers when native integration changes.
- For process/lifecycle changes, verify signal cleanup, terminal restoration, no
  stale owned process, and accurate nonzero failure exits.
- Only request another hardware pass after all relevant offline gates pass.

For the historical song POC:

- Carp: `carp --check poc/song.carp` and `carp -b poc/song.carp`.
- Jolt: `clj-kondo --cache false --lang clj --lint poc/song.jolt`, then use
  `cljstyle pipe` as documented in `README.md`. Do not apply Clojure tools to Carp.
- For musical/process changes, compare score events and orchestras, verify all
  401 notes and the ending, render both without clipping, and test interruption
  plus engine-failure propagation.
- Allow six-significant-digit Carp rounding and random pluck variation; do not
  require byte-identical WAVs.

Use temporary files for ad-hoc checks and renders. Do not add a framework solely
for verification.
