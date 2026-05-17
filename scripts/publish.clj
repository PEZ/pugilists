(ns publish
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def properties-file "src/pez/mini/Pugilist.properties")

(defn- current-version []
  (some->> (slurp properties-file)
           str/split-lines
           (some #(when (str/starts-with? % "robot.version=")
                    (subs % (count "robot.version="))))))

(defn- set-version! [version]
  (let [content (slurp properties-file)
        updated (str/replace content
                             #"robot\.version=.*"
                             (str "robot.version=" version))]
    (spit properties-file updated)
    (println (str "Version set to " version " in " properties-file))))

(defn publish!
  "Build, copy JAR to site/, and deploy to Netlify.
   Pass version as argument: bb publish 2.5.4"
  [{:keys [version]}]
  (let [current (current-version)]
    (if version
      (set-version! version)
      (println (str "Publishing current version: " current)))

    (println "Building...")
    (p/shell {:extra-env {"JAVA_HOME" "/Library/Java/JavaVirtualMachines/graalvm-23.jdk/Contents/Home"}}
             "./gradlew" "build")

    (fs/create-dirs "site")
    (fs/copy "build/libs/pugilists.jar" "site/pugilists.jar"
             {:replace-existing true})
    (println "JAR copied to site/pugilists.jar")

    (println "Deploying to Netlify...")
    (p/shell "netlify" "deploy" "--prod" "--dir=site")
    (println (str "\nPublished version: " (or version current)))))
