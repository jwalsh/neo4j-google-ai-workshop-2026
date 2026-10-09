(ns local-graph.session
  "One local database for a REPL session: what dev/user.clj is for Aura.

  `up!` starts embedded Neo4j, opens a driver to it and loads the day file
  unless it is already there; `q` asks; `down!` closes both. The CIDER
  comment file and the Org file both go through this."
  (:require [local-graph.embedded :as embedded]
            [local-graph.lab :as lab]
            [workshop.neo4j :as neo4j])
  (:import (org.neo4j.driver Driver)))

(defonce ^{:doc "The open driver to the local server, or nil."} local-driver
  (atom nil))

(defn driver
  "The driver to the local server. Throws when `up!` has not run, and never
  falls back to the instance .env names."
  ^Driver []
  (or @local-driver
      (throw (ex-info "No local database. (local-graph.session/up!) first." {}))))

(defn up!
  "Server, driver, load, verify. Safe to call again: a running server is
  kept and a loaded database is not loaded twice."
  ([] (up! {}))
  ([start-options]
   (let [started (embedded/start! start-options)]
     (when-not @local-driver
       (reset! local-driver (neo4j/connect (embedded/connection-env (:port started)))))
     (let [before (lab/verify (driver))
           loaded (when-not (:loaded? before) (lab/load! (driver)))]
       (cond-> (assoc started :verification (lab/verify (driver)))
         loaded (assoc :load-ms (:total-ms loaded)))))))

(defn down!
  []
  (when-let [^Driver open-driver @local-driver]
    (.close open-driver)
    (reset! local-driver nil))
  (embedded/stop!))

(defn q
  "Rows from the local database, as workshop.neo4j/query returns them."
  ([statement] (q statement {}))
  ([statement parameters] (neo4j/query (driver) statement parameters)))

(defn table
  "Rows as a header and vectors of values, in the statement's column order:
  the shape Org Babel prints as a table."
  ([statement] (table statement {}))
  ([statement parameters]
   (let [result (-> (.executableQuery (driver) statement)
                    (.withParameters ^java.util.Map (update-keys parameters name))
                    (.execute))
         column-names (vec (.keys result))]
     (into [column-names]
           (map (fn [^org.neo4j.driver.Record record]
                  (mapv #(neo4j/->clj (.asObject (.get record ^String %))) column-names)))
           (.records result)))))

(defn- org-cell
  "A value as something the Emacs Lisp reader takes: numbers and strings as
  they are, dates as ISO text, anything else as its printed form in a string."
  [cell-value]
  (cond
    (nil? cell-value) ""
    (or (integer? cell-value) (string? cell-value)) cell-value
    (float? cell-value) (double cell-value)
    (instance? java.time.temporal.Temporal cell-value) (str cell-value)
    :else (pr-str cell-value)))

(defn org-table
  "The rows of `table` printed as an Emacs Lisp list with an hline under the
  header: what ob-cypher-cider.el reads back into an Org table. parameters
  has string keys, as Org's :var gives them."
  ([statement] (org-table statement {}))
  ([statement parameters]
   (let [[column-names & data-rows] (table statement parameters)]
     (pr-str (concat [(apply list column-names) 'hline]
                     (map #(apply list (map org-cell %)) data-rows))))))
