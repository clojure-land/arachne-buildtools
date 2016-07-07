(ns arachne.buildtools
  "Tools for building the Arachne project itself."
  {:boot/export-tasks true}
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [boot.core :as b]
            [boot.task.built-in :as task]
            [boot.util :as bu]
            [arachne.buildtools.git :as g]))

(defn- dev-dep?
  [dep]
  (some #(= [:scope "test"] %) (partition 2 1 dep)))

(defn- generate-project-clj!
  []
  (let [proj-file (io/file "project.clj")
        pom (:task-options (meta #'task/pom))
        head (list 'defproject
                    (:project pom)
                    (:version pom)
                    :dependencies (vec
                                   (filter (complement dev-dep?)
                                           (b/get-env :dependencies)))
                    :resource-paths #{"resources"}
                    :source-paths #{"src"}
                    :test-paths #{"test"}
                    :profiles {:dev {:dependencies (vec
                                                    (filter dev-dep?
                                                            (b/get-env :dependencies)))
                                     :source-paths #{"dev"}}})
        txt (bu/pp-str head)
        txt (str ";; WARNING: this file is automatically generated for Cursive compatibility\n ;; Edit project.edn or build.boot if you want to modify the real configuration\n" txt)]
    (println "Regenerating project.clj file for " (:project pom) (:version pom))
    (spit proj-file txt)))

(defn- set-pom-options!
  "Imperatively set the pom task options"
  [{:keys [project version description license]}]
  (let [{:keys [major minor patch qualifier]} version]
    (b/task-options!
     task/pom
     {:project project
      :description description
      :license license
      :version (str major "."
                    minor "."
                    patch (cond
                           (= :dev qualifier) (str "-dev-" (g/current-sha "."))
                           qualifier (str "-" qualifier)
                           :else ""))})))

(defn- git-dep
  "Given a dependency form from project.edn, clone/update and install
  it locally if it is a git dependency. No-op if it isn't a git
  dependency."
  [[name version :as dep]]
  (if (string? version)
    dep
    [name (g/ensure-installed! dep)]))

(defn- set-env!
  "Sets the boot environment. Hardcodes things that will be common
  across all Arachne projects."
  [proj-data]
  (b/set-env!
   :resource-paths #{"src" "resources"}
   :source-paths #{"test" "dev"}
   :dependencies (fn [deps]
                   (filter #(not= 'org.arachne-framework/arachne-buildtools (first %))
                           (concat deps (doall (map git-dep (:deps proj-data))))))))

(defn read-project!
  "Imperatively read the project edn file and use it to set env and
  task options. Every time this is run, it will also spit out an
  updated project.clj file."
  [filename]
  (let [proj-file (io/file filename)]
    (when-not (.exists proj-file)
      (throw (ex-info (format "Could not find file %s in process directory (%s)"
                              filename
                              (.getCanonicalPath (io/file ".")))
                      {:filename filename})))
    (let [proj-data (edn/read-string (slurp proj-file))]
      (set-pom-options! proj-data)
      (set-env! proj-data)
      (generate-project-clj!))))

(b/deftask print-version
  "Print out the artifact version based on the current POM configuration"
  []
  (println "Installed Version:" (:version (:task-options (meta #'task/pom))))
  identity)

(b/deftask build
  "Build the project and install to local maven repo"
  []
  (g/throw-if-not-clean "." "Cannot build: git repository has uncommitted changes")
  (comp (task/pom) (task/jar) (task/install) (print-version)))

