# Agentic Guitar Learning Partner — agent controls

This project does **not** use a REPL. Read `README.md` before changing sounds.
For user-directed audio: `bb play guitar a minor 2 64 2.0 0.18`, `bb play chord a
minor 3`, `bb play mode d dorian`, or `bb play progression` (one complete
32-bar Fly Me to the Moon chorus at 64 BPM, two minutes: lead melody,
sustained electric roots with vibrato at every chord change, right-hand tapping
on the remaining eighths, and syncopated kicks on the and of beats 1 and 3).
`bb status` verifies that the project-owned Csound process exists and shows its
latest accompaniment chord. `bb watch` follows live chord text from an existing
progression without starting audio; Ctrl+C detaches only the text display.
On any request to stop: **run `bb stop` and then `bb
status`**; do not claim silence before confirming the process exited. Do not
kill unrelated Csound processes. Audio is off by default; any playing sound
automatically ends within 10 minutes (Csound score-time shutdown and an OS
wall-clock alarm). `bb test` tests both limits without live audio. The score generator is
`src/guitar_score.carp`, complete melody/harmony data is `src/fly_me.carp`,
synth is `csound/guitar.orc`, process management is
`scripts/control.bb`.
