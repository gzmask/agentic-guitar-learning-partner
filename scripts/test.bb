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

(defn score [kind root-note quality octave]
  (run-command! [executable kind root-note quality (str octave) "64" "2.0" "0.15"]))

(defn score-frequencies [score]
  (let [[_ instrument _ _ & fields] (str/split (first (str/split-lines score)) #" ")]
    (mapv #(Double/parseDouble %) (drop (if (= instrument "1") 3 1) fields))))

(defn near? [expected actual]
  (< (Math/abs (- expected actual)) 0.02))

(defn assert! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn -main []
  (run-command! ["carp" "-b" "src/guitar_score.carp"])
  (let [guitar (score "guitar" "a" "minor" 2)
        dorian (score "mode" "d" "dorian" 2)
        chord (score "chord" "a" "minor" 3)
        root-score (score "root" "a" "minor" 2)
        progression (score "progression" "a" "minor" 3)
        arpeggio-notes (filter #(str/starts-with? % "i 2 ")
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
    (assert! (= 256 (count arpeggio-notes)) "Expected 4 loops × 8 chords × 8 notes")
    (assert! (= 32 (count kick-notes)) "Expected one kick per bar across four loops")
    (assert! (near? 0.0 (Double/parseDouble (nth (str/split (first kick-notes) #" ") 2)))
             "First kick must coincide with the first bar")
    (assert! (near? 3.75 (Double/parseDouble (nth (str/split (second kick-notes) #" ") 2)))
             "Kicks must land every four beats at 64 BPM")
    (assert! (near? 116.25 (Double/parseDouble (nth (str/split (last kick-notes) #" ") 2)))
             "Last kick must land on the final bar, not after it")
    (assert! (str/includes? progression "i 99 120 0.01")
             "Progression must end after 120 seconds at 64 BPM")
    (assert! (near? 220.0 (Double/parseDouble (nth (str/split (first arpeggio-notes) #" ") 4)))
             "Arpeggio must begin on Am7 at A3")
    (assert! (near? 261.626 (Double/parseDouble (nth (str/split (second arpeggio-notes) #" ") 4)))
             "The second note should be the third (C4) of Am7")
    (assert! (near? 0.46875 (Double/parseDouble (nth (str/split (second arpeggio-notes) #" ") 2)))
             "Notes should fall on eighth-note subdivisions at 64 BPM")
    (assert! (near? 123.471 (Double/parseDouble (nth (str/split (nth arpeggio-notes 40) #" ") 4)))
             "The sixth chord must be Bm7b5 at B2")
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
                                                        (take 2 kick-notes)))
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
    (println "Carp and Csound hard-stop tests passed; no live audio is running.")))

(-main)
