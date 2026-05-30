(ns benchmark
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [codesize]
            [remote]))

(def ^:dynamic *robocode-home* (str (fs/expand-home "~/robocode")))
(def ^:dynamic *worker-id* 0)

(defn- kill-remote-battles!
  "Kill any remote Java processes running benchmark battles."
  []
  (when-let [ctx @remote/remote-ctx]
    (try (remote/ssh! ctx "pkill -f 'benchmark-[0-9]+\\.battle' 2>/dev/null; true") (catch Exception _))))

;; Backward-compat vars; replaced by ctx in Phase 4
(def num-workers 5)
(def java-home (str (fs/expand-home "~/.sdkman/candidates/java/21.0.11-amzn")))

(defn- resolve-commit
  "Resolve a commit ref to its short hash and subject line."
  [ref]
  (let [result (p/shell {:out :string :err :string}
                        "git" "log" "-1" "--format=%h %s" ref)]
    (str/trim (:out result))))

(defn- source-file-attr
  "Returns the SourceFile attribute of a compiled class via javap."
  [classes-root class-name]
  (let [result (p/shell {:out :string :err :string :continue true}
                        "javap" "-classpath" classes-root "-verbose" class-name)
        line (first (filter #(str/includes? % "SourceFile:") (str/split-lines (:out result))))]
    (when line
      (second (re-find #"SourceFile:\s+\"(.+)\"" line)))))

(defn- read-robot-version
  "Read robot.version from a bot's built .properties file, or nil if absent."
  [props-file]
  (when (fs/exists? props-file)
    (some->> (slurp props-file)
             str/split-lines
             (some #(second (re-find #"^robot\.version=(.+)$" %)))
             str/trim)))

(defn- check-version-collision!
  "Throw if a released jar for this bot+version already exists among released-jars.
   Forgetting to bump robot.version makes Robocode load the stale released jar
   instead of the freshly built benched jar, silently contaminating results."
  [bot version released-jars]
  (when version
    (let [collision (str bot "_" version ".jar")]
      (when (contains? (set released-jars) collision)
        (throw (ex-info
                (str "\n\nVERSION COLLISION: " collision " already exists in the Robocode robots directory.\n"
                     "Robocode would load that released jar instead of your freshly built code,\n"
                     "silently contaminating the benchmark.\n"
                     "Fix: bump robot.version in src/" (str/replace bot "." "/")
                     ".properties, rebuild, and re-run the benchmark.\n")
                {:bot bot :version version :collision collision}))))))

(defn- deploy-jar!
  "Create bot jar from build output and deploy to Robocode.
   Local mode: copies jar directly to ~/robocode/robots/.
   Remote mode: builds jar to a temp path, scps to remote, deletes robot.database remotely."
  [ctx bot build-dir]
  (let [ns-path (str/replace bot "." "/")
        class-dir (str build-dir "/classes/java/main/" (subs ns-path 0 (str/last-index-of ns-path "/")))
        source-file (str (subs ns-path (inc (str/last-index-of ns-path "/"))) ".java")
        classes-root (str build-dir "/classes/java/main")
        abs-root (str (fs/absolutize classes-root))
        class-files (->> (fs/list-dir class-dir)
                         (map str)
                         (filter #(str/ends-with? % ".class"))
                         (filter (fn [f]
                                   (let [rel (subs (str (fs/absolutize f)) (inc (count abs-root)))
                                         cn (-> rel (str/replace ".class" "") (str/replace "/" "."))]
                                     (= (source-file-attr classes-root cn) source-file)))))
        props-file (str build-dir "/classes/java/main/" ns-path ".properties")
        jar-name (str bot "_benched.jar")
        entries (cond-> (mapv #(subs (str (fs/absolutize %)) (inc (count abs-root))) class-files)
                  (fs/exists? props-file) (conj (subs (str (fs/absolutize props-file))
                                                      (inc (count abs-root)))))]
    (if (= :remote (:mode ctx))
      (let [tmp-jar (str (fs/absolutize (str ".tmp/" jar-name)))
            remote-robots (str (:robocode-home ctx) "/robots/")]
        (check-version-collision! bot (read-robot-version props-file)
                                  (-> (remote/ssh! ctx (str "ls " remote-robots))
                                      str/split-lines))
        (fs/create-dirs ".tmp")
        (apply p/shell {:dir classes-root} "jar" "cf" tmp-jar entries)
        (remote/scp-to-remote! ctx tmp-jar (str remote-robots jar-name))
        (remote/ssh! ctx (str "rm -f " remote-robots "robot.database")))
      (let [jar-path (str *robocode-home* "/robots/" jar-name)]
        (check-version-collision! bot (read-robot-version props-file)
                                  (->> (fs/list-dir (str *robocode-home* "/robots/"))
                                       (map (comp str fs/file-name))
                                       (filter #(str/ends-with? % ".jar"))))
        (apply p/shell {:dir classes-root} "jar" "cf" jar-path entries)
        (fs/delete-if-exists (str *robocode-home* "/robots/robot.database"))))))
(defn- build-and-deploy!
  "Build the bot and deploy its jar to Robocode. If commit is provided,
   uses git worktree to build in an isolated directory."
  [ctx bot commit]
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
        (deploy-jar! ctx bot (str worktree-dir "/build"))
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
      (deploy-jar! ctx bot "build")
      (println "Ready.\n"))))

(def default-roster "config/benchmark-roster.edn")

(defn- bot-name
  "Extract bot name from a roster entry (string or {:bot name} map)."
  [entry]
  (if (map? entry) (:bot entry) entry))

(defn- load-roster [path]
  (let [roster (edn/read-string (slurp path))]
    (when-not (and (map? roster) (every? vector? (vals roster)))
      (throw (ex-info "Roster must be a map of category keywords to vectors" {:path path})))
    roster))

(defn- find-category [opponent roster]
  (some (fn [[cat bots]]
          (when (some #(= opponent (bot-name %)) bots)
            cat))
        roster))

(defn- find-roster-aps
  "Look up the LiteRumble APS for an opponent from roster data."
  [opponent roster]
  (some (fn [[_ bots]]
          (some (fn [entry]
                  (when (and (map? entry) (= opponent (:bot entry)))
                    (:aps entry)))
                bots))
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

(defn- run-battle-on!
  "Create battle file and run Robocode, returning results text.
   Local mode: writes battle file to .tmp/, runs java locally.
   Remote mode: pipes battle file content via stdin, then runs java in a
   separate SSH call — both using the ControlMaster socket."
  [ctx bot opponent rounds worker-id]
  (if (= :local (:mode ctx))
    (let [battle-file (create-battle-file! bot opponent rounds)]
      (run-battle! battle-file))
    (let [robo-home    *robocode-home*   ; worker-specific remote dir (dynamically bound)
          java-cmd     (str (:java-home ctx) "/bin/java")
          battle-path  (format "/tmp/benchmark-%d.battle" worker-id)
          results-path (format "/tmp/benchmark-results-%d.txt" worker-id)
          battle-content (str "#Battle Properties\n"
                              "robocode.battleField.width=800\n"
                              "robocode.battleField.height=600\n"
                              "robocode.battle.numRounds=" rounds "\n"
                              "robocode.battle.gunCoolingRate=0.1\n"
                              "robocode.battle.rules.inactivityTime=450\n"
                              "robocode.battle.hideEnemyNames=true\n"
                              "robocode.battle.selectedRobots=" bot "," opponent "\n")
          ssh-base      (cond-> ["ssh"]
                          @remote/ssh-control-path (into ["-o" (str "ControlPath=" @remote/ssh-control-path)])
                          true (conj (:host ctx)))]
      ;; Write battle file via stdin — avoids all quoting/escaping issues with content
      (apply p/shell {:in battle-content :out :string :err :string}
             (conj (vec ssh-base) (str "cat > " battle-path)))
      ;; Run Robocode, cat results, cleanup — all in one SSH call
      ;; Redirect Robocode stdout/stderr to /dev/null so only cat output comes through
      (remote/ssh! ctx (str "cd " robo-home
                            " && " java-cmd
                            " -Djava.awt.headless=true"
                            " -cp 'libs/*'"
                            " -Xmx512M"
                            " -XX:+IgnoreUnrecognizedVMOptions"
                            " --add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
                            " --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
                            " robocode.Robocode"
                            " -nodisplay -nosound"
                            " -battle " battle-path
                            " -results " results-path
                            " > /dev/null 2>&1"
                            " ; cat " results-path
                            " ; rm -f " battle-path " " results-path)))))

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

(defn- run-single-match! [ctx bot opponent rounds worker-id]
  (let [results-text (run-battle-on! ctx bot opponent rounds worker-id)
        results      (parse-results results-text)]
    (when results
      (let [bot-result (first (filter #(str/starts-with? (:name %) bot) results))
            opp-result (first (filter #(not (str/starts-with? (:name %) bot)) results))]
        (when (and bot-result opp-result)
          (let [total (+ (:score bot-result) (:score opp-result))]
            (if (pos? total)
              (* 100.0 (/ (:score bot-result) total))
              0.0)))))))

(defn- run-pairing! [ctx bot opponent rounds match-length roster]
  (let [num-matches (max 1 (quot rounds match-length))
        aps-values (doall
                    (keep (fn [_] (run-single-match! ctx bot opponent match-length *worker-id*))
                          (range num-matches)))]
    (when (seq aps-values)
      (let [mean (/ (reduce + aps-values) (count aps-values))
            sd (stddev aps-values mean)]
        {:opponent opponent
         :category (find-category opponent roster)
         :roster-aps (find-roster-aps opponent roster)
         :aps mean
         :min-aps (apply min aps-values)
         :max-aps (apply max aps-values)
         :stddev sd
         :matches (count aps-values)
         :win? (> mean 50.0)}))))

(defn- with-win-flag
  "Ensure a pairing has a :win? key (saved logs omit it)."
  [pairing]
  (if (contains? pairing :win?)
    pairing
    (assoc pairing :win? (> (:aps pairing) 50.0))))

(defn- category-order
  "Keep categories in first-seen order from pairings."
  [pairings]
  (->> pairings (map :category) distinct vec))

(defn- format-category-section
  "Render one category section in benchmark-output style.
   Returns {:lines [...] :avg n :avg-delta n?}."
  [category pairings]
  (let [label (str/replace (name category) "-" " ")
        sorted-pairings (sort-by :aps pairings)
        item-lines (mapv (fn [p]
                           (let [diff-str (if-let [ra (:roster-aps p)]
                                            (format "  Δ%+.1f" (- (:aps p) ra))
                                            "")]
                             (format "  %-40s %6.2f%% APS  (%.1f-%.1f ±%.1f)  %s%s"
                                     (:opponent p)
                                     (:aps p)
                                     (:min-aps p)
                                     (:max-aps p)
                                     (:stddev p)
                                     (if (:win? p) "WIN" "LOSS")
                                     diff-str)))
                         sorted-pairings)
        avg (/ (reduce + (map :aps pairings)) (count pairings))
        with-roster (filter :roster-aps pairings)
        avg-delta (when (seq with-roster)
                    (/ (reduce + (map #(- (:aps %) (:roster-aps %)) with-roster))
                       (count with-roster)))
        avg-line (format "  %-40s %6.2f%% avg%s" "" avg
                         (if avg-delta (format "  Δ%+.1f avg" avg-delta) ""))]
    {:lines (into [(format "")
                   (format "  %s" (str/upper-case label))
                   (str "  " (apply str (repeat 60 "-")))]
                  (conj item-lines avg-line))
     :avg avg
     :avg-delta avg-delta}))

(defn- format-benchmark-report-lines
  "Render benchmark results into the same textual report format used by benchmark!"
  [{:keys [bot rounds match-length pairings commit elapsed-seconds]}]
  (let [pairings (mapv with-win-flag pairings)
        num-matches (max 1 (quot rounds match-length))
        header-lines [(str "" (apply str (repeat 66 "=")))
                      (format "BENCHMARK RESULTS — %s — %d×%d rounds%s"
                              bot num-matches match-length
                              (if commit (str " — " commit) ""))
                      (apply str (repeat 66 "="))]
        by-cat (group-by :category pairings)
        ordered-cats (category-order pairings)
        sections (mapcat (fn [cat]
                           (when-let [cat-pairings (get by-cat cat)]
                             (:lines (format-category-section cat cat-pairings))))
                         ordered-cats)
        overall (/ (reduce + (map :aps pairings)) (count pairings))
        with-roster (filter :roster-aps pairings)
        avg-delta (when (seq with-roster)
                    (/ (reduce + (map #(- (:aps %) (:roster-aps %)) with-roster))
                       (count with-roster)))
        wins (count (filter :win? pairings))
        footer-lines (cond-> [(str "")
                              (str "  " (apply str (repeat 60 "=")))
                              (format "  %-40s %6.2f%% OVERALL APS%s" "" overall
                                      (if avg-delta (format "  Δ%+.1f avg" avg-delta) ""))
                              (format "  %-40s %d/%d wins" "" wins (count pairings))]
                       elapsed-seconds
                       (conj (format "  %-40s %s elapsed"
                                     ""
                                     (format "%d:%02d"
                                             (int (/ elapsed-seconds 60))
                                             (int (mod elapsed-seconds 60))))))]
    (vec (concat header-lines sections footer-lines))))

(defn- print-benchmark-report!
  [data]
  (doseq [line (format-benchmark-report-lines data)]
    (println line)))

(defn report-log!
  "Render a saved benchmark EDN log in the same report format as benchmark output.
   Usage: bb benchmark-report <log.edn> [output.md]"
  [{:keys [log output]}]
  (when-not log
    (println "Usage: bb benchmark-report <log.edn> [output.md]")
    (System/exit 1))
  (let [data (edn/read-string (slurp log))
        report-data {:bot (:bot data)
                     :rounds (:rounds data)
                     :match-length (:match-length data)
                     :pairings (:pairings data)
                     :commit (:commit data)
                     :elapsed-seconds (:elapsed-seconds data)}
        lines (format-benchmark-report-lines report-data)]
    (if output
      (let [md (str "## " (or (:commit data) (:bot data)) "\n\n"
                    "Source: " log "\n\n"
                    "```text\n"
                    (str/join "\n" lines)
                    "\n```\n")]
        (spit output md)
        (println (format "Wrote report to %s" output)))
      (doseq [line lines]
        (println line)))))

(defn- save-results! [bot rounds match-length results timestamp commit-info elapsed-s jar-path]
  (let [dir "../pugilists-dev/research/benchmarks/logs/"
        base (format "benchmark-%s" timestamp)
        path (str dir base ".edn")
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
    (when (and jar-path (fs/exists? jar-path))
      (fs/copy jar-path (str dir base ".jar") {:replace-existing true}))
    (println (format "\nResults saved to %s" path))))

(defn benchmark!
  "Run benchmark battles and report APS per category.
   Options: :bot - fully qualified bot name
            :rounds - total rounds per opponent (default: 100)
            :match-length - rounds per match (default: 35, like LiteRumble)
            :commit - optional git ref to benchmark (default: working tree)
            :roster - path to roster EDN file (default: config/benchmark-roster.edn)"
  [{:keys [bot rounds match-length commit roster local?]
    :or {rounds 105 match-length 35}}]
  (when-not bot
    (println "Usage: bb benchmark <bot> [rounds] [match-length] [commit] [roster]")
    (println "Example: bb benchmark pez.mini.Pugilist")
    (println "         bb benchmark pez.mini.Pugilist 100")
    (println "         bb benchmark pez.mini.Pugilist 100 10")
    (println "         bb benchmark pez.mini.Pugilist 100 10 HEAD~3")
    (println "         bb benchmark pez.mini.Pugilist 100 10 - config/my-roster.edn")
    (System/exit 1))
  (let [ctx         (remote/load-exec-ctx "benchmark.edn" local?)
        roster-path (or roster default-roster)
        roster-data (load-roster roster-path)
        commit-info (when commit (resolve-commit commit))
        num-matches (max 1 (quot rounds match-length))]
    (remote/open-ssh-control! ctx "bench")
    (remote/start-caffeinate! ctx)
    (remote/register-shutdown-hook! (fn []
                                      (kill-remote-battles!)
                                      (remote/stop-caffeinate!)
                                      (remote/close-ssh-control!)))
    (try
      (remote/check-remote! ctx)
      (remote/check-robocode-installed! ctx)
      (build-and-deploy! ctx bot commit)
      (let [jar-path (if (= :remote (:mode ctx))
                       (str (fs/absolutize (str ".tmp/" bot "_benched.jar")))
                       (str *robocode-home* "/robots/" bot "_benched.jar"))
            bot-codesize (codesize/get-size jar-path)
            opponents (into [] (comp cat (map bot-name)) (vals roster-data))
            total (count opponents)
            n-workers (min (:num-workers ctx) total)
            start-ms (System/currentTimeMillis)
            timestamp (.format (java.time.LocalDateTime/now)
                               (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))
            ;; All workers use APFS clones — original ~/robocode is never a battle dir
            _ (println (format "Setting up %d robocode instance%s..." n-workers (if (> n-workers 1) "s" "")))
            worker-homes (mapv (partial remote/ensure-robocode-copy! ctx) (range n-workers))
            ;; Round-robin shard opponents across workers
            shards (reduce (fn [acc [i opp]]
                             (update acc (mod i n-workers) conj opp))
                           (vec (repeat n-workers []))
                           (map-indexed vector opponents))
            opp->index (into {} (map-indexed (fn [i o] [o i]) opponents))]
        (println (format "Running %s\nBenchmark: %d opponents, %d×%d rounds each, %d workers, bot: %s%s%s\n"
                         (if (= :remote (:mode ctx))
                           (str "on: " (:host ctx))
                           "locally")
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
                                             (locking *out*
                                               (print (format "        battling %s...\r" opponent))
                                               (flush))
                                             (let [result (run-pairing! ctx bot opponent rounds match-length roster-data)
                                                   done (swap! progress inc)]
                                               (locking *out*
                                                 (if result
                                                   (let [diff-str (if-let [ra (:roster-aps result)]
                                                                    (format "  Δ%+.1f" (- (:aps result) ra))
                                                                    "")]
                                                     (println (format "  [%2d/%d] vs %-40s %6.2f%% APS  (%.1f–%.1f ±%.1f)%s"
                                                                      done total opponent
                                                                      (:aps result) (:min-aps result) (:max-aps result) (:stddev result)
                                                                      diff-str)))
                                                   (println (format "  [%2d/%d] vs %-40s FAILED"
                                                                    done total opponent)))
                                                 (flush))
                                               result))
                                           shard))))))
                            (range n-workers))
              results-by-worker (mapv deref futures)
              all-results (sort-by #(get opp->index (:opponent %))
                                   (into [] cat results-by-worker))]
          (let [elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)]
            (print-benchmark-report! {:bot bot
                                      :rounds rounds
                                      :match-length match-length
                                      :pairings all-results
                                      :commit commit-info
                                      :elapsed-seconds elapsed-s})
            (println)
            (save-results! bot rounds match-length all-results timestamp commit-info elapsed-s jar-path))
          (if (= :remote (:mode ctx))
            (remote/ssh! ctx (str "rm -rf " (str/join " " (map #(format "/tmp/robocode-%d" %) (range n-workers)))))
            (doseq [wid (range n-workers)]
              (fs/delete-tree (format ".tmp/robocode-%d" wid))
              (fs/delete-if-exists (format ".tmp/benchmark-%d.battle" wid))
              (fs/delete-if-exists (format ".tmp/benchmark-results-%d.txt" wid))))))
      (finally
        (remote/stop-caffeinate!)
        (remote/close-ssh-control!)
        (remote/unregister-shutdown-hook!)))))

(defn- run-benchmark-worker!
  "Run a complete benchmark for a single ref using a specific robocode copy.
   Designed for parallel execution - minimal stdout output."
  [bot rounds match-length ref worker-id robo-home roster-data timestamp]
  (binding [*robocode-home* robo-home
            *worker-id* worker-id]
    (let [label (or ref "current")
          jar-path (str robo-home "/robots/" bot "_benched.jar")
          bot-codesize (codesize/get-size jar-path)
          opponents (into [] (comp cat (map bot-name)) (vals roster-data))
          total (count opponents)
          num-matches (max 1 (quot rounds match-length))
          start-ms (System/currentTimeMillis)]
      (locking *out*
        (println (format "  [%d] %s: %d opponents, %d×%d rounds%s"
                         worker-id label total num-matches match-length
                         (if bot-codesize (str " [" bot-codesize " bytes]") ""))))
      (let [results (doall
                     (map (fn [opponent]
                            (run-pairing! {:mode :local :robocode-home *robocode-home* :java-home java-home} bot opponent rounds match-length roster-data))
                          opponents))
            elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)
            overall (/ (reduce + (map :aps results)) (count results))
            wins (count (filter :win? results))
            commit-info (when ref (resolve-commit ref))
            save-ts (format "%s-%s" timestamp (str/replace label #"[^a-zA-Z0-9._-]" "_"))]
        (save-results! bot rounds match-length results save-ts commit-info elapsed-s jar-path)
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
  (let [ctx (remote/load-exec-ctx "benchmark.edn" true)
        roster-data (load-roster default-roster)
        n (count refs)
        num-matches (max 1 (quot rounds match-length))
        opponents (into [] (comp cat (map bot-name)) (vals roster-data))
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
                        robo-home (remote/ensure-robocode-copy! ctx i)]
                    (println (format "  [%d] Building %s..." i label))
                    (binding [*robocode-home* robo-home
                              *worker-id* i]
                      (build-and-deploy! ctx bot ref))
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

(def benchmark-lock-dir ".tmp")

(defn- benchmark-lock-path
  "Return the queue lock path for a benchmark execution scope."
  [scope]
  (str benchmark-lock-dir "/benchmark-" (name scope) ".lock"))

(defn- benchmark-lock-scope
  "Choose the queue lock scope for a benchmark invocation."
  [opts]
  (if (:local? opts) :local :remote))

(defn- benchmark-lock-options
  "Open options for the benchmark coordination lock."
  []
  (into-array java.nio.file.OpenOption
              [java.nio.file.StandardOpenOption/CREATE
               java.nio.file.StandardOpenOption/READ
               java.nio.file.StandardOpenOption/WRITE]))

(defn- read-benchmark-lock-owner
  "Read metadata for the benchmark currently holding the queue lock."
  [lock-path]
  (when (fs/exists? lock-path)
    (let [content (str/trim (slurp lock-path))]
      (when (seq content)
        (try
          (edn/read-string content)
          (catch Exception _
            {:raw content}))))))

(defn- format-benchmark-lock-owner
  "Format benchmark lock metadata for queue status output."
  [owner]
  (if-let [benchmark (:benchmark owner)]
    (format "%s, pid %s, started %s"
            benchmark
            (:pid owner "?")
            (:started-at owner "?"))
    (pr-str owner)))

(defn- benchmark-lock-scope-label
  "Describe a queue lock scope for user-facing status output."
  [scope]
  (case scope
    :local "local benchmark"
    :remote "remote benchmark"
    (str (name scope) " benchmark")))

(defn- announce-benchmark-queued!
  "Tell the user this benchmark is waiting behind another benchmark."
  [lock-path scope label]
  (let [scope-label (benchmark-lock-scope-label scope)]
    (if-let [owner (read-benchmark-lock-owner lock-path)]
      (println (format "Another %s is running (%s). Queued: %s"
                       scope-label
                       (format-benchmark-lock-owner owner)
                       label))
      (println (format "Another %s is running. Queued: %s"
                       scope-label
                       label)))))

(defn- wait-for-benchmark-lock!
  "Wait until the benchmark queue lock is available."
  [channel lock-path scope label]
  (announce-benchmark-queued! lock-path scope label)
  (let [lock (.lock channel)]
    (println (format "Benchmark queue ready: %s" label))
    lock))

(defn- write-benchmark-lock-owner!
  "Record the benchmark holding the queue lock."
  [channel label]
  (let [content (pr-str {:pid (.pid (java.lang.ProcessHandle/current))
                         :started-at (str (java.time.Instant/now))
                         :benchmark label})]
    (.truncate channel 0)
    (.position channel 0)
    (.write channel (java.nio.ByteBuffer/wrap (.getBytes content "UTF-8")))
    (.force channel true)))

(defn- clear-benchmark-lock-owner!
  "Clear benchmark lock metadata before releasing the queue lock."
  [channel]
  (.truncate channel 0)
  (.force channel true))

(defn- acquire-benchmark-lock!
  "Acquire the benchmark queue lock, waiting if another benchmark holds it."
  [channel lock-path scope label]
  (let [lock (or (.tryLock channel)
                 (wait-for-benchmark-lock! channel lock-path scope label))]
    (write-benchmark-lock-owner! channel label)
    lock))

(defn- with-benchmark-lock
  "Run f while holding the benchmark queue lock."
  [scope label f]
  (fs/create-dirs benchmark-lock-dir)
  (let [lock-path (benchmark-lock-path scope)]
    (with-open [channel (java.nio.channels.FileChannel/open
                         (.toPath (fs/file lock-path))
                         (benchmark-lock-options))]
      (let [_lock (acquire-benchmark-lock! channel lock-path scope label)]
        (try
          (f)
          (finally
            (clear-benchmark-lock-owner! channel)))))))

(defn- benchmark-lock-label
  "Describe a queued benchmark for status output and lock metadata."
  [kind opts]
  (let [summary (assoc (select-keys opts [:bot :rounds :match-length :commit :roster :refs])
                       :mode (benchmark-lock-scope opts))]
    (str kind " " (pr-str summary))))

(defn queued-benchmark!
  "Run benchmark!, waiting for any benchmark already running on the same execution target."
  [opts]
  (if (:bot opts)
    (with-benchmark-lock (benchmark-lock-scope opts)
      (benchmark-lock-label "benchmark" opts)
      #(benchmark! opts))
    (benchmark! opts)))

(defn queued-benchmark-parallel!
  "Run benchmark-parallel!, waiting for any already-running local benchmark to finish."
  [opts]
  (if (and (:bot opts) (seq (:refs opts)))
    (with-benchmark-lock :local
      (benchmark-lock-label "benchmark-parallel" (assoc opts :local? true))
      #(benchmark-parallel! opts))
    (benchmark-parallel! opts)))
