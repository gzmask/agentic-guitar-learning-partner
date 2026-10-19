#!/usr/bin/env bb
(ns test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def root (str (fs/parent (fs/parent (fs/real-path *file*)))))
(def executable (str (fs/path root "out" "guitar-score")))
(def orchestra (str (fs/path root "csound" "guitar.orc")))
(def test-dir (str (fs/path root ".state")))

(defn run-command! [command]
  (let [{:keys [exit out err]} @(process/process command {:dir root :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:command command :error err})))
    out))

(defn score [kind root-note quality octave & [bpm]]
  (run-command! [executable kind root-note quality (str octave) (str (or bpm 64)) "2.0" "0.15"]))

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

(defn assert-progression! [generated-score bpm]
  (let [roots (instrument-events generated-score 2)
        taps (instrument-events generated-score 4)
        kicks (instrument-events generated-score 5)
        eighth (/ 30.0 bpm)
        bar-seconds (* eighth 8)
        root-frequencies [220.0 146.832 195.998 130.813 174.614 123.471 164.814 220.0]
        end-seconds (Double/parseDouble
                     (second (re-find #"(?m)^i 99 ([0-9.]+) 0\.01$" generated-score)))]
    (assert! (= 32 (count roots)) "Expected one sustained root per bar across four loops")
    (assert! (= 224 (count taps)) "Expected seven tapping notes per bar across four loops")
    (assert! (= 64 (count kicks)) "Expected two syncopated kicks per bar across four loops")
    (doseq [[index [start duration frequency amplitude pan]] (map-indexed vector roots)]
      (assert! (near? (* index bar-seconds) start) "Root must anchor the downbeat")
      (assert! (near? (* bar-seconds 0.98) duration) "Root must sustain for 98% of the bar")
      (assert! (near? (nth root-frequencies (mod index 8)) frequency) "Incorrect progression root")
      (assert! (near? (/ 0.15 2.2) amplitude 1.0e-6) "Incorrect sustained-root amplitude")
      (assert! (near? 0.0 pan) "Sustained root must be centered"))
    (doseq [[index [start duration _ amplitude pan]] (map-indexed vector taps)]
      (let [bar (quot index 7)
            subdivision (inc (mod index 7))]
        (assert! (near? (+ (* bar bar-seconds) (* subdivision eighth)) start)
                 "Tapping notes must occupy the remaining seven eighth-note subdivisions")
        (assert! (near? (* eighth 1.3) duration) "Incorrect tapping duration")
        (assert! (near? (/ 0.15 2.5) amplitude 1.0e-6) "Incorrect tapping amplitude")
        (assert! (near? (- 0.2 (* 0.06 subdivision)) pan) "Incorrect tapping pan")))
    (assert! (near? 261.626 (nth (first taps) 2)) "The first tap must be C4, the third of Am7")
    (doseq [[index [start duration amplitude]] (map-indexed vector kicks)]
      (let [bar (quot index 2)
            primary? (even? index)
            subdivision (if primary? 1 5)]
        (assert! (near? (+ (* bar bar-seconds) (* subdivision eighth)) start)
                 "Kicks must land on the and of beats 1 and 3, not the root downbeat")
        (assert! (near? 0.35 duration) "Incorrect kick duration")
        (assert! (near? (* 0.15 (if primary? 0.6 0.4)) amplitude 1.0e-6)
                 "The answering kick must be lighter than the first")))
    (assert! (near? (* 32 bar-seconds) end-seconds) "Progression must end after four loops")
    (assert! (every? #(< (first %) end-seconds) (concat roots taps kicks))
             "No note may start at or after the score's hard stop")))

(defn -main []
  (run-command! ["carp" "-b" "src/guitar_score.carp"])
  (let [guitar (score "guitar" "a" "minor" 2)
        dorian (score "mode" "d" "dorian" 2)
        chord (score "chord" "a" "minor" 3)
        root-score (score "root" "a" "minor" 2)
        progression (score "progression" "a" "minor" 3)
        arpeggio-notes (filter #(re-find #"^i (?:2|4) " %)
                               (str/split-lines progression))
        kick-notes (filter #(str/starts-with? % "i 5 ")
                           (str/split-lines progression))
        wav (str (fs/path test-dir "smoke.wav"))
        finite-score (str (fs/path test-dir "smoke.sco"))
        progression-wav (str (fs/path test-dir "progression-smoke.wav"))
        progression-sample (str (fs/path test-dir "progression-smoke.sco"))]
    (assert! (= 5 (count (score-frequencies guitar))) "Expected five guitar strings")
    (assert! (every? true? (map near? [110.0 164.814 220.0 261.626 329.628]
                               (score-frequencies guitar))) "Incorrect A minor guitar voicing")
    (assert! (near? 123.471 (nth (score-frequencies dorian) 3)) "Dorian voicing lacks B2")
    (assert! (near? 261.626 (second (score-frequencies chord))) "Am chord lacks C4")
    (assert! (near? 110.0 (first (score-frequencies root-score))) "A2 root is out of tune")
    (assert! (str/includes? guitar "i 1 0 -1 64 2 0.15") "Wrong tempo or envelope")
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
    (run-command! ["csound" "-d" "-m0" "-W" "-o" wav orchestra finite-score])
    (let [rendered-seconds (/ (fs/size wav) 192000.0)]
      (assert! (<= 2.49 rendered-seconds 2.55)
               (str "Csound did not stop at the 2.5-second test cap: " rendered-seconds)))
    (spit progression-sample (str (str/join "\n" (concat (take 16 arpeggio-notes)
                                                        (take 4 kick-notes)))
                                  "\ni 99 6 0.01\nf 0 z\n"))
    (run-command! ["csound" "-d" "-m0" "-W" "-o" progression-wav orchestra progression-sample])
    (let [rendered-seconds (/ (fs/size progression-wav) 192000.0)]
      (assert! (<= 5.99 rendered-seconds 6.05)
               (str "Csound progression did not end at 6-second test cap: " rendered-seconds)))
    (let [started (System/nanoTime)
          {:keys [exit]} @(process/process ["/usr/bin/perl" "-e"
                                           "alarm shift @ARGV; exec @ARGV or die $!"
                                           "1" "/bin/sleep" "5"]
                                          {:out :string :err :string})
          elapsed (/ (- (System/nanoTime) started) 1.0e9)]
      (assert! (and (not (zero? exit)) (<= 0.9 elapsed 3.0))
               (str "OS wall-clock alarm did not terminate its child in 1s: " elapsed)))
    (println "Carp arrangement and Csound hard-stop tests passed; no live audio was started.")))

(-main)
