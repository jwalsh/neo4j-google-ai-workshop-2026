(ns local-graph.lab
  "Lab 4's load and the repository's gate, pointed at a local Bolt server.

  Everything that touches a database takes a driver made from an explicit
  env map (local-graph.embedded/connection-env or `bolt-env`). Nothing here
  calls workshop.neo4j/connect with no arguments, which is the arity that
  reads .env and reaches the hosted instance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.neo4j :as neo4j])
  (:import (java.io File)
           (org.neo4j.driver Driver Record Session SessionConfig)))

(set! *warn-on-reflection* true)

(def ^File repository-root
  "The directory that holds labs/lab-04, found by walking up from the working
  directory: this code runs from the experiment (CIDER, clojure -M:gate) and
  from the repository root (ob-clojure in a root REPL) alike."
  (loop [^File candidate (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? candidate)
      (throw (ex-info "No labs/lab-04 above the working directory." {}))
      (.isDirectory (io/file candidate "labs" "lab-04")) candidate
      :else (recur (.getParentFile candidate)))))

(def experiment-directory
  (io/file repository-root "experiments" "008-local-graph-dx"))

(def csv-file
  (io/file repository-root form13/local-path))

(def csv-url
  "The day file as a file: URL, the form LOAD CSV takes."
  (str (.toURI ^File csv-file)))

(defn bolt-env
  "An env map for any Bolt server, for workshop.neo4j/connect."
  [uri username password]
  {"NEO4J_URI" uri "NEO4J_USERNAME" username "NEO4J_PASSWORD" password
   "NEO4J_DATABASE" "neo4j"})

(defn holdings
  "The parsed day file. workshop.form13 reads a path relative to the working
  directory and downloads the file when it is not there; binding the path to
  an absolute one makes it read the copy in the repository's data/."
  []
  (when-not (.exists ^File csv-file)
    (throw (ex-info (str csv-file " is missing. From the repository root: "
                         "gmake data/form13-2023-05-11.csv")
                    {:path (str csv-file)})))
  (with-redefs [form13/local-path (str csv-file)]
    (form13/read-rows)))

(defmacro timed
  "The value of body with how long it took: {:ms ... :value ...}."
  [& body]
  `(let [started-nanoseconds# (System/nanoTime)
         value# (do ~@body)]
     {:ms (/ (- (System/nanoTime) started-nanoseconds#) 1e6) :value value#}))

(def ^:dynamic *database*
  "The database name sent with each statement. Neo4j's default is neo4j;
  Memgraph's is memgraph, and it refuses any other."
  "neo4j")

(defmacro with-database
  "Runs body with every statement addressed to database-name.
  workshop.neo4j/query takes the name from .env or the process environment
  and has no argument for it, so the var behind it is rebound here."
  [database-name & body]
  `(let [database-name# ~database-name]
     (binding [*database* database-name#]
       (with-redefs [neo4j/database-name (constantly database-name#)]
         ~@body))))

(defn query-auto-commit
  "One statement in an implicit (auto-commit) transaction. workshop.neo4j/query
  runs a managed transaction, and Neo4j refuses CALL { } IN TRANSACTIONS
  inside one; this is the other way in."
  ([driver statement] (query-auto-commit driver statement {}))
  ([^Driver driver ^String statement parameters]
   (let [string-keyed (into {} (map (fn [[parameter-name parameter-value]]
                                      [(name parameter-name) parameter-value]))
                            parameters)]
     (with-open [^Session session (.session driver (SessionConfig/forDatabase *database*))]
       (mapv (fn [^Record record] (neo4j/->clj (.asMap record)))
             (.list (.run session statement ^java.util.Map string-keyed)))))))

(defn- needs-auto-commit?
  "Statements a managed transaction is refused for: CALL { } IN TRANSACTIONS
  on Neo4j and Memgraph, and on Memgraph any constraint or index statement
  (\"not allowed in multicommand transactions\"). Neo4j takes schema
  statements either way, so they all go the same road."
  [statement]
  (boolean (or (re-find #"(?i)\bIN\s+(\d+\s+CONCURRENT\s+)?TRANSACTIONS\b" statement)
               (re-find #"(?i)^\s*USING\s+PERIODIC\s+COMMIT\b" statement)
               (re-find #"(?i)^\s*(CREATE|DROP)\s+(CONSTRAINT|INDEX)\b" statement))))

(defn run-file
  "Each statement of a Cypher file, in order, with parameters. A statement
  that batches its own transactions goes through query-auto-commit. Returns
  a vector of {:statement :ms :rows}."
  ([driver cypher-path] (run-file driver cypher-path {}))
  ([driver cypher-path parameters]
   (mapv (fn [statement]
           (let [used-parameters (select-keys parameters
                                              (filter #(str/includes? statement (str "$" (name %)))
                                                      (keys parameters)))
                 {:keys [ms value]}
                 (timed (if (needs-auto-commit? statement)
                          (query-auto-commit driver statement used-parameters)
                          (neo4j/query driver statement used-parameters)))]
             {:statement statement :ms ms :rows value}))
         (neo4j/statements (slurp (io/file cypher-path))))))

(def lab-files
  "Lab 4's four files, absolute, as workshop.form13/load-files names them."
  (mapv #(str (io/file repository-root %)) form13/load-files))

(def local-files
  "This experiment's rewrite of the same four, for a local Community server."
  (mapv #(str (io/file experiment-directory "cypher" %))
        ["01-constraints.cypher" "02-companies.cypher"
         "03-managers.cypher" "04-relationships.cypher"]))

(defn load!
  "Runs a load: the local files with the local CSV unless told otherwise.
  :csv-url is what $csvUrl becomes, and has to be a path the server can
  read, which for a container is a path inside it. Returns one {:file :ms}
  per file and the total."
  ([driver] (load! driver {}))
  ([driver {:keys [files] file-url :csv-url :or {files local-files file-url csv-url}}]
   (let [steps (mapv (fn [cypher-path]
                       (let [{:keys [ms]} (timed (run-file driver cypher-path {:csvUrl file-url}))]
                         {:file (.getName (io/file cypher-path)) :ms ms}))
                     files)]
     {:steps steps :total-ms (reduce + (map :ms steps))})))

(defn verify
  "workshop.form13/verify: the database's counts beside the file's."
  [driver]
  (form13/verify driver (holdings)))

(defn delete-all!
  "Empties the database's data, in batches, and leaves the constraints."
  [driver]
  (query-auto-commit driver "MATCH (n) CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 1000 ROWS"))

(defn attempt
  "Runs thunk and returns {:ok? true :ms :value} or {:ok? false :error}, so a
  log of attempts can hold the failures beside the passes."
  [thunk]
  (try
    (let [{:keys [ms value]} (timed (thunk))]
      {:ok? true :ms ms :value value})
    (catch Throwable problem
      {:ok? false
       :error (str/join " | " (distinct (keep #(some-> ^Throwable % .getMessage)
                                              (take-while some? (iterate #(.getCause ^Throwable %) problem)))))})))

(defn database-name-in-effect
  "What workshop.neo4j/query will send as the database name. It reads .env in
  the working directory and the process environment, not the map connect was
  given, so it is worth looking at before trusting a query."
  []
  (neo4j/database-name (env/load-env)))

(defn memory
  "This JVM's heap in megabytes after a collection, and the resident set size
  the operating system reports for the process."
  []
  (System/gc)
  (let [runtime (Runtime/getRuntime)
        megabytes #(/ (double %) 1024 1024)
        process-id (.pid (java.lang.ProcessHandle/current))
        resident-kilobytes (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["ps" "-o" "rss=" "-p" (str process-id)]))
                               (.start) (.getInputStream) slurp str/trim parse-long)]
    {:heap-used-mb (megabytes (- (.totalMemory runtime) (.freeMemory runtime)))
     :heap-max-mb (megabytes (.maxMemory runtime))
     :resident-mb (/ resident-kilobytes 1024.0)}))

(defn directory-megabytes
  [directory-path]
  (/ (double (reduce + (map #(.length ^File %) (filter #(.isFile ^File %) (file-seq (io/file directory-path))))))
     1024 1024))
