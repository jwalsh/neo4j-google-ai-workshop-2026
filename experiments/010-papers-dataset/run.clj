;; clojure -M:dev experiments/010-papers-dataset/run.clj [MODEL]
;; Needs `bb papers fetch` done, a Neo4j (WORKSHOP_ENV) and an Ollama
;; (OLLAMA_URL). Builds the three layers and loads them.
(require '[clojure.pprint :as pprint]
         '[clojure.string :as str]
         '[workshop.neo4j :as neo4j]
         '[workshop.papers :as papers]
         '[workshop.telemetry :as telemetry])

(def model-name (or (first *command-line-args*) "qwen2.5-coder:7b-instruct-q4_K_M"))
(def question "Which benchmarks or datasets does this paper name? List their names, not descriptions.")
(def all-papers (papers/index))
(def paper-graph (papers/graph all-papers))

(def mention-results
  (vec (for [paper all-papers]
         (let [answer (try (papers/mentions paper question {:model model-name})
                           (catch Exception failure {:failed (ex-message failure)}))]
           (println (:arxiv paper) (pr-str (:kept answer)) "dropped" (pr-str (:dropped answer)) (or (:failed answer) ""))
           (assoc answer :arxiv (:arxiv paper))))))

(def mention-rows
  (vec (for [{:keys [arxiv kept]} mention-results, mention kept] [arxiv (str/trim mention)])))

(with-open [driver (neo4j/connect)]
  (println "loaded:" (papers/load! driver paper-graph mention-rows))
  (println "again: " (papers/load! driver paper-graph mention-rows))
  (pprint/print-table
   (neo4j/query driver "MATCH (t:Topic)<-[:MENTIONS]-(p:Paper) WITH t, count(p) AS papers WHERE papers > 1 RETURN t.name AS mentioned, papers ORDER BY papers DESC, mentioned LIMIT 15"))
  (pprint/print-table
   (neo4j/query driver "MATCH (p:Paper)<-[:CITES]-(q:Paper) RETURN p.title AS paper, count(q) AS citedBy ORDER BY citedBy DESC LIMIT 5")))

(spit "experiments/010-papers-dataset/mentions.edn"
      (with-out-str (pprint/pprint {:model model-name :question question :results mention-results})))
(spit "experiments/010-papers-dataset/graph.edn" (with-out-str (pprint/pprint paper-graph)))
(pprint/print-table (filter #(#{"ollama" "cypher"} (:name %)) (telemetry/summary)))
(shutdown-agents)
