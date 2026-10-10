(ns user
  "What a REPL starts in. One driver for the session, opened on first use.

    (q \"MATCH (n) RETURN count(n) AS nodes\")
    (q \"MATCH (m:Manager) WHERE m.managerName = $name RETURN m\" {:name \"...\"})
    (run-file \"labs/lab-03-connect.cypher\")
    (datasets)
    (status)
    (dashboard)
    (use-env! \"books-graph.env\")   ; another graph, same REPL
    (peek-dataset \"hands-on-lab/form13-2023.csv\")"
  (:require [clojure.edn :as edn]
            [workshop.annotations :as annotations]
            [workshop.books :as books]
            [workshop.dashboard :as dashboard]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.gcp :as gcp]
            [workshop.neo4j :as neo4j]
            [workshop.status :as status]))

(defonce ^:private session-driver (atom nil))

;; The env file the session's driver is opened from. nil is the default:
;; WORKSHOP_ENV if the process has it, else .env.
(defonce ^:private session-env-file (atom nil))

(defn driver
  "The session's driver, opened the first time it is asked for, from the
  env file `use-env!` named or else from .env."
  []
  (or @session-driver
      (reset! session-driver
              (neo4j/connect (if-let [env-file @session-env-file]
                               (env/load-env env-file)
                               (env/load-env))))))

(defn disconnect
  "Closes the session's driver. The next call to `driver` reads the env
  file again."
  []
  (when-let [open-driver @session-driver]
    (.close ^java.lang.AutoCloseable open-driver)
    (reset! session-driver nil)))

(defn use-env!
  "Points `q` and the rest at the Neo4j another env file names, without a
  new REPL: (use-env! \"books-graph.env\"). nil goes back to .env. Returns
  the file, the server and how many nodes it has, so a wrong or stopped
  server shows at once."
  [env-file]
  (disconnect)
  (reset! session-env-file env-file)
  {:env (or env-file (System/getenv "WORKSHOP_ENV") ".env")
   :uri (get (if env-file (env/load-env env-file) (env/load-env)) "NEO4J_URI")
   :nodes (:nodes (first (neo4j/query (driver) "MATCH (n) RETURN count(n) AS nodes")))})

(defn q
  "Runs one Cypher statement and returns its rows."
  ([statement] (neo4j/query (driver) statement))
  ([statement parameters] (neo4j/query (driver) statement parameters)))

(defn table
  "One statement's rows as a vector of vectors of strings, the column names
  first: what an org-babel block prints as a table. env-file names another
  graph for this one statement and leaves the session's alone; nil asks the
  session's. Columns keep the statement's order up to eight of them."
  ([env-file statement] (table env-file statement {}))
  ([env-file statement parameters]
   (let [rows (if env-file
                (with-open [other-driver (neo4j/connect (env/load-env env-file))]
                  (neo4j/query other-driver statement parameters))
                (q statement parameters))
         columns (vec (keys (first rows)))
         cell (fn [value] (if (string? value) value (pr-str value)))]
     (into [(mapv name columns)]
           (map (fn [row] (mapv #(cell (get row %)) columns)))
           rows))))

(defn shelf-books
  "The shelf's books as [{:id :title}]: what a note can be about."
  []
  (mapv #(select-keys % [:id :title]) (edn/read-string (slurp books/default-shelf))))

(defn annotate!
  "Keeps a note on a book in the XDG data directory, outside the repository:
  (annotate! {:book \"graph-databases\" :page 30 :text \"...\"}). bb books-load
  or (load-annotations!) puts the notes in the books graph."
  [note]
  (annotations/annotate! note))

(defn annotations-org
  "One book's notes as org text, for M-x workshop-annotations."
  [book]
  (annotations/org book (annotations/annotations book)))

(defn load-annotations!
  "Replaces the books graph's notes with the store's, whatever graph the
  session is on."
  []
  (with-open [books-driver (neo4j/connect (env/load-env "books-graph.env"))]
    (annotations/load! books-driver (annotations/rows (annotations/annotations) (books/outlines)))))

(defn run-file [cypher-path] (neo4j/run-file (driver) cypher-path))
(defn ping [] (neo4j/ping (driver)))
(defn counts [] (neo4j/counts (driver)))

(defn datasets [] (gcp/list-objects (gcp/public-storage)))

(defn peek-dataset
  ([object-name] (peek-dataset object-name 5))
  ([object-name line-count]
   (gcp/peek-object (gcp/public-storage) gcp/dataset-bucket object-name line-count)))

(defonce ^:private stop-dashboard (atom nil))

(defn dashboard
  "Starts the status page, or restarts it after a change to its code."
  ([] (dashboard dashboard/default-port))
  ([port]
   (when-let [stop @stop-dashboard] (stop))
   (reset! stop-dashboard (dashboard/start port))
   (str "http://127.0.0.1:" port "/")))

(defn status [] (status/all-checks (driver)))

(defn holdings
  "The Form 13 day file as maps, from data/ (downloaded on first use)."
  []
  @form13/rows)

(defn env-names
  "Which connection variables are set. Names only."
  []
  (sort (filter #(re-find #"^(NEO4J|AURA|GOOGLE_CLOUD)_" %) (keys (env/load-env)))))
