(ns rumble-stats
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn- url-encode [s]
  (-> (java.net.URLEncoder/encode s "UTF-8")
      (str/replace "+" "%20")))

(defn- parse-url
  "Extract page type, game and bot-version from a LiteRumble URL."
  [url]
  (let [path-part (first (str/split url #"\?"))
        page-type (cond
                    (str/includes? path-part "/Rankings") :rankings
                    :else :bot-details)
        query-str (last (str/split url #"\?"))
        params (->> (str/split query-str #"&")
                    (map #(str/split % #"=" 2))
                    (into {} (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))))]
    {:page-type page-type
     :game (:game params)
     :bot-version (:name params)}))

(defn- fetch-bot-details [game bot-version]
  (let [url (format "https://literumble.appspot.com/BotDetails?game=%s&name=%s&api=1"
                    game (url-encode bot-version))]
    (json/parse-string (:body (http/get url)) true)))

(defn- fetch-rankings [game]
  (let [url (format "https://literumble.appspot.com/Rankings?game=%s&api=1" game)]
    (json/parse-string (:body (http/get url)) true)))

(def ^:private pairing-columns
  [:rank :name :APS :APS_CI :NPP :survival :KNNPBI :battles])

(def ^:private col-formats
  {:rank "%4s" :name "%-45s" :APS "%7.2f" :APS_CI "%5.2f"
   :NPP "%7.2f" :survival "%7.2f" :KNNPBI "%7.2f" :battles "%4s"})

(def ^:private col-labels
  {:rank "Rank" :name "Name" :APS "APS" :APS_CI "±CI"
   :NPP "NPP" :survival "Surv" :KNNPBI "KNNPBI" :battles "Btl"})

(defn- format-summary [data]
  (let [stats (dissoc data :pairingsList)
        pairings (:pairingsList data)
        wins (count (filter #(> (:APS %) 50) pairings))
        losses (count (filter #(< (:APS %) 50) pairings))]
    (str "\n" (:name stats) "\n"
         (format "  APS: %.2f (±%.2f)  PWIN: %.2f  ANPP: %.2f\n"
                 (:APS stats) (:APS_CI stats) (:PWIN stats) (:ANPP stats))
         (format "  Survival: %.2f  Vote: %.2f  W/L: %d/%d\n"
                 (:survival stats) (:vote stats) wins losses)
         (format "  Pairings: %d  Battles: %d  Latest: %s\n"
                 (:pairings stats) (:battles stats) (:latest stats)))))

(defn- format-pairings-table [pairings]
  (let [header (str/join "  " (map col-labels pairing-columns))
        sep (apply str (repeat (count header) "-"))
        rows (map (fn [p]
                    (str/join "  " (map (fn [col]
                                          (format (col-formats col) (get p col)))
                                        pairing-columns)))
                  pairings)]
    (str/join "\n" (concat [header sep] rows [""]))))

(def ^:private rankings-columns
  [:rank :name :APS :PWIN :ANPP :survival :vote :pairings :battles])

(def ^:private rankings-col-formats
  {:rank "%4s" :name "%-45s" :APS "%7.2f" :PWIN "%6.2f"
   :ANPP "%7.2f" :survival "%7.2f" :vote "%6.2f" :pairings "%4s" :battles "%6s"})

(def ^:private rankings-col-labels
  {:rank "Rank" :name "Name" :APS "APS" :PWIN "PWIN"
   :ANPP "ANPP" :survival "Surv" :vote "Vote" :pairings "Pr" :battles "Btl"})

(defn- format-rankings-table [data]
  (let [header (str/join "  " (map rankings-col-labels rankings-columns))
        sep (apply str (repeat (count header) "-"))
        rows (map (fn [p]
                    (str/join "  " (map (fn [col]
                                          (format (rankings-col-formats col) (get p col)))
                                        rankings-columns)))
                  data)]
    (str/join "\n" (concat [header sep] rows [""]))))

(defn- resolve-input [input game rankings?]
  (if (str/starts-with? input "http")
    (parse-url input)
    (if rankings?
      {:page-type :rankings :game input}
      {:page-type :bot-details
       :game (or game "minirumble")
       :bot-version input})))

(defn- match-bot-filter? [name patterns]
  (some #(re-find % name) patterns))

(defn- parse-bot-filters [s]
  (when s
    (mapv re-pattern (str/split s #","))))

(defn- resolve-sort-key [col-name]
  (let [aliases {"aps" :APS "ci" :APS_CI "npp" :NPP "surv" :survival
                 "survival" :survival "knnpbi" :KNNPBI "battles" :battles
                 "btl" :battles "rank" :rank "name" :name
                 "pwin" :PWIN "anpp" :ANPP "vote" :vote "pairings" :pairings}]
    (get aliases (str/lower-case col-name))))

(defn- write-agent-output! [data]
  (fs/create-dirs ".tmp")
  (let [path ".tmp/rumble-stats.edn"]
    (spit path (with-out-str (pp/pprint data)))
    (println (str "Agent-readable output: " path))))

(defn stats!
  "Fetch and display LiteRumble stats.\n  Input: URL or bot-version string (or game name with --rankings).\n  Options:\n    --game GAME     Game type (default: minirumble)\n    --rankings      Treat input as game name and show full leaderboard\n    --sort COL      Sort by column (APS, KNNPBI, NPP, PWIN, ANPP, survival, vote, battles, rank)\n    --asc           Sort ascending (default: descending)\n    --head N        Show top N entries\n    --tail N        Show bottom N entries\n    --filter-bots PATTERNS  Comma-separated regexes to filter by bot name\n    --columns       List available columns only\n    --edn           Write full data to .tmp/rumble-stats.edn"
  [args]
  (let [{:keys [opts args]} (cli/parse-args args
                                            {:coerce {:head :int :tail :int
                                                      :asc :boolean :columns :boolean
                                                      :edn :boolean :rankings :boolean}})
        input (first args)]
    (when-not input
      (println "Usage: bb rumble-stats <url-or-bot-version> [options]")
      (println "\nBot details:")
      (println "  bb rumble-stats 'pez.mini.Pugilist 2.5.11' --sort KNNPBI --head 20")
      (println "  bb rumble-stats 'https://literumble.appspot.com/BotDetails?game=minirumble&name=pez.mini.Pugilist%202.5.11'")
      (println "\nRankings:")
      (println "  bb rumble-stats minirumble --rankings --head 20")
      (println "  bb rumble-stats 'https://literumble.appspot.com/Rankings?game=minirumble' --head 20")
      (println "  bb rumble-stats microrumble --rankings --sort vote --head 10")
      (println "\nFiltering:")
      (println "  bb rumble-stats 'pez.mini.Pugilist 2.5.11' --filter-bots 'sheldor,Foilist'")
      (println "  bb rumble-stats minirumble --rankings --filter-bots 'pez\\.mini'")
      (System/exit 1))
    (let [{:keys [page-type game bot-version]} (resolve-input input (:game opts) (:rankings opts))
          bot-filters (parse-bot-filters (:filter-bots opts))]
      (case page-type
        :rankings
        (let [_ (println (format "Fetching %s rankings..." game))
              data (fetch-rankings game)
              filtered (if bot-filters
                         (filter #(match-bot-filter? (:name %) bot-filters) data)
                         data)
              sort-key (when (:sort opts) (resolve-sort-key (:sort opts)))
              _ (when (and (:sort opts) (not sort-key))
                  (println (format "Unknown sort column: %s" (:sort opts)))
                  (System/exit 1))
              sorted (if sort-key
                       (sort-by sort-key (if (:asc opts) compare (fn [a b] (compare b a))) filtered)
                       filtered)
              sliced (cond
                       (:head opts) (take (:head opts) sorted)
                       (:tail opts) (take-last (:tail opts) sorted)
                       :else sorted)]
          (println (format "\n%s rankings — %d participants" game (count data)))
          (when bot-filters
            (println (format "Filtered to %d matching bots" (count filtered))))
          (when (or sort-key (:head opts) (:tail opts))
            (println (format "%d of %d%s%s"
                             (count sliced) (count filtered)
                             (if sort-key (str ", sorted by " (name sort-key)) "")
                             (if (:asc opts) " asc" " desc"))))
          (println)
          (println (format-rankings-table sliced))
          (when (:columns opts)
            (println "Available sort columns:")
            (println "  APS, PWIN, ANPP, survival, vote, rank, battles, pairings"))
          (when (:edn opts)
            (write-agent-output! {:game game :rankings sliced})))

        :bot-details
        (let [_ (println (format "Fetching %s details for %s..." game bot-version))
              data (fetch-bot-details game bot-version)
              pairings (if bot-filters
                         (filter #(match-bot-filter? (:name %) bot-filters) (:pairingsList data))
                         (:pairingsList data))]
          (println (format-summary data))
          (when (:columns opts)
            (println "Available sort columns:")
            (println "  APS, NPP, survival, KNNPBI, battles, rank, name, CI")
            (println (format "\nPairings table columns: %s" (str/join ", " (map (comp name) pairing-columns)))))
          (when bot-filters
            (println (format "Filtered to %d of %d pairings" (count pairings) (count (:pairingsList data)))))
          (when (or (:sort opts) (:head opts) (:tail opts) bot-filters)
            (let [sort-key (when (:sort opts) (resolve-sort-key (:sort opts)))
                  _ (when (and (:sort opts) (not sort-key))
                      (println (format "Unknown sort column: %s" (:sort opts)))
                      (println "Available: APS, NPP, survival, KNNPBI, battles, rank, name, CI")
                      (System/exit 1))
                  sorted (if sort-key
                           (sort-by sort-key (if (:asc opts) compare (fn [a b] (compare b a))) pairings)
                           pairings)
                  sliced (cond
                           (:head opts) (take (:head opts) sorted)
                           (:tail opts) (take-last (:tail opts) sorted)
                           :else sorted)]
              (println (format "Pairings (%d of %d)%s%s:\n"
                               (count sliced) (count pairings)
                               (if sort-key (str " sorted by " (name sort-key)) "")
                               (if (:asc opts) " asc" " desc")))
              (println (format-pairings-table sliced))))
          (when (:edn opts)
            (write-agent-output! {:summary (dissoc data :pairingsList)
                                  :pairings pairings})))))))
