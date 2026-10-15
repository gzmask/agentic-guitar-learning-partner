sr = 48000
ksmps = 64
nchnls = 2
0dbfs = 1

; score: i 1 0 -1 bpm envelope-sec total-amplitude f1 f2 f3 f4 f5
; One five-string downstroke every two beats. The orchestra, not a REPL,
; owns timing and self-terminating envelopes.
instr 1
  kbpm = p4
  iduration = p5
  iamplitude = p6 / 5
  kstrike metro kbpm / 120
  if (kstrike == 1) then
    event "i", 2, 0,      iduration, p7,  iamplitude, -0.4
    event "i", 2, 0.018,  iduration, p8,  iamplitude, -0.2
    event "i", 2, 0.036,  iduration, p9,  iamplitude,  0
    event "i", 2, 0.054,  iduration, p10, iamplitude,  0.2
    event "i", 2, 0.072,  iduration, p11, iamplitude,  0.4
  endif
endin

; Plucked string: deterministic, hard two-second (or configured) envelope.
instr 2
  aenv linseg 0, 0.008, 1, max(0.01, p3 - 0.008), 0
  astr pluck p5, p4, p4, 0, 1
  astr tone astr, 4800
  aleft = astr * aenv * sqrt((1 - p6) / 2)
  aright = astr * aenv * sqrt((1 + p6) / 2)
  outs aleft, aright
endin

; A restrained kick: one low-frequency sweep on beat 1 of each bar.
; score: i 5 start 0.35 amplitude
instr 5
  aenv expseg 1, 0.35, 0.0001
  kfreq expseg 145, 0.09, 52, 0.26, 46
  akick oscili p4 * aenv, kfreq
  outs akick, akick
endin

; score: i 3 0 -1 amplitude f1 f2 f3 f4 f5
; Stable five-note pad with slow edges; process termination is the hard stop.
instr 3
  aenv linseg 0, 1.2, 1, 86400, 1
  a1 oscili p4 / 5, p5
  a2 oscili p4 / 5, p6
  a3 oscili p4 / 5, p7
  a4 oscili p4 / 5, p8
  a5 oscili p4 / 5, p9
  outs (a1 + a2 + a3 + a4 + a5) * aenv * 0.8, (a1 + a2 + a3 + a4 + a5) * aenv * 0.8
endin

; Hard end of performance: a sustained pad or a late strum cannot extend it.
instr 99
  exitnow
endin
