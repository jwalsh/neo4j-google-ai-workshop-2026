;; Experiment 015. Every Cypher statement this repository holds, through
;; cypherfmt and through workshop.dashboard/format-statement, and what each
;; did to it. Run from the repository root:
;;
;;   CYPHERFMT=/path/to/node_modules/.bin/cypherfmt \
;;     clojure -M -i experiments/015-cypherfmt/compare.clj
;;
;; Writes results.edn and the journey statement in each layout beside this
;; file. Reads no database and no .env.
(ns compare
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.agent :as agent]
            [workshop.dashboard :as dashboard]))

(def here "experiments/015-cypherfmt")
(def journey-tool "Subway Journey Between Two Stations")

(def cypherfmt
  (or (System/getenv "CYPHERFMT")
      (throw (ex-info "Set CYPHERFMT to the cypherfmt command." {}))))

(defn statements
  "Every statement held, as {:source :name :text}."
  []
  (concat
   (for [{:keys [id] agent-path :agent} (agent/registry)
         {:keys [template] tool-name :name} (:tools (agent/load-agent agent-path))]
     {:source (str "agent " id) :name tool-name :text template})
   (for [{:keys [title statement]} dashboard/data-questions]
     {:source "dashboard /data" :name title :text statement})
   (for [query-file (sort (file-seq (io/file "labs")))
         :when (str/ends-with? (.getName query-file) ".cypher")]
     {:source "labs" :name (str query-file) :text (slurp query-file)})))

(defn run-cypherfmt
  "The text through cypherfmt on standard input: {:exit :out :err :millis}."
  [text]
  (let [started (System/nanoTime)
        process (.start (ProcessBuilder. ^java.util.List [cypherfmt]))]
    (with-open [to-formatter (.getOutputStream process)]
      (.write to-formatter (.getBytes ^String text "UTF-8")))
    (let [out (slurp (.getInputStream process))
          err (slurp (.getErrorStream process))
          exit (.waitFor process)]
      {:exit exit :out out :err (str/trim err)
       :millis (Math/round (/ (- (System/nanoTime) started) 1e6))})))

(defn squeezed [text] (str/replace text #"\s+" ""))

(defn shape
  [text]
  (let [lines (str/split-lines (str/trim text))]
    {:lines (count lines) :widest (apply max 0 (map count lines))}))

(defn first-difference
  "Thirty characters either side of where two squeezed texts part."
  [before after]
  (let [position (count (take-while true? (map = before after)))
        around (fn [text] (subs text (max 0 (- position 15)) (min (count text) (+ position 30))))]
    {:before (around before) :after (around after)}))

(defn examine
  [{:keys [text] :as statement}]
  (let [{:keys [exit out err millis]} (run-cypherfmt text)
        again (when (zero? exit) (run-cypherfmt out))
        before (squeezed text)
        after (squeezed out)
        ours (dashboard/format-statement text)]
    (merge (dissoc statement :text)
           {:characters (count text)
            :exit exit
            :millis millis
            :change (cond (not (zero? exit)) :failed
                          (= before after) :whitespace-only
                          (= (str/lower-case before) (str/lower-case after)) :letter-case
                          :else :more)
            :idempotent? (boolean (and again (= out (:out again))))
            :cypherfmt (when (zero? exit) (shape out))
            :workshop (shape ours)
            :formatted out}
           (when-not (zero? exit) {:err (subs err 0 (min 300 (count err)))})
           (when (and (zero? exit) (not= before after)) {:differs (first-difference before after)}))))

(let [results (mapv examine (statements))
      journey (first (filter #(= journey-tool (:name %)) results))
      journey-text (:text (first (filter #(= journey-tool (:name %)) (statements))))
      hand (slurp (str here "/journey.hand.cypher"))
      hand-check (run-cypherfmt hand)]
  (spit (str here "/journey.cypherfmt.cypher") (str (str/trim (:formatted journey)) "\n"))
  (spit (str here "/journey.workshop.cypher") (str (dashboard/format-statement journey-text) "\n"))
  (spit (str here "/results.edn")
        (with-out-str (pprint/pprint (mapv #(dissoc % :formatted) results))))
  (println (count results) "statements")
  (println "by change      " (into (sorted-map) (frequencies (map :change results))))
  (println "not idempotent " (mapv :name (remove :idempotent? (filter #(zero? (:exit %)) results))))
  (println "failed         " (mapv :name (filter #(= :failed (:change %)) results)))
  (let [timings (sort (map :millis results))]
    (println "millis a call   least" (first timings) "median" (nth timings (quot (count timings) 2))
             "most" (last timings)))
  (println "wider than 80   cypherfmt"
           (count (filter #(> (get-in % [:cypherfmt :widest] 0) 80) results))
           "workshop" (count (filter #(> (get-in % [:workshop :widest]) 80) results)))
  (println "journey         cypherfmt" (:cypherfmt journey) "workshop" (:workshop journey) "hand" (shape hand))
  (println "hand layout     same text as the template?" (= (squeezed hand) (squeezed journey-text))
           "| cypherfmt leaves it alone?" (= (str/trim hand) (str/trim (:out hand-check)))
           "| cypherfmt of hand = cypherfmt of template?"
           (= (str/trim (:out hand-check)) (str/trim (:formatted journey)))))
(shutdown-agents)
