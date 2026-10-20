# Agentic Guitar Learning Partner — agent controls

This project does **not** use a REPL. Read `README.md` before changing sounds.
For user-directed audio: `bb play guitar a minor 2 64 2.0 0.18`, `bb play chord a
minor 3`, `bb play mode d dorian`, or `bb play progression` (four loops of the
Fly Me to the Moon changes at 64 BPM, two minutes: sustained electric roots
with vibrato, seven right-hand taps per bar, and syncopated kicks on the and of
beats 1 and 3). `bb status` verifies that the project-owned
Csound process exists. On any request to stop: **run `bb stop` and then `bb
status`**; do not claim silence before confirming the process exited. Do not
kill unrelated Csound processes. Audio is off by default; any playing sound
automatically ends within 10 minutes (Csound score-time shutdown and an OS
wall-clock alarm). `bb test` tests both limits without live audio. The score generator is
`src/guitar_score.carp`, synth is `csound/guitar.orc`, process management is
`scripts/control.bb`.
