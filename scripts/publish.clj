(ns publish
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def classes-dir "build/classes/java/main")
(def site-url "https://pugilists.netlify.app")
(def index-file "site-index.edn")

(defn- read-properties [path]
  (->> (slurp (str path))
       str/split-lines
       (remove #(str/starts-with? % "#"))
       (keep #(let [[k v] (str/split % #"=" 2)]
                (when v [(str/trim k) (str/trim v)])))
       (into {})))

(defn- find-bots []
  (->> (fs/glob "src" "**/*.properties")
       (map (fn [props-path]
              (let [props (read-properties props-path)
                    classname (get props "robot.classname")
                    version (get props "robot.version")
                    package-dir (str/replace (subs classname 0 (str/last-index-of classname ".")) "." "/")]
                {:classname classname
                 :version version
                 :package-dir package-dir
                 :properties-path props-path})))))

(defn- create-bot-jar! [{:keys [classname version package-dir]}]
  (let [jar-name (str classname "_" version ".jar")
        jar-path (str "site/" jar-name)]
    (println (str "  " jar-name))
    (p/shell "jar" "cf" jar-path
             "-C" classes-dir package-dir)
    jar-name))

(defn- set-version! [classname version]
  (let [props-file (str "src/" (str/replace classname "." "/") ".properties")
        content (slurp props-file)
        updated (str/replace content #"robot\.version=.*" (str "robot.version=" version))]
    (spit props-file updated)
    (println (str "Version set to " version " in " props-file))))

(defn- load-index []
  (if (fs/exists? index-file)
    (edn/read-string (slurp index-file))
    #{}))

(defn- save-index! [jars]
  (spit index-file (with-out-str (pprint/pprint (into (sorted-set) jars)))))

(defn- sync-existing-jars!
  "Download any jars listed in the index that aren't in site/ locally."
  []
  (let [index (load-index)]
    (doseq [jar-name index]
      (let [local-path (str "site/" jar-name)]
        (when-not (fs/exists? local-path)
          (println (str "  Downloading " jar-name "..."))
          (p/shell "curl" "-sfo" local-path (str site-url "/" jar-name)))))))

(defn publish!
  "Build and deploy per-bot JARs to Netlify.
   bb publish [version] — version applies to Pugilist."
  [{:keys [version]}]
  (when version
    (set-version! "pez.mini.Pugilist" version))

  (println "Building...")
  (p/shell {:extra-env {"JAVA_HOME" "/Library/Java/JavaVirtualMachines/graalvm-23.jdk/Contents/Home"}}
           "./gradlew" "build")

  (fs/create-dirs "site")
  (println "Syncing existing jars...")
  (sync-existing-jars!)

  (println "Creating per-bot JARs:")
  (let [bots (find-bots)
        jar-names (mapv create-bot-jar! bots)
        all-jars (into (load-index) jar-names)]

    (save-index! all-jars)
    (fs/copy index-file "site/site-index.edn" {:replace-existing true})
    (println (str "\nDeploying " (count (fs/glob "site" "*.jar")) " bot JARs to Netlify..."))
    (p/shell "netlify" "deploy" "--prod" "--dir=site")

    (println "\nTagging...")
    (doseq [{:keys [classname version]} bots]
      (let [tag (str classname "_" version)]
        (println (str "  " tag))
        (p/shell "git" "tag" tag)
        (p/shell "git" "push" "origin" tag)))

    (println "\nPublished JARs:")
    (doseq [name (sort all-jars)]
      (println (str "  " site-url "/" name)))))
