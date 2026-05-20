(ns rumble-roster
  (:require [babashka.http-client :as http]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; Column definitions for each page type
;; Index is 0-based position in the <td> cells after the rank cell
(def ^:private rankings-columns
  {"APS" 3, "PWIN" 4, "ANPP" 5, "Vote" 6, "Survival" 7})

(def ^:private bot-details-columns
  {"APS" 4, "NPP" 5, "Survival" 6, "KNNPBI" 7})

(defn- url-encode [s]
  (-> (java.net.URLEncoder/encode s "UTF-8")
      (str/replace "+" "%20")))

(defn- fetch-rankings [game order]
  (let [url (format "https://literumble.appspot.com/Rankings?game=%s&order=%s"
                    game (url-encode order))]
    (:body (http/get url))))

(defn- fetch-bot-details [game bot-version order]
  (let [url (format "https://literumble.appspot.com/BotDetails?game=%s&name=%s&order=%s"
                    game (url-encode bot-version) (url-encode order))]
    (:body (http/get url))))

(defn- extract-name [cell-html]
  (-> (re-find #">([^<]+)<" cell-html) second str/trim))

(defn- parse-rows
  "Parse table rows from LiteRumble HTML.
   column-index is the 0-based <td> position of the sort column.
   aps-index is the position of the APS column (always extracted)."
  [html column-index aps-index]
  (->> (re-seq #"(?s)<tr>\s*<td>(\d+)</td>.*?</tr>" html)
       (keep (fn [[row-html rank-str]]
               (let [cells (->> (re-seq #"(?s)<td[^>]*>(.*?)</td>" row-html)
                                (mapv second))
                     rank (parse-long rank-str)]
                 (when (> (count cells) (max column-index aps-index))
                   (let [name     (extract-name (nth cells 2))
                         col-raw  (-> (nth cells column-index)
                                      (str/replace #"<[^>]+>" "")
                                      str/trim)
                         col-val  (parse-double col-raw)
                         aps-raw  (-> (nth cells aps-index)
                                      (str/replace #"<[^>]+>" "")
                                      str/trim)
                         aps      (parse-double aps-raw)]
                     {:rank rank :name name :col-val col-val :aps aps})))))
       vec))

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
        col-idx (get col-map column)
        aps-idx (get col-map "APS")]
    (when-not col-idx
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
          html   (if use-details?
                   (fetch-bot-details game bot order)
                   (fetch-rankings game order))
          bots   (parse-rows html col-idx aps-idx)
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