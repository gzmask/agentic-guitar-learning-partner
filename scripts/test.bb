#!/usr/bin/env bb
(ns test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [control :as control]))

(def root (str (fs/parent (fs/parent (fs/real-path *file*)))))
(def executable (str (fs/path root "out" "guitar-score")))
(def orchestra (str (fs/path root "csound" "guitar.orc")))
(def test-dir (str (fs/path root ".state")))

(defn run-command! [command]
  (let [{:keys [exit out err]} @(process/process command {:dir root :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:command command :error err})))
    out))

(defn score [kind root-note quality octave & [bpm amplitude]]
  (run-command! [executable kind root-note quality (str octave) (str (or bpm 64))
                 "2.0" (str (or amplitude 0.15))]))

(defn score-frequencies [score]
  (let [[_ instrument _ _ & fields] (str/split (first (str/split-lines score)) #" ")]
    (mapv #(Double/parseDouble %) (drop (if (= instrument "1") 3 1) fields))))

(defn near?
  ([expected actual] (near? expected actual 0.02))
  ([expected actual tolerance]
   (< (Math/abs (- expected actual)) tolerance)))

(defn assert! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn instrument-events [generated-score instrument]
  (->> (str/split-lines generated-score)
       (filter #(str/starts-with? % (str "i " instrument " ")))
       (mapv (fn [line]
               (mapv #(Double/parseDouble %) (drop 2 (str/split line #" +")))))))

;; Independent form expectations: [sixteenth-note onset, length, root MIDI].
(def expected-harmony
  [[0 16 57] [16 16 50] [32 16 55] [48 8 48] [56 8 48]
   [64 16 53] [80 16 47] [96 16 52] [112 8 57] [120 8 57]
   [128 16 50] [144 16 55] [160 16 48] [176 8 52] [184 8 57]
   [192 16 50] [208 16 55] [224 8 50] [232 8 48] [240 8 47] [248 8 52]
   [256 16 57] [272 16 50] [288 16 55] [304 8 48] [312 8 48]
   [320 16 53] [336 16 47] [352 16 52] [368 8 57] [376 8 57]
   [384 16 50] [400 16 55] [416 16 52] [432 16 57]
   [448 16 50] [464 16 55] [480 16 48] [496 16 48]])

(defn midi-hz [midi]
  (* 440.0 (Math/pow 2.0 (/ (- midi 69) 12.0))))

(def expected-chord-names
  ["Am7" "Dm7" "G7" "Cmaj7" "C7" "Fmaj7" "Bm7b5" "E7" "Am7" "A7"
   "Dm7" "G7" "Cmaj7" "Em7" "A7" "Dm7" "G7" "Dm7" "Cmaj7" "Bm7b5" "E7"
   "Am7" "Dm7" "G7" "Cmaj7" "C7" "Fmaj7" "Bm7b5" "E7" "Am7" "A7"
   "Dm7" "G7" "Em7" "A7" "Dm7" "G7" "Cmaj7" "Cmaj7"])

(defn assert-chord-cues! [generated-score bpm]
  (let [cues (instrument-events generated-score 7)
        qualities {"m7" [3 7 10] "7" [4 7 10] "maj7" [4 7 11] "m7b5" [3 6 10]}]
    (assert! (= 39 (count cues)) "Expected a text cue for every accompaniment chord change")
    (doseq [[[at _ midi] name [start duration bar beat root-midi third fifth seventh]]
            (map vector expected-harmony expected-chord-names cues)]
      (assert! (near? (* at (/ 15.0 bpm)) start)
               "Chord output must use the same score time as the accompaniment")
      (assert! (near? 0.001 duration 1.0e-6) "Text cues should be short and silent")
      (assert! (== (inc (quot at 16)) bar) "Incorrect displayed bar")
      (assert! (== (inc (quot (mod at 16) 4)) beat) "Incorrect displayed beat")
      (assert! (== midi root-midi) "Displayed chord root must match the accompaniment")
      (assert! (= (get qualities (str/replace name #"^[A-G][#b]?" ""))
                  (mapv long [third fifth seventh]))
               "Displayed chord quality must match the accompaniment"))))

(defn assert-chord-display! []
  (assert! (nil? (control/parse-chord "SECTION 1:\n")) "Startup messages are not chord cues")
  (doseq [partial ["CHORD bar=04/32 beat=3 name="
                   "CHORD bar=06/32 beat=1 name=Bm7"]]
    (assert! (nil? (control/parse-chord partial))
             "A partial log write must not produce a chord, even if its prefix is a valid name"))
  (let [log (str "\u001b[mCHORD bar=04/32 beat=1 name=Cmaj7\n"
                 "\u001b[mCHORD bar=04/32 beat=3 name=C7\n\u001b[m")]
    (assert! (= {:bar 4 :beat 3 :chord "C7"} (control/parse-chord log))
             "Use the latest engine cue, stripping Csound terminal colors"))
  (assert! (= "Bar 06/32 | beat 1 | Bm7b5"
              (control/format-chord {:bar 6 :beat 1 :chord "Bm7b5"}))
           "Chord display should be readable text")
  (doseq [next-pid [nil 202]]
    (let [step (atom 0)
          output (atom [])
          first-chord {:bar 4 :beat 1 :chord "Cmaj7"}
          second-chord {:bar 4 :beat 3 :chord "C7"}
          snapshots [nil first-chord first-chord nil second-chord]]
      (control/follow-chords! 101
                             #(if (< @step (count snapshots)) 101 next-pid)
                             #(nth snapshots @step)
                             #(swap! output conj %)
                             #(swap! step inc))
      (assert! (= ["Bar 04/32 | beat 1 | Cmaj7"
                   "Bar 04/32 | beat 3 | C7"
                   "Chord display detached (playback stopped or replaced)."]
                  @output)
               "Watch must skip repeats, show half-bar changes, and detach on stop or replacement")))
  (with-redefs [control/owned-process (constantly nil)
                control/current-chord #(throw (ex-info "Stale chord must not be read" {}))]
    (assert! (str/includes? (with-out-str (control/status!)) "Stopped")
             "Stopped playback must never show stale chord output"))
  (let [dir (fs/create-temp-dir {:prefix "guitar-watch-test-"})]
    (try
      (with-redefs [control/state-dir (str dir)
                    control/-main (fn [& _]
                                    (with-open [file (java.io.RandomAccessFile.
                                                       (str (fs/path dir "controller.lock")) "rw")
                                                channel (.getChannel file)]
                                      (boolean (.tryLock channel))))]
        (assert! (control/run-controller! ["watch"])
                 "Watch must leave the controller lock available for stop/play"))
      (finally (fs/delete-tree dir)))))

(defn assert-progression! [generated-score bpm]
  (assert-chord-cues! generated-score bpm)
  (let [roots (instrument-events generated-score 2)
        taps (instrument-events generated-score 4)
        kicks (instrument-events generated-score 5)
        melody (instrument-events generated-score 6)
        tick (/ 15.0 bpm)
        bar-seconds (* tick 16)
        expected-taps (for [[start length _] expected-harmony
                            offset (range 2 length 2)]
                        [(+ start offset) (min 2.6 (- length offset)) (/ offset 2)])
        end-seconds (Double/parseDouble
                     (second (re-find #"(?m)^i 99 ([0-9.]+) 0\.01$" generated-score)))]
    (assert! (= 39 (count roots)) "Expected all 39 chord changes, including half-bar changes")
    (assert! (= 217 (count taps)) "Expected tapping between each sustained chord root")
    (assert! (= 64 (count kicks)) "Expected two syncopated kicks per bar")
    (assert! (= 81 (count melody)) "Expected the complete 32-bar melody with ties merged")
    (doseq [[[at length midi] [start duration frequency amplitude pan]]
            (map vector expected-harmony roots)]
      (assert! (near? (* at tick) start) "Root must anchor each chord change")
      (assert! (near? (* length tick 0.98) duration) "Root must sustain for 98% of its chord")
      (assert! (near? (midi-hz midi) frequency) "Incorrect full-form progression root")
      (assert! (near? (/ 0.15 2.2) amplitude 1.0e-6) "Incorrect sustained-root amplitude")
      (assert! (near? 0.0 pan) "Sustained root must be centered"))
    (doseq [[[at length subdivision] [start duration _ amplitude pan]]
            (map vector expected-taps taps)]
      (assert! (near? (* at tick) start) "Tapping notes must follow eighth-note subdivisions")
      (assert! (near? (* length tick) duration) "Taps must release before the next chord")
      (assert! (near? (/ 0.15 4.0) amplitude 1.0e-6) "Taps must sit below the melody")
      (assert! (near? (- 0.2 (* 0.06 subdivision)) pan) "Incorrect tapping pan"))
    (assert! (near? 261.626 (nth (first taps) 2)) "The first tap must be C4, the third of Am7")
    (doseq [[at midi] [[54 59] [62 58] [84 53] [114 60] [122 61]
                      [178 55] [186 61] [226 53] [234 52]]]
      (let [[_ _ frequency] (first (filter #(near? (* at tick) (first %) 0.001) taps))]
        (assert! (some? frequency) (str "Missing harmony-defining tap at tick " at))
        (assert! (near? (midi-hz midi) frequency) "Incorrect chord quality or half-bar change")))
    ;; Recognizable phrases, chromatic pickups, long ties and the distinct ending.
    (doseq [[at length midi] [[0 4 72] [4 4 71] [8 2 69] [10 6 67]
                             [48 15 64] [96 4 68] [124 4 61] [160 27 67]
                             [188 4 59] [232 23 64] [256 4 72] [380 4 61]
                             [416 27 76] [448 2 74] [480 32 72]]]
      (let [[_ duration frequency] (first (filter #(near? (* at tick) (first %) 0.001) melody))]
        (assert! (some? frequency) (str "Missing melody note at tick " at))
        (assert! (near? (* length tick) duration) "Incorrect melody rhythm or tie")
        (assert! (near? (midi-hz midi) frequency) "Incorrect melody pitch")))
    (doseq [[index [start duration amplitude]] (map-indexed vector kicks)]
      (let [bar (quot index 2)
            primary? (even? index)]
        (assert! (near? (+ (* bar bar-seconds) (* (if primary? 2 10) tick)) start)
                 "Kicks must stay on the and of beats 1 and 3")
        (assert! (near? 0.35 duration) "Incorrect kick duration")
        (assert! (near? (* 0.15 (if primary? 0.6 0.4)) amplitude 1.0e-6)
                 "The answering kick must be lighter than the first")))
    (doseq [[start duration _ amplitude pan] melody]
      (assert! (pos? duration) "Melody notes must have positive durations")
      (assert! (<= (+ start duration) (+ end-seconds 0.001)) "Melody must release by the ending")
      (assert! (near? (* 0.15 0.65) amplitude 1.0e-6) "Incorrect lead amplitude")
      (assert! (near? 0.3 pan) "Lead must be separated from the accompaniment"))
    (doseq [[[start duration] [next-start]] (partition 2 1 melody)]
      (assert! (<= (+ start duration) (+ next-start 0.001)) "The lead must be monophonic"))
    (assert! (near? (* 32 bar-seconds) end-seconds) "Full form must end after 32 bars")
    (assert! (every? #(< (first %) end-seconds) (concat roots taps kicks melody))
             "No note may start at or after the score's hard stop")))

(defn render! [score-path wav-path]
  (let [command ["csound" "-d" "-m0" "-W" "-o" wav-path orchestra score-path]
        {:keys [exit out err]} @(process/process command {:dir root :out :string :err :string})
        log (str/replace (str out err) #"\u001b\[[0-9;]*m" "")
        peaks (map #(Double/parseDouble %)
                   (rest (re-find #"overall amps:\s+([0-9.]+)\s+([0-9.]+)" log)))
        clipped (rest (re-find #"overall samples out of range:\s+(\d+)\s+(\d+)" log))]
    (assert! (zero? exit) (str "Csound render failed: " log))
    (assert! (= ["0" "0"] clipped) "Offline render must not clip either channel")
    (assert! (and (= 2 (count peaks)) (every? #(< 0 % 1) peaks))
             "Offline render must produce audible signal below full scale")
    log))

(defn -main []
  (run-command! ["carp" "-b" "src/guitar_score.carp"])
  (let [guitar (score "guitar" "a" "minor" 2)
        dorian (score "mode" "d" "dorian" 2)
        chord (score "chord" "a" "minor" 3)
        root-score (score "root" "a" "minor" 2)
        progression (score "progression" "a" "minor" 3)
        sample-notes (filter #(and (re-find #"^i (?:2|4|5|6|7) " %)
                                   (< (Double/parseDouble (nth (str/split % #" ") 2)) 6))
                             (str/split-lines progression))
        wav (str (fs/path test-dir "smoke.wav"))
        finite-score (str (fs/path test-dir "smoke.sco"))
        progression-wav (str (fs/path test-dir "progression-smoke.wav"))
        progression-sample (str (fs/path test-dir "progression-smoke.sco"))
        full-song-wav (str (fs/path test-dir "full-song-smoke.wav"))
        full-song-score (str (fs/path test-dir "full-song-smoke.sco"))]
    (assert! (= 5 (count (score-frequencies guitar))) "Expected five guitar strings")
    (assert! (every? true? (map near? [110.0 164.814 220.0 261.626 329.628]
                               (score-frequencies guitar))) "Incorrect A minor guitar voicing")
    (assert! (near? 123.471 (nth (score-frequencies dorian) 3)) "Dorian voicing lacks B2")
    (assert! (near? 261.626 (second (score-frequencies chord))) "Am chord lacks C4")
    (assert! (near? 110.0 (first (score-frequencies root-score))) "A2 root is out of tune")
    (assert! (str/includes? guitar "i 1 0 -1 64 2 0.15") "Wrong tempo or envelope")
    (assert-chord-display!)
    (assert-progression! progression 64)
    (doseq [bpm [20 240]]
      (assert-progression! (score "progression" "a" "minor" 3 bpm) bpm))
    (doseq [generated-score [guitar dorian chord root-score]]
      (assert! (str/includes? generated-score "i 99 600 0.01")
               "Missing Csound ten-minute hard stop"))
    (fs/create-dirs test-dir)
    ;; A two-second string triggered at 1.875s would otherwise keep running
    ;; past 2.5s. Prove the shutdown instrument terminates the actual engine.
    (spit finite-score (str/replace guitar "i 99 600 0.01" "i 99 2.5 0.01"))
    (render! finite-score wav)
    (let [rendered-seconds (/ (fs/size wav) 192000.0)]
      (assert! (<= 2.49 rendered-seconds 2.55)
               (str "Csound did not stop at the 2.5-second test cap: " rendered-seconds)))
    (spit progression-sample (str (str/join "\n" sample-notes)
                                  "\ni 99 6 0.01\nf 0 z\n"))
    (assert! (= {:bar 2 :beat 1 :chord "Dm7"}
                (control/parse-chord (render! progression-sample progression-wav)))
             "Csound must emit the current chord when its score event actually runs")
    (let [rendered-seconds (/ (fs/size progression-wav) 192000.0)]
      (assert! (<= 5.99 rendered-seconds 6.05)
               (str "Csound progression did not end at 6-second test cap: " rendered-seconds)))
    ;; Exercise the complete form, fastest notes, long final tie and maximum
    ;; accepted amplitude, not just an opening excerpt. No DAC is opened.
    (spit full-song-score (score "progression" "a" "minor" 3 240 0.5))
    (let [log (render! full-song-score full-song-wav)]
      (assert! (= expected-chord-names
                  (mapv :chord (keep #(control/parse-chord (str % "\n"))
                                    (str/split-lines log))))
               "Csound must emit all 39 chord names in order, exactly once")
      (assert! (= {:bar 32 :beat 1 :chord "Cmaj7"} (control/parse-chord log))
               "The final chord output must reach the tonic ending"))
    (let [rendered-seconds (/ (fs/size full-song-wav) 192000.0)]
      (assert! (<= 31.99 rendered-seconds 32.05)
               (str "Full song must stop after 32 bars at 240 BPM: " rendered-seconds)))
    (let [started (System/nanoTime)
          {:keys [exit]} @(process/process ["/usr/bin/perl" "-e"
                                           "alarm shift @ARGV; exec @ARGV or die $!"
                                           "1" "/bin/sleep" "5"]
                                          {:out :string :err :string})
          elapsed (/ (- (System/nanoTime) started) 1.0e9)]
      (assert! (and (not (zero? exit)) (<= 0.9 elapsed 3.0))
               (str "OS wall-clock alarm did not terminate its child in 1s: " elapsed)))
    (println "Carp arrangement, chord display and Csound hard-stop tests passed; no live audio was started.")))

(-main)
