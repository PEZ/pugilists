(ns benchmark
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [codesize]))

(def ^:dynamic *robocode-home* (str (fs/expand-home "~/robocode")))
(def ^:dynamic *worker-id* 0)
(def num-workers 5)
(def java-home "/Users/pez/.sdkman/candidates/java/21.0.11-amzn")

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
          jar-path (str *robocode-home* "/robots/" jar-name)
        classes-root (str build-dir "/classes/java/main")
        abs-root (str (fs/absolutize classes-root))
        entries (cond-> (mapv #(subs (str (fs/absolutize %)) (inc (count abs-root))) class-files)
                  (fs/exists? props-file) (conj (subs (str (fs/absolutize props-file))
                                                      (inc (count abs-root)))))]
    (apply p/shell {:dir classes-root} "jar" "cf" jar-path entries)
      (fs/delete-if-exists (str *robocode-home* "/robots/robot.database"))))
(defn- build-and-deploy!
  "Build the bot and deploy its jar to Robocode. If commit is provided,
   uses git worktree to build in an isolated directory."
  [bot commit]
  (if commit
      (let [worktree-dir (str (fs/absolutize (format ".tmp/benchmark-worktree-%d" *worker-id*)))]
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

(def default-roster "config/benchmark-roster.edn")

(defn- load-roster [path]
  (let [roster (edn/read-string (slurp path))]
    (when-not (and (map? roster) (every? vector? (vals roster)))
      (throw (ex-info "Roster must be a map of category keywords to vectors of bot names" {:path path})))
    roster))

(defn- find-category [opponent roster]
  (some (fn [[cat bots]]
          (when (some #(= opponent %) bots)
            cat))
        roster))

(defn- create-battle-file! [bot opponent rounds]
    (let [path (str (fs/absolutize (format ".tmp/benchmark-%d.battle" *worker-id*)))
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
    (let [results-path (str (fs/absolutize (format ".tmp/benchmark-results-%d.txt" *worker-id*)))
          java-cmd (str java-home "/bin/java")]
      (p/shell {:dir *robocode-home*
              :out :string
              :err :string}
             java-cmd
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

(defn- stddev [values mean]
  (if (<= (count values) 1)
    0.0
    (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) values))
                  (dec (count values))))))

(defn- run-single-match! [bot opponent rounds]
  (let [battle-file (create-battle-file! bot opponent rounds)
        results-text (run-battle! battle-file)
        results (parse-results results-text)]
    (when results
      (let [bot-result (first (filter #(str/starts-with? (:name %) bot) results))
            opp-result (first (filter #(not (str/starts-with? (:name %) bot)) results))]
        (when (and bot-result opp-result)
          (let [total (+ (:score bot-result) (:score opp-result))]
            (if (pos? total)
              (* 100.0 (/ (:score bot-result) total))
              0.0)))))))

(defn- run-pairing! [bot opponent rounds match-length roster]
  (let [num-matches (max 1 (quot rounds match-length))
        aps-values (doall
                    (keep (fn [_] (run-single-match! bot opponent match-length))
                          (range num-matches)))]
    (when (seq aps-values)
      (let [mean (/ (reduce + aps-values) (count aps-values))
            sd (stddev aps-values mean)]
        {:opponent opponent
         :category (find-category opponent roster)
         :aps mean
         :min-aps (apply min aps-values)
         :max-aps (apply max aps-values)
         :stddev sd
         :matches (count aps-values)
         :win? (> mean 50.0)}))))

(defn- print-category-results [category pairings]
  (let [label (str/replace (name category) "-" " ")]
    (println (format "\n  %s" (str/upper-case label)))
    (println (str "  " (apply str (repeat 60 "-"))))
    (doseq [p (sort-by :aps pairings)]
      (println (format "  %-40s %6.2f%% APS  (%.1f-%.1f ±%.1f)  %s"
                       (:opponent p)
                       (:aps p)
                       (:min-aps p)
                       (:max-aps p)
                       (:stddev p)
                       (if (:win? p) "WIN" "LOSS"))))
    (let [avg (/ (reduce + (map :aps pairings)) (count pairings))]
      (println (format "  %-40s %6.2f%% avg" "" avg))
      avg)))

(defn- save-results! [bot rounds match-length results timestamp commit-info elapsed-s]
  (let [path (format "research/benchmarks/logs/benchmark-%s.edn" timestamp)
        data (cond-> {:bot bot
                      :rounds rounds
                      :match-length match-length
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

(defn- ensure-robocode-copy!
  "Create an APFS clone of the robocode installation for a worker."
  [worker-id]
  (let [target (format ".tmp/robocode-%d" worker-id)
        abs-target (str (fs/absolutize target))]
    (fs/delete-tree target)
    (fs/create-dirs ".tmp")
    (p/shell {:out :string :err :string}
             "cp" "-Rc" (str (fs/expand-home "~/robocode")) target)
    abs-target))

(defn benchmark!
  "Run benchmark battles and report APS per category.
   Options: :bot - fully qualified bot name
            :rounds - total rounds per opponent (default: 100)
            :match-length - rounds per match (default: 35, like LiteRumble)
            :commit - optional git ref to benchmark (default: working tree)
            :roster - path to roster EDN file (default: config/benchmark-roster.edn)"
  [{:keys [bot rounds match-length commit roster]
    :or {rounds 105 match-length 35}}]
  (when-not bot
    (println "Usage: bb benchmark <bot> [rounds] [match-length] [commit] [roster]")
    (println "Example: bb benchmark pez.mini.Pugilist")
    (println "         bb benchmark pez.mini.Pugilist 100")
    (println "         bb benchmark pez.mini.Pugilist 100 10")
    (println "         bb benchmark pez.mini.Pugilist 100 10 HEAD~3")
    (println "         bb benchmark pez.mini.Pugilist 100 10 - config/my-roster.edn")
    (System/exit 1))
  (let [roster-path (or roster default-roster)
        roster-data (load-roster roster-path)
        commit-info (when commit (resolve-commit commit))
        num-matches (max 1 (quot rounds match-length))]
    (build-and-deploy! bot commit)
    (let [jar-path (str *robocode-home* "/robots/" bot "_2.5.5.jar")
          bot-codesize (codesize/get-size jar-path)
          opponents (into [] cat (vals roster-data))
          total (count opponents)
          n-workers (min num-workers total)
          start-ms (System/currentTimeMillis)
          timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))
          ;; Worker 0 = original ~/robocode, workers 1..n-1 = APFS clones
          _ (when (> n-workers 1)
              (println (format "Setting up %d robocode instances..." n-workers)))
          worker-homes (into [*robocode-home*]
                             (map ensure-robocode-copy!)
                             (range 1 n-workers))
          ;; Round-robin shard opponents across workers
          shards (reduce (fn [acc [i opp]]
                           (update acc (mod i n-workers) conj opp))
                         (vec (repeat n-workers []))
                         (map-indexed vector opponents))
          opp->index (into {} (map-indexed (fn [i o] [o i]) opponents))]
      (println (format "Benchmark: %d opponents, %d×%d rounds each, %d workers, bot: %s%s%s\n"
                       total num-matches match-length n-workers bot
                       (if commit-info (str " @ " commit-info) "")
                       (if bot-codesize (str " [" bot-codesize " bytes]") "")))
      (let [progress (atom 0)
            futures (mapv (fn [wid]
                            (let [shard (nth shards wid)
                                  home (nth worker-homes wid)]
                              (future
                                (binding [*robocode-home* home
                                          *worker-id* wid]
                                  (doall
                                   (keep (fn [opponent]
                                           (let [result (run-pairing! bot opponent rounds match-length roster-data)
                                                 done (swap! progress inc)]
                                             (locking *out*
                                               (if result
                                                 (println (format "  [%2d/%d] vs %-40s %6.2f%% APS  (%.1f–%.1f ±%.1f)"
                                                                  done total opponent
                                                                  (:aps result) (:min-aps result) (:max-aps result) (:stddev result)))
                                                 (println (format "  [%2d/%d] vs %-40s FAILED"
                                                                  done total opponent)))
                                               (flush))
                                             result))
                                         shard))))))
                          (range n-workers))
            results-by-worker (mapv deref futures)
            all-results (sort-by #(get opp->index (:opponent %))
                                 (into [] cat results-by-worker))]
        (println (str "\n" (apply str (repeat 66 "="))))
        (println (format "BENCHMARK RESULTS — %s — %d×%d rounds%s"
                         bot num-matches match-length
                         (if commit-info (str " — " commit-info) "")))
        (println (apply str (repeat 66 "=")))
        (let [by-cat (group-by :category all-results)
              cat-avgs (doall (map (fn [cat]
                                    (when-let [pairings (get by-cat cat)]
                                      [cat (print-category-results cat pairings)]))
                                  (keys roster-data)))]
          (println (str "\n  " (apply str (repeat 60 "="))))
          (let [overall (/ (reduce + (map :aps all-results)) (count all-results))]
            (println (format "  %-40s %6.2f%% OVERALL APS" "" overall))
            (println (format "  %-40s %d/%d wins" "" (count (filter :win? all-results)) (count all-results))))
          (let [elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)]
            (println (format "  %-40s %s elapsed" "" (format "%d:%02d" (int (/ elapsed-s 60)) (int (mod elapsed-s 60)))))
            (println)
            (save-results! bot rounds match-length all-results timestamp commit-info elapsed-s)))
        (doseq [wid (range n-workers)]
          (fs/delete-if-exists (format ".tmp/benchmark-%d.battle" wid))
          (fs/delete-if-exists (format ".tmp/benchmark-results-%d.txt" wid)))))))

(defn- run-benchmark-worker!
  "Run a complete benchmark for a single ref using a specific robocode copy.
   Designed for parallel execution - minimal stdout output."
  [bot rounds match-length ref worker-id robo-home roster-data timestamp]
  (binding [*robocode-home* robo-home
            *worker-id* worker-id]
    (let [label (or ref "current")
          jar-path (str robo-home "/robots/" bot "_2.5.5.jar")
          bot-codesize (codesize/get-size jar-path)
          opponents (into [] cat (vals roster-data))
          total (count opponents)
          num-matches (max 1 (quot rounds match-length))
          start-ms (System/currentTimeMillis)]
      (locking *out*
        (println (format "  [%d] %s: %d opponents, %d×%d rounds%s"
                         worker-id label total num-matches match-length
                         (if bot-codesize (str " [" bot-codesize " bytes]") ""))))
      (let [results (doall
                     (keep-indexed
                      (fn [i opponent]
                        (run-pairing! bot opponent rounds match-length roster-data))
                      opponents))
            elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)
            overall (/ (reduce + (map :aps results)) (count results))
            wins (count (filter :win? results))
            commit-info (when ref (resolve-commit ref))
            save-ts (format "%s-%s" timestamp (str/replace label #"[^a-zA-Z0-9._-]" "_"))]
        (save-results! bot rounds match-length results save-ts commit-info elapsed-s)
        (fs/delete-if-exists (format ".tmp/benchmark-%d.battle" worker-id))
        (fs/delete-if-exists (format ".tmp/benchmark-results-%d.txt" worker-id))
        (locking *out*
          (println (format "  [%d] %s: %.2f%% APS, %d/%d wins (%.0fs)"
                           worker-id label overall wins (count results) elapsed-s)))
        {:label label
         :overall overall
         :wins wins
         :total (count results)
         :codesize bot-codesize
         :elapsed elapsed-s}))))

(defn benchmark-parallel!
  "Run benchmarks for multiple git refs in parallel.
   Options: :bot - fully qualified bot name
            :rounds - total rounds per opponent (default: 105)
            :match-length - rounds per match (default: 35)
            :refs - vector of git refs (nil = current working tree)"
  [{:keys [bot rounds match-length refs]
    :or {rounds 105 match-length 35}}]
  (when (or (not bot) (empty? refs))
    (println "Usage: bb benchmark-parallel <bot> <rounds> <ref1> [ref2] ... [current]")
    (println "Example: bb benchmark-parallel pez.mini.Pugilist 105 pez.mini.Pugilist_2.5.4 current")
    (System/exit 1))
  (let [roster-data (load-roster default-roster)
        n (count refs)
        num-matches (max 1 (quot rounds match-length))
        opponents (into [] cat (vals roster-data))
        timestamp (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))]
    (println (format "Parallel benchmark: %d versions, %d opponents, %d×%d rounds\n"
                     n (count opponents) num-matches match-length))
    ;; Phase 1: Setup robocode copies and build all versions (sequential)
    (println "Phase 1: Build")
    (let [robo-homes
          (mapv (fn [[i ref]]
                  (let [label (or ref "current")
                        _ (println (format "  [%d] Setting up robocode copy for %s..." i label))
                        robo-home (ensure-robocode-copy! i)]
                    (println (format "  [%d] Building %s..." i label))
                    (binding [*robocode-home* robo-home
                              *worker-id* i]
                      (build-and-deploy! bot ref))
                    (println (format "  [%d] %s ready" i label))
                    robo-home))
                (map-indexed vector refs))]
      ;; Phase 2: Run benchmarks in parallel
      (println "\nPhase 2: Benchmark (parallel)")
      (let [futures
            (mapv (fn [[i ref]]
                    (let [robo-home (nth robo-homes i)]
                      (future
                        (try
                          (run-benchmark-worker! bot rounds match-length ref i robo-home roster-data timestamp)
                          (catch Exception e
                            (locking *out*
                              (println (format "  [%d] %s FAILED: %s" i (or ref "current") (.getMessage e))))
                            nil)))))
                  (map-indexed vector refs))
            results (mapv deref futures)]
        ;; Phase 3: Summary
        (println (str "\n" (apply str (repeat 66 "="))))
        (println "PARALLEL BENCHMARK SUMMARY")
        (println (apply str (repeat 66 "=")))
        (doseq [r (remove nil? results)]
          (println (format "  %-20s %6.2f%% APS  %d/%d wins  [%s bytes]  (%.0fs)"
                           (:label r) (:overall r) (:wins r) (:total r)
                           (or (:codesize r) "?") (:elapsed r))))
        (println (format "\n  Robocode copies in .tmp/robocode-{0..%d} (rm -rf .tmp/robocode-* to clean)" (dec n)))
        (println)))))
