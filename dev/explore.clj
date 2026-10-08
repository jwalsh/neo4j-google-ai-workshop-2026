(ns explore
  "Forms to evaluate one at a time from the editor (C-x C-e in CIDER), lab by
  lab. Nothing here runs when the file is loaded: every block is a comment.
  Start a REPL with `bb repl` or cider-jack-in, then work down the page."
  (:require [clojure.pprint :refer [print-table]]
            [user :refer [counts dashboard holdings peek-dataset q run-file status]]
            [workshop.form13 :as form13]
            [workshop.graph-builder :as builder]
            [workshop.msa :as msa]
            [workshop.neo4j :as neo4j]))

;; Connections
(comment
  (status)
  (dashboard)                       ; => "http://127.0.0.1:8377/"
  (q "RETURN 1 AS ok")
  (q "SHOW DATABASES YIELD name, currentStatus, default")
  (counts)
  :rcf)

;; Lab 4: the day file before it is in the database
(comment
  (peek-dataset form13/object-name 3)
  (form13/summary (holdings))
  (print-table (form13/top-holdings (holdings) 10))
  (take 5 (form13/name-variants (holdings)))   ; one CUSIP, several names
  (print-table (form13/holders-of (holdings) "nvidia"))
  (print-table (form13/odd-prices (holdings)))
  :rcf)

;; Lab 4: the load, and whether the database holds what the file says
(comment
  (form13/verify (user/driver) (holdings))
  (form13/load! (user/driver) (holdings))      ; a no-op when :loaded? is true
  (run-file "labs/lab-04/03-constraints.cypher")
  (q "SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties")
  :rcf)

;; Lab 4: the same questions asked of the graph
(comment
  (q "MATCH (m:Manager) RETURN m LIMIT 3")
  (q "MATCH (m:Manager)-[o:OWNS]->(c:Company {companyName: $name})
      RETURN m.managerName AS manager, o.value AS value, o.shares AS shares
      ORDER BY value DESC"
     {:name "NVIDIA CORPORATION"})
  (q "MATCH (m:Manager)-[o:OWNS]->(c:Company)
      RETURN m.managerName AS manager, count(c) AS companies, sum(o.value) AS value
      ORDER BY companies DESC LIMIT 10")
  :rcf)

;; Lab 5: degree centrality, which Bloom computes with a Graph Analytics
;; session, two other ways: from the file, and in Cypher.
(comment
  (print-table (take 10 (form13/manager-degree (holdings))))
  (print-table (form13/company-degree (holdings) 10))
  (print-table
   (q "MATCH (c:Company)<-[:OWNS]-(m:Manager)
       RETURN c.companyName AS company, count(m) AS managers
       ORDER BY managers DESC LIMIT 10"))
  ;; managers who hold the same companies: the communities the lab hints at
  (print-table
   (q "MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager)
       WHERE a.managerName < b.managerName
       RETURN a.managerName AS a, b.managerName AS b, count(c) AS shared
       ORDER BY shared DESC LIMIT 10"))
  :rcf)

;; experiments/001-msa-similarity: the Dataproc notebook's similarity graph,
;; in memory. The census file is fetched as the experiment's README says.
(comment
  (def similarity-edges (msa/edges "data/census_demographics_by_metro_area.csv"))
  (count similarity-edges)                     ; => 5822
  (print-table (take 5 (msa/most-similar similarity-edges
                                         "Boston-Cambridge-Newton, MA-NH Metro Area")))
  :rcf)

;; Lab 6 by another road: the LLM Graph Builder's API (experiments/005).
;; Needs the login token; see workshop.graph-builder's docstring.
(comment
  (builder/connect)
  (builder/token-limits)
  (map #(select-keys % [:fileName :status :nodeCount :relationshipCount]) (builder/sources))
  (builder/run-lab-06!)                        ; a no-op once Completed
  (print-table (run-file "labs/lab-06/03-entities-by-label.cypher"))
  (print-table (q "MATCH (category:__Entity__ {id: 'Home'})-[relationship]->(member)
                   RETURN type(relationship) AS relationship, member.id AS member"))
  ;; from a chunk of text to what was read out of it
  (q "MATCH (chunk:Chunk)-[:HAS_ENTITY]->(entity)
      RETURN chunk.position AS chunk, collect(entity.id)[..8] AS entities
      ORDER BY chunk")
  ;; Lab 4's constraint is why the builder was not allowed the label Company
  (q "SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties")
  :rcf)

;; Values that come back from the driver
(comment
  (neo4j/->clj (first (q "MATCH p = (:Manager)-[:OWNS]->(:Company) RETURN p LIMIT 1")))
  :rcf)
