(ns benchmark
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def robocode-home (str (fs/expand-home "~/robocode")))

(def benchmark-bots
  {;; Bullet shielders/catchers — Pugilist's worst weakness
   :shielders
   ["dsekercioglu.shield.ColdBreath"
    "rsim.micro.uCatcher"
    "wiki.BasicBulletShielder"
    "rsim.mini.BulletCatcher"]

   ;; Top mini bots — competitive peers
   :top-minis
   ["sheldor.mini.FoilistMC"
    "sheldor.mini.Foilist"
    "voidious.mini.Komarious"
    "simonton.mini.WeeksOnEnd"
    "mld.LittleBlackBook"]

   ;; Bots where we underperform (negative KNNPBI)
   :underperform
   ["dft.Guppy"
    "exauge.Leopard"
    "nz.jdc.nano.PatternAdept"
    "wiki.mini.GouldingiHT"]

   ;; Bots we beat well — regression canaries
   :canaries
   ["sample.Crazy"
    "sample.SpinBot"
    "sample.Walls"]})

(def all-opponents
  (into [] cat (vals benchmark-bots)))

(defn- create-battle-file! [bot opponent rounds]
  (let [path (str (fs/absolutize ".tmp/benchmark.battle"))
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

(defn- run-battle! [battle-file]
  (let [results-path (str (fs/absolutize ".tmp/benchmark-results.txt"))]
    (p/shell {:dir robocode-home
              :out :string
              :err :string}
             "java"
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

(defn- parse-results [results-text]
  (let [lines (str/split-lines (str/trim results-text))
        data-lines (drop 2 lines)]
    (when (seq data-lines)
      (mapv (fn [line]
              (let [[_ rank name score pct] (re-find #"(\d+)\w+:\s+(\S+.*\S)\s+(\d+)\s+\((\d+)%\)" line)]
                {:name (str/trim (or name ""))
                 :score (parse-long (or score "0"))
                 :pct (parse-long (or pct "0"))
                 :rank (parse-long (or rank "0"))}))
            data-lines))))

(defn- find-category [opponent]
  (some (fn [[cat bots]]
          (when (some #(= opponent %) bots)
            cat))
        benchmark-bots))

(defn- run-pairing! [bot opponent rounds]
  (let [battle-file (create-battle-file! bot opponent rounds)
        results-text (run-battle! battle-file)
        results (parse-results results-text)]
    (when results
      (let [bot-result (first (filter #(str/starts-with? (:name %) bot) results))
            opp-result (first (filter #(not (str/starts-with? (:name %) bot)) results))]
        (when (and bot-result opp-result)
          (let [total (+ (:score bot-result) (:score opp-result))
                aps (if (pos? total)
                      (* 100.0 (/ (:score bot-result) total))
                      0.0)]
            {:opponent opponent
             :category (find-category opponent)
             :aps aps
             :bot-score (:score bot-result)
             :opp-score (:score opp-result)
             :win? (> (:score bot-result) (:score opp-result))}))))))

(defn- print-category-results [category pairings]
  (let [label (str/replace (name category) "-" " ")]
    (println (format "\n  %s" (str/upper-case label)))
    (println (str "  " (apply str (repeat 60 "-"))))
    (doseq [p (sort-by :aps pairings)]
      (println (format "  %-40s %6.2f%% APS  %s"
                       (:opponent p)
                       (:aps p)
                       (if (:win? p) "WIN" "LOSS"))))
    (let [avg (/ (reduce + (map :aps pairings)) (count pairings))]
      (println (format "  %-40s %6.2f%% avg" "" avg))
      avg)))

(defn- save-results! [bot rounds results timestamp]
  (let [path (format "plans/benchmark-%s.edn" timestamp)
        data {:bot bot
              :rounds rounds
              :timestamp timestamp
              :pairings (mapv #(dissoc % :win?) results)
              :overall-aps (/ (reduce + (map :aps results)) (count results))
              :by-category (->> results
                                (group-by :category)
                                (into {} (map (fn [[k v]]
                                                [k (/ (reduce + (map :aps v)) (count v))]))))}]
    (spit path (pr-str data))
    (println (format "\nResults saved to %s" path))))

(defn benchmark!
  "Run benchmark battles and report APS per category.
   Options: :bot - fully qualified bot name
            :rounds - rounds per battle (default: 100)"
  [{:keys [bot rounds]
    :or {rounds 100}}]
  (when-not bot
    (println "Usage: bb benchmark <bot> [rounds]")
    (println "Example: bb benchmark pez.mini.Pugilist")
    (println "         bb benchmark pez.mini.Pugilist 200")
    (System/exit 1))
  (let [opponents all-opponents
        total (count opponents)
        timestamp (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))]
    (println (format "Benchmark: %d opponents, %d rounds each, bot: %s\n" total rounds bot))
    (let [results (doall
                   (keep-indexed
                    (fn [i opponent]
                      (print (format "  [%2d/%d] vs %-40s" (inc i) total opponent))
                      (flush)
                      (let [result (run-pairing! bot opponent rounds)]
                        (if result
                          (do (println (format "%6.2f%% APS" (:aps result)))
                              result)
                          (do (println "FAILED")
                              nil))))
                    opponents))]
      (println (str "\n" (apply str (repeat 66 "="))))
      (println (format "BENCHMARK RESULTS — %s — %d rounds" bot rounds))
      (println (apply str (repeat 66 "=")))
      (let [by-cat (group-by :category results)
            cat-avgs (doall (map (fn [cat]
                                  (when-let [pairings (get by-cat cat)]
                                    [cat (print-category-results cat pairings)]))
                                [:shielders :top-minis :underperform :canaries]))]
        (println (str "\n  " (apply str (repeat 60 "="))))
        (let [overall (/ (reduce + (map :aps results)) (count results))]
          (println (format "  %-40s %6.2f%% OVERALL APS" "" overall))
          (println (format "  %-40s %d/%d wins" "" (count (filter :win? results)) (count results))))
        (println)
        (save-results! bot rounds results timestamp))
      (fs/delete-if-exists ".tmp/benchmark.battle")
      (fs/delete-if-exists ".tmp/benchmark-results.txt"))))
