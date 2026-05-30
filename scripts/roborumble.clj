(ns roborumble
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- load-exec-ctx
  "Load execution context from roborumble.edn. Returns a context map with
   :mode (:remote or :local), :host, :robocode-home, :java-home, :num-workers."
  [local?]
  (let [config (edn/read-string (slurp "roborumble.edn"))
        mode   (if local? :local :remote)
        raw    (get config mode)]
    (if local?
      {:mode          :local
       :robocode-home (str (fs/expand-home "~/robocode"))
       :java-home     (str (fs/expand-home (get raw :java-home
                                               "~/.sdkman/candidates/java/21.0.11-amzn")))
       :num-workers   (get raw :num-workers 4)}
      {:mode          :remote
       :host          (:host raw)
       :robocode-home (:robocode-home raw "~/robocode")
       :java-home     (:java-home raw "~/.sdkman/candidates/java/21.0.11-amzn")
       :num-workers   (get raw :num-workers 4)})))

;; --- SSH helpers (copied from benchmark.clj) ---

(def ^:private ssh-control-path (atom nil))

(defn- open-ssh-control!
  "Open an SSH ControlMaster socket for connection multiplexing."
  [ctx]
  (when (= :remote (:mode ctx))
    (let [socket (str "/tmp/ssh-roborumble-" (System/currentTimeMillis) ".sock")]
      (p/shell {:out :string :err :string}
               "ssh" "-MNf"
               "-o" "ControlPersist=yes"
               "-o" (str "ControlPath=" socket)
               (:host ctx))
      (reset! ssh-control-path socket))))

(defn- close-ssh-control!
  "Close the SSH ControlMaster socket."
  []
  (when-let [socket @ssh-control-path]
    (p/shell {:out :string :err :string :continue true}
             "ssh" "-O" "exit"
             "-o" (str "ControlPath=" socket)
             "ignored-host")
    (reset! ssh-control-path nil)))

(defn- ssh!
  "Run a shell command on the remote host via SSH, returning stdout."
  [ctx cmd]
  (let [args (cond-> ["ssh"]
               @ssh-control-path (into ["-o" (str "ControlPath=" @ssh-control-path)])
               true (into [(:host ctx) cmd]))
        result (apply p/shell {:out :string :err :string} args)]
    (:out result)))

(defn- check-remote!
  "Fail fast if the remote host is unreachable or Java is missing."
  [ctx]
  (when (= :remote (:mode ctx))
    (let [result (p/shell {:out :string :err :string :continue true}
                          "ssh"
                          "-o" (str "ControlPath=" (or @ssh-control-path "none"))
                          "-o" "ConnectTimeout=5"
                          (:host ctx)
                          (str (:java-home ctx) "/bin/java -version"))]
      (when (not= 0 (:exit result))
        (throw (ex-info (str "Remote host unreachable or Java not found: " (:host ctx))
                        {:host (:host ctx) :exit (:exit result) :err (:err result)}))))))

(defn- check-robocode-installed!
  "Verify the target host has a working Robocode installation."
  [ctx]
  (if (= :remote (:mode ctx))
    (let [result (p/shell {:out :string :err :string :continue true}
                          "ssh"
                          "-o" (str "ControlPath=" (or @ssh-control-path "none"))
                          (:host ctx)
                          (str "test -d " (:robocode-home ctx) "/libs && echo OK"))]
      (when (or (not= 0 (:exit result))
                (not (str/includes? (:out result) "OK")))
        (throw (ex-info (str "Robocode not installed on remote: " (:robocode-home ctx))
                        {:host (:host ctx) :robocode-home (:robocode-home ctx)}))))
    (when-not (fs/exists? (str (fs/expand-home "~/robocode") "/libs"))
      (throw (ex-info "Robocode not installed locally: ~/robocode/libs not found" {})))))

;; --- Caffeinate helpers (copied from benchmark.clj) ---

(def ^:private caffeinate-pid (atom nil))
(def ^:private remote-ctx (atom nil))

(defn- start-caffeinate!
  "Start caffeinate on the remote host to prevent macOS CPU throttling."
  [ctx]
  (when (= :remote (:mode ctx))
    (reset! remote-ctx ctx)
    (let [pid (str/trim (ssh! ctx "caffeinate -disu -t 7200 </dev/null >/dev/null 2>&1 & echo $!"))]
      (reset! caffeinate-pid pid))))

(defn- stop-caffeinate!
  "Stop the remote caffeinate process."
  []
  (when-let [pid @caffeinate-pid]
    (when-let [ctx @remote-ctx]
      (try (ssh! ctx (str "kill " pid)) (catch Exception _)))
    (reset! caffeinate-pid nil)))

;; --- Worker setup (copied from benchmark.clj) ---

(defn- ensure-robocode-copy!
  "Create an APFS clone of the robocode installation for a worker."
  [ctx worker-id]
  (if (= :remote (:mode ctx))
    (let [target (format "/tmp/robocode-%d" worker-id)]
      (ssh! ctx (str "rm -rf " target
                     " && cp -Rc " (:robocode-home ctx) " " target))
      target)
    (let [target (format ".tmp/robocode-%d" worker-id)
          abs-target (str (fs/absolutize target))]
      (fs/delete-tree target)
      (fs/create-dirs ".tmp")
      (p/shell {:out :string :err :string}
               "cp" "-Rc" (str (fs/expand-home "~/robocode")) target)
      abs-target)))

;; --- Shutdown hook ---

(def ^:private shutdown-hook (atom nil))

(defn- kill-remote-workers!
  "Kill any remote Java processes running RoboRumble."
  []
  (when-let [ctx @remote-ctx]
    (try (ssh! ctx "pkill -f 'roborumble.RoboRumbleAtHome' 2>/dev/null; true")
         (catch Exception _))))

(defn- cleanup-worker-copies!
  "Remove worker robocode copies."
  [ctx n-workers]
  (try
    (if (= :remote (:mode ctx))
      (ssh! ctx (str "rm -rf " (str/join " " (map #(format "/tmp/robocode-%d" %) (range n-workers)))))
      (doseq [wid (range n-workers)]
        (fs/delete-tree (format ".tmp/robocode-%d" wid))))
    (catch Exception _)))

(defn- register-shutdown-hook!
  "Register a JVM shutdown hook to clean up on Ctrl-C."
  [ctx n-workers]
  (when-not @shutdown-hook
    (let [hook (Thread. ^Runnable (fn []
                                   (println "\nShutting down...")
                                   (kill-remote-workers!)
                                   (cleanup-worker-copies! ctx n-workers)
                                   (stop-caffeinate!)
                                   (close-ssh-control!)))]
      (reset! shutdown-hook hook)
      (.addShutdownHook (Runtime/getRuntime) hook))))

(defn- unregister-shutdown-hook!
  "Remove the shutdown hook after normal cleanup."
  []
  (when-let [hook @shutdown-hook]
    (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch Exception _))
    (reset! shutdown-hook nil)))

;; --- Worker ---

(def ^:dynamic *worker-id* 0)

(defn- prefix-stream!
  "Read lines from stream and print each with a prefix. Runs in a future."
  [prefix stream]
  (future
    (with-open [rdr (java.io.BufferedReader. (java.io.InputStreamReader. stream))]
      (loop []
        (when-let [line (.readLine rdr)]
          (locking *out*
            (println (str prefix " " line)))
          (recur))))))

(defn- run-roborumble-worker!
  "Launch one RoboRumbleAtHome process. Blocks until the process exits."
  [ctx robocode-home]
  (let [java-cmd (str (:java-home ctx) "/bin/java")
        prefix (format "[W%d]" *worker-id*)
        cmd (if (= :remote (:mode ctx))
              (cond-> ["ssh"]
                @ssh-control-path (into ["-o" (str "ControlPath=" @ssh-control-path)])
                true (into [(:host ctx)
                            (str "cd " robocode-home
                                 " && " java-cmd
                                 " -cp 'libs/*'"
                                 " -Xmx512M"
                                 " -XX:+IgnoreUnrecognizedVMOptions"
                                 " --add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
                                 " --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
                                 " roborumble.RoboRumbleAtHome"
                                 " ./roborumble/roborumble.txt")]))
              [java-cmd
               "-cp" "libs/*"
               "-Xmx512M"
               "-XX:+IgnoreUnrecognizedVMOptions"
               "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
               "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
               "roborumble.RoboRumbleAtHome"
               "./roborumble/roborumble.txt"])
        opts (cond-> {:out :pipe :err :pipe}
               (= :local (:mode ctx)) (assoc :dir robocode-home))
        proc (p/process cmd opts)]
    (locking *out*
      (println (format "  %s Started in %s" prefix robocode-home)))
    (prefix-stream! prefix (:out proc))
    (prefix-stream! prefix (:err proc))
    @proc))

;; --- Queuing (same pattern as benchmark) ---

(def ^:private lock-dir ".tmp")

(defn- lock-path [scope]
  (str lock-dir "/roborumble-" (name scope) ".lock"))

(defn- lock-options []
  (into-array java.nio.file.OpenOption
              [java.nio.file.StandardOpenOption/CREATE
               java.nio.file.StandardOpenOption/READ
               java.nio.file.StandardOpenOption/WRITE]))

(defn- with-roborumble-lock [scope f]
  (fs/create-dirs lock-dir)
  (let [lp (lock-path scope)]
    (with-open [channel (java.nio.channels.FileChannel/open
                         (.toPath (fs/file lp))
                         (lock-options))]
      (let [lock (or (.tryLock channel)
                     (do (println (format "Another roborumble is running (%s). Waiting..." (name scope)))
                         (.lock channel)))]
        (try
          (let [content (pr-str {:pid (.pid (java.lang.ProcessHandle/current))
                                 :started-at (str (java.time.Instant/now))})]
            (.truncate channel 0)
            (.position channel 0)
            (.write channel (java.nio.ByteBuffer/wrap (.getBytes content "UTF-8")))
            (.force channel true))
          (f)
          (finally
            (.truncate channel 0)
            (.force channel true)))))))

;; --- Main orchestration ---

(defn run!
  "Run N parallel RoboRumble client workers.
   Options: :local? - run locally instead of on remote host"
  [{:keys [local?]}]
  (let [ctx (load-exec-ctx local?)
        n-workers (:num-workers ctx)
        scope (if local? :local :remote)]
    (with-roborumble-lock scope
      (fn []
        (open-ssh-control! ctx)
        (start-caffeinate! ctx)
        (register-shutdown-hook! ctx n-workers)
        (try
          (check-remote! ctx)
          (check-robocode-installed! ctx)
          (println (format "RoboRumble: %d workers %s"
                           n-workers
                           (if (= :remote (:mode ctx))
                             (str "on " (:host ctx))
                             "locally")))
          (let [worker-homes (mapv (partial ensure-robocode-copy! ctx) (range n-workers))
                futures (mapv (fn [wid]
                                (future
                                  (binding [*worker-id* wid]
                                    (try
                                      (run-roborumble-worker! ctx (nth worker-homes wid))
                                      (catch Exception e
                                        (locking *out*
                                          (println (format "  [W%d] Crashed: %s" wid (.getMessage e)))))))))
                              (range n-workers))]
            (println "Press Ctrl-C to stop.\n")
            ;; Block until all futures complete (they run until Ctrl-C)
            (doseq [f futures] (deref f)))
          (finally
            (kill-remote-workers!)
            (cleanup-worker-copies! ctx n-workers)
            (stop-caffeinate!)
            (close-ssh-control!)
            (unregister-shutdown-hook!)))))))
