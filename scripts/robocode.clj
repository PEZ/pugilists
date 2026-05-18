(ns robocode
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(def robocode-home
  (str (fs/expand-home "~/robocode")))

(defn- java-major-version []
  (let [version-output (:out (p/shell {:out :string} "java" "-version"))
        ;; java -version outputs to stderr
        version-err (:err (p/shell {:err :string} "java" "-version"))
        version-str (or (re-find #"\"([^\"]+)\"" version-err) "")
        version-part (first (clojure.string/split (second (re-find #"\"([^\"]+)\"" version-err)) #"\+"))
        major (first (clojure.string/split version-part #"\."))]
    (if (= major "1")
      (second (clojure.string/split version-part #"\."))
      major)))

(defn- java-options []
  (let [major (parse-long (java-major-version))]
    (when (and (> major 11) (< major 24))
      ["-Djava.security.manager=allow"])))

(defn launch! [_opts]
  (let [java-opts (java-options)
        env (if java-opts
              {"_JAVA_OPTIONS" (clojure.string/join " " java-opts)}
              {})]
    (p/shell {:dir robocode-home
              :extra-env env}
             "java"
             "-cp" "libs/*"
             "-Xmx512M"
             "-Xdock:name=Robocode"
             "-Xdock:icon=robocode.ico"
             "-XX:+IgnoreUnrecognizedVMOptions"
             "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
             "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
             "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED"
             "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
             "robocode.Robocode")))

(defn roborumble! [_opts]
  (let [java-opts (java-options)
        env (cond-> {"JAVA_HOME" "/Users/pez/.sdkman/candidates/java/17.0.17-tem"}
              java-opts (assoc "_JAVA_OPTIONS" (clojure.string/join " " java-opts)))]
    (p/shell {:dir robocode-home
              :extra-env env}
             (str "/Users/pez/.sdkman/candidates/java/17.0.17-tem/bin/java")
             "-cp" "libs/*"
             "-Xmx512M"
             "-XX:+IgnoreUnrecognizedVMOptions"
             "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
             "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
             "roborumble.RoboRumbleAtHome"
             "./roborumble/roborumble.txt")))
