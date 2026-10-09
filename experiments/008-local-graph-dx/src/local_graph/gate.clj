(ns local-graph.gate
  "clojure -M:gate. Starts embedded Neo4j Community on an empty store, runs
  the local load, and asks workshop.form13/verify whether the database holds
  what the file says. Prints what it measured as EDN and exits 1 when the
  counts disagree."
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [local-graph.embedded :as embedded]
            [local-graph.lab :as lab]
            [workshop.neo4j :as neo4j]))

(def exploration-queries
  "The questions dev/explore.clj asks of Aura under Labs 4 and 5."
  {:holders-of-nvidia
   ["MATCH (m:Manager)-[o:OWNS]->(c:Company {companyName: $name})
     RETURN m.managerName AS manager, o.value AS value, o.shares AS shares
     ORDER BY value DESC"
    {:name "NVIDIA CORPORATION"}]
   :manager-degree
   ["MATCH (m:Manager)-[o:OWNS]->(c:Company)
     RETURN m.managerName AS manager, count(c) AS companies, sum(o.value) AS value
     ORDER BY companies DESC LIMIT 10" {}]
   :company-degree
   ["MATCH (c:Company)<-[:OWNS]-(m:Manager)
     RETURN c.companyName AS company, count(m) AS managers
     ORDER BY managers DESC LIMIT 10" {}]
   :shared-holdings
   ["MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager)
     WHERE a.managerName < b.managerName
     RETURN a.managerName AS a, b.managerName AS b, count(c) AS shared
     ORDER BY shared DESC LIMIT 10" {}]})

(defn time-queries
  "Each exploration query twice: first run (planning included) and second."
  [driver]
  (into {}
        (map (fn [[query-name [statement parameters]]]
               (let [first-run (lab/timed (neo4j/query driver statement parameters))
                     second-run (lab/timed (neo4j/query driver statement parameters))]
                 [query-name {:rows (count (:value first-run))
                              :first-ms (:ms first-run)
                              :second-ms (:ms second-run)
                              :top (first (:value first-run))}])))
        exploration-queries))

(defn run-gate
  "The whole thing, from an empty store to a stopped server."
  [start-options]
  (embedded/stop!)
  (embedded/wipe! (:home start-options embedded/default-home))
  (let [started (embedded/start! start-options)
        measured
        (with-open [driver (neo4j/connect (embedded/connection-env))]
          (let [version (first (neo4j/query driver "CALL dbms.components() YIELD name, versions, edition RETURN name, versions[0] AS version, edition"))
                loaded (lab/load! driver)
                verification (lab/verify driver)
                reload (lab/load! driver)
                queries (time-queries driver)]
            {:server version
             :startup-ms (:startup-ms started)
             :load loaded
             :reload-total-ms (:total-ms reload)
             :verification-after-reload (:actual (lab/verify driver))
             :verification verification
             :queries queries
             :memory (lab/memory)
             :store-mb (lab/directory-megabytes (:home started))}))
        stopped (embedded/stop!)]
    (merge measured stopped)))

(defn run-bolt-gate
  "The same gate against a Bolt server something else started: a container.
  Empties the database first. csv-url is as the server sees the file;
  database is neo4j unless the engine calls it something else."
  [{:keys [uri username password csv-url files database]
    :or {files lab/local-files database "neo4j"}}]
  (lab/with-database database
    (with-open [driver (neo4j/connect (lab/bolt-env uri username password))]
      (let [connected (lab/timed (neo4j/ping driver))
            emptied (lab/attempt #(lab/query-auto-commit driver "MATCH (n) DETACH DELETE n"))
            loaded (lab/load! driver {:csv-url csv-url :files files})
            verification (lab/verify driver)
            reload (lab/load! driver {:csv-url csv-url :files files})]
        {:uri uri
         :database database
         :files (mapv #(.getName (io/file %)) files)
         :first-query-ms (:ms connected)
         :emptied? (:ok? emptied)
         :load loaded
         :verification verification
         :reload-total-ms (:total-ms reload)
         :verification-after-reload (:actual (lab/verify driver))
         :queries (time-queries driver)}))))

(defn- numbered-cypher-files
  [cypher-directory]
  (->> (.listFiles (io/file cypher-directory))
       (map str)
       (filter #(re-find #"/\d\d-[^/]+\.cypher$" %))
       sort
       vec))

(defn -main
  "No arguments or a page-cache size: the embedded gate. With
  `bolt URI USERNAME PASSWORD CSV-URL [cypher-directory [database]]`: the
  gate against that server. An empty username is written as a dash."
  [& [first-argument uri username password csv-url cypher-directory database]]
  (let [dash->empty #(if (= "-" %) "" %)
        result (if (= "bolt" first-argument)
                 (run-bolt-gate
                  (cond-> {:uri uri :username (dash->empty username)
                           :password (dash->empty password) :csv-url csv-url}
                    cypher-directory (assoc :files (numbered-cypher-files cypher-directory))
                    database (assoc :database database)))
                 (run-gate (cond-> {} first-argument (assoc :page-cache first-argument))))]
    (pprint result)
    (shutdown-agents)
    (System/exit (if (get-in result [:verification :loaded?]) 0 1))))
