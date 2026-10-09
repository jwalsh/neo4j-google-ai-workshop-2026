;; clojure -M:dev experiments/006-structured-extraction/outline.clj MODEL [MODEL ...]
;; The decomposed extraction: one list per question, kept only if in the text.
(require '[clojure.pprint :as pprint] '[clojure.set] '[clojure.string] '[workshop.extract :as extract])
(def page-text (slurp "data/Apple_10-K_2025_2_pages.txt"))
(def wanted #{"homepod" "homepod mini" "apple tv 4k"})
(def results
  (vec (for [model-name *command-line-args*]
         (let [started (System/nanoTime)
               answer (try (extract/outline page-text
                                            "Which product categories does the company report? List the category names."
                                            #(str "Which products does the category \"" % "\" include? List the product names.")
                                            "INCLUDES"
                                            {:model model-name})
                           (catch Exception failure {:failed (ex-message failure)}))
               home (set (map clojure.string/lower-case (get-in answer [:members "Home"])))]
           {:model model-name
            :seconds (format "%.1f" (/ (- (System/nanoTime) started) 1e9))
            :groups (:groups answer)
            :home (get-in answer [:members "Home"])
            :gate (if (= wanted (clojure.set/intersection wanted home)) :passed :not-passed)
            :edges (count (:edges answer))
            :with-evidence (count (filter :evidence (:edges answer)))
            :dropped (into {} (remove (comp empty? val) (:dropped answer)))
            :members (:members answer)
            :failed (:failed answer)}))))
(require 'clojure.set)
(doseq [result results] (pprint/pprint result) (println))
(spit "experiments/006-structured-extraction/outline-results.edn" (with-out-str (pprint/pprint results)))
(shutdown-agents)
