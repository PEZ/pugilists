(ns remote
  "Shared remote execution infrastructure for benchmark and roborumble.
   SSH multiplexing, caffeinate, APFS cloning, shutdown hooks, and pre-flight checks."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; --- Execution context ---

(defn load-exec-ctx
  "Load execution context from an EDN config file. Returns a context map with
   :mode (:remote or :local), :host, :robocode-home, :java-home, :num-workers."
  [config-file local?]
  (let [config (edn/read-string (slurp config-file))
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
       :num-workers   (get raw :num-workers 4)
       :cpu-constant  (:cpu-constant raw)})))

;; --- SSH helpers ---

(def ssh-control-path (atom nil))

(defn open-ssh-control!
  "Open an SSH ControlMaster socket for connection multiplexing.
   Stores the socket path in ssh-control-path atom."
  [ctx socket-prefix]
  (when (= :remote (:mode ctx))
    (let [socket (str "/tmp/ssh-" socket-prefix "-" (System/currentTimeMillis) ".sock")]
      (p/shell {:out :string :err :string}
               "ssh" "-MNf"
               "-o" "ControlPersist=yes"
               "-o" (str "ControlPath=" socket)
               (:host ctx))
      (reset! ssh-control-path socket))))

(defn close-ssh-control!
  "Close the SSH ControlMaster socket."
  []
  (when-let [socket @ssh-control-path]
    (p/shell {:out :string :err :string :continue true}
             "ssh" "-O" "exit"
             "-o" (str "ControlPath=" socket)
             "ignored-host")
    (reset! ssh-control-path nil)))

(defn ssh!
  "Run a shell command on the remote host via SSH, returning stdout as a string.
   Uses ControlMaster socket if available."
  [ctx cmd]
  (let [args (cond-> ["ssh"]
               @ssh-control-path (into ["-o" (str "ControlPath=" @ssh-control-path)])
               true (into [(:host ctx) cmd]))
        result (apply p/shell {:out :string :err :string} args)]
    (:out result)))

(defn scp-to-remote!
  "Copy a local file to remote host:path."
  [ctx local-path remote-path]
  (let [args (cond-> ["scp"]
               @ssh-control-path (into ["-o" (str "ControlPath=" @ssh-control-path)])
               true (into [local-path (str (:host ctx) ":" remote-path)]))]
    (apply p/shell {:out :string :err :string} args)))

;; --- Pre-flight checks ---

(defn check-remote!
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

(defn check-robocode-installed!
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

;; --- Caffeinate ---

(def ^:private caffeinate-pid (atom nil))
(def remote-ctx (atom nil))

(defn start-caffeinate!
  "Start caffeinate on the remote host to prevent macOS CPU throttling."
  [ctx]
  (when (= :remote (:mode ctx))
    (reset! remote-ctx ctx)
    (let [pid (str/trim (ssh! ctx "caffeinate -disu -t 7200 </dev/null >/dev/null 2>&1 & echo $!"))]
      (reset! caffeinate-pid pid))))

(defn stop-caffeinate!
  "Stop the remote caffeinate process."
  []
  (when-let [pid @caffeinate-pid]
    (when-let [ctx @remote-ctx]
      (try (ssh! ctx (str "kill " pid)) (catch Exception _)))
    (reset! caffeinate-pid nil)))

;; --- Worker setup ---

(defn ensure-robocode-copy!
  "Create an APFS clone of the robocode installation for a worker.
   Local mode: cp -Rc ~/robocode .tmp/robocode-N, returns absolute path.
   Remote mode: cp -Rc on remote machine, returns remote path."
  [ctx worker-id]
  (if (= :remote (:mode ctx))
    (let [target (format "/tmp/robocode-%d" worker-id)
          robo-home (:robocode-home ctx)]
      (ssh! ctx (str "rm -rf " target
                     " && cp -Rc " robo-home " " target))
      (when-let [cpu (:cpu-constant ctx)]
        (ssh! ctx (format "sed -i '' 's/robocode.cpu.constant=.*/robocode.cpu.constant=%d/' %s/config/robocode.properties" cpu target)))
      target)
    (let [target (format ".tmp/robocode-%d" worker-id)
          abs-target (str (fs/absolutize target))]
      (fs/delete-tree target)
      (fs/create-dirs ".tmp")
      (p/shell {:out :string :err :string}
               "cp" "-Rc" (str (fs/expand-home "~/robocode")) target)
      abs-target)))

;; --- Shutdown hooks ---

(def ^:private shutdown-hook (atom nil))

(defn register-shutdown-hook!
  "Register a JVM shutdown hook. cleanup-fn is called on Ctrl-C."
  [cleanup-fn]
  (when-not @shutdown-hook
    (let [hook (Thread. ^Runnable cleanup-fn)]
      (reset! shutdown-hook hook)
      (.addShutdownHook (Runtime/getRuntime) hook))))

(defn unregister-shutdown-hook!
  "Remove the shutdown hook after normal cleanup."
  []
  (when-let [hook @shutdown-hook]
    (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch Exception _))
    (reset! shutdown-hook nil)))