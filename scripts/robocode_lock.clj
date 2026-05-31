(ns robocode-lock
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def lock-dir ".tmp")

(defn- lock-path
  "Return the queue lock path for a robocode execution scope."
  [scope]
  (str lock-dir "/robocode-" (name scope) ".lock"))

(defn- lock-options
  "Open options for the robocode coordination lock."
  []
  (into-array java.nio.file.OpenOption
              [java.nio.file.StandardOpenOption/CREATE
               java.nio.file.StandardOpenOption/READ
               java.nio.file.StandardOpenOption/WRITE]))

(defn- read-lock-owner
  "Read metadata for the task currently holding the queue lock."
  [lock-path]
  (when (fs/exists? lock-path)
    (let [content (str/trim (slurp lock-path))]
      (when (seq content)
        (try
          (edn/read-string content)
          (catch Exception _
            {:raw content}))))))

(defn- format-lock-owner
  "Format lock metadata for queue status output."
  [owner]
  (if-let [task (:task owner)]
    (format "%s, pid %s, started %s"
            task
            (:pid owner "?")
            (:started-at owner "?"))
    (pr-str owner)))

(defn- lock-scope-label
  "Describe a queue lock scope for user-facing status output."
  [scope]
  (case scope
    :local "local robocode task"
    :remote "remote robocode task"
    (str (name scope) " robocode task")))

(defn- announce-queued!
  "Tell the user this task is waiting behind another robocode task."
  [lock-path scope label]
  (let [scope-label (lock-scope-label scope)]
    (if-let [owner (read-lock-owner lock-path)]
      (println (format "Another %s is running (%s). Queued: %s"
                       scope-label
                       (format-lock-owner owner)
                       label))
      (println (format "Another %s is running. Queued: %s"
                       scope-label
                       label)))))

(defn- wait-for-lock!
  "Wait until the robocode queue lock is available."
  [channel lock-path scope label]
  (announce-queued! lock-path scope label)
  (let [lock (.lock channel)]
    (println (format "Robocode queue ready: %s" label))
    lock))

(defn- write-lock-owner!
  "Record the task holding the queue lock."
  [channel label]
  (let [content (pr-str {:pid (.pid (java.lang.ProcessHandle/current))
                         :started-at (str (java.time.Instant/now))
                         :task label})]
    (.truncate channel 0)
    (.position channel 0)
    (.write channel (java.nio.ByteBuffer/wrap (.getBytes content "UTF-8")))
    (.force channel true)))

(defn- clear-lock-owner!
  "Clear lock metadata before releasing the queue lock."
  [channel]
  (.truncate channel 0)
  (.force channel true))

(defn- acquire-lock!
  "Acquire the robocode queue lock, waiting if another task holds it."
  [channel lock-path scope label]
  (let [lock (or (.tryLock channel)
                 (wait-for-lock! channel lock-path scope label))]
    (write-lock-owner! channel label)
    lock))

(defn with-robocode-lock
  "Run f while holding the robocode queue lock for scope."
  [scope label f]
  (fs/create-dirs lock-dir)
  (let [path (lock-path scope)]
    (with-open [channel (java.nio.channels.FileChannel/open
                         (.toPath (fs/file path))
                         (lock-options))]
      (let [_lock (acquire-lock! channel path scope label)]
        (try
          (f)
          (finally
            (clear-lock-owner! channel)))))))
