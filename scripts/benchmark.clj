(ns benchmark
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [codesize]))

(def robocode-home (str (fs/expand-home "~/robocode")))
(def java-home "/Users/pez/.sdkman/candidates/java/17.0.17-tem")

(defn- resolve-commit
  "Resolve a commit ref to its short hash and subject line."
  [ref]
  (let [result (p/shell {:out :string :err :string}
                        "git" "log" "-1" "--format=%h %s" ref)]
    (str/trim (:out result))))

(defn- deploy-jar!
  "Create bot jar from build output and deploy to Robocode."
  [bot build-dir]
  (let [ns-path (str/replace bot "." "/")
        class-dir (str build-dir "/classes/java/main/" (subs ns-path 0 (str/last-index-of ns-path "/")))
        class-files (->> (fs/list-dir class-dir)
                         (map str)
                         (filter #(str/ends-with? % ".class")))
        props-file (str build-dir "/classes/java/main/" ns-path ".properties")
        jar-name (str bot "_2.5.5.jar")
        jar-path (str robocode-home "/robots/" jar-name)
        classes-root (str build-dir "/classes/java/main")
        abs-root (str (fs/absolutize classes-root))
        entries (cond-> (mapv #(subs (str (fs/absolutize %)) (inc (count abs-root))) class-files)
                  (fs/exists? props-file) (conj (subs (str (fs/absolutize props-file))
                                                      (inc (count abs-root)))))]
    (apply p/shell {:dir classes-root} "jar" "cf" jar-path entries)
    (fs/delete-if-exists (str robocode-home "/robots/robot.database"))))

(defn- build-and-deploy!
  "Build the bot and deploy its jar to Robocode. If commit is provided,
   uses git worktree to build in an isolated directory."
  [bot commit]
  (if commit
    (let [worktree-dir (str (fs/absolutize ".tmp/benchmark-worktree"))]
      (try
        (println (format "Creating worktree for %s..." commit))
        (fs/delete-tree worktree-dir)
        (p/shell {:out :string :err :string}
                 "git" "worktree" "add" "--detach" worktree-dir commit)
        (println "Building...")
        (p/shell {:dir worktree-dir
                  :out :string :err :string
                  :extra-env {"JAVA_HOME" java-home}}
                 "./gradlew" "clean" "build")
        (println "Deploying to Robocode...")
        (deploy-jar! bot (str worktree-dir "/build"))
        (println "Ready.\n")
        (finally
          (fs/delete-tree worktree-dir)
          (p/shell {:out :string :err :string}
                   "git" "worktree" "prune"))))
    (do
      (println "Building...")
      (p/shell {:out :string :err :string
                :extra-env {"JAVA_HOME" java-home}}
               "./gradlew" "clean" "build")
      (println "Deploying to Robocode...")
      (deploy-jar! bot "build")
      (println "Ready.\n"))))

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

(defn- save-results! [bot rounds results timestamp commit-info elapsed-s]
  (let [path (format "plans/benchmark-%s.edn" timestamp)
        data (cond-> {:bot bot
                      :rounds rounds
                      :timestamp timestamp
                      :elapsed-seconds elapsed-s
                      :pairings (mapv #(dissoc % :win?) results)
                      :overall-aps (/ (reduce + (map :aps results)) (count results))
                      :by-category (->> results
                                        (group-by :category)
                                        (into {} (map (fn [[k v]]
                                                        [k (/ (reduce + (map :aps v)) (count v))]))))}
               commit-info (assoc :commit commit-info))]
    (spit path (pr-str data))
    (println (format "\nResults saved to %s" path))))

(defn benchmark!
  "Run benchmark battles and report APS per category.
   Options: :bot - fully qualified bot name
            :rounds - rounds per battle (default: 100)
            :commit - optional git ref to benchmark (default: working tree)"
  [{:keys [bot rounds commit]
    :or {rounds 100}}]
  (when-not bot
    (println "Usage: bb benchmark <bot> [rounds] [commit]")
    (println "Example: bb benchmark pez.mini.Pugilist")
    (println "         bb benchmark pez.mini.Pugilist 200")
    (println "         bb benchmark pez.mini.Pugilist 100 HEAD~3")
    (System/exit 1))
  (let [commit-info (when commit (resolve-commit commit))]
    (build-and-deploy! bot commit)
    (let [jar-path (str robocode-home "/robots/" bot "_2.5.5.jar")
          bot-codesize (codesize/get-size jar-path)
          opponents all-opponents
          total (count opponents)
          start-ms (System/currentTimeMillis)
          timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))]
      (println (format "Benchmark: %d opponents, %d rounds each, bot: %s%s%s\n"
                       total rounds bot
                       (if commit-info (str " @ " commit-info) "")
                       (if bot-codesize (str " [" bot-codesize " bytes]") "")))
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
        (println (format "BENCHMARK RESULTS — %s — %d rounds%s"
                         bot rounds
                         (if commit-info (str " — " commit-info) "")))
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
          (let [elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)]
            (println (format "  %-40s %s elapsed" "" (format "%d:%02d" (int (/ elapsed-s 60)) (int (mod elapsed-s 60)))))
            (println)
            (save-results! bot rounds results timestamp commit-info elapsed-s)))
        (fs/delete-if-exists ".tmp/benchmark.battle")
        (fs/delete-if-exists ".tmp/benchmark-results.txt")))))
