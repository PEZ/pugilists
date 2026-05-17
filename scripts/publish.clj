(ns publish
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def classes-dir "build/classes/java/main")

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
  ;; Clean old JARs
  (doseq [old-jar (fs/glob "site" "*.jar")]
    (fs/delete old-jar))

  (println "Creating per-bot JARs:")
  (let [bots (find-bots)
        jar-names (mapv create-bot-jar! bots)]

    (println (str "\nDeploying " (count jar-names) " bot JARs to Netlify..."))
    (p/shell "netlify" "deploy" "--prod" "--dir=site")

    (println "\nPublished JARs:")
    (doseq [name jar-names]
      (println (str "  https://pugilists.netlify.app/" name)))))
