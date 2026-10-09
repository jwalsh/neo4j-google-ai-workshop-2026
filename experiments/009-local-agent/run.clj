;; clojure -M:dev experiments/009-local-agent/run.clj MODEL [MODEL ...]
;; Needs a server with the Form 13 graph (WORKSHOP_ENV) and an Ollama
;; (OLLAMA_URL). Prints one row per question and model, then the scores.
(require '[clojure.edn :as edn]
         '[clojure.pprint :as pprint]
         '[workshop.agent :as agent]
         '[workshop.neo4j :as neo4j])

(def questions (edn/read-string (slurp "experiments/009-local-agent/questions.edn")))
(def analyst (agent/load-agent))

(defn grade
  "right: the expected tool ran with the expected parameters, or nothing ran
  when nothing should. refused: nothing ran when something should have.
  wrong: rows came back that do not answer the question."
  [{expected-tool :tool expected-parameters :parameters} {:keys [tool parameters rows]}]
  (let [ran? (some? rows)]
    (cond (and (nil? expected-tool) (not ran?)) :right
          (nil? expected-tool) :wrong
          (not ran?) :refused
          (and (= expected-tool tool)
               (= expected-parameters (select-keys parameters (keys expected-parameters)))) :right
          :else :wrong)))

(def results
  (with-open [driver (neo4j/connect)]
    (vec (for [model-name *command-line-args*
               expected questions]
           (let [answer (try (agent/ask driver analyst (:question expected) {:model model-name})
                             (catch Exception failure {:problem (ex-message failure)}))]
             {:model model-name
              :question (:question expected)
              :grade (grade expected answer)
              :chose (get-in answer [:choice :tool])
              :parameters (:parameters answer)
              :dropped (get-in answer [:choice :dropped])
              :problem (:problem answer)
              :rows (count (:rows answer))
              :seconds (get-in answer [:choice :seconds])})))))

(pprint/print-table [:model :grade :question :chose :parameters :dropped :problem :rows] results)
(println)
(pprint/print-table
 (for [[model-name model-results] (group-by :model results)
       :let [grades (frequencies (map :grade model-results))]]
   {:model model-name :right (grades :right 0) :refused (grades :refused 0) :wrong (grades :wrong 0)
    :of (count model-results)
    :median-seconds (format "%.2f" (nth (sort (keep :seconds model-results))
                                        (quot (count (keep :seconds model-results)) 2)))}))
(spit "experiments/009-local-agent/results.edn" (with-out-str (pprint/pprint results)))
(shutdown-agents)
