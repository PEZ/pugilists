(ns battle
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def robocode-home (str (fs/expand-home "~/robocode")))

(def reference-bots
  ["sample.Crazy" "sample.SpinBot" "sample.Walls"
   "sample.Tracker" "sample.Fire" "sample.Corners"])

(defn- create-battle-file!
  "Create a temporary .battle file for a 1v1 matchup."
  [bot opponent rounds]
  (let [path (str (fs/absolutize ".tmp/test.battle"))
        content (str "#Battle Properties\n"
                     "robocode.battleField.width=800\n"
                     "robocode.battleField.height=600\n"
                     "robocode.battle.numRounds=" rounds "\n"
                     "robocode.battle.gunCoolingRate=0.1\n"
                     "robocode.battle.rules.inactivityTime=450\n"
                     "robocode.battle.hideEnemyNames=true\n"
                     "robocode.battle.selectedRobots=" bot "," opponent "\n")]
    (fs/create-dirs ".tmp")
    (spit path content)
    path))

(defn- run-battle!
  "Run a headless battle and return the results text."
  [battle-file]
  (let [results-path (str (fs/absolutize ".tmp/results.txt"))]
    (p/shell {:dir robocode-home
              :out :string
              :err :string}
             "java"
             "-Djava.awt.headless=true"
             "-cp" "libs/*"
             "-Xmx512M"
             "-XX:+IgnoreUnrecognizedVMOptions"
             "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
             "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
             "robocode.Robocode"
             "-nodisplay" "-nosound"
             "-battle" battle-file
             "-results" results-path)
    (when (fs/exists? results-path)
      (slurp results-path))))

(defn- parse-results
  "Parse Robocode results file into maps."
  [results-text]
  (let [lines (str/split-lines (str/trim results-text))
        data-lines (drop 2 lines)] ;; skip "Results for N rounds" and header
    (when (seq data-lines)
      (mapv (fn [line]
              (let [[_ rank name score pct] (re-find #"(\d+)\w+:\s+(\S+.*\S)\s+(\d+)\s+\((\d+)%\)" line)]
                {:name (str/trim (or name ""))
                 :score (parse-long (or score "0"))
                 :pct (parse-long (or pct "0"))
                 :rank (parse-long (or rank "0"))}))
            data-lines))))

(defn- print-results [results bot]
  (doseq [r results]
    (println (format "  %-40s Score: %5d (%2d%%)" (:name r) (:score r) (:pct r))))
  (let [bot-result (first (filter #(str/starts-with? (:name %) bot) results))
        opponent-result (first (filter #(not (str/starts-with? (:name %) bot)) results))]
    (when (and bot-result opponent-result)
      (if (> (:score bot-result) (:score opponent-result))
        (println "  -> WIN")
        (println "  -> LOSS")))))

(defn battle!
  "Run battles against reference bots.
   Options: :bot - fully qualified bot name
            :opponents - seq of opponents (default: reference-bots)
            :rounds - rounds per battle (default: 35)"
  [{:keys [bot opponents rounds]
    :or {rounds 35}}]
  (when-not bot
    (println "Usage: bb battle <bot> [rounds]")
    (println "Example: bb battle pez.mini.Pugilist")
    (println "         bb battle pez.mini.Pugilist 100")
    (System/exit 1))
  (let [opponents (or opponents reference-bots)
        wins (atom 0)
        total (count opponents)]
    (println (format "Running %d battles (%d rounds each) for %s\n" total rounds bot))
    (doseq [opponent opponents]
      (println (format "vs %s:" opponent))
      (let [battle-file (create-battle-file! bot opponent rounds)
            results-text (run-battle! battle-file)
            results (parse-results results-text)]
        (if results
          (do
            (print-results results bot)
            (let [bot-result (first (filter #(str/starts-with? (:name %) bot) results))
                  opp-result (first (filter #(not (str/starts-with? (:name %) bot)) results))]
              (when (and bot-result opp-result
                         (> (:score bot-result) (:score opp-result)))
                (swap! wins inc))))
          (println "  No results (battle may have failed)"))
        (println)))
    (println (format "Results: %d/%d wins" @wins total))
    (fs/delete-if-exists ".tmp/test.battle")
    (fs/delete-if-exists ".tmp/results.txt")))
