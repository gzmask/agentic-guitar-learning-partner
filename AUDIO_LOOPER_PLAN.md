# Guitar-Controlled Audio Looper — Hardened Implementation Plan

## Status

This document is the agreed design for the next looper POC. It is a plan, not
an implementation.

The new looper records and replays the guitar's **stereo PCM waveform**. Pitch
tracking is used only for control commands. It must never convert the recorded
performance into MIDI notes or resynthesize the guitar tone.

The existing song comparison and detector/looper experiments remain unchanged
until this plan is implemented and verified. In particular, `song.carp` and
`song.jolt` are out of scope.

## Goals

- Record the actual FX100 or microphone input into reusable audio-loop slots.
- Preserve the player's tone, chords, dynamics, articulation, and effects.
- Control the application using scale-relative guitar-note commands.
- Provide six identical loop slots rather than special drum/bass/rhythm tracks.
- Support record start/stop, playback start/stop, and destructive erase.
- Give immediate terminal and spoken feedback without blocking real-time audio.
- Keep command parsing, looper policy, audio effects, voice prompts, and terminal
  rendering separated and testable.
- Keep the process foreground-only, interruptible with Ctrl+C, and bounded by
  the existing 600-second safety limit.

## Non-goals for the first implementation

- MIDI generation or note-to-synth guitar playback.
- Track types such as drum, bass, or rhythm.
- Overdubbing.
- Multiple simultaneous recorders.
- Automatic chord, key, tempo, or beat detection.
- Arbitrary chromatic command vocabularies.
- A background service, daemon, controller, or network API.
- Persistent project/session management.
- Editing arbitrary regions from a recorded waveform.

## Selected implementation stack

The first implementation uses **Jolt as the application host and Csound 6 as an
embedded audio engine through the native C API**:

```text
Jolt
├── startup and device selection
├── scale/wake configuration
├── pure command reducer and looper policy
├── voice-prompt coordination
├── terminal dashboard and event log
└── jolt.ffi adapter to libcsound

Csound 6.18
├── CoreAudio input/output
├── pitchamdf, RMS gate, and sample clock
├── stereo PCM recording tables
├── loop playback, crossfades, and mixing
└── prompt-buffer playback
```

This is deliberately **not** the current one-shot
`clojure.java.shell/sh -> csound` pattern. The live engine must not be a blocking
subprocess whose command state is hidden inside its orchestra. Jolt owns semantic
state and policy; Csound owns sample-accurate DSP and buffers.

The initial integration rules are:

- Load the Csound framework directly with `jolt.ffi` and isolate every native
  binding in one adapter namespace/file.
- Detect and validate the Csound API version at startup. The target is Csound
  6.18; Csound 7 is a separate compatibility effort because major API versions
  are not assumed compatible.
- Pin a supported Jolt version before implementation. The existing POCs run on
  0.8.10 while the current stable release is newer; existing checks must pass on
  the selected version before the looper depends on newer FFI/process behavior.
- Run the long-lived native Csound performance call on a dedicated Jolt OS
  thread/future and mark it as blocking where the selected Jolt FFI requires it.
- Do not drive `csoundPerformKsmps` from a Jolt allocation-heavy loop. Jolt must
  remain outside the per-audio-block real-time path.
- Exchange state through public Csound control/event APIs at a bounded polling
  rate. Do not share unvalidated raw channel pointers across threads.
- Give every engine-to-host note/status event a monotonically increasing
  sequence number. A gap cancels the current command parser state rather than
  guessing missing input.
- Give every host-to-engine operation its own sequence number so a slow poll
  cannot execute one operation twice or silently overwrite another.
- Avoid C-to-Jolt callbacks in the first version. Add a minimal native shim only
  if the API spike proves direct FFI plus polling insufficient.
- Keep queues and event history bounded. Csound continues audio safely if the
  dashboard or prompt coordinator is temporarily delayed.

Carp remains a possible second host implementation after the semantics are
stable. Its deterministic memory and direct C integration are valuable, but its
no-GC advantage is not decisive while Csound—not the host language—owns the
real-time audio path.

## Core model

All musical layers are generic audio loop slots:

```text
Loop 1
Loop 2
Loop 3
Loop 4
Loop 5
Loop 6
```

The content of a slot may sound like drums, bass, rhythm guitar, lead guitar, or
anything else. The looper does not attach semantic track types to it.

Each slot owns:

```clojure
{:id             1
 :clip-state     :empty       ; :empty | :ready
 :record-state   :idle        ; :idle | :armed | :recording | :finalizing
 :play-state     :stopped     ; :stopped | :queued-start | :playing | :queued-stop
 :buffer         nil
 :length-samples 0
 :cycle          0}
```

The system additionally owns one command parser, at most one active recorder,
a shared audio clock, prompt playback state, and a bounded event log.

## Audio architecture

```text
Physical stereo input
        |
        +--> raw monitor ------------------------------------+
        |                                                     |
        +--> active PCM recorder --> slot buffer --> player --+--> mixer --> output
        |                                                     |
        +--> pitch tracker --> note events --> command parser |
                                                              |
Prompt buffers ------------------------------------------------+
```

Important boundaries:

- The recorder receives physical stereo input, not detected notes.
- The pitch tracker receives physical input, not the output mix.
- Loop playback and voice prompts must not be routed internally back into the
  detector.
- With a physical microphone, speakers can still leak into the microphone.
  Microphone mode therefore requires headphones and disables raw software
  monitoring by default.

### Audio format and capacity

Initial defaults:

- 44.1 kHz.
- Stereo.
- Float32 engine buffers.
- Maximum 120 seconds per slot.
- At most one slot recording at a time.
- Up to six slots playing at once.
- Approximately 42.3 MiB maximum PCM data per 120-second stereo slot and about
  254 MiB if all six slots reach the maximum.

A real-time loop lives in an engine buffer. Writing a `.wav` file is an optional
future export operation, not part of the playback path.

When the maximum recording duration is reached, the engine must finalize safely,
announce that the maximum was reached, and never write past the allocated buffer.

### Click prevention

Finalized clips receive a short 5–10 ms boundary crossfade. Playback start/stop
and application exit use a short gain ramp rather than an instantaneous cut.

## Startup flow

Input selection must happen before guitar-driven setup because the program
cannot detect guitar notes before opening an input device.

```text
1. Select input device using the keyboard.
2. Select default or custom control configuration.
3. Open and validate the audio engine.
4. Preload voice prompts.
5. Show the dashboard and wait for the wake phrase.
```

Example:

```text
Input device:
1. FX100
2. Mac microphone
> 1

Control setup:
1. Default: C major, wake = C D C D
2. Customize scale and wake phrase
> [Enter]
```

Pressing Enter selects the default configuration. Input device names must be
resolved from the currently available CoreAudio devices; volatile `adcN` numbers
must not be treated as permanent identities.

Output uses the configured/default output device and is displayed in the
terminal. An explicit output selector may be added without changing the command
model.

## Control scale

### Default

The default control profile is C major:

```text
Degree:  1   2   3   4   5   6   7
Note:    C   D   E   F   G   A   B
```

Default registers:

```text
Wake:       C5 D5 C5 D5
Selectors:  C4 D4 E4 F4 G4 A4 B4
Operations: C5 D5 E5 F5 G5
```

Using a lower selector register and a higher operation register prevents a
command such as "Loop 1, record start" from becoming two indistinguishable C
notes. It is physically `C4 -> C5`, while remaining logically `degree 1 ->
degree 1`.

### Customization

Custom setup asks the player to play an ascending seven-note scale plus the
upper tonic:

```text
1 2 3 4 5 6 7 1'
```

The first note is explicitly the tonic. The eighth note validates the octave and
completeness of the scale. The first seven played MIDI pitches become the lower
selector register; operation pitches are derived one octave above where they
remain in the supported detector and guitar range.

The first version supports seven-degree scales only. It stores the degree-to-
pitch mapping; identifying a familiar display name such as "C major" is useful
but is not required for command execution.

Pitch classes alone are not enough for command parsing. Wake, selector, and
operation tokens also retain their configured register to reduce collisions
with ordinary playing.

## Wake phrase

The default wake phrase is:

```text
C5 D5 C5 D5
```

Internally it is represented both as exact configured pitches and as scale
degrees:

```clojure
{:midi    [72 74 72 74]
 :degrees [1 2 1 2]}
```

Custom setup asks the player to perform a 3–6 note wake phrase and then remain
silent for one second. The screen displays the captured notes and degrees before
accepting it.

Wake matching requirements:

- Stable pitch confirmation before emitting a note event.
- Configured-register matching, not only pitch-class matching.
- Maximum cents deviation from the target, initially ±35 cents.
- Explicit note events rather than raw analyzer samples.
- A bounded phrase-completion window.
- Prefix/suffix recovery after an incorrect candidate without treating consumed
  command notes as the next wake phrase.
- The successful wake phrase is fully consumed.

## Note-event model and repeated notes

The detector produces explicit events:

```clojure
{:type        :note-on
 :id          123
 :onset-time  12.300
 :confirm-time 12.355
 :midi        60
 :degree      1
 :cents       7.2
 :hz          262.7
 :db          -23.4}

{:type       :note-off
 :id         123
 :time       12.710
 :duration   0.410}
```

`onset-time` and `confirm-time` are distinct. Command recognition may happen
after the stability delay, but audio trimming uses the estimated onset/sample
position rather than adding the recognition delay to the loop.

Initial detector policy remains close to the proven POCs:

- 55 ms stable-pitch confirmation.
- Hysteretic RMS gate, initially -44/-50 dBFS.
- Csound `pitchamdf` over 70–1400 Hz at its native analysis rate, initially
  without median filtering, downsampling, or an input prefilter.
- Pitch validity comes from the RMS gate and frequency range; `pitchamdf` does
  not provide Tartini-style confidence.
- Approximately 120 ms release before clearing an active note.

A repeated pitch may emit a second `note-on` only after a real `note-off`.
Therefore:

```text
C held for 2 seconds              => [C]
C, release >= threshold, C        => [C C]
C4 followed directly by C5        => [C4 C5]
C with vibrato or level flutter    => [C]
```

Control instructions tell the player to articulate repeated command notes
staccato. A later transient/re-attack detector may improve same-pitch retriggering,
but it must not turn compressor pumping, distortion, vibrato, or brief level
dips into duplicate commands.

## Command grammar

The normal grammar is:

```text
WAKE -> LOOP_SELECTOR -> OPERATION
```

Exit is a separate destructive path:

```text
WAKE -> DEGREE_7 -> EXIT_CONFIRMATION
```

### Loop selector

| Degree | Default note | Meaning |
|---:|---|---|
| 1 | C4 | Loop 1 |
| 2 | D4 | Loop 2 |
| 3 | E4 | Loop 3 |
| 4 | F4 | Loop 4 |
| 5 | G4 | Loop 5 |
| 6 | A4 | Loop 6 |
| 7 | B4 | Exit request |

### Operations

| Degree | Default note | Operation |
|---:|---|---|
| 1 | C5 | Record start |
| 2 | D5 | Record stop |
| 3 | E5 | Playback start |
| 4 | F5 | Playback stop |
| 5 | G5 | Erase recorded clip |
| 6–7 | A5/B5 | Reserved/invalid |

Examples:

```text
C5 D5 C5 D5 -> C4 -> C5   Loop 1 record start
C5 D5 C5 D5 -> C4 -> D5   Loop 1 record stop
C5 D5 C5 D5 -> D4 -> E5   Loop 2 playback start
C5 D5 C5 D5 -> D4 -> F5   Loop 2 playback stop
C5 D5 C5 D5 -> C4 -> G5   Request erase of Loop 1
```

Every successful or cancelled command clears the parser and enters a short
cooldown. A command suffix must never become the prefix of the next wake phrase.

## Control state machine

```text
DORMANT
  -- wake matched --> ANNOUNCING_READY

ANNOUNCING_READY
  -- prompt finished + guard --> WAIT_LOOP

WAIT_LOOP
  -- degree 1..6 --> ANNOUNCING_LOOP
  -- degree 7 --> WAIT_EXIT_RELEASE
  -- invalid/timeout --> CANCELLED

ANNOUNCING_LOOP
  -- prompt finished + guard --> WAIT_OPERATION(loop-id)

WAIT_OPERATION
  -- degree 1..4 --> VALIDATE_AND_EXECUTE
  -- degree 5 --> WAIT_ERASE_RELEASE
  -- invalid/timeout --> CANCELLED

VALIDATE_AND_EXECUTE
  -- accepted/rejected --> ANNOUNCING_RESULT

ANNOUNCING_RESULT
  -- prompt finished + guard --> DORMANT
```

Timeouts begin only after the relevant voice prompt and guard interval finish.
During prompts the detector may continue updating its gate and debug estimate,
but command token emission is suspended.

## Loop operation policy

Operations are explicit and idempotent; they are not toggles.

| Slot state | Record start | Record stop | Playback start | Playback stop | Erase |
|---|---|---|---|---|---|
| Empty | Enabled | Disabled | Disabled | No-op | Disabled |
| Armed/recording | No-op | Enabled for active slot | Disabled | Disabled | Disabled |
| Finalizing | Disabled | No-op | Disabled | Disabled | Disabled |
| Ready/stopped | Disabled | Disabled | Enabled | No-op | Enabled with confirmation |
| Playing | Disabled | Disabled | No-op | Enabled | Disabled |
| Queued transition | Disabled | Disabled | State-dependent no-op | State-dependent no-op | Disabled |

`record start` never silently overwrites a clip. The player must erase the old
clip first. The initial implementation has no overdub behavior.

Multiple slots may play concurrently. Only one slot may be armed or recording.
While any slot is recording, the guitar command channel accepts only:

- record stop for that active slot;
- the confirmed emergency/exit path.

Other loop commands are rejected. This prevents wake and command notes intended
for one slot from being recorded into another slot's PCM waveform.

## Recording boundaries

### Record start

1. Recognize and validate the complete command.
2. Announce that recording is armed.
3. Wait for the voice prompt and guard interval to finish.
4. Start at the selected synchronization boundary.
5. Emit a short non-pitched cue and announce/display `RECORDING`.

No startup command or voice prompt may enter the recorded buffer.

### Record stop

The engine cannot wait until the full stop command is recognized because the
wake phrase and command notes would already have been recorded.

1. Keep recording while the command is being recognized.
2. Retain the sample-aligned onset of the wake phrase's first note.
3. After a valid `record stop`, choose the final audio boundary at or before that
   onset according to the synchronization policy.
4. Discard all PCM samples after that boundary.
5. Apply the boundary crossfade.
6. Mark the clip ready and announce completion.

An invalid or timed-out command does not stop recording. Because commands other
than record stop are rejected during recording, the first implementation does
not attempt arbitrary middle-of-clip command removal.

## Synchronization policy

The hardened default is a shared cycle:

- The first successfully finalized clip establishes the master cycle length.
- If no master exists, the first recording starts after its prompt/guard cue and
  ends at the stop wake's onset-derived sample boundary.
- Later recordings start on the next master-cycle boundary.
- Later recording lengths are complete integer multiples of the master cycle.
- Their stop boundary is the latest completed master-cycle boundary at or before
  the stop wake onset.
- Playback start and stop are queued to the next master-cycle boundary.
- The terminal and voice distinguish `queued` from actual `started/stopped`.

If the slot that originally established the master is erased while other clips
remain, the master period is retained. The master resets only when all clips are
empty.

A future configuration may permit fully asynchronous slots, but that is not the
initial behavior.

## Erase operation

Erase is destructive and requires explicit confirmation.

```text
WAKE -> LOOP_SELECTOR -> degree 5
```

Example:

```text
C5 D5 C5 D5 -> C4 -> G5
```

Flow:

1. Receive the first G5 and wait for its `note-off`.
2. Say: `Erase looper one? Play G again to confirm.`
3. Wait for the prompt and guard interval.
4. Require a fresh G5 note, held for at least 800 ms and then released.
5. On success, fade/release the clip buffer and set the slot to empty.
6. Any other note or timeout cancels without changing the clip.

Erase is enabled only for a ready, stopped clip. A playing loop must first receive
an explicit playback-stop command. Erase never implicitly stops and deletes a
playing clip.

## Exit operation

Exit is also destructive and requires confirmation:

```text
WAKE -> degree 7 -> release -> prompt -> fresh degree 7 confirmation
```

The second degree-7 note must be held for at least 800 ms and released. Any other
note or timeout cancels exit.

Confirmed exit:

1. Fade the mixer over at least 30 ms.
2. Stop/finalize or discard any active incomplete recording according to the
   explicit shutdown policy; never publish a partial clip as complete.
3. Stop and destroy only the embedded Csound instance owned by the host.
4. Restore the terminal.
5. Return an accurate process exit status.

Ctrl+C remains an immediate foreground safety stop and does not require a guitar
confirmation.

## Voice prompts

Voice prompts are part of the state machine. They are not fire-and-forget log
messages.

Typical flow:

```text
wake
  -> "Ready. Select looper."
C4
  -> "Looper one selected. Select action."
C5
  -> "Recording armed."
boundary
  -> "Recording."
```

Minimum prompts:

- Ready; select looper.
- Looper one through looper six selected.
- Select action.
- Recording armed / recording / recorded.
- Playback queued / playing / stopped.
- Erase confirmation / erased.
- Invalid command and state-specific rejection.
- Command cancelled.
- Exit confirmation / exiting.
- Maximum recording length reached.
- Audio engine failure when it is safe to report one.

### Prompt implementation

Do not invoke live TTS synchronously from an audio callback. For the macOS POC:

1. Generate the finite prompt set into temporary AIFF/WAV files at startup,
   potentially using `say`.
2. Load those files into engine buffers.
3. Play them through the selected engine output.
4. Send a semantic `prompt-finished` event to the controller.
5. Cache prompts for the process lifetime and delete temporary assets on normal
   cleanup where practical.

Each prompt begins with an immediate short, low-level, non-pitched cue. The cue
provides low-latency acknowledgement; speech explains the state.

During prompt playback:

- command-token emission is suspended;
- the screen says that listening is paused;
- a 100–150 ms guard follows prompt completion;
- command timeout starts after the guard;
- raw detector/debug display may continue.

With microphone input, headphones are required so spoken output is not captured
acoustically by the microphone.

## Terminal dashboard

The normal terminal uses a fixed ANSI dashboard rather than printing an
unbounded line for every analyzer sample.

Example:

```text
GUITAR AUDIO LOOPER
--------------------------------------------------------------------
Input: FX100                 Output: MacBook Speakers
Control: WAITING FOR WAKE    Voice: idle
Scale: C major
Degrees: 1=C  2=D  3=E  4=F  5=G  6=A  7=B
Wake: 1-2-1-2  [C5 D5 C5 D5]

LOOPERS
1  EMPTY
2  RECORDING   00:12.438
3  READY       length=8.000s
4  PLAYING     length=16.000s  cycle=4
5  EMPTY
6  STOPPED     length=8.000s

PITCH DEBUG
Gate: OPEN    Level: -23.4 dBFS
Current: C4   MIDI=60   degree=1   cents=+7.2   262.7 Hz
Event: NOTE_ON C4
History: C5 D5 C5 D5 C4
Parsed: WAKE -> LOOP 1

AVAILABLE GUITAR INPUT
C5  Record start       [disabled: clip already exists]
D5  Record stop        [disabled: not recording]
E5  Playback start     [enabled]
F5  Playback stop      [disabled: not playing]
G5  Erase recorded     [enabled: confirmation required]

Keyboard: [d] raw pitch debug  [c] configure  [q] safe quit
--------------------------------------------------------------------
EVENTS
12.340  Wake recognized
13.102  Looper 1 selected
13.104  Voice: "Looper one selected. Select action."
```

### Required dashboard information

1. Current input/output, scale, degree mapping, wake phrase, parser state, and
   voice-prompt state.
2. All loop slot states, durations, queue status, and cycles.
3. Latest pitch estimate and confirmed `note-on`/`note-off` event.
4. Recent accepted tokens and parser interpretation.
5. Context-sensitive valid and invalid navigation options.
6. Command timeout countdown where applicable.
7. A bounded recent semantic-event log.

### Context-sensitive menu

The menu is generated from the same policy data used to validate commands and
select voice prompts. There must not be separate hard-coded truth in the parser,
voice layer, and renderer.

A policy result resembles:

```clojure
{:degree       3
 :note         "E5"
 :operation    :playback-start
 :enabled?     true
 :reason       nil
 :voice-prompt :playback-queued}
```

The UI shows disabled operations and the reason rather than silently omitting
them.

### Rendering constraints

- Never print from an audio callback.
- Pass audio/control events through a bounded queue.
- Render an immutable snapshot at no more than 10 frames per second.
- Redraw only when relevant state changes.
- Hide and restore the cursor correctly.
- Restore terminal state on normal exit, Ctrl+C, and handled failures.
- Provide `--no-tui` line-oriented output for checks and redirected logs.
- Raw analyzer output is opt-in and rate-limited; it is never printed at the
  analyzer's full rate.

## Pure control/effect boundary

The controller should be a pure reducer where practical:

```clojure
(step state event) => [new-state effects]
```

Effects represent I/O without performing it in the reducer:

```clojure
{:type :prompt :id :select-looper}
{:type :record-arm :loop-id 1}
{:type :record-finalize :loop-id 1 :boundary-sample 441000}
{:type :play-queue :loop-id 2 :at-sample 882000}
{:type :buffer-erase :loop-id 1}
{:type :exit}
```

Prompt completion, audio-boundary completion, and engine errors return as input
events. This keeps terminal rendering and speech from controlling musical state
through side effects.

## Safety and failure handling

- Do not start live audio during development unless the user explicitly requests
  playback/testing.
- Keep the Jolt host process in the foreground and keep the embedded Csound
  instance owned by that process.
- Run Csound performance outside Jolt's per-block allocation path; never call
  terminal rendering or prompt logic from the real-time path.
- Check the Jolt and Csound API versions before starting audio.
- Treat engine/host event sequence gaps as command cancellation, never as a
  partially recognized command.
- Preserve the independent 600-second wall-clock alarm around the host process
  and an engine-side session limit.
- Ctrl+C must stop the owned engine and restore the terminal.
- Never use `killall` or broad `pkill` cleanup.
- Propagate nonzero engine failures.
- Do not publish a partial recording as a completed clip.
- On queue overflow, buffer allocation failure, or timestamp inconsistency,
  reject/finalize safely and report the reason.
- Validate all sample boundaries against allocated buffer lengths.
- Use headphones and low output volume, especially with microphone input.
- Disable raw software monitoring by default for microphone input.

## Verification plan

### Pure logic checks

Run without audio devices and verify:

- Default and custom scale mapping.
- Wake prefix matching, timeout, recovery, and consumption.
- Register-aware selector/action parsing.
- Held note produces one token.
- Released/rearticulated same pitch produces two tokens.
- `C4 -> C5` produces selector degree 1 then operation degree 1.
- All state/operation policy combinations.
- Duplicate commands are idempotent.
- Prompt and guard states reject premature tokens.
- Erase and exit require release plus fresh held confirmation.
- Invalid/timeout destructive commands preserve buffers.
- Recording mode rejects commands for other slots.
- Command suffixes cannot seed a new wake phrase.
- Dashboard menu exactly matches policy output.

### Embedded API checks

Run without audio devices and verify:

- Jolt can load the selected Csound 6 framework and read the expected API
  version.
- `csoundCreate`/compile/start/stop/cleanup/destroy succeeds through the isolated
  FFI adapter.
- A no-audio CSD can perform on the dedicated blocking worker while the Jolt
  control/UI thread remains responsive.
- Host-to-engine and engine-to-host sequence numbers are monotonic and duplicate
  operations are rejected.
- An injected event-sequence gap resets the command parser fail-closed.
- Public control-channel access is safe under the selected Csound performance
  model; otherwise the spike must stop and introduce a minimal native bridge
  before application work continues.
- Engine compile/start/runtime failures become structured host errors and leave
  no live Csound instance.

### Audio checks without live input

Using generated temporary audio buffers:

- Stereo input remains stereo and is not resynthesized.
- Record-start boundary excludes prompts and command audio.
- Record-stop trimming uses wake onset/sample time, not confirmation time.
- Buffer bounds hold at zero, normal, and maximum durations.
- Boundary crossfades avoid discontinuities.
- Six concurrent players mix without clipping under the defined gain policy.
- Queued start/stop occurs on exact master boundaries.
- Erase releases the correct slot only.
- Engine errors propagate to a nonzero host exit.

### Manual live checks

Only when the user requests and is ready to play:

- FX100 and microphone selection.
- Default C-major setup and custom setup.
- Wake recognition under clean and effected guitar tones.
- Same-note staccato behavior.
- Voice prompt timing and command guards.
- Record/play/stop/erase on all six slots.
- No command phrase in finalized recordings.
- No prompt feedback with headphones.
- Ctrl+C cleanup and confirmed guitar exit.

### Acceptance criteria

- Recorded playback audibly preserves the original input rather than a synthesized
  approximation.
- A held pitch never becomes two command tokens.
- Intentional repeated notes are reliably separable with documented articulation.
- No destructive operation executes from a single unconfirmed token.
- No recording is silently overwritten.
- Menus, spoken prompts, and actual accepted commands remain consistent.
- The embedded Csound instance and any owned startup prompt-generation children
  stop on exit; no unrelated process is signaled.

## Implementation sequence

0. **Jolt/libcsound technical spike**
   - Pin the Jolt version and Csound 6.18 API expectation.
   - Load libcsound through `jolt.ffi` and validate its version.
   - Create, compile, perform, stop, clean up, and destroy a no-audio instance.
   - Prove the dedicated blocking performance worker does not block control/UI.
   - Prove sequenced bidirectional control events and fail-closed gap handling.
   - Record and replay a generated stereo signal offline before opening devices.

1. **Pure domain model**
   - Scale/wake configuration.
   - Note-event model.
   - Command parser and policy.
   - Six-slot state machine.
   - Pure checks for all transitions.

2. **Terminal dashboard**
   - Startup keyboard selection.
   - Context-sensitive menu.
   - Pitch event/debug presentation.
   - `--no-tui` and cleanup behavior.

3. **Single-slot PCM engine**
   - Stereo recording buffer.
   - Sample-aligned start/stop.
   - Crossfade and playback.
   - Offline verification first.

4. **Six slots and master cycle**
   - One active recorder.
   - Concurrent playback.
   - Queued cycle-boundary transitions.
   - Capacity and clipping checks.

5. **Voice prompts**
   - Startup generation/cache.
   - Engine-buffer playback.
   - Prompt-finished events and guards.
   - Microphone/headphone safety.

6. **Destructive operations and shutdown**
   - Confirmed erase.
   - Confirmed guitar exit.
   - Ctrl+C and engine-failure cleanup.

7. **Live validation**
   - Performed only with explicit user participation and low-volume/headphone
     precautions.

## Recommended first implementation target

Create a new self-contained Jolt/Csound POC. Jolt owns startup configuration,
the pure control reducer, command policy, voice coordination, dashboard, and
error handling. Csound 6.18 is embedded through `jolt.ffi` and owns CoreAudio,
`pitchamdf`, the sample clock, PCM buffers, playback, crossfades, prompt audio,
and mixing.

Borrow the host-owned pure-state architecture demonstrated by
`guitar_gesture_looper.clj`, not its JVM, Overtone, SuperCollider, OSC, or
Tartini dependencies. Do not copy the current Jolt scripts' blocking `sh`
launcher or move the expanded command state machine into Csound orchestra code.

Use a separate source file while the existing note-based POCs remain useful as
historical experiments. Stabilize and verify one Jolt/libcsound implementation
before considering a Carp host port; do not rewrite all POCs simultaneously.
