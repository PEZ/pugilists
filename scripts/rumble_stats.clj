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
  "Extract game and bot-version from a LiteRumble BotDetails URL."
  [url]
  (let [query-str (last (str/split url #"\?"))
        params (->> (str/split query-str #"&")
                    (map #(str/split % #"=" 2))
                    (into {} (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))))]
    {:game (:game params)
     :bot-version (:name params)}))

(defn- fetch-bot-details [game bot-version]
  (let [url (format "https://literumble.appspot.com/BotDetails?game=%s&name=%s&api=1"
                    game (url-encode bot-version))]
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
  (let [stats (dissoc data :pairingsList)]
    (str "\n" (:name stats) "\n"
         (format "  APS: %.2f (±%.2f)  PWIN: %.2f  ANPP: %.2f\n"
                 (:APS stats) (:APS_CI stats) (:PWIN stats) (:ANPP stats))
         (format "  Survival: %.2f  Vote: %.2f\n"
                 (:survival stats) (:vote stats))
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

(defn- resolve-input [input game]
  (if (str/starts-with? input "http")
    (parse-url input)
    {:game (or game "minirumble")
     :bot-version input}))

(defn- resolve-sort-key [col-name]
  (let [aliases {"aps" :APS "ci" :APS_CI "npp" :NPP "surv" :survival
                 "survival" :survival "knnpbi" :KNNPBI "battles" :battles
                 "btl" :battles "rank" :rank "name" :name}]
    (get aliases (str/lower-case col-name))))

(defn- write-agent-output! [data]
  (fs/create-dirs ".tmp")
  (let [path ".tmp/rumble-stats.edn"]
    (spit path (with-out-str (pp/pprint data)))
    (println (str "Agent-readable output: " path))))

(defn stats!
  "Fetch and display LiteRumble bot stats.\n  Input: URL or bot-version string.\n  Options:\n    --game GAME     Game type (default: minirumble)\n    --sort COL      Sort pairings by column (APS, KNNPBI, NPP, survival, battles, rank)\n    --asc           Sort ascending (default: descending)\n    --head N        Show top N pairings\n    --tail N        Show bottom N pairings\n    --columns       List available columns only\n    --edn           Write full data to .tmp/rumble-stats.edn"
  [args]
  (let [{:keys [opts args]} (cli/parse-args args
                                            {:coerce {:head :int :tail :int
                                                      :asc :boolean :columns :boolean
                                                      :edn :boolean}})
        input (first args)]
    (when-not input
      (println "Usage: bb rumble-stats <url-or-bot-version> [options]")
      (println "  bb rumble-stats 'https://literumble.appspot.com/BotDetails?game=minirumble&name=pez.mini.Pugilist%202.5.11'")
      (println "  bb rumble-stats 'pez.mini.Pugilist 2.5.11' --sort KNNPBI --head 20")
      (println "  bb rumble-stats 'pez.mini.Pugilist 2.5.11' --sort APS --tail 10")
      (println "  bb rumble-stats 'pez.mini.Pugilist 2.5.11' --edn")
      (System/exit 1))
    (let [{:keys [game bot-version]} (resolve-input input (:game opts))
          _ (println (format "Fetching %s details for %s..." game bot-version))
          data (fetch-bot-details game bot-version)
          pairings (:pairingsList data)]
      (println (format-summary data))
      (when (:columns opts)
        (println "Available sort columns:")
        (println "  APS, NPP, survival, KNNPBI, battles, rank, name, CI")
        (println (format "\nPairings table columns: %s" (str/join ", " (map (comp name) pairing-columns)))))
      (when (or (:sort opts) (:head opts) (:tail opts))
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
                              :pairings pairings})))))
