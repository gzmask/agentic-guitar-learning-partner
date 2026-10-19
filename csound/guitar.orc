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

; score: i 2 start duration freq amplitude pan
; Sustaining electric string with a plucked attack and delayed vibrato.
instr 2
  ; electric guitar: fast attack, long sustain plateau, quick release
  aenv linseg 0, 0.006, 1, 0.05, 0.85, max(0.01, p3 - 0.15), 0.8, 0.09, 0
  astr pluck p5, p4, p4, 0, 1
  ; sustained tone layer (fundamental + octave) reinforces the string
  ; delayed vibrato: depth ramps in ~0.35s after the note, ~5.5 Hz, +/-1.5%
  kvibdepth linseg 0, 0.35, 0, 0.25, 1
  kvib oscili kvibdepth * 0.015, 5.5
  kvfreq = p4 * (1 + kvib)
  asus1 oscili p5 * 0.5, kvfreq
  asus2 oscili p5 * 0.22, kvfreq * 2
  amix = astr + asus1 + asus2
  ; soft overdrive: harmonics, compression and sustain (amp-driven)
  idrive = 30
  adrv = tanh(amix * idrive) * p5 * 1.2
  ; tame the fizz without killing the bite
  adrv tone adrv, 2800
  aleft = adrv * aenv * sqrt((1 - p6) / 2)
  aright = adrv * aenv * sqrt((1 + p6) / 2)
  outs aleft, aright
endin

; score: i 4 start duration freq amplitude pan
; Right-hand tapping voice: percussive attack, short decay, electric drive,
; no vibrato and no long sustain plateau -- the melodic counterpart to the
; ringing root on instr 2.
instr 4
  aenv linseg 0, 0.004, 1, 0.03, 0.5, max(0.01, p3 - 0.1), 0.18, 0.066, 0
  astr pluck p5, p4, p4, 0, 1
  abody oscili p5 * 0.3, p4
  amix = astr + abody
  adrv = tanh(amix * 24) * p5 * 1.1
  adrv tone adrv, 3200
  aleft = adrv * aenv * sqrt((1 - p6) / 2)
  aright = adrv * aenv * sqrt((1 + p6) / 2)
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
