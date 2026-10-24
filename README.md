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

## FX100 pitch detector (separate experiment)

`guitar-to-midi.jolt` reads the FX100 USB audio input and prints detected MIDI
note numbers, note names/octaves (C4 = MIDI 60), frequency, and level. It tracks
one note at a time; it does not yet send MIDI messages or synthesize a MIDI voice.
The two song implementations above are unchanged.

```bash
jolt guitar-to-midi.jolt                 # console detection, silent output
jolt guitar-to-midi.jolt --monitor       # also hear the stereo USB input at -6 dB
jolt guitar-to-midi.jolt --check --monitor # syntax check only; no audio devices
```

The current defaults are `adc2`/`dac4` (FX100 input/output). With `--monitor`,
listen through headphones/speakers connected to the FX100. To use a different
output, supply both device identifiers, for example:

```bash
csound -+rtaudio=coreaudio --devices
jolt guitar-to-midi.jolt --monitor adc2 dac5
```

Device numbers can change; verify that the chosen input is the FX100, not a
microphone. Start with low volume and preferably headphones. Avoid duplicate
monitoring through both the FX100's direct path and Csound (it can sound doubled
or phasey); stop if feedback occurs. Monitoring passes through the FX100's USB
signal, including any effects, independently of pitch detection and its noise
gate. A clean preset works best for detection. USB/software monitoring adds
latency. Default mode remains silent. Both modes stay in the foreground:
**Ctrl+C stops**, with a 600-second score limit and independent wall-clock alarm.

### AMDF alternatives: `guitar-pitchamdf.jolt` / `guitar-pitchamdf.carp`

These self-contained Jolt and Carp implementations use the same Csound
`pitchamdf` orchestra, without changing the working `ptrack` script. They print
confirmed notes immediately as MIDI number, name/octave (C4=60), frequency,
cents, and the AMDF RMS level:

```bash
jolt guitar-pitchamdf.jolt --monitor adc2 dac5  # FX100 input, selected output
jolt guitar-pitchamdf.jolt                    # default adc2/dac4, silent output
jolt guitar-pitchamdf.jolt --raw adc2 dac5     # estimates every 100 ms, no playback
jolt guitar-pitchamdf.jolt --check            # syntax only; no audio devices
```

For Carp, build once and pass the same options to the generated executable:

```bash
carp --check guitar-pitchamdf.carp             # check Carp source only
carp -b guitar-pitchamdf.carp                  # compile; no audio devices
pitchamdf="${TMPDIR:-/tmp}/carp-guitar-pitchamdf/guitar-pitchamdf"
"$pitchamdf" --check                          # check Csound; no audio devices
"$pitchamdf" --monitor adc2 dac5               # live input and monitoring
"$pitchamdf" --raw --gate=-48 adc2 dac5        # live detection, silent output
```

`carp -x guitar-pitchamdf.carp` builds and starts live detection with the defaults
(`adc2`/`dac4`, silent output). Build artifacts stay in the temporary directory.
Carp streams Csound's logs directly; both versions wait for the foreground
engine and return a nonzero exit code if it fails. Carp also uses system Perl
for the independent safety alarm. Neither implementation depends on the other.

AMDF searches 70–1400 Hz, uses its native analysis rate (`iexcps=0`), with no
pitch/RMS median filtering, no downsampling, and no input prefilter. Confirmed
notes require 55 ms of stable MIDI pitch; 120 ms of release allows the same note
to be reported again. It does not constrain notes to C major or any song.

The default RMS gate is **-44/-50 dBFS**, allowing quieter notes than the old
live script's settings. Override its opening threshold with `--gate=-48` (the
closing threshold stays 6 dB lower). `--raw` bypasses note confirmation, not the
gate; closed-gate/out-of-range estimates display `note=--` with their Hz/level.
AMDF's RMS is not `ptrack`'s spectral dB estimate: this live script is for
subjective evaluation, not the calibrated same-recording comparison below.

`--monitor` passes stereo input through at -6 dB independently of detection.
Use headphones/low volume, verify device numbers, and avoid duplicate monitoring
or feedback. All output remains in the foreground; Ctrl+C stops it, and the score
and independent OS alarm both cap runtime at 600 seconds. No MIDI messages are
sent. Both versions require Csound and system Perl, plus their respective
language runtime/toolchain.

## 60-second note looper (Carp)

`guitar-looper.carp` is a separate, self-contained experiment. It records
**detected notes, not audio**, for the first 60 seconds, while passing the live
stereo guitar through at -6 dB. At 60 seconds it stops detection and starts
looping that phrase with the pluck/overdrive/sustain/vibrato guitar tone from
`song.carp`. New playing stays **raw stereo audio** throughout: it is not
converted to synth notes or added to the recording. The live input and recorded
synth loop are mixed together. Synth voices have 150 ms of additional ringing
time and do not cut each other off.

```bash
carp --check guitar-looper.carp
carp -b guitar-looper.carp       # creates ./guitar-looper; generated C stays in temp
./guitar-looper --check         # Csound syntax only; no devices or output files
./guitar-looper adc2 dac5       # FX100 input -> MacBook speakers (verify numbers)
# Or, for a quieter input:
./guitar-looper --gate=-48 adc2 dac5
```

Defaults are `adc2`/`dac5`, currently FX100 input / MacBook speakers. Device
numbers can change: check `csound -+rtaudio=coreaudio --devices`. Start with low
volume/headphones, avoid duplicate direct monitoring, and never select a
microphone that can feed the speakers back into the detector.

The detector uses the same 70–1400 Hz native-rate AMDF, -44/-50 dBFS RMS gate,
55 ms pitch confirmation and 120 ms release as `guitar-pitchamdf.carp`. Note
starts use confirmation time; durations end at the next confirmed pitch or the
start of a confirmed silence. A note still held at 60 seconds is closed there.
Rests and timing are retained; pitches are rounded to equal-tempered MIDI notes.
This is monophonic detection, not chord transcription.

On completion of the recording, `./guitar-loop.csd` is atomically replaced with
a standalone, single-pass synthesized take (60 seconds plus a 150 ms tail). An
interrupted/incomplete take leaves the previous file unchanged. Both this file
and the local binary are git-ignored. To replay only the saved synth take:

```bash
/usr/bin/perl -e 'alarm 600; exec @ARGV or die $!' \
  csound -d -m0 -odac5 guitar-loop.csd
```

The live session plays up to nine 60-second loops after recording. **Ctrl+C
stops both monitoring and synthesis**; the score ends at 600 seconds total and
an independent OS alarm caps wall time at 600 seconds. No background process,
MIDI messages, overdubbing, or modification of the two song implementations is
involved. Carp manages configuration and score export; Csound does the DSP.

## Guitar-controlled note looper (Jolt)

`guitar-gesture-looper.jolt` replaces the fixed recording timer with guitar-note
commands. It starts idle, with raw stereo monitoring at -6 dB:

| Current state | Play these four notes | Action |
| --- | --- | --- |
| Idle | C5 → D5 → C5 → D5 | Start recording detected notes |
| Recording | D5 → C5 → D5 → C5 | Finish recording and loop the synthesized take |
| Playback | D5 → C5 → D5 → C5 | Fade out and exit the program |

```bash
jolt guitar-gesture-looper.jolt --check        # syntax only; no audio devices
jolt guitar-gesture-looper.jolt adc2 dac5      # FX100 -> MacBook speakers
# Or use --gate=-48 before the two device arguments for a quieter input.
```

C4 is MIDI 60, so the controls are **C5 = MIDI 72 (~523 Hz)** and
**D5 = MIDI 74 (~587 Hz)**, not C4/D4. Play four consecutive, clearly sustained
single notes, with all four confirmations within **4 seconds**. The detector
uses the same native-rate AMDF, RMS gate and 55 ms confirmation / 120 ms release
as the standalone detector. The terminal prints confirmed MIDI notes and state
changes. A held note counts only once; repeating the same pitch requires a
release. Each transition consumes its entire command: play a fresh four-note
command for the next transition, rather than sharing notes between commands.

Neither the start nor the complete stop command enters the take. Its duration
ends at the first note of the stop command, excluding the command's trailing
time. Incomplete commands remain ordinary recorded notes. These exact gestures
are reserved: playing them as part of the music can trigger a transition.
Recording length is **manual, not fixed at 60 seconds**. The take is kept in
memory and discarded on exit; this script does not overwrite `guitar-loop.csd`.

Playback uses the same ringing synthesized guitar tone as the Carp looper.
New guitar playing remains raw and is never overdubbed or synthesized, but is
still analyzed for the exit command. Raw monitoring is independent of the noise
gate. An empty take is allowed and still accepts the exit command.

Defaults are `adc2`/`dac5`; verify current device numbers and use low volume or
headphones to avoid feedback/direct-monitor doubling. The engine stays in the
foreground. **Ctrl+C always stops it**, and both the score and independent OS
alarm limit the whole session (idle + recording + playback) to 600 seconds.
Jolt handles configuration and process launch; Csound handles the real-time
state machine and DSP. The existing scripts and song implementations are unchanged.

## Guitar-controlled note looper (JVM Clojure + Tartini)

`guitar_gesture_looper.clj` implements the same three guitar-note commands as
the Jolt gesture looper, but uses SuperCollider's `Tartini.kr` for monophonic
pitch detection. Clojure owns the note filter and state machine; SuperCollider
handles raw monitoring and the synthesized loop. It requires Overtone and the
`PitchDetection` sc3-plugin described in the next section.

```bash
clojure -Sdeps '{:deps {overtone/overtone {:mvn/version "0.16.3331"}}}' \
  -M guitar_gesture_looper.clj --check

clojure -Sdeps '{:deps {overtone/overtone {:mvn/version "0.16.3331"}}}' \
  -M guitar_gesture_looper.clj --input Fx100 --output Fx100
```

`--check` verifies the pure state machine and compiles the Tartini, voice, and
mixer graphs without opening audio devices. Live device values are
SuperCollider/CoreAudio names, not Csound `adcN`/`dacN` identifiers. Override
the default -44/-50 dBFS gate with, for example, `--gate=-48`.

The commands, four-second command window, command-note exclusion, 55 ms pitch
confirmation, 120 ms release, in-memory note capture, raw -6 dB monitoring, and
600-second foreground safety limit match the Jolt version. Tartini runs with
threshold 0.93, N=2048, k=1024, overlap=1024; confidence must exceed 0.9. Loop
notes are sent back to SuperCollider as timestamped synth events, while new
guitar playing remains raw and is never overdubbed. Use low volume/headphones,
avoid direct-monitor doubling, and press Ctrl+C to stop.

## PCM audio looper — ready for live hardware validation

[`AUDIO_LOOPER_PLAN.md`](AUDIO_LOOPER_PLAN.md) describes the six-slot looper that
records the actual stereo waveform and uses pitch only for commands. **Stages
0–6 are implemented and pass the offline suite.** The explicit live path is wired,
but has not yet been validated with a real guitar/FX100 session. The existing
note loopers and songs are unchanged.

```bash
jolt guitar-audio-looper.jolt                 # OFFLINE UI preview; no audio stream
jolt guitar-audio-looper.jolt --no-tui        # same preview, plain output
jolt guitar-audio-looper.jolt --devices       # current CoreAudio metadata only
jolt guitar-audio-looper.jolt --check-control # pure reducer checks
jolt guitar-audio-looper.jolt --check-ui      # pure setup/renderer checks
jolt guitar-audio-looper.jolt --check-runtime # reducer/effect adapter checks
jolt guitar-audio-looper.jolt --check-engine  # production DSP/PCM/TTS checks
jolt guitar-audio-looper.jolt --check         # complete offline suite
jolt guitar-audio-looper.jolt --live          # live mode; input selection starts audio
```

The default command remains an **offline preview**, not a silent launch of live
audio. On a terminal it offers the current input list and then uses the default
C-major profile immediately; every later looper command comes from the guitar. `[d]` toggles pitch debug, `[c]` reopens configuration only
when the session is empty/idle, and `[q]` quits. Ctrl+D, Ctrl+C, SIGTERM, and
handled errors restore terminal configuration and the cursor/alternate screen.
Redirected preview prints one plain snapshot and exits; `--no-tui` never emits
ANSI and logs only semantic state transitions unless pitch debug is enabled.
Because `--no-tui` explicitly selects line mode, use `--live` without that flag
for the full fixed ANSI dashboard.

Only `--live` can open audio. It requires a foreground TTY and fresh UID-based
device resolution. Selecting the input is the only mandatory keyboard setup;
that selection starts finite prompt preparation and then opens the engine, with
a low-volume/headphone warning but no redundant `LIVE` text confirmation. An
initial FX100 hardware pass completed end-to-end record/playback and is driving
iterative control/mix improvements.

Device enumeration reads CoreAudio metadata without opening a stream. Selection
retains stable CoreAudio UIDs and re-resolves volatile `adcN`/`dacN` identifiers;
an unplugged or ambiguous device is rejected. FX100 is treated as guitar/line
input. Other inputs require explicit headphone confirmation and default to
software monitoring off.

`audio_looper/control.jolt` is the pure `(step state event) => [state effects]`
policy. `runtime.jolt` is the 100 Hz host adapter. `dsp.jolt` and `engine.jolt`
own the production Csound document and bounded transport. Csound performs all
real-time work: `pitchamdf`, gating, the sample clock, six stereo PCM buffers,
one recorder, six players, cycle-boundary scheduling, crossfades, prompt audio,
and output mixing. Wake, selector, action, and exit-confirmation notes
match their configured scale degree in any supported octave; parser phase keeps
the meanings unambiguous. Jolt does no per-block DSP, and recorded guitar is
never converted to MIDI or resynthesized.

The native engine uses a sequenced host mailbox and a 128-entry committed event
ring. Pending reducer effects become clips/playback only after matching native
acknowledgements. Tests cover arbitrary sample boundaries, command trimming,
5 ms seams, 10 ms start/stop ramps, six concurrent slots, isolated erase,
maximum-length freezing, event gaps/duplicates, clipping headroom, and every
recorded stereo sample against its source. The first/master clip also removes
outer silence from bounded 10 ms RMS summaries at -50 dBFS while retaining 20 ms
preroll and 50 ms tail. Later clips preserve complete master cycles so trimming
cannot destroy synchronization. A single loop plays at 0.65 gain while the raw
software monitor uses 0.30, a roughly 9 dB loop-to-live relative increase over
the first hardware-tested mix; simultaneous loops are normalized before the
final limiter.

Finite spoken prompts are generated by owned `/usr/bin/say` children before the
audio stream opens, validated as 44.1 kHz mono float32 WAV, copied into Csound
tables, and deleted at shutdown. Csound—not `say`—plays them. The finite
94-prompt cache is generated before audio opens; startup remains inside the independent 600-second OS limit.

The POC pins **Jolt v0.8.10, Csound 6.18/API 4.00, double MYFLT**.
`audio_looper/csound.jolt` owns all FFI bindings. The bounded
`audio_looper/diagnostics.c` bridge latches engine errors and bulk-loads validated
prompt WAVs through libsndfile. Apple command line tools compile it into a
temporary dylib linked to the same Csound framework and Homebrew libsndfile.
Set `CSOUND_LIBRARY` or `SNDFILE_PREFIX` for nonstandard installations.

`--check` opens no audio devices. It runs pure policy/UI/runtime checks plus the
actual production orchestra offline: stereo PCM preservation, six slots,
capacity, prompt/cue completion and cancellation, and native detector lifecycle
against the pure reference. Separate process checks cover SIGINT/SIGTERM cleanup,
the OS alarm, incompatible ABI rejection, and engine failures. A full offline
startup with the complete prompt set and maximum six-slot capacity also completes
without clipping or a lingering process.

The installed engine stores tables as float64. A 120-second stereo slot requires
about **80.75 MiB**, or **484.5 MiB** for six before engine overhead. Float32 is
only the temporary prompt/offline WAV representation.

## Same-recording pitch comparison

`guitar_pitch.clj` is a separate JVM Clojure/Overtone experiment. Its fair
comparison mode runs Csound `ptrack` and `pitchamdf` plus SuperCollider
`Pitch.kr` and `Tartini.kr` **offline on one recording**, rather than comparing
different performances or audio output devices. Requires Clojure, SuperCollider,
Csound, FFmpeg, and the `PitchDetection` extension from the matching
[sc3-plugins release](https://github.com/supercollider/sc3-plugins/releases).
For SuperCollider 3.14.1, copy the `PitchDetection` directory from
`sc3-plugins-3.14.1-macOS.zip` into
`~/Library/Application Support/SuperCollider/Extensions/`. No project dependency
manifest or persistent server is needed.

Record a clean, single-note passage with your DAW, or capture 30 seconds from
FX100 without playback (run this yourself when ready to play):

```bash
ffmpeg -f avfoundation -i ":Fx100" -t 30 -c:a pcm_s16le /tmp/guitar.wav
```

Then compare that same take:

```bash
clojure -Sdeps '{:deps {overtone/overtone {:mvn/version "0.16.3331"}}}' \
  -M guitar_pitch.clj --compare /tmp/guitar.wav
```

All four trackers receive the exact same float32 mono WAV at 44.1 kHz (stereo
is averaged as `(L + R) / 2`), with 32-sample control blocks. The Clojure layer
samples all traces on the same 100 Hz audio-time grid and supplies **one shared
1024-sample trailing RMS calculation**, -36/-42 dBFS gate, 55 ms note confirmation,
and 120 ms release. No tracker's own amplitude estimate controls this gate;
`pitchamdf`'s RMS output is deliberately ignored. Pitch.kr's separate amplitude
threshold is disabled; its intrinsic `hasFreq` validity is still respected.
Tartini uses its confidence output with a `>0.9` validity threshold. Parameters
are printed: `ptrack` hop=1024; `pitchamdf` 70–1400 Hz, native analysis rate,
median=0, downsample=1; `Pitch.kr` 70–1400 Hz, exec=100 Hz, bins/octave=64,
median=1; Tartini threshold=0.93, N=2048, k=1024, overlap=1024. This compares
these explicit configurations, not a claim about their default settings.

Each row shows the shared timestamp/RMS and all four raw pitch estimates and
newly confirmed notes (`new=`). Add `--raw` to print every analysis row,
including invalid/silent estimates. The final MIDI sequences help locate
disagreements; event counts alone are **not accuracy scores**, and audio-time
confirmation positions are **not hardware round-trip latency measurements**.
Accuracy needs known notes/onsets; live latency needs a separate measurement.

The comparison opens no audio devices, ends at the recording's ending (maximum
length less than 600 seconds), and deletes its temporary files. Ctrl+C stops the
owned foreground child; each child also has a 600-second wall-clock alarm. The
existing standalone live scripts remain useful for trying the input, but are
**not** the calibrated comparison. `guitar_pitch.clj --check` compiles the
Pitch.kr and Tartini graphs without opening audio; only `--compare` verifies
that the external Tartini server plugin is installed. Live mode still uses
Pitch.kr, defaults to 60 seconds, and prints an explicit normal-ending message.

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
