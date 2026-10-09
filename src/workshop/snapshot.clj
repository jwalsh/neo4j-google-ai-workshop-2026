(ns workshop.snapshot
  "The graph written out as EDN, because the instance does not outlive the
  lab. Nodes and relationships with their element ids, so the edges can be
  put back; values EDN cannot hold (dates, date-times) become strings."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]
            [workshop.neo4j :as neo4j]))

(defn- edn-safe
  [value]
  (walk/postwalk (fn [item]
                   (if (or (nil? item) (string? item) (number? item) (boolean? item)
                           (keyword? item) (coll? item))
                     item
                     (str item)))
                 value))

(defn graph
  "Nodes and relationships matching a WHERE clause on the node variable n.
  \"true\" takes everything. A relationship is kept when both ends are."
  [driver node-filter]
  (let [nodes (neo4j/query driver (str "MATCH (n) WHERE " node-filter
                                       " RETURN elementId(n) AS id, labels(n) AS labels, properties(n) AS properties"))
        kept-ids (set (map :id nodes))
        relationships (->> (neo4j/query driver (str "MATCH (n)-[r]->(m) WHERE " node-filter
                                                    " RETURN elementId(n) AS source, type(r) AS type,"
                                                    " properties(r) AS properties, elementId(m) AS target"))
                           (filterv #(kept-ids (:target %))))]
    (edn-safe {:nodes nodes :relationships relationships})))

(defn write!
  "Writes a snapshot to a path and returns what it held."
  [driver node-filter output-path]
  (let [snapshot (graph driver node-filter)]
    (io/make-parents output-path)
    (with-open [writer (io/writer output-path)]
      (binding [*out* writer *print-length* nil]
        (pprint/pprint snapshot)))
    {:path output-path
     :nodes (count (:nodes snapshot))
     :relationships (count (:relationships snapshot))
     :labels (frequencies (mapcat :labels (:nodes snapshot)))}))

(def document-graph-filter
  "Everything the Graph Builder wrote: what cannot be rebuilt from a file."
  "NOT n:Company AND NOT n:Manager")
