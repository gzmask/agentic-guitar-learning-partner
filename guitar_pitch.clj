;; JVM Clojure + Overtone / SuperCollider Pitch.kr, separate from the song POC.
;; clojure -Sdeps '{:deps {overtone/overtone {:mvn/version "0.16.3331"}}}' -M guitar_pitch.clj [--monitor]
;; --check compiles the graph without opening audio or starting scsynth.
;; --compare recording.wav compares both trackers offline; requires ffmpeg + csound.

(ns guitar-pitch
  (:require
    [clojure.java.io :as io]
    [overtone.osc :as osc]
    [overtone.osc.encode :as osc-encode]
    [overtone.sc.machinery.synthdef :as synthdef]
    [overtone.sc.synth :refer [defsynth]]
    [overtone.sc.ugens :as u])
  (:import
    (java.net
      DatagramSocket)
    (java.util
      Locale)
    (java.util.concurrent
      LinkedBlockingQueue
      TimeUnit)))


;; Defining the graph does not boot a server. In:ar starts after output buses.
;; Median=1 avoids adding hidden smoothing on top of the 55 ms note filter.
;; A finer coarse search (64, not the default 16) reduced local semitone jumps.
;; Macro-generated synth/control bindings are verified by --check.
^{:clj-kondo/ignore [:unresolved-symbol]}
(defsynth guitar-detector [monitor-gain 0]
  (let [[left right] (u/in:ar (u/num-output-buses:ir) 2)
        mono (* (+ left right) 0.5)
        [hz has-freq] (u/pitch:kr mono
                                  :init-freq 440 :min-freq 70 :max-freq 1400
                                  :exec-freq 100 :max-bins-per-octave 64
                                  :median 1 :amp-threshold 0.005)
        db (u/ampdb (max (u/amplitude:kr mono 0.01 0.01) 1.0e-9))
        ;; Free the synth at 600 score seconds, independently of the JVM loop.
        elapsed (u/line:kr 0 600 600 2)]
    (u/send-reply:kr (u/impulse:kr 100) "/guitar/pitch" [elapsed hz db has-freq])
    (u/out:ar 0 [(* left monitor-gain) (* right monitor-gain)])))


(def usage
  (str "Usage: ... -M guitar_pitch.clj [--monitor] [--raw] [--seconds 1..600]\n"
       "       [--input DEVICE] [--output DEVICE] [--check | --help]\n"
       "       ... -M guitar_pitch.clj --compare recording.wav [--raw]\n"
       "Live defaults: Fx100 input/output, 60 seconds, silent output.\n"
       "Comparison: same recording/RMS/filter; no live audio; ffmpeg + csound required.\n"
       "SCSYNTH may override the scsynth executable path."))


(defn parse-options
  [args]
  (loop [args (seq args)
         opts {:input "Fx100" :output "Fx100" :seconds 60
               :monitor? false :raw? false :check? false :help? false :compare nil}]
    (if-let [arg (first args)]
      (cond
        (#{"--monitor" "--raw" "--check" "--help"} arg)
        (recur (next args) (assoc opts ({"--monitor" :monitor? "--raw" :raw?
                                         "--check" :check? "--help" :help?} arg) true))

        (#{"--input" "--output" "--seconds" "--compare"} arg)
        (let [value (second args)]
          (when (or (nil? value) (.startsWith ^String value "--"))
            (throw (ex-info usage {})))
          (if (= arg "--seconds")
            (let [seconds (parse-long value)]
              (when-not (and seconds (<= 1 seconds 600))
                (throw (ex-info "--seconds must be an integer between 1 and 600." {})))
              (recur (nnext args) (assoc opts :seconds seconds)))
            (recur (nnext args) (assoc opts ({"--input" :input "--output" :output "--compare" :compare} arg) value))))

        :else (throw (ex-info usage {})))
      (do
        (when (and (:compare opts) (:monitor? opts))
          (throw (ex-info "--compare is offline; it cannot be combined with --monitor." {})))
        opts))))


(def note-names ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])


(defn pitch-label
  [hz]
  (when (and (Double/isFinite (double hz)) (<= 70 hz 1400))
    (let [pitch (+ 69.0 (* 12.0 (/ (Math/log (/ hz 440.0)) (Math/log 2.0))))
          midi (Math/round pitch)]
      {:midi midi
       :note (str (nth note-names (mod midi 12)) (dec (quot midi 12)))
       :cents (* 100.0 (- pitch midi))})))


(def initial-state
  {:gate? false :candidate nil :active nil :since 0.0 :silent-since nil
   :last-time -1.0 :event nil})


(defn track-note
  "Pure note filter driven by synth time, not OSC arrival or JVM scheduling time."
  [{:keys [gate? candidate active since silent-since last-time] :as state}
   {:keys [time hz db has-freq] :as sample}]
  (if (<= time last-time)
    (assoc state :event nil)
    (let [gate? (> db (if gate? -42.0 -36.0))
          label (when (and gate? (> has-freq 0.5)) (pitch-label hz))
          state (assoc state :last-time time :gate? gate? :event nil)]
      (if-let [midi (:midi label)]
        (let [since (if (or (not= midi candidate) (> (- time last-time) 0.05)) time since)
              emit? (and (>= (- time since) 0.055) (not= midi active))]
          (assoc state :candidate midi :since since :silent-since nil
                 :active (if emit? midi active)
                 :event (when emit? (merge sample label))))
        (let [silent-since (or silent-since time)]
          (assoc state :candidate nil :silent-since silent-since
                 :active (when (< (- time silent-since) 0.12) active)))))))


(defn print-sample!
  [{:keys [time hz db has-freq] :as sample} raw?]
  (let [{:keys [midi note cents]} (if raw? (when (> has-freq 0.5) (pitch-label hz)) sample)]
    (println (String/format Locale/ROOT
                            "t=%7.3fs  MIDI %3s  %-3s  %8.2f Hz  %+6.1f cents  %6.1f dBFS  hasFreq=%.0f"
                            (to-array [time (or midi "--") (or note "--") hz
                                       (or cents 0.0) db has-freq])))
    (flush)))


(defn scsynth-path
  []
  (or (System/getenv "SCSYNTH")
      (some #(when (.canExecute (io/file %)) %)
            ["/Applications/SuperCollider.app/Contents/Resources/scsynth"
             "/opt/homebrew/bin/scsynth" "/usr/local/bin/scsynth" "/usr/bin/scsynth"])
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
        (throw (ex-info "The owned scsynth process did not exit." {:pid (.pid child)}))))))


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
          (= "/fail" (:path message)) (throw (ex-info (str "scsynth: " (:args message)) {}))
          (= path (:path message)) message
          (> (System/nanoTime) deadline) (throw (ex-info (str "Timed out waiting for " path) {}))
          :else (recur))))))


(defn listen!
  [{:keys [input output seconds monitor? raw?]}]
  (let [port (unused-port)
        ;; exec keeps the child PID; an OS alarm also bounds a stalled JVM.
        command ["/usr/bin/perl" "-e" "alarm 600; exec @ARGV or die $!"
                 (scsynth-path) "-u" (str port) "-B" "127.0.0.1"
                 "-H" input output "-i" "2" "-o" "2" "-S" "44100"
                 "-z" "32" "-D" "0" "-R" "0" "-s" "1"]
        child (.start (doto (ProcessBuilder. ^java.util.List command)
                        (.inheritIO)))
        peer (atom nil)
        stopped? (atom false)
        cleanup! (fn []
                   (when (compare-and-set! stopped? false true)
                     (try
                       (stop-process! child)
                       (finally
                         (when-let [client @peer]
                           (osc/osc-close client true))))))
        hook (Thread. ^Runnable cleanup! "guitar-pitch-cleanup")
        queue (LinkedBlockingQueue. 1024)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [client (osc/osc-client "127.0.0.1" port false)]
        (reset! peer client)
        (osc/osc-listen client #(.offer queue %) ::incoming)
        ;; Readiness is retried because CoreAudio may take a moment to open.
        (loop [attempts 100]
          (check-child! child)
          (when (zero? attempts)
            (throw (ex-info "scsynth did not become ready within 10 seconds." {})))
          (osc/osc-send client "/status")
          (let [message (.poll queue 100 TimeUnit/MILLISECONDS)]
            (when-not (= "/status.reply" (:path message))
              (recur (dec attempts)))))
        (osc/osc-send client "/notify" (int 1))
        (await-message! queue child "/done")
        (osc/osc-send client "/d_recv" (synthdef/synthdef-bytes (:sdef guitar-detector)))
        (await-message! queue child "/done")
        (osc/osc-send client "/s_new" (:name (:sdef guitar-detector))
                      (int 1000) (int 0) (int 0) "monitor-gain" (float (if monitor? 0.5 0)))
        (println (str "Pitch.kr: " input " -> " output "; "
                      (if monitor? "monitor -6 dB" "silent") "; " seconds " seconds. Ctrl+C stops."))
        (println "Analysis: 44.1 kHz, Pitch.kr 100 Hz, 70..1400 Hz, median=1, bins/octave=64.")
        (println (if raw? "RAW: every 100 ms; hasFreq=0 means held/invalid pitch."
                     "Filtered: -36/-42 dBFS gate, 55 ms stable pitch, 120 ms release."))
        (when monitor?
          (println "Use headphones/low volume; avoid duplicate direct monitoring or feedback."))
        (flush)
        (let [deadline (+ (System/nanoTime) (* seconds 1000000000))]
          (loop [state initial-state last-raw-time -1.0]
            (when (< (System/nanoTime) deadline)
              (check-child! child)
              (let [{:keys [path args]} (.poll queue 200 TimeUnit/MILLISECONDS)]
                (when (= path "/fail")
                  (throw (ex-info (str "scsynth: " args) {})))
                (if (= path "/guitar/pitch")
                  (let [[_ _ time hz db has-freq] args
                        sample {:time (double time) :hz (double hz) :db (double db)
                                :has-freq (double has-freq)}
                        state (track-note state sample)
                        raw-due? (and raw? (>= (- time last-raw-time) 0.1))]
                    (when-let [event (if raw-due? sample (when-not raw? (:event state)))]
                      (print-sample! event raw?))
                    (recur state (if raw-due? time last-raw-time)))
                  (recur state last-raw-time)))))))
      (finally
        (cleanup!)
        (try
          (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _))))))


;; Offline comparison: both engines consume one normalized mono WAV. Only their
;; frequency/validity outputs differ; RMS, time grid, gating and note filtering
;; are computed once here. No audio devices or OSC server are opened.
^{:clj-kondo/ignore [:unresolved-symbol]}
(defsynth offline-pitch []
  (let [mono (u/in:ar (u/num-output-buses:ir) 1)
        [hz valid] (u/pitch:kr mono :init-freq 440 :min-freq 70 :max-freq 1400
                               :exec-freq 100 :max-bins-per-octave 64 :median 1
                               :amp-threshold 0)]
    ;; Control-rate recording avoids K2A interpolation of raw pitch estimates.
    (u/record-buf:kr [hz valid] 0 :loop 0)))


(defn run-offline!
  [command log-file]
  (let [child (.start (doto (ProcessBuilder. ^java.util.List
                             (into ["/usr/bin/perl" "-e" "alarm 600; exec @ARGV or die $!"] command))
                        (.redirectErrorStream true)
                        (.redirectOutput (io/file log-file))))
        hook (Thread. ^Runnable #(stop-process! child) "pitch-comparison-cleanup")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [exit (.waitFor child)
            log (slurp log-file)]
        ;; scsynth NRT can return zero even when a server command failed.
        (when (or (not (zero? exit)) (re-find #"(?m)FAILURE IN SERVER|\*\*\* ERROR" log))
          (throw (ex-info (str "Offline engine failed:\n" log) {:exit (if (zero? exit) 1 exit)}))))
      (finally
        (stop-process! child)
        (try
          (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _))))))


(defn float-wav
  [file]
  (with-open [input (javax.sound.sampled.AudioSystem/getAudioInputStream (io/file file))]
    (let [format (.getFormat input)]
      (when-not (and (= "PCM_FLOAT" (str (.getEncoding format)))
                     (= 32 (.getSampleSizeInBits format))
                     (= 44100.0 (.getSampleRate format)))
        (throw (ex-info "Expected a 44.1 kHz float32 WAV from the offline engine." {})))
      {:frames (.getFrameLength input)
       :channels (.getChannels format)
       :data (.asFloatBuffer
               (doto (java.nio.ByteBuffer/wrap (.readAllBytes input))
                 (.order (if (.isBigEndian format)
                           java.nio.ByteOrder/BIG_ENDIAN java.nio.ByteOrder/LITTLE_ENDIAN))))})))


(defn rms-db
  [^java.nio.FloatBuffer samples end-frame]
  (let [start (max 0 (- end-frame 1024))
        energy (loop [frame start total 0.0]
                 (if (< frame end-frame)
                   (let [value (double (.get samples (int frame)))]
                     (recur (inc frame) (+ total (* value value))))
                   total))]
    ;; Zero padding before the recording matches a fixed 1024-sample window.
    (* 10.0 (Math/log10 (max 1.0e-18 (/ energy 1024.0))))))


(defn osc-bytes
  [packet]
  (let [buffer (java.nio.ByteBuffer/allocate 65536)]
    (osc-encode/osc-encode-packet buffer packet)
    (java.util.Arrays/copyOf (.array buffer) (.position buffer))))


(defn write-nrt!
  [file blocks duration result-file]
  (let [spawn (osc-bytes (osc/osc-msg "/s_new" (:name (:sdef offline-pitch))
                                      (int 1000) (int 0) (int 0)))
        load (osc-bytes (osc/osc-msg "/d_recv" (synthdef/synthdef-bytes (:sdef offline-pitch)) spawn))
        score [[0.0 [(osc/osc-msg "/b_alloc" (int 0) (int blocks) (int 2) load)]]
               [duration [(osc/osc-msg "/n_free" (int 1000))
                          (osc/osc-msg "/b_write" (int 0) (str result-file) "wav" "float"
                                       (int -1) (int 0) (int 0))]]
               [(+ duration 0.05) [(osc/osc-msg "/c_set" (int 0) (float 0))]]]]
    (with-open [stream (java.io.DataOutputStream. (io/output-stream file))]
      (doseq [[time messages] score]
        (let [bytes (osc-bytes (apply osc/osc-bundle 0 messages))]
          ;; NRT timetags count from score zero, not the wall-clock NTP epoch.
          (.putLong (java.nio.ByteBuffer/wrap bytes) 8 (long (* time 4294967296.0)))
          (.writeInt stream (alength ^bytes bytes))
          (.write stream ^bytes bytes))))))


(defn ptrack-document
  [duration]
  (str "<CsoundSynthesizer>\n<CsInstruments>\n"
       "sr=44100\nksmps=32\nnchnls=2\nnchnls_i=1\n0dbfs=1\n"
       "instr 1\n ain inch 1\n khz,kignored ptrack ain,1024\n"
       " kvalid = (khz >= 70 && khz <= 1400 ? 1 : 0)\n"
       ;; Scale the numeric telemetry below full scale; it is not audio.
       " afreq upsamp khz/2000\n avalid upsamp kvalid\n outs afreq,avalid\nendin\n"
       "</CsInstruments>\n<CsScore>\ni 1 0 " duration "\ne\n</CsScore>\n</CsoundSynthesizer>\n"))


(defn compare-traces
  "Return aligned rows with identical source-time/RMS input to both filters."
  [source ptrack pitch]
  (let [^java.nio.FloatBuffer audio (:data source)
        ^java.nio.FloatBuffer p (:data ptrack)
        ^java.nio.FloatBuffer s (:data pitch)
        frames (:frames source)]
    (when-not (and (= 1 (:channels source)) (= 2 (:channels ptrack) (:channels pitch))
                   (>= (:frames ptrack) frames)
                   (>= (:frames pitch) (quot (+ frames 31) 32)))
      (throw (ex-info "Offline engines returned incomplete or misaligned traces." {})))
    (:rows
      (reduce
        (fn [{:keys [states rows]} frame]
          (let [block (quot frame 32)
                end-frame (min frames (* (inc block) 32))
                base {:time (/ end-frame 44100.0) :db (rms-db audio end-frame)}
                inputs [(assoc base :hz (* 2000.0 (.get p (int (* block 64))))
                               :has-freq (double (.get p (int (inc (* block 64))))))
                        (assoc base :hz (double (.get s (int (* block 2))))
                               :has-freq (double (.get s (int (inc (* block 2))))))]
                states (mapv track-note states inputs)]
            {:states states
             :rows (conj rows (assoc base :inputs inputs :states states))}))
        {:states [initial-state initial-state] :rows []}
        (range 0 frames 441)))))


(defn comparison-cell
  [{:keys [hz has-freq]} {:keys [event]}]
  (let [label (when (> has-freq 0.5) (pitch-label hz))]
    (String/format Locale/ROOT "%8.2f Hz %-3s valid=%.0f new=%-3s"
                   (to-array [hz (or (:note label) "--") has-freq (or (:note event) "--")]))))


(defn print-comparison!
  [rows raw?]
  (doseq [{:keys [time db inputs states]} rows
          :when (or raw? (some :event states))]
    (println (String/format Locale/ROOT "t=%7.3fs RMS=%6.1f dBFS | ptrack %s | Pitch.kr %s"
                            (to-array [time db (comparison-cell (first inputs) (first states))
                                       (comparison-cell (second inputs) (second states))]))))
  (println "Accepted MIDI sequences:")
  (doseq [[index name] [[0 "ptrack"] [1 "Pitch.kr"]]]
    (println name (vec (keep #(get-in % [:states index :event :midi]) rows))))
  (println "Event counts are NOT accuracy. Audio-time confirmations are NOT hardware round-trip latency."))


(defn compare-file!
  [{:keys [compare raw?]}]
  (let [source (io/file compare)
        channels (.getChannels (.getFormat (javax.sound.sampled.AudioSystem/getAudioFileFormat source)))
        directory (.toFile (java.nio.file.Files/createTempDirectory "guitar-pitch-compare-"
                                                                    (make-array java.nio.file.attribute.FileAttribute 0)))
        file #(io/file directory %)]
    (try
      (when-not (#{1 2} channels)
        (throw (ex-info "Comparison accepts mono or stereo WAV input only." {})))
      (println "Offline comparison: same mono WAV, 44.1 kHz, 32-sample blocks, 100 Hz reporting.")
      (println "Shared: trailing 1024-sample RMS, -36/-42 dBFS gate, 55 ms stable, 120 ms release.")
      (println "ptrack: hop=1024; Pitch.kr: 70..1400 Hz, exec=100 Hz, bins=64, median=1, ampThreshold=0.")
      (println "Raw Pitch.kr validity is respected; its amplitude gate is disabled. No audio devices are opened.")
      (flush)
      (run-offline! ["ffmpeg" "-nostdin" "-y" "-v" "error" "-i" (.getAbsolutePath source)
                     "-map" "0:a:0" "-af" (if (= 2 channels) "pan=mono|c0=0.5*c0+0.5*c1" "anull")
                     "-ar" "44100" "-ac" "1" "-t" "600.1" "-c:a" "pcm_f32le" (str (file "mono.wav"))]
                    (file "normalize.log"))
      (let [{:keys [frames] :as audio} (float-wav (file "mono.wav"))
            blocks (quot (+ frames 31) 32)
            duration (/ (* blocks 32) 44100.0)]
        (when-not (< 0 frames (* 600 44100))
          (throw (ex-info "Comparison WAV must be nonempty and shorter than 600 seconds." {})))
        (write-nrt! (file "pitch.osc") blocks duration (file "pitch.wav"))
        (spit (file "ptrack.csd") (ptrack-document duration))
        (run-offline! [(scsynth-path) "-N" (str (file "pitch.osc")) (str (file "mono.wav"))
                       (str (file "silent.wav")) "44100" "WAV" "float"
                       "-i" "1" "-o" "1" "-z" "32" "-D" "0" "-R" "0"]
                      (file "pitch.log"))
        (run-offline! ["csound" "-d" "-m0" "-W" "-f" "-i" (str (file "mono.wav"))
                       "-o" (str (file "ptrack.wav")) (str (file "ptrack.csd"))]
                      (file "ptrack.log"))
        (print-comparison! (compare-traces audio (float-wav (file "ptrack.wav"))
                                           (float-wav (file "pitch.wav"))) raw?)
        (println "Finished normally: both engines reached the same WAV ending."))
      (finally
        (doseq [entry (reverse (file-seq directory))]
          (io/delete-file entry true))))))


(defn -main
  [& args]
  (let [{:keys [help? check? compare] :as options} (parse-options args)]
    (cond
      help? (println usage)
      check? (do
               (synthdef/synthdef-bytes (:sdef guitar-detector))
               (synthdef/synthdef-bytes (:sdef offline-pitch))
               (println "Live and offline Pitch.kr graphs compiled; no audio devices opened."))
      compare (compare-file! options)
      :else (do
              (listen! options)
              (println (str "Finished normally: configured " (:seconds options) " seconds reached."))))))


(try
  (apply -main *command-line-args*)
  (System/exit 0)
  (catch Exception error
    (binding [*out* *err*]
      (println (ex-message error)))
    (System/exit (let [exit (:exit (ex-data error))]
                   (if (and (integer? exit) (pos? exit)) exit 1)))))
