(ns user
  "What a REPL starts in. One driver for the session, opened on first use.

    (q \"MATCH (n) RETURN count(n) AS nodes\")
    (q \"MATCH (m:Manager) WHERE m.managerName = $name RETURN m\" {:name \"...\"})
    (run-file \"labs/lab-03-connect.cypher\")
    (datasets)
    (status)
    (dashboard)
    (peek-dataset \"hands-on-lab/form13-2023.csv\")"
  (:require [workshop.dashboard :as dashboard]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.gcp :as gcp]
            [workshop.neo4j :as neo4j]
            [workshop.status :as status]))

(defonce ^:private session-driver (atom nil))

(defn driver
  "The session's driver, opened from .env the first time it is asked for."
  []
  (or @session-driver (reset! session-driver (neo4j/connect))))

(defn disconnect
  "Closes the session's driver. The next call to `driver` reads .env again."
  []
  (when-let [open-driver @session-driver]
    (.close ^java.lang.AutoCloseable open-driver)
    (reset! session-driver nil)))

(defn q
  "Runs one Cypher statement and returns its rows."
  ([statement] (neo4j/query (driver) statement))
  ([statement parameters] (neo4j/query (driver) statement parameters)))

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
