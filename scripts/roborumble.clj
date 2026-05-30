(ns roborumble
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [remote]))

(defn- kill-remote-workers!
  "Kill any remote Java processes running RoboRumble."
  []
  (when-let [ctx @remote/remote-ctx]
    (try (remote/ssh! ctx "pkill -f 'roborumble.RoboRumbleAtHome' 2>/dev/null; true")
         (catch Exception _))))

(defn- cleanup-worker-copies!
  "Remove worker robocode copies."
  [ctx n-workers]
  (try
    (if (= :remote (:mode ctx))
      (remote/ssh! ctx (str "rm -rf " (str/join " " (map #(format "/tmp/robocode-%d" %) (range n-workers)))))
      (doseq [wid (range n-workers)]
        (fs/delete-tree (format ".tmp/robocode-%d" wid))))
    (catch Exception _)))

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
                @remote/ssh-control-path (into ["-o" (str "ControlPath=" @remote/ssh-control-path)])
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
  (let [ctx (remote/load-exec-ctx "roborumble.edn" local?)
        n-workers (:num-workers ctx)
        scope (if local? :local :remote)]
    (with-roborumble-lock scope
      (fn []
        (remote/open-ssh-control! ctx "roborumble")
        (remote/start-caffeinate! ctx)
        (remote/register-shutdown-hook! (fn []
                                          (println "\nShutting down...")
                                          (kill-remote-workers!)
                                          (cleanup-worker-copies! ctx n-workers)
                                          (remote/stop-caffeinate!)
                                          (remote/close-ssh-control!)))
        (try
          (remote/check-remote! ctx)
          (remote/check-robocode-installed! ctx)
          (println (format "RoboRumble: %d workers %s"
                           n-workers
                           (if (= :remote (:mode ctx))
                             (str "on " (:host ctx))
                             "locally")))
          (let [worker-homes (mapv (partial remote/ensure-robocode-copy! ctx) (range n-workers))
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
            (remote/stop-caffeinate!)
            (remote/close-ssh-control!)
            (remote/unregister-shutdown-hook!)))))))
