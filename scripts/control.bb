#!/usr/bin/env bb
(ns control
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def root (str (fs/parent (fs/parent (fs/real-path *file*)))))
(def state-dir (str (fs/path root ".state")))
(def pid-file (str (fs/path state-dir "csound.edn")))
(def score-file (str (fs/path state-dir "practice.sco")))
(def log-file (str (fs/path state-dir "csound.log")))
(def orchestra (str (fs/path root "csound" "guitar.orc")))
(def compiler-output (str (fs/path root "out" "guitar-score")))
(def max-runtime-seconds 600)
(def accepted-notes #{"c" "c#" "db" "d" "d#" "eb" "e" "f" "f#" "gb" "g" "g#" "ab" "a" "a#" "bb" "b"})
(def accepted-chords #{"major" "minor" "power" "minor7" "major7" "dominant7" "sus2" "sus4"})
(def accepted-modes #{"ionian" "major" "dorian" "phrygian" "lydian" "mixolydian" "aeolian" "minor" "locrian"})

(defn fail! [message]
  (binding [*out* *err*] (println message))
  (System/exit 1))

(defn executable! [name]
  (let [{:keys [exit out]} @(process/process ["/usr/bin/which" name] {:out :string :err :string})]
    (when-not (zero? exit) (fail! (str "Install " name " first.")))
    (str/trim out)))

(defn build! []
  (executable! "carp")
  (let [{:keys [exit err]} @(process/process ["carp" "-b" "src/guitar_score.carp"]
                                              {:dir root :out :string :err :string})]
    (when-not (zero? exit) (fail! (str "Carp build failed: " err)))))

(defn parse-number [s label lower upper]
  (let [value (try (Double/parseDouble s) (catch Exception _ Double/NaN))]
    (when-not (<= lower value upper)
      (fail! (str label " must be between " lower " and " upper ": " s)))
    s))

(defn practice-args [args]
  (let [[kind raw-root raw-quality raw-octave raw-bpm raw-envelope raw-amp & extra] args
        kind (or kind "guitar")
        root (str/lower-case (or raw-root "a"))
        quality (str/lower-case (or raw-quality (if (= kind "mode") "aeolian" "minor")))
        octave (or raw-octave (if (#{"chord" "progression"} kind) "3" "2"))
        bpm (or raw-bpm "64")
        envelope (or raw-envelope "2.0")
        amp (or raw-amp "0.18")]
    (when (or (seq extra) (not (#{"guitar" "chord" "mode" "root" "progression"} kind)))
      (fail! "Usage: bb play [guitar|chord|mode|root|progression] [root] [quality] [octave] [bpm] [envelope-seconds] [amp]"))
    (when-not (accepted-notes root) (fail! (str "Unknown root note: " root)))
    (when (and (#{"guitar" "chord"} kind) (not (accepted-chords quality)))
      (fail! (str "Unknown chord quality: " quality)))
    (when (and (= kind "mode") (not (accepted-modes quality)))
      (fail! (str "Unknown mode: " quality)))
    (when (and (= kind "progression") (or (not= root "a") (not= quality "minor")))
      (fail! "This progression is the fixed Am7–Dm7–G7–Cmaj7–Fmaj7–Bm7b5–E7–Am7 form."))
    (parse-number octave "Octave" 0 7)
    (when-not (re-matches #"[0-7]" octave) (fail! "Octave must be an integer 0–7"))
    (parse-number bpm "BPM" 20 240)
    (parse-number envelope "Envelope" 0.02 30)
    (parse-number amp "Amplitude" 0.001 0.5)
    [kind root quality octave bpm envelope amp]))

(defn process-command [pid]
  (let [{:keys [out]} @(process/process ["/bin/ps" "-p" (str pid) "-o" "command="]
                                              {:out :string :err :string})]
    (str/trim out)))

(defn owned-process []
  (when (fs/exists? pid-file)
    (try
      (let [{:keys [pid]} (edn/read-string (slurp pid-file))
            cmd (process-command pid)]
        (when (and (re-find #"(^|/)csound " cmd)
                   (str/includes? cmd orchestra)
                   (str/includes? cmd score-file))
          pid))
      (catch Exception _ nil))))

(defn score-end-seconds [score]
  (some-> (re-find #"(?m)^i 99 ([0-9]+(?:\.[0-9]+)?) 0\.01$" score)
          second
          Double/parseDouble))

(defn status! []
  (if-let [pid (owned-process)]
    (let [{:keys [started-at score-end]} (edn/read-string (slurp pid-file))
          elapsed (/ (- (System/currentTimeMillis) started-at) 1000.0)
          remaining (max 0 (long (Math/ceil (- max-runtime-seconds elapsed))))
          score-remaining (max 0 (long (Math/ceil (- score-end elapsed))))]
      (println (str "Playing (Csound PID " pid "; score ends in ~" score-remaining
                    "s; wall-clock safety cap in " remaining "s): "
                    (str/trim (first (str/split-lines (slurp score-file)))))))
    (println "Stopped (no Csound process owned by this project).")))

(defn stop! []
  (if-let [pid (owned-process)]
    (do
      (try
        (let [handle (.get (java.lang.ProcessHandle/of pid))]
          (.destroy handle)
          (try
            (.get (.onExit handle) 2 TimeUnit/SECONDS)
            (catch java.util.concurrent.TimeoutException _
              (.destroyForcibly handle)
              (.get (.onExit handle) 2 TimeUnit/SECONDS))))
        (catch java.util.NoSuchElementException _))
      (fs/delete-if-exists pid-file)
      (println (str "Stopped Csound PID " pid ".")))
    (do
      (fs/delete-if-exists pid-file)
      (println "Already stopped; no Csound process was signalled."))))

(defn play! [args]
  (let [params (practice-args args)
        csound (executable! "csound")]
    (build!)
    (let [{:keys [exit out err]} @(process/process (into [compiler-output] params)
                                                     {:dir root :out :string :err :string})]
      (let [score-end (score-end-seconds out)]
        (when-not (and (zero? exit) score-end
                       (<= 0.01 score-end max-runtime-seconds)
                       (str/includes? out "f 0 z"))
          (fail! (str "Score generation failed or exceeded ten minutes: " err))))
      (stop!)
      (fs/create-dirs state-dir)
      (spit score-file out)
      ;; alarm survives exec: Csound becomes this PID and SIGALRM kills it after
      ;; 600 wall-clock seconds even if Csound's own score-time exit hangs.
      (let [worker (process/process ["/usr/bin/perl" "-e"
                                     "alarm shift @ARGV; exec @ARGV or die $!"
                                     (str max-runtime-seconds)
                                     csound "-d" "-m0" "-odac" orchestra score-file]
                                    {:dir root :out (io/file log-file) :err :out})
            pid (.pid (:proc worker))]
        (spit pid-file (pr-str {:pid pid
                                :started-at (System/currentTimeMillis)
                                :score-end (score-end-seconds out)
                                :score (str/trim (first (str/split-lines out)))}))
        (Thread/sleep 750)
        (if (owned-process)
          (println (str "Playing via Csound PID " pid ": " (str/trim (first (str/split-lines out)))))
          (do (fs/delete-if-exists pid-file)
              (fail! (str "Csound exited early. See " log-file "\n" (slurp log-file)))))))))

(defn -main [& args]
  (fs/create-dirs state-dir)
  (case (first args)
    "build" (do (build!) (println "Built Carp score generator."))
    "play" (play! (rest args))
    "stop" (stop!)
    "status" (status!)
    (fail! "Usage: bb build | bb play [kind root quality octave bpm envelope amp] | bb stop | bb status")))

(fs/create-dirs state-dir)
(with-open [file (java.io.RandomAccessFile. (str (fs/path state-dir "controller.lock")) "rw")
            channel (.getChannel file)]
  (.lock channel)
  ;; Closing the channel releases its lock (FileLock.close is not exposed by bb).
  (apply -main *command-line-args*))
