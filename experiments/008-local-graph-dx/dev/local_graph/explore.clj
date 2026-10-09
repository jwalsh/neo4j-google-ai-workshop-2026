(ns local-graph.explore
  "Forms to evaluate one at a time from the editor (C-x C-e in CIDER). Nothing
  here runs when the file is loaded: every block is a comment. cider-jack-in
  from this directory (the .dir-locals.el picks this deps.edn and the :dev
  alias), then work down the page.

  The database is Neo4j Community inside the REPL's own JVM, on
  bolt://localhost:7688, with its store under scratch/. Nothing here can
  reach the hosted instance: every driver is made from an explicit env map."
  (:require [clojure.pprint :refer [print-table]]
            [local-graph.embedded :as embedded]
            [local-graph.gate :as gate]
            [local-graph.lab :as lab]
            [local-graph.session :as session :refer [q]]
            [workshop.form13 :as form13]
            [workshop.neo4j :as neo4j]))

;; Start, load, verify
(comment
  (session/up!)                     ; server, driver, load unless loaded, verify
  ;; => {:port 7688, :startup-ms ..., :verification {... :loaded? true}}
  (q "RETURN 1 AS ok")
  (q "CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition")
  (neo4j/counts (session/driver))
  (lab/verify (session/driver))
  (lab/memory)
  :rcf)

;; The pieces of up!, one at a time
(comment
  (embedded/start!)
  (embedded/connection-env)         ; what workshop.neo4j/connect is given
  (lab/database-name-in-effect)     ; what workshop.neo4j/query will send
  lab/csv-url
  (lab/run-file (session/driver) (first lab/local-files))
  (lab/load! (session/driver))      ; MERGE throughout: a second run adds nothing
  :rcf)

;; What the lab's own files do here, unchanged; see the README's log,
;; attempts 2 to 4. The last of the three fetches the CSV over https.
(comment
  ;; Node key: Enterprise only. On an empty store this throws 51N27. After
  ;; up!, it returns [] and creates nothing: IF NOT EXISTS matches the
  ;; UNIQUE constraint of the same name and calls it done.
  (lab/attempt #(neo4j/run-file (session/driver) (nth lab/lab-files 0)))
  ;; IN TRANSACTIONS in a managed transaction: refused.
  (lab/attempt #(neo4j/run-file (session/driver) (nth lab/lab-files 3)))
  ;; The same file through an auto-commit session: runs.
  (lab/attempt #(lab/run-file (session/driver) (nth lab/lab-files 3)))
  :rcf)

;; Lab 4: the day file before it is in the database (dev/explore.clj's forms)
(comment
  (def holdings (lab/holdings))
  (form13/summary holdings)
  (print-table (form13/top-holdings holdings 10))
  (take 5 (form13/name-variants holdings))
  (print-table (form13/holders-of holdings "nvidia"))
  :rcf)

;; Lab 4: the same questions asked of the graph (dev/explore.clj's queries)
(comment
  (q "SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties")
  (q "MATCH (m:Manager) RETURN m LIMIT 3")
  (q "MATCH (m:Manager)-[o:OWNS]->(c:Company {companyName: $name})
      RETURN m.managerName AS manager, o.value AS value, o.shares AS shares
      ORDER BY value DESC"
     {:name "NVIDIA CORPORATION"})
  (q "MATCH (m:Manager)-[o:OWNS]->(c:Company)
      RETURN m.managerName AS manager, count(c) AS companies, sum(o.value) AS value
      ORDER BY companies DESC LIMIT 10")
  :rcf)

;; Lab 5: degree centrality, from the file and in Cypher
(comment
  (print-table (take 10 (form13/manager-degree holdings)))
  (print-table (form13/company-degree holdings 10))
  (print-table
   (q "MATCH (c:Company)<-[:OWNS]-(m:Manager)
       RETURN c.companyName AS company, count(m) AS managers
       ORDER BY managers DESC LIMIT 10"))
  (print-table
   (q "MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager)
       WHERE a.managerName < b.managerName
       RETURN a.managerName AS a, b.managerName AS b, count(c) AS shared
       ORDER BY shared DESC LIMIT 10"))
  ;; the file and the graph agree on the answer
  (= (map (juxt :company :managers) (form13/company-degree holdings 3))
     (map (juxt :company :managers)
          (q "MATCH (c:Company)<-[:OWNS]-(m:Manager)
              RETURN c.companyName AS company, count(m) AS managers
              ORDER BY managers DESC, company LIMIT 3")))
  :rcf)

;; Values that come back from the driver
(comment
  (neo4j/->clj (first (q "MATCH p = (:Manager)-[:OWNS]->(:Company) RETURN p LIMIT 1")))
  (session/table "MATCH (m:Manager) RETURN m.managerName AS manager LIMIT 3")
  :rcf)

;; Timings, and starting over
(comment
  (gate/time-queries (session/driver))
  (lab/delete-all! (session/driver))
  (lab/verify (session/driver))     ; zeros: see the README on what verify says of a half load
  (lab/load! (session/driver))
  :rcf)

;; Stop. The store stays under scratch/; (embedded/wipe!) empties it.
(comment
  (session/down!)
  (embedded/wipe!)
  :rcf)
