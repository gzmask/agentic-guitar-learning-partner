#!/usr/bin/env clojure
;; JVM Clojure + Overtone guitar-controlled note looper.
;; Tartini.kr detects monophonic notes; SuperCollider monitors and synthesizes.
;; C5 D5 C5 D5 starts recording. D5 C5 D5 C5 starts playback; repeat to exit.
;; Run with the Overtone dependency shown in README.md. Low volume/headphones first.

(ns guitar-gesture-looper
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [overtone.osc :as osc]
    [overtone.sc.envelope :as envelope]
    [overtone.sc.machinery.synthdef :as synthdef]
    [overtone.sc.machinery.ugen.fn-gen :as fn-gen]
    [overtone.sc.synth :refer [defsynth]]
    [overtone.sc.ugens :as u])
  (:import
    (java.net
      DatagramSocket)
    (java.util
      Locale)
    (java.util.concurrent
      LinkedBlockingQueue
      TimeUnit)
    (java.util.concurrent.atomic
      AtomicInteger)))


(def sample-rate 44100.0)
(def block-seconds (/ 32.0 sample-rate))
(def maximum-notes 16384)
(def mix-bus 16)
(def source-group-id 1000)
(def mixer-node-id 1001)
(def detector-node-id 1002)
(def first-voice-node-id 2000)
(def start-command [72 74 72 74])
(def stop-command [74 72 74 72])


(def tartini-spec
  ;; Tartini is supplied by sc3-plugins rather than Overtone's UGen metadata.
  {:name "Tartini" :num-outs 2})


(defn tartini:kr
  [in]
  (fn-gen/mk-scugen tartini-spec :kr 0 [in 0.93 2048 0 1024 0.5]))


^{:clj-kondo/ignore [:unresolved-symbol]}
(defsynth looper-detector [monitor-gain 0.5 output-bus 16]
  (let [[left right] (u/in:ar (u/num-output-buses:ir) 2)
        mono (* (+ left right) 0.5)
        [hz confidence] (tartini:kr mono)
        db (u/ampdb (max (u/amplitude:kr mono 0.01 0.01) 1.0e-9))
        ;; This frees detection at 600 server seconds. The process has its own alarm.
        elapsed (u/line:kr 0 600 600 2)]
    (u/send-reply:kr (u/impulse:kr 100) "/guitar/looper-pitch"
                     [elapsed hz db confidence])
    (u/out:ar output-bus [(* left monitor-gain) (* right monitor-gain)])))


^{:clj-kondo/ignore [:unresolved-symbol]}
(defsynth looper-voice [freq 440 duration 0.3 amp 0.081818 pan 0 output-bus 16]
  (let [amplitude-envelope
        (envelope/envelope [0 1 0.85 0.8 0]
                           [0.006 0.05 (- duration 0.146) 0.09])
        amplitude (u/env-gen:kr amplitude-envelope 1 1 0 1 2)
        string (u/pluck:ar (* amp (u/white-noise:ar)) 1
                           (/ 1 70) (/ 1 freq) duration 0.5)
        vibrato-depth (u/env-gen:kr (envelope/envelope [0 0 1] [0.35 0.25])
                                    1 1 0 1 0)
        vibrato (* (u/sin-osc:kr 5.5) vibrato-depth 0.015)
        vibrato-frequency (* freq (+ 1 vibrato))
        sustain-1 (* amp 0.5 (u/sin-osc:ar vibrato-frequency))
        sustain-2 (* amp 0.22 (u/sin-osc:ar (* vibrato-frequency 2)))
        driven (* (u/tanh (* (+ string sustain-1 sustain-2) 30)) amp 1.2)
        filtered (u/lpf:ar driven 2800)]
    (u/out:ar output-bus (u/pan2:ar (* filtered amplitude) pan 1))))


^{:clj-kondo/ignore [:unresolved-symbol]}
(defsynth looper-mixer [input-bus 16 master-gain 1]
  (let [gain (u/lag:kr master-gain 0.03)]
    (u/out:ar 0 (* (u/in:ar input-bus 2) gain))))


(def usage
  (str "Usage: clojure -Sdeps '{:deps {overtone/overtone "
       "{:mvn/version \"0.16.3331\"}}}' -M poc/guitar_gesture_looper.clj\n"
       "       [--input DEVICE] [--output DEVICE] [--gate=-44] [--check | --help]\n"
       "Defaults: Fx100 input/output, raw stereo monitor -6 dB, gate -44/-50 dBFS.\n"
       "Device values are SuperCollider/CoreAudio names, not Csound adcN/dacN IDs."))


(defn parse-options
  [args]
  (loop [args (seq args)
         options {:input "Fx100" :output "Fx100" :gate -44.0
                  :check? false :help? false}]
    (if-let [arg (first args)]
      (cond
        (#{"--check" "--help"} arg)
        (recur (next args)
               (assoc options ({"--check" :check? "--help" :help?} arg) true))

        (#{"--input" "--output"} arg)
        (let [value (second args)]
          (when (or (nil? value) (str/starts-with? value "--"))
            (throw (ex-info usage {})))
          (recur (nnext args)
                 (assoc options ({"--input" :input "--output" :output} arg) value)))

        (str/starts-with? arg "--gate=")
        (let [gate (parse-double (subs arg 7))]
          (when-not (and gate (<= -90.0 gate -6.0))
            (throw (ex-info "--gate must be a number between -90 and -6 dBFS." {})))
          (recur (next args) (assoc options :gate gate)))

        :else
        (throw (ex-info usage {})))
      options)))


(def tracker-initial-state
  {:gate? false
   :candidate nil
   :active nil
   :candidate-since 0.0
   :silent-since nil
   :last-time -1.0
   :event nil
   :released-at nil})


(defn pitch-label
  [hz]
  (when (and (Double/isFinite (double hz)) (<= 70.0 hz 1400.0))
    (let [pitch (+ 69.0 (* 12.0 (/ (Math/log (/ hz 440.0)) (Math/log 2.0))))]
      {:midi (Math/round pitch)
       :cents (* 100.0 (- pitch (Math/round pitch)))})))


(defn track-note
  "Pure hysteresis/stability filter driven by SuperCollider time, not JVM time."
  [{:keys [gate? candidate active candidate-since silent-since last-time] :as state}
   {:keys [time hz db confidence] :as sample}
   gate-open]
  (if (<= time last-time)
    (assoc state :event nil :released-at nil)
    (let [gate? (> db (if gate? (- gate-open 6.0) gate-open))
          label (when (and gate? (> confidence 0.9)) (pitch-label hz))
          state (assoc state :last-time time :gate? gate?
                       :event nil :released-at nil)]
      (if-let [midi (:midi label)]
        (let [candidate-since (if (or (not= midi candidate)
                                      (> (- time last-time) 0.05))
                                time
                                candidate-since)
              emit? (and (>= (- time candidate-since) 0.055)
                         (not= midi active))]
          (assoc state
                 :candidate midi
                 :candidate-since candidate-since
                 :silent-since nil
                 :active (if emit? midi active)
                 :event (when emit? (merge sample label))))
        (let [silent-since (or silent-since time)
              release? (and active (>= (- time silent-since) 0.12))]
          (assoc state
                 :candidate nil
                 :silent-since silent-since
                 :active (if release? nil active)
                 :released-at (when release? silent-since)))))))


(def control-initial-state
  {:mode :idle
   :tracker tracker-initial-state
   :history []
   :record-start nil
   :notes []
   :active-note nil
   :loop-length nil
   :playback-wall-ms nil
   :next-cycle 0})


(defn close-active-note
  [state end-time]
  (if-let [index (:active-note state)]
    (-> state
        (assoc-in [:notes index :length]
                  (max block-seconds
                       (- end-time (:record-start state)
                          (get-in state [:notes index :start]))))
        (assoc :active-note nil))
    state))


(defn record-confirmed-note
  [state {:keys [time midi]}]
  (when (>= (count (:notes state)) maximum-notes)
    (throw (ex-info "Recording capacity exceeded." {})))
  (let [state (close-active-note state time)
        index (count (:notes state))]
    (-> state
        (update :notes conj {:start (max 0.0 (- time (:record-start state)))
                             :length block-seconds
                             :midi midi})
        (assoc :active-note index))))


(defn append-history
  [history event]
  (->> (conj history (select-keys event [:time :midi]))
       (take-last 4)
       vec))


(defn command?
  [history command]
  (and (= 4 (count history))
       (<= (- (:time (peek history)) (:time (first history))) 4.0)
       (= command (mapv :midi history))))


(defn process-confirmed-note
  "Apply one confirmed note and return [state effects]. Effects are for I/O only."
  [state event]
  (let [state (if (= :recording (:mode state))
                (record-confirmed-note state event)
                state)
        history (append-history (:history state) event)
        state (assoc state :history history)
        note-effect {:type :note :event event :mode (:mode state)}]
    (cond
      (and (= :idle (:mode state)) (command? history start-command))
      [(assoc state
              :mode :recording
              :history []
              :record-start (:time event)
              :notes []
              :active-note nil)
       [note-effect {:type :recording :time (:time event)}]]

      (and (= :recording (:mode state)) (command? history stop-command))
      (let [kept-count (- (count (:notes state)) 4)
            notes (subvec (:notes state) 0 kept-count)
            command-start (:time (first history))
            loop-length (max 0.1 (- command-start (:record-start state)))]
        [(assoc state
                :mode :playback
                :history []
                :notes notes
                :active-note nil
                :loop-length loop-length
                :next-cycle 0)
         [note-effect {:type :playback :time (:time event)
                       :loop-length loop-length :notes (count notes)}]])

      (and (= :playback (:mode state)) (command? history stop-command))
      [(assoc state :mode :exiting :history [])
       [note-effect {:type :exit :time (:time event)}]]

      :else
      [state [note-effect]])))


(defn process-sample
  [state sample gate-open]
  (let [tracker (track-note (:tracker state) sample gate-open)
        state (assoc state :tracker tracker)
        state (if (and (= :recording (:mode state)) (:released-at tracker))
                (close-active-note state (:released-at tracker))
                state)]
    (if-let [event (:event tracker)]
      (process-confirmed-note state event)
      [state []])))


(defn scsynth-path
  []
  (or (System/getenv "SCSYNTH")
      (some #(when (.canExecute (io/file %)) %)
            ["/Applications/SuperCollider.app/Contents/Resources/scsynth"
             "/opt/homebrew/bin/scsynth"
             "/usr/local/bin/scsynth"
             "/usr/bin/scsynth"])
      "scsynth"))


(defn unused-port
  []
  (with-open [socket (DatagramSocket. 0)]
    (.getLocalPort socket)))


(defn stop-process!
  [^Process child]
  (when (.isAlive child)
    (.destroy child)
    (when-not (.waitFor child 2 TimeUnit/SECONDS)
      (.destroyForcibly child)
      (when-not (.waitFor child 2 TimeUnit/SECONDS)
        (throw (ex-info "The owned scsynth process did not exit."
                        {:pid (.pid child)}))))))


(defn check-child!
  [^Process child]
  (when-not (.isAlive child)
    (throw (ex-info "scsynth exited; check its device/plugin error above."
                    {:exit (.exitValue child)}))))


(defn await-message!
  [queue ^Process child path]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (check-child! child)
      (let [message (.poll ^LinkedBlockingQueue queue 100 TimeUnit/MILLISECONDS)]
        (cond
          (= "/fail" (:path message))
          (throw (ex-info (str "scsynth: " (:args message)) {}))

          (= path (:path message))
          message

          (> (System/nanoTime) deadline)
          (throw (ex-info (str "Timed out waiting for " path) {}))

          :else
          (recur))))))


(defn install-synth!
  [client queue child synth]
  (osc/osc-send client "/d_recv" (synthdef/synthdef-bytes (:sdef synth)))
  (await-message! queue child "/done"))


(defn start-engine!
  [{:keys [input output]}]
  (let [port (unused-port)
        started-at (System/nanoTime)
        command ["/usr/bin/perl" "-e" "alarm 600; exec @ARGV or die $!"
                 (scsynth-path) "-u" (str port) "-B" "127.0.0.1"
                 "-H" input output "-i" "2" "-o" "2" "-S" "44100"
                 "-z" "32" "-D" "0" "-R" "0" "-s" "1"]
        child (.start (doto (ProcessBuilder. ^java.util.List command)
                        (.inheritIO)))
        client (atom nil)
        stopped? (atom false)
        cleanup! (fn []
                   (when (compare-and-set! stopped? false true)
                     (try
                       (stop-process! child)
                       (finally
                         (when-let [peer @client]
                           (osc/osc-close peer true))))))
        hook (Thread. ^Runnable cleanup! "guitar-looper-cleanup")
        queue (LinkedBlockingQueue. 2048)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [peer (osc/osc-client "127.0.0.1" port false)]
        (reset! client peer)
        (osc/osc-listen peer #(.offer queue %) ::incoming)
        (loop [attempts 100]
          (check-child! child)
          (when (zero? attempts)
            (throw (ex-info "scsynth did not become ready within 10 seconds." {})))
          (osc/osc-send peer "/status")
          (let [message (.poll queue 100 TimeUnit/MILLISECONDS)]
            (when-not (= "/status.reply" (:path message))
              (recur (dec attempts)))))
        (osc/osc-send peer "/notify" (int 1))
        (await-message! queue child "/done")
        (doseq [synth [looper-detector looper-voice looper-mixer]]
          (install-synth! peer queue child synth))
        (osc/osc-send peer "/g_new" (int source-group-id) (int 0) (int 0))
        (osc/osc-send peer "/s_new" (:name (:sdef looper-mixer))
                      (int mixer-node-id) (int 1) (int 0)
                      "input-bus" (float mix-bus) "master-gain" (float 1))
        (osc/osc-send peer "/s_new" (:name (:sdef looper-detector))
                      (int detector-node-id) (int 1) (int source-group-id)
                      "monitor-gain" (float 0.5) "output-bus" (float mix-bus))
        (osc/osc-send peer "/sync" (int 1))
        (await-message! queue child "/synced")
        {:child child
         :client peer
         :queue queue
         :cleanup cleanup!
         :hook hook
         ;; Leave one second for graceful cleanup before the independent alarm.
         :deadline (+ started-at (* 599 1000000000))})
      (catch Exception error
        (cleanup!)
        (try
          (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _))
        (throw error)))))


(defn midi-frequency
  [midi]
  (* 440.0 (Math/pow 2.0 (/ (- midi 69.0) 12.0))))


(defn root-format
  [pattern & arguments]
  (String/format Locale/ROOT pattern (to-array arguments)))


(defn schedule-voice!
  [client node-ids wall-time-ms {:keys [midi length]}]
  (let [node-id (.getAndIncrement ^AtomicInteger node-ids)
        message (osc/osc-msg
                  "/s_new" (:name (:sdef looper-voice))
                  (int node-id) (int 1) (int source-group-id)
                  "freq" (float (midi-frequency midi))
                  "duration" (float (+ length 0.15))
                  "amp" (float (/ 0.18 2.2))
                  "pan" (float 0)
                  "output-bus" (float mix-bus))]
    (osc/osc-send-bundle client (osc/osc-bundle wall-time-ms message))))


(defn schedule-due-cycles!
  [client node-ids state]
  (if (and (= :playback (:mode state)) (:playback-wall-ms state))
    (let [horizon (+ (System/currentTimeMillis) 1000)]
      (loop [cycle (:next-cycle state)]
        (let [cycle-time (+ (:playback-wall-ms state)
                            (* 1000.0 cycle (:loop-length state)))]
          (if (<= cycle-time horizon)
            (do
              (doseq [{:keys [start] :as note} (:notes state)]
                (schedule-voice! client node-ids
                                 (long (+ cycle-time (* 1000.0 start)))
                                 note))
              (println (root-format "LOOP cycle=%d notes=%d"
                                    (inc cycle) (count (:notes state))))
              (flush)
              (recur (inc cycle)))
            (assoc state :next-cycle cycle)))))
    state))


(defn print-effect!
  [{:keys [type time event mode loop-length notes]}]
  (case type
    :note
    (println (root-format
               "NOTE t=%.3fs MIDI %d confidence=%.3f state=%s"
               (:time event) (:midi event) (:confidence event) (name mode)))

    :recording
    (println (root-format
               "STATE RECORDING t=%.3fs (D5 C5 D5 C5 to finish)" time))

    :playback
    (println (root-format
               "STATE PLAYBACK t=%.3fs duration=%.3fs notes=%d (repeat stop command to exit)"
               time loop-length notes))

    :exit
    (println (root-format "STATE EXIT t=%.3fs (guitar command)" time))

    nil)
  (flush))


(defn sample-from-message
  [{:keys [args]}]
  (let [[_ _ time hz db confidence] args]
    {:time (double time)
     :hz (double hz)
     :db (double db)
     :confidence (double confidence)}))


(defn run-looper!
  [{:keys [gate]}
   {:keys [client queue child deadline]}]
  (let [node-ids (AtomicInteger. first-voice-node-id)]
    (loop [state control-initial-state]
      (check-child! child)
      (if (>= (System/nanoTime) deadline)
        :timeout
        (let [message (.poll ^LinkedBlockingQueue queue 100 TimeUnit/MILLISECONDS)]
          (when (= "/fail" (:path message))
            (throw (ex-info (str "scsynth: " (:args message)) {})))
          (let [[state effects]
                (if (= "/guitar/looper-pitch" (:path message))
                  (process-sample state (sample-from-message message) gate)
                  [state []])
                _ (doseq [effect effects] (print-effect! effect))
                state (if (and (= :playback (:mode state))
                               (nil? (:playback-wall-ms state)))
                        (assoc state :playback-wall-ms (+ (System/currentTimeMillis) 100))
                        state)
                state (schedule-due-cycles! client node-ids state)]
            (if (= :exiting (:mode state))
              (do
                (osc/osc-send client "/n_set" (int mixer-node-id)
                              "master-gain" (float 0))
                (Thread/sleep 40)
                :gesture)
              (recur state))))))))


(defn check-pure-logic!
  []
  (let [pitch-samples [{:time 0.0 :hz 440.0 :db -20.0 :confidence 1.0}
                       {:time 0.03 :hz 440.0 :db -20.0 :confidence 1.0}
                       {:time 0.06 :hz 440.0 :db -20.0 :confidence 1.0}
                       {:time 0.10 :hz 0.0 :db -80.0 :confidence 0.0}
                       {:time 0.23 :hz 0.0 :db -80.0 :confidence 0.0}]
        tracker-states (rest (reductions #(track-note %1 %2 -44.0)
                                         tracker-initial-state pitch-samples))
        confirmed (:event (nth tracker-states 2))
        released (:released-at (last tracker-states))
        events (mapv (fn [index midi]
                       {:time (* 0.5 (inc index)) :midi midi :confidence 1.0})
                     (range 4) start-command)
        [recording _] (reduce (fn [[state _] event]
                                (process-confirmed-note state event))
                              [control-initial-state []]
                              events)
        music {:time 2.5 :midi 69 :confidence 1.0}
        [recording _] (process-confirmed-note recording music)
        stop-events (mapv (fn [index midi]
                            {:time (+ 3.0 (* 0.5 index)) :midi midi :confidence 1.0})
                          (range 4) stop-command)
        [playback _] (reduce (fn [[state _] event]
                               (process-confirmed-note state event))
                             [recording []]
                             stop-events)]
    (when-not (and (= 69 (:midi confirmed))
                   (= 0.10 released)
                   (= :recording (:mode recording))
                   (= :playback (:mode playback))
                   (= [69] (mapv :midi (:notes playback)))
                   (= 1.0 (:loop-length playback)))
      (throw (ex-info "Internal state-machine check failed." {})))))


(defn check!
  []
  (check-pure-logic!)
  (doseq [synth [looper-detector looper-voice looper-mixer]]
    (synthdef/synthdef-bytes (:sdef synth)))
  (println "Looper state machine and Tartini/voice/mixer graphs compiled; no audio devices opened."))


(defn listen!
  [{:keys [input output gate] :as options}]
  (println (str "Tartini guitar gesture looper: " input " -> " output
                "; raw stereo monitor -6 dB."))
  (println "IDLE: C5 D5 C5 D5 -> RECORDING; D5 C5 D5 C5 -> PLAYBACK; repeat -> EXIT.")
  (println "C5=MIDI 72, D5=MIDI 74. Each command is four fresh confirmed notes within 4 seconds.")
  (println (str "RMS gate " gate "/" (- gate 6.0)
                " dBFS; Tartini confidence >0.9; 55 ms confirmation, 120 ms release."))
  (println "Command notes are excluded. New playing stays raw during synthesized playback.")
  (println "Low volume/headphones; avoid feedback and duplicate direct monitoring.")
  (println "Foreground only: Ctrl+C stops. Server timer and OS alarm limit the session to 600 seconds.")
  (flush)
  (let [{:keys [cleanup hook] :as engine} (start-engine! options)]
    (try
      (let [reason (run-looper! options engine)]
        (when (= :timeout reason)
          (println "Finished normally: 600-second session limit reached.")))
      (finally
        (cleanup)
        (try
          (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _))))))


(defn -main
  [& args]
  (let [{:keys [help? check?] :as options} (parse-options args)]
    (cond
      help? (println usage)
      check? (check!)
      :else (listen! options))))


(try
  (apply -main *command-line-args*)
  (System/exit 0)
  (catch Exception error
    (binding [*out* *err*]
      (println (ex-message error)))
    (System/exit (let [exit (:exit (ex-data error))]
                   (if (and (integer? exit) (pos? exit)) exit 1)))))
