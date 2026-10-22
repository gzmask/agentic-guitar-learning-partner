# Single-song Carp/Jolt comparison — agent instructions

## Goal and scope

Read `README.md` first. This repository is a small POC comparing Carp and Jolt
with the same two-minute Fly Me to the Moon arrangement.

- `song.carp` and `song.jolt` are the canonical implementations in this repo.
- Each must remain a self-contained source file, including its Csound orchestra.
- Do not add generic practice modes, a controller, REPL/nREPL workflow, services,
  shared modules, dependency manifests, build tasks, or a benchmark/test framework
  unless explicitly requested. There are no `bb play/stop/status/test` tasks.
- Do not depend on or edit the old sibling Jolt checkout when working here.

## Implementation and comparison

Prefer pure data transformations; isolate process/file I/O at the entry point.
Use idiomatic features of each language, not artificial line-for-line similarity.

Carp has static types and ownership/borrowing. Keep meaningful `Chord`/`Note`
types and collection transformations instead of flat positional data, unsafe
indexing, or hand-written accumulator loops. Its copy/ownership APIs are not
Clojure-style structurally shared persistent collections.

Jolt runs Clojure on Scheme, not the JVM. Keep vectors and sequence operations;
do not assume arbitrary Java interop works. Neither implementation uses a REPL.

Both generate a Csound document and send it to a foreground child process.
Csound, not Carp/Jolt, performs the DSP. Do not claim this compares two audio
engines. Keep the embedded orchestras identical; duplication is intentional so
each file runs independently.

Preserve the fixed 64 BPM, amplitude 0.18, complete 32-bar form, and 120-second
score ending: 39 roots, 217 taps, 64 kicks, 81 melody notes (401 total).
Preserve pitch, duration, pan, the quiet guide melody (0.2 post-drive gain),
foreground taps (2.0 post-drive gain), root vibrato, and syncopated kicks.
Update both versions together if the requested musical behavior changes.

## Audio safety

Do not start live audio unless the user requests playback. Use offline rendering
for development:

```bash
SONG_WAV=/tmp/song-carp.wav carp -x song.carp
jolt song.jolt /tmp/song-jolt.wav
```

Playback is foreground-only; Ctrl+C stops it. Keep both the score-time ending
and the independent 600-second OS wall-clock alarm. Never introduce a daemon
or leave an audio process running after testing.

For a stop request, interrupt only the foreground process group owned by the
requested playback and verify its Csound process exited before claiming silence.
Do not use stale `bb` controller commands, `killall`, or broad `pkill`; never
signal unrelated audio processes.

## Verification

- Carp: `carp --check song.carp` and `carp -b song.carp`.
- Jolt: `clj-kondo --cache false --lang clj --lint song.jolt`, then check formatting
  with `cljstyle pipe` as documented in README. Do not apply Clojure tools to Carp.
- For musical/process changes: compare generated score events and identical
  orchestras, verify all 401 notes and the ending, render both with no clipping,
  and test Ctrl+C plus propagation of engine failures.
- Allow six-significant-digit Carp rounding; never treat random pluck variation
  as a score mismatch or require byte-identical WAVs.
- Use temporary files for ad-hoc checks and renders; keep generated files out
  of the repo. Do not add a framework just to perform verification.
