(ns rumble-roster
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; Column definitions: user-facing name → JSON key
(def ^:private rankings-columns
  {"APS" :APS, "PWIN" :PWIN, "ANPP" :ANPP, "Vote" :vote, "Survival" :survival})

(def ^:private bot-details-columns
  {"APS" :APS, "NPP" :NPP, "Survival" :survival, "KNNPBI" :KNNPBI})

(defn- url-encode [s]
  (-> (java.net.URLEncoder/encode s "UTF-8")
      (str/replace "+" "%20")))

(defn- fetch-rankings [game order]
  (let [url (format "https://literumble.appspot.com/Rankings?game=%s&order=%s&api=1"
                    game (url-encode order))]
    (json/parse-string (:body (http/get url)) true)))

(defn- fetch-bot-details [game bot-version order]
  (let [url (format "https://literumble.appspot.com/BotDetails?game=%s&name=%s&order=%s&api=1"
                    game (url-encode bot-version) (url-encode order))]
    (:pairingsList (json/parse-string (:body (http/get url)) true))))

(defn- parse-bots
  "Extract bot entries from JSON data.
   col-key is the keyword for the sort column (e.g. :KNNPBI)."
  [data col-key]
  (mapv (fn [entry]
          {:rank    (:rank entry)
           :name    (:name entry)
           :col-val (get entry col-key)
           :aps     (:APS entry)})
        data))

(defn- select-roster
  "Select top N, middle N, bottom N, and random N from the sorted list.
   Returns a map of {:top [...] :middle [...] :random [...] :bottom [...]}
   where each entry is {:bot \"name\" :aps value}."
  [bots {:keys [top middle bottom random]}]
  (let [n        (count bots)
        ->entry  (fn [{:keys [name aps]}] {:bot name :aps aps})
        top-n    (mapv ->entry (take top bots))
        bottom-n (mapv ->entry (take-last bottom bots))
        mid-idx  (quot n 2)
        half     (quot middle 2)
        middle-n (mapv ->entry (->> bots (drop (- mid-idx half)) (take middle)))
        taken    (into #{} (map :rank) (concat (take top bots) (take-last bottom bots)
                                               (->> bots (drop (- mid-idx half)) (take middle))))
        pool     (filterv #(not (taken (:rank %))) bots)
        random-n (mapv ->entry (take random (shuffle pool)))]
    {:top top-n :middle middle-n :random random-n :bottom bottom-n}))

(defn- format-edn [roster {:keys [game source column top middle bottom random]}]
  (let [header (str (format ";; LiteRumble %s roster from %s\n" game source)
                    (format ";; Sorted by %s: top %d + middle %d + random %d + bottom %d = %d\n"
                            column top middle random bottom (+ top middle random bottom))
                    (format ";; Generated %s\n" (str (java.time.LocalDateTime/now))))]
    (str header (with-out-str (pp/pprint roster)))))

(defn- parse-flags [args]
  (loop [remaining (vec args), acc {}]
    (if (< (count remaining) 2)
      acc
      (let [[k v & more] remaining]
        (if (str/starts-with? k "--")
          (recur (vec more) (assoc acc (keyword (subs k 2)) v))
          acc)))))

(defn generate!
  "Generate a benchmark roster from LiteRumble.
   Without --bot: uses Rankings page (all bots in the game).
   With --bot: uses BotDetails page (opponents of that bot, has KNNPBI).
   --column selects the sort metric (default: APS).
   Rankings columns: APS, PWIN, ANPP, Vote, Survival.
   BotDetails columns: APS, NPP, Survival, KNNPBI."
  [args]
  (let [flags   (parse-flags args)
        bot     (get flags :bot)
        game    (get flags :game "minirumble")
        column  (get flags :column "APS")
        top     (parse-long (get flags :top "5"))
        middle  (parse-long (get flags :middle "5"))
        bottom  (parse-long (get flags :bottom "5"))
        random  (parse-long (get flags :random "5"))
        output  (get flags :output)
        use-details? (or bot
                         (and (contains? bot-details-columns column)
                              (not (contains? rankings-columns column))))
        col-map (if use-details? bot-details-columns rankings-columns)
        col-key (get col-map column)]
    (when-not col-key
      (println (format "Unknown column '%s' for %s page." column (if use-details? "BotDetails" "Rankings")))
      (println (format "Available: %s" (str/join ", " (keys col-map))))
      (System/exit 1))
    (when (and use-details? (not bot))
      (println "KNNPBI requires --bot (BotDetails page).")
      (println "Usage: bb rumble-roster --bot 'pez.mini.Pugilist 2.5.7' --column KNNPBI")
      (System/exit 1))
    (let [order  (str "-" column)
          source (if use-details? (str "BotDetails for " bot) "Rankings")
          _      (println (format "Fetching %s %s (column: %s)..." game source column))
          data   (if use-details?
                   (fetch-bot-details game bot order)
                   (fetch-rankings game order))
          bots   (parse-bots data col-key)
          _      (println (format "Parsed %d bots" (count bots)))
          roster (select-roster bots {:top top :middle middle :bottom bottom :random random})
          edn    (format-edn roster {:game game :source source :column column
                                     :top top :middle middle :bottom bottom :random random})]
      (if output
        (do (spit output edn)
            (println (format "Wrote %d-bot roster to %s"
                             (reduce + (map (comp count val) roster)) output))
            (doseq [[cat bots] roster
                     {:keys [bot aps]} bots]
              (println (format "  %-12s %-40s APS %7.2f" (clojure.core/name cat) bot aps))))
        (print edn)))))

(defn generate-full!
  "Generate a full benchmark roster from LiteRumble, excluding a specific bot.
   Usage: bb full-roster <weightclass> <bot>
   Example: bb full-roster mini pez.mini.Pugilist"
  [[weightclass bot-name]]
  (let [game        (str weightclass "rumble")
        short-name  (last (str/split bot-name #"\."))
        output      (format "config/benchmark-roster-%s-full.edn" weightclass)
        _           (println (format "Fetching all %s rankings..." game))
        data        (fetch-rankings game "-APS")
        _           (println (format "Fetched %d bots" (count data)))
        bots        (->> data
                         (remove #(str/starts-with? (:name %) (str bot-name " ")))
                         (remove #(= (:name %) bot-name))
                         (sort-by :APS >)
                         (mapv #(hash-map :bot (:name %) :aps (:APS %))))
        n           (count bots)
        header      (str (format ";; Full %s roster (excluding %s) — %d bots\n" game short-name n)
                         (format ";; Generated %s from LiteRumble Rankings API\n"
                                 (str (java.time.LocalDateTime/now))))
        edn         (str header (with-out-str (pp/pprint {:all bots})))]
    (spit output edn)
    (println (format "Wrote %d-bot roster to %s" n output))))