(ns extract-bot
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def robots-dir (str (fs/expand-home "~/robocode/robots")))
(def participants-file (str (fs/expand-home "~/robocode/roborumble/files/particip1v1.txt")))
(def target-dir "research/bots")

(defn- lookup-version [bot-name]
  (let [prefix (str bot-name " ")]
    (->> (slurp participants-file)
         str/split-lines
         (some #(when (str/starts-with? % prefix)
                  (-> (subs % (count prefix))
                      (str/split #",")
                      first))))))

(defn- jar-path [bot-name version]
  (str robots-dir "/" bot-name "_" version ".jar"))

(defn extract!
  "Extract source files from a bot jar into research/bots/<bot-name>/.
   Looks up the version from the RoboRumble participants file.
   Options: :bot - bot name (e.g. sheldor.mini.Foilist)"
  [{:keys [bot]}]
  (when-not bot
    (println "Usage: bb extract-bot <bot-name>")
    (println "Example: bb extract-bot sheldor.mini.Foilist")
    (System/exit 1))
  (let [version (lookup-version bot)]
    (when-not version
      (println (format "Bot '%s' not found in %s" bot participants-file))
      (System/exit 1))
    (let [jar (jar-path bot version)
          dest-dir (str target-dir "/" bot)]
      (when-not (fs/exists? jar)
        (println (format "Jar not found: %s" jar))
        (println "Run: bb install" bot)
        (System/exit 1))
      (fs/create-dirs dest-dir)
      (p/shell "unzip" "-o" "-j" "-d" dest-dir jar "*.java")
      (println (format "Extracted source from %s_%s.jar → %s/" bot version dest-dir)))))