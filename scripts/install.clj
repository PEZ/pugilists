(ns install
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.string :as str]))

(def participants-url "https://robowiki.net/wiki/RoboRumble/Participants?action=raw")
(def robots-dir (str (fs/expand-home "~/robocode/robots")))

(defn- fetch-participants []
  (let [body (:body (http/get participants-url))]
    ;; Extract the <pre>...</pre> block containing the actual list
    (-> (re-find #"(?s)<pre>(.*?)</pre>" body)
        second)))

(defn- parse-participants
  "Parse the participants list into a map of classname -> {:version v :url u}.
   Format is one line per entry: classname version,url
   The first space separates classname from version,url."
  [text]
  (let [lines (->> (str/split-lines text)
                   (map str/trim)
                   (remove str/blank?))]
    (into {}
          (keep (fn [line]
                  (let [space-idx (str/index-of line " ")]
                    (when space-idx
                      (let [classname (subs line 0 space-idx)
                            rest (subs line (inc space-idx))
                            comma-idx (str/index-of rest ",")]
                        (when comma-idx
                          [classname {:version (subs rest 0 comma-idx)
                                      :url (subs rest (inc comma-idx))}]))))))
          lines)))

(defn- download-jar! [url dest]
  (let [response (http/get url {:as :bytes})]
    (if (= 200 (:status response))
      (do
        (java.io.File/.mkdirs (.getParentFile (java.io.File. dest)))
        (fs/write-bytes dest (:body response))
        true)
      (do
        (println (str "Download failed with status " (:status response)))
        false))))

(defn- install-one! [participants bot]
  (let [entry (get participants bot)]
    (when-not entry
      (println (str "Bot '" bot "' not found in participants list"))
      (println "Available bots matching your query:")
      (doseq [name (->> (keys participants)
                        (filter #(str/includes? (str/lower-case %) (str/lower-case bot)))
                        sort
                        (take 10))]
        (println (str "  " name)))
      (System/exit 1))
    (let [{:keys [version url]} entry
          jar-name (str bot "_" version ".jar")
          dest (str robots-dir "/" jar-name)]
      (if (fs/exists? dest)
        (println (str jar-name " already installed"))
        (do
          (println (str "Downloading " jar-name "..."))
          (println (str "  from: " url))
          (if (download-jar! url dest)
            (println (str "Installed to " dest))
            (println (str "FAILED: " jar-name))))))))

(defn install! [{:keys [bot all]}]
  (when-not (or bot all)
    (println "Usage: bb install <classname>")
    (println "       bb install --all")
    (println "Example: bb install voidious.mini.Komarious")
    (System/exit 1))
  (println "Fetching participants list...")
  (let [text (fetch-participants)]
    (when-not text
      (println "Failed to fetch participants list")
      (System/exit 1))
    (let [participants (parse-participants text)]
      (if all
        (let [bots (sort (keys participants))
              total (count bots)]
          (println (str "Installing " total " bots..."))
          (doseq [[i bot-name] (map-indexed vector bots)]
            (print (str "[" (inc i) "/" total "] "))
            (install-one! participants bot-name))
          (println "Done."))
        (install-one! participants bot)))))
