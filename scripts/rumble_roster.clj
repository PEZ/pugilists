(ns rumble-roster
  (:require [babashka.http-client :as http]
            [clojure.string :as str]))

(defn- fetch-bot-details
  "Fetch the BotDetails HTML from LiteRumble, sorted by the given column."
  [game bot-version order]
  (let [encoded (-> (java.net.URLEncoder/encode bot-version "UTF-8")
                    (str/replace "+" "%20"))
        url (format "https://literumble.appspot.com/BotDetails?game=%s&name=%s&order=%s"
                    game encoded order)]
    (:body (http/get url))))

(defn- parse-opponents
  "Parse opponent rows from BotDetails HTML.
   Returns a vector of maps sorted by the order used in the URL."
  [html]
  (->> (re-seq #"(?s)<tr>\s*<td>(\d+)</td>.*?</tr>" html)
       (keep (fn [[row-html rank-str]]
               (let [cells (->> (re-seq #"(?s)<td[^>]*>(.*?)</td>" row-html)
                                (mapv second))
                     rank (parse-long rank-str)]
                 (when (>= (count cells) 9)
                   (let [name-cell (nth cells 2)
                         name (-> (re-find #">([^<]+)<" name-cell)
                                  second
                                  str/trim)
                         aps  (parse-double (str/trim (nth cells 4)))
                         knnpbi-str (-> (nth cells 7)
                                        (str/replace #"<[^>]+>" "")
                                        str/trim)
                         knnpbi (parse-double knnpbi-str)]
                     {:rank rank :name name :aps aps :knnpbi knnpbi})))))
       vec))

(defn- select-roster
  "Select top N, middle N, bottom N, and random N opponents by KNNPBI rank.
   Returns a sorted collection of maps."
  [opponents {:keys [top middle bottom random]}]
  (let [n        (count opponents)
        top-n    (take top opponents)
        bottom-n (take-last bottom opponents)
        mid-idx  (quot n 2)
        half     (quot middle 2)
        middle-n (->> opponents
                      (drop (- mid-idx half))
                      (take middle))
        selected-indices (into #{}
                               (map :rank)
                               (concat top-n bottom-n middle-n))
        pool     (filterv #(not (selected-indices (:rank %))) opponents)
        random-n (take random (shuffle pool))]
    (->> (concat top-n middle-n random-n bottom-n)
         (sort-by :knnpbi)
         vec)))

(defn- format-edn
  "Format the roster as an EDN vector with a header comment."
  [bots {:keys [game bot-version order top middle bottom random]}]
  (let [lines (mapv (fn [{:keys [name knnpbi]}]
                      (format " \"%s\" ;; KNNPBI %.2f" name knnpbi))
                    bots)]
    (str (format ";; LiteRumble %s roster for %s\n" game bot-version)
         (format ";; Sorted by %s: top %d + middle %d + random %d + bottom %d = %d\n"
                 order top middle random bottom (+ top middle random bottom))
         (format ";; Generated %s\n" (str (java.time.LocalDateTime/now)))
         "[\n"
         (str/join "\n" lines)
         "\n]\n")))

(defn generate!
  "Generate a benchmark roster from LiteRumble BotDetails.
   Called with *command-line-args*. First positional arg is bot-version.
   Options:
     --game         rumble game (default \"minirumble\")
     --order        sort column (default \"-KNNPBI\")
     --top N        how many from the top/worst (default 5)
     --middle N     how many from the middle (default 5)
     --bottom N     how many from the bottom/best (default 5)
     --random N     how many random from remainder (default 5)
     --output FILE  output file path (default stdout)"
  [args]
  (let [bot-version (first args)
        flag-args   (rest args)
        flags       (loop [remaining (vec flag-args)
                           acc {}]
                      (if (< (count remaining) 2)
                        acc
                        (let [[k v & more] remaining]
                          (if (str/starts-with? k "--")
                            (recur (vec more) (assoc acc (keyword (subs k 2)) v))
                            acc))))
        game    (get flags :game "minirumble")
        order   (get flags :order "-KNNPBI")
        top     (parse-long (get flags :top "5"))
        middle  (parse-long (get flags :middle "5"))
        bottom  (parse-long (get flags :bottom "5"))
        random  (parse-long (get flags :random "5"))
        output  (get flags :output)]
    (when-not bot-version
      (println "Usage: bb rumble-roster 'pez.mini.Pugilist 2.5.7' [options]")
      (println "Options:")
      (println "  --game         minirumble|roborumble (default: minirumble)")
      (println "  --order        sort column (default: -KNNPBI)")
      (println "  --top N        from worst end (default: 5)")
      (println "  --middle N     from the middle (default: 5)")
      (println "  --bottom N     from best end (default: 5)")
      (println "  --random N     random from remainder (default: 5)")
      (println "  --output FILE  write to file (default: stdout)")
      (System/exit 1))
    (println (format "Fetching %s BotDetails for %s (order: %s)..." game bot-version order))
    (let [html      (fetch-bot-details game bot-version order)
          opponents (parse-opponents html)
          _         (println (format "Parsed %d opponents" (count opponents)))
          roster    (select-roster opponents {:top top :middle middle :bottom bottom :random random})
          edn       (format-edn roster {:game game :bot-version bot-version :order order
                                        :top top :middle middle :bottom bottom :random random})]
      (if output
        (do (spit output edn)
            (println (format "Wrote %d-bot roster to %s" (count roster) output))
            (doseq [{:keys [name knnpbi]} roster]
              (println (format "  %-50s KNNPBI %7.2f" name knnpbi))))
        (print edn)))))