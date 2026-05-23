(ns codesize
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def build-dir "build/classes/java/main")
(def codesize-jar "CodeSizeUtility/CodeSizeUtility.jar")

(defn- namespace->path
  "Converts a namespace like pez.mini.Pugilist to pez/mini/Pugilist"
  [ns-str]
  (str/replace ns-str "." "/"))

(defn- class-file->class-name
  "Converts a class file path to its JVM dotted class name relative to build-dir."
  [abs-build class-file]
  (-> (subs (str (fs/absolutize class-file)) (inc (count abs-build)))
      (str/replace ".class" "")
      (str/replace "/" ".")))

(defn- source-file-attr
  "Returns the SourceFile attribute of a compiled class, e.g. \"Aristocles.java\"."
  [class-name]
  (let [result (p/shell {:out :string :err :string :continue true}
                        "javap" "-classpath" build-dir "-verbose" class-name)
        line (first (filter #(str/includes? % "SourceFile:") (str/split-lines (:out result))))]
    (when line
      (second (re-find #"SourceFile:\s+\"(.+)\"" line)))))

(defn- find-class-files
  "Find all .class files belonging to a bot namespace.
   Only includes classes compiled from the same source file as the bot."
  [ns-str]
  (let [ns-path (namespace->path ns-str)
        class-dir (str build-dir "/" (fs/parent ns-path))
        source-file (str (fs/file-name ns-path) ".java")
        abs-build (str (fs/absolutize build-dir))]
    (when (fs/exists? class-dir)
      (->> (fs/list-dir class-dir)
           (map str)
           (filter #(str/ends-with? % ".class"))
           (filter #(= (source-file-attr (class-file->class-name abs-build %)) source-file))))))

(defn- find-properties-file [ns-str]
  (let [props-path (str build-dir "/" (namespace->path ns-str) ".properties")]
    (when (fs/exists? props-path) props-path)))

(defn- create-bot-jar!
  "Create a temporary jar containing only the bot's classes and properties."
  [ns-str]
  (let [abs-build (str (fs/absolutize build-dir))
        jar-path (str (fs/absolutize (str ".tmp/" (str/replace ns-str "." "-") ".jar")))
        class-files (find-class-files ns-str)
        props-file (find-properties-file ns-str)]
    (when (empty? class-files)
      (println (str "No class files found for " ns-str " in " build-dir))
      (println "Run `bb build` first.")
      (System/exit 1))
    (fs/create-dirs ".tmp")
    (let [entries (cond-> (mapv (fn [f] (subs (str (fs/absolutize f)) (inc (count abs-build)))) class-files)
                    props-file (conj (subs (str (fs/absolutize props-file)) (inc (count abs-build)))))]
      (apply p/shell {:dir build-dir}
             "jar" "cf" jar-path
             entries))
    jar-path))

(defn get-size
  "Return the codesize (as integer) for the given bot jar path, or nil."
  [jar-path]
  (let [result (p/shell {:out :string :in (java.io.ByteArrayInputStream. (.getBytes "exit\n"))}
                        "java" "-jar" codesize-jar jar-path)
        lines (str/split-lines (:out result))
        size-line (first (filter #(str/includes? % "Codesize") lines))]
    (when size-line
      (parse-long (re-find #"\d+" (subs size-line (str/index-of size-line ":")))))))

(defn check! [{:keys [bot]}]
  (when-not bot
    (println "Usage: bb codesize <namespace>")
    (println "Example: bb codesize pez.mini.Pugilist")
    (System/exit 1))
  (when-not (fs/exists? build-dir)
    (println "Build output not found. Run `bb build` first.")
    (System/exit 1))
  (let [jar-path (create-bot-jar! bot)
        size (get-size jar-path)]
    (when size (println (str "Codesize: " size)))
    (fs/delete jar-path)))
