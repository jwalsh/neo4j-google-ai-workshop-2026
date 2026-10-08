(ns workshop.neo4j
  "The Aura instance, over Bolt with the official Java driver.

  `connect` opens a driver from the environment; `query` runs one statement
  and returns its rows as Clojure maps."
  (:require [clojure.string :as str]
            [workshop.env :as env])
  (:import (java.util List Map)
           (org.neo4j.driver AuthTokens Driver GraphDatabase QueryConfig Record)
           (org.neo4j.driver.types Node Path Relationship)))

(set! *warn-on-reflection* true)

(defn connect
  "A driver for the instance .env names. Close it, or use with-open.
  The returned driver carries the database name as metadata."
  (^Driver [] (connect (env/load-env)))
  (^Driver [env-map]
   (let [{:strs [NEO4J_URI NEO4J_USERNAME NEO4J_PASSWORD]}
         (env/require-vars env-map ["NEO4J_URI" "NEO4J_USERNAME" "NEO4J_PASSWORD"])]
     (GraphDatabase/driver ^String NEO4J_URI
                           (AuthTokens/basic NEO4J_USERNAME NEO4J_PASSWORD)))))

(defn database-name
  ([] (database-name (env/load-env)))
  ([env-map] (get env-map "NEO4J_DATABASE" "neo4j")))

(defn ->clj
  "A driver value as Clojure data. Nodes, relationships and paths become
  maps; lists and maps are converted through."
  [driver-value]
  (cond
    (instance? Node driver-value)
    (let [^Node node driver-value]
      {:labels (vec (.labels node))
       :properties (->clj (.asMap node))})

    (instance? Relationship driver-value)
    (let [^Relationship relationship driver-value]
      {:type (.type relationship)
       :properties (->clj (.asMap relationship))})

    (instance? Path driver-value)
    (let [^Path path driver-value]
      {:nodes (mapv ->clj (.nodes path))
       :relationships (mapv ->clj (.relationships path))})

    (instance? Map driver-value)
    (into {} (map (fn [[map-key map-value]] [(keyword map-key) (->clj map-value)]))
          ^Map driver-value)

    (instance? List driver-value)
    (mapv ->clj ^List driver-value)

    :else driver-value))

(defn query
  "Runs one Cypher statement in a managed transaction and returns a vector
  of row maps keyed by column keyword. parameters is a map with string or
  keyword keys."
  ([driver statement] (query driver statement {}))
  ([^Driver driver ^String statement parameters]
   (let [string-keyed (into {} (map (fn [[parameter-name parameter-value]]
                                      [(name parameter-name) parameter-value]))
                            parameters)
         result (-> (.executableQuery driver statement)
                    (.withParameters ^Map string-keyed)
                    (.withConfig (-> (QueryConfig/builder)
                                     (.withDatabase (database-name))
                                     (.build)))
                    (.execute))]
     (mapv (fn [^Record record] (->clj (.asMap record)))
           (.records result)))))

(defn statements
  "The statements in Cypher text: split where a semicolon ends a line, with
  // comment lines dropped."
  [cypher-text]
  (->> (str/split cypher-text #";[ \t]*(?:\r?\n|$)")
       (map (fn [statement]
              (->> (str/split-lines statement)
                   (remove #(str/starts-with? (str/trim %) "//"))
                   (str/join "\n")
                   str/trim)))
       (remove str/blank?)
       vec))

(defn run-file
  "Runs each statement in a Cypher file in order. Returns a vector of
  {:statement ... :rows ...}."
  [driver cypher-path]
  (mapv (fn [statement] {:statement statement :rows (query driver statement)})
        (statements (slurp cypher-path))))

(defn ping
  [driver]
  (= [{:ok 1}] (query driver "RETURN 1 AS ok")))

(defn databases
  [driver]
  (query driver "SHOW DATABASES YIELD name, currentStatus, default"))

(defn counts
  "Node counts by label and relationship counts by type: what a load did."
  [driver]
  {:nodes (query driver (str "MATCH (n) UNWIND labels(n) AS label "
                             "RETURN label, count(*) AS count ORDER BY label"))
   :relationships (query driver (str "MATCH ()-[r]->() "
                                     "RETURN type(r) AS type, count(*) AS count ORDER BY type"))})
