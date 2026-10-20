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

; score: i 2 (root/strum) or i 6 (lead) start duration freq amplitude pan
; Roots and lead share the selected electric tone, but remain separate tracks.
instr 2, 6
  ; Fit attack/release inside short lead notes, even at 240 BPM.
  iattack = min(0.006, p3 * 0.1)
  idecay = min(0.05, p3 * 0.15)
  irelease = min(0.09, p3 * 0.25)
  iplateau = p3 - iattack - idecay - irelease
  aenv linseg 0, iattack, 1, idecay, 0.85, iplateau, 0.8, irelease, 0
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

; score: i 7 start 0.001 bar beat root-midi third fifth seventh
; Silent text cue, initialized on the same score clock as the chord change.
; Numeric p-fields avoid version-dependent score string handling.
; Live output goes to the owned Csound log; offline renders stay independent.
instr 7
  Sroots[] fillarray "C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"
  iroot = int(p6) % 12
  Squality = "?"
  if (p7 == 3 && p8 == 6 && p9 == 10) then
    Squality = "m7b5"
  elseif (p7 == 3 && p8 == 7 && p9 == 10) then
    Squality = "m7"
  elseif (p7 == 4 && p8 == 7 && p9 == 11) then
    Squality = "maj7"
  elseif (p7 == 4 && p8 == 7 && p9 == 10) then
    Squality = "7"
  endif
  Slabel strcat Sroots[iroot], Squality
  printf_i "CHORD bar=%02d/32 beat=%d name=%s\n", 1, p4, p5, Slabel
endin

; A restrained kick: low-frequency sweeps on the and of beats 1 and 3.
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
