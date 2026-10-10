;; clojure -M:dev experiments/013-laptop-models/run.clj MODEL [MODEL ...]
;; Every question set, each model, twice: with the model asked once (the
;; agent as experiment 009 measured it) and with the second question for the
;; chosen tool's own arguments. Needs the three servers and an Ollama
;; (OLLAMA_URL). Prints the scores and writes results.edn.
(require '[clojure.edn :as edn]
         '[clojure.pprint :as pprint]
         '[workshop.agent :as agent])

(def question-files
  {"form13" "experiments/009-local-agent/questions.edn"
   "papers" "experiments/009-local-agent/questions-papers.edn"
   "mbta" "experiments/013-laptop-models/questions-mbta.edn"})

(defn grade
  "As experiment 009 grades. right: the expected tool ran with the expected
  parameters, or nothing ran when nothing should. refused: nothing ran when
  something should have. wrong: rows came back that do not answer the
  question."
  [{expected-tool :tool expected-parameters :parameters} {:keys [tool parameters rows]}]
  (let [ran? (some? rows)]
    (cond (and (nil? expected-tool) (not ran?)) :right
          (nil? expected-tool) :wrong
          (not ran?) :refused
          (and (= expected-tool tool)
               (= expected-parameters (select-keys parameters (keys expected-parameters)))) :right
          :else :wrong)))

(def results
  (vec (for [model-name *command-line-args*
             calls [:one :two]
             [agent-id question-file] question-files
             expected (edn/read-string (slurp question-file))]
         (let [answer (try (agent/ask-agent agent-id (:question expected)
                                            {:model model-name :single-call (= :one calls)})
                           (catch Exception failure {:problem (ex-message failure)}))
               chose (get-in answer [:choice :tool])]
           {:model model-name
            :calls calls
            :agent agent-id
            :question (:question expected)
            :grade (grade expected answer)
            ;; The model's part alone: did it name the tool, or "none" when
            ;; no tool answers. The checks around it can still save or sink
            ;; the answer.
            :tool-chosen? (= chose (or (:tool expected) agent/no-tool))
            :chose chose
            :parameters (:parameters answer)
            :dropped (get-in answer [:choice :dropped])
            :problem (:problem answer)
            :rows (count (:rows answer))
            :seconds (get-in answer [:choice :seconds])}))))

(defn- scores
  [group-keys]
  (for [[group group-results] (sort-by (comp vec key) (group-by (apply juxt group-keys) results))
        :let [grades (frequencies (map :grade group-results))
              seconds (sort (keep :seconds group-results))]]
    (merge (zipmap group-keys group)
           {:right (grades :right 0) :refused (grades :refused 0) :wrong (grades :wrong 0)
            :of (count group-results)
            :tool-chosen (count (filter :tool-chosen? group-results))
            :median-seconds (format "%.2f" (nth seconds (quot (count seconds) 2)))})))

(pprint/print-table (scores [:model :calls :agent]))
(pprint/print-table (scores [:model :calls]))
(println "\nnot right:")
(pprint/print-table [:model :calls :agent :grade :question :chose :parameters :dropped :problem]
                    (remove #(= :right (:grade %)) results))
(spit "experiments/013-laptop-models/results.edn" (with-out-str (pprint/pprint results)))
(shutdown-agents)
