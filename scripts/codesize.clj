(ns codesize
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def build-dir "build/classes/java/main")

(def codesize-jar "CodeSizeUtility/CodeSizeUtility.jar")

(def java-home "/Users/pez/.sdkman/candidates/java/21.0.11-amzn")

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

(defn- extract-class-name [code]
  (second (re-find #"public class (\w+)" code)))

(defn check-code! [{:keys [code]}]
  (when (str/blank? code)
    (println "No code provided. Pipe Java source via stdin: cat Bot.java | bb codesize --code")
    (System/exit 1))
  (let [class-name (extract-class-name code)]
    (when-not class-name
      (println "Could not find 'public class Foo' in provided code.")
      (System/exit 1))
    (let [tmp-dir (str (fs/create-temp-dir))
          classes-dir (str (fs/create-dirs (str tmp-dir "/classes")))
          src-file (str tmp-dir "/" class-name ".java")
          jar-path (str tmp-dir "/bot.jar")
          javac (str java-home "/bin/javac")
          robocode-jar (str (fs/home) "/robocode/libs/robocode.jar")]
      (try
        (spit src-file code)
        (let [result (p/shell {:continue true :out :string :err :string}
                              javac "-cp" robocode-jar "-d" classes-dir src-file)]
          (when (not= 0 (:exit result))
            (println "Compilation failed:")
            (println (:err result))
            (System/exit 1)))
        (let [class-files (->> (fs/glob classes-dir "**.class") (mapv str))]
          (when (empty? class-files)
            (println "No class files produced.")
            (System/exit 1))
          (apply p/shell {:dir classes-dir}
                 "jar" "cf" jar-path
                 (mapv #(str (fs/relativize classes-dir %)) class-files)))
        (let [size (get-size jar-path)]
          (when size (println (str "Codesize: " size))))
        (finally
          (fs/delete-tree tmp-dir))))))
