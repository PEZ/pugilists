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

(defn- namespace->path
  "Converts a namespace like pez.micro.Aristocles to pez/micro/Aristocles"
  [ns-str]
  (str/replace ns-str "." "/"))

(defn- class-file->class-name
  "Converts a class file path to its JVM dotted class name relative to classes-dir."
  [abs-classes class-file]
  (-> (subs (str (fs/absolutize class-file)) (inc (count abs-classes)))
      (str/replace ".class" "")
      (str/replace "/" ".")))

(defn- source-file-attr
  "Returns the SourceFile attribute of a compiled class, e.g. \"Aristocles.java\"."
  [class-name]
  (let [result (p/shell {:out :string :err :string :continue true}
                        "javap" "-classpath" classes-dir "-verbose" class-name)
        line (first (filter #(str/includes? % "SourceFile:") (str/split-lines (:out result))))]
    (when line
      (second (re-find #"SourceFile:\s+\"(.+)\"" line)))))

(defn- find-class-files
  "Find all .class files belonging to a bot, filtered by SourceFile attribute."
  [classname]
  (let [ns-path (namespace->path classname)
        class-dir (str classes-dir "/" (fs/parent ns-path))
        source-file (str (fs/file-name ns-path) ".java")
        abs-classes (str (fs/absolutize classes-dir))]
    (when (fs/exists? class-dir)
      (->> (fs/list-dir class-dir)
           (map str)
           (filter #(str/ends-with? % ".class"))
           (filter #(= (source-file-attr (class-file->class-name abs-classes %)) source-file))))))

(defn- find-properties-file
  "Find the bot's .properties file in classes-dir."
  [classname]
  (let [props-path (str classes-dir "/" (namespace->path classname) ".properties")]
    (when (fs/exists? props-path) props-path)))

(defn- find-bots []
  (->> (fs/glob "src" "**/*.properties")
       (map (fn [props-path]
              (let [props (read-properties props-path)
                    classname (get props "robot.classname")
                    version (get props "robot.version")]
                {:classname classname
                 :version version
                 :properties-path props-path})))))

(defn- create-bot-jar! [{:keys [classname version]}]
  (let [jar-name (str classname "_" version ".jar")
        jar-path (str (fs/absolutize (str "site/" jar-name)))
        abs-classes (str (fs/absolutize classes-dir))
        class-files (find-class-files classname)
        props-file (find-properties-file classname)]
    (println (str "  " jar-name))
    (let [entries (cond-> (mapv (fn [f] (subs (str (fs/absolutize f)) (inc (count abs-classes)))) class-files)
                    props-file (conj (subs (str (fs/absolutize props-file)) (inc (count abs-classes)))))]
      (apply p/shell {:dir classes-dir}
             "jar" "cf" jar-path
             entries))
    jar-name))

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
  "Build and deploy per-bot JARs to Netlify."
  []

  (println "Building...")
  (p/shell {:extra-env {"JAVA_HOME" "/Library/Java/JavaVirtualMachines/graalvm-23.jdk/Contents/Home"}}
           "./gradlew" "build")

  (fs/create-dirs "site")
  (println "Syncing existing jars...")
  (sync-existing-jars!)

  (println "Creating per-bot JARs:")
  (let [bots (remove (fn [{:keys [classname]}]
                       (empty? (find-class-files classname)))
                     (find-bots))
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
        (if (zero? (:exit @(p/process ["git" "tag" tag] {:inherit true})))
          (p/shell "git" "push" "origin" tag)
          (println (str "    (tag already exists, skipping)")))))

    (println "\nPublished JARs:")
    (doseq [name (sort all-jars)]
      (println (str "  " site-url "/" name)))))
