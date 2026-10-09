(ns workshop.cli
  "clojure -M:run <command>. The tasks in bb.edn call this."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.agent :as agent]
            [workshop.audit :as audit]
            [workshop.books :as books]
            [workshop.labs :as labs]
            [workshop.mbta :as mbta]
            [workshop.dashboard :as dashboard]
            [workshop.form13 :as form13]
            [workshop.gcp :as gcp]
            [workshop.msa :as msa]
            [workshop.neo4j :as neo4j]
            [workshop.papers :as papers]
            [workshop.redact :as redact]
            [workshop.status :as status]))

(defn- print-rows
  [rows]
  (if (seq rows)
    (pprint/print-table rows)
    (println "(no rows)")))

(def commands
  {"ping"      {:doc "RETURN 1 against the instance in .env"
                :run (fn [_]
                       (with-open [driver (neo4j/connect)]
                         (println (if (neo4j/ping driver) "ok" "unexpected answer"))))}
   "databases" {:doc "SHOW DATABASES"
                :run (fn [_]
                       (with-open [driver (neo4j/connect)]
                         (print-rows (neo4j/databases driver))))}
   "counts"    {:doc "Node counts by label, relationship counts by type"
                :run (fn [_]
                       (with-open [driver (neo4j/connect)]
                         (let [{:keys [nodes relationships]} (neo4j/counts driver)]
                           (print-rows nodes)
                           (print-rows relationships))))}
   "cypher"    {:doc "Run every statement in a Cypher file: cypher labs/x.cypher"
                :run (fn [[cypher-path]]
                       (when-not cypher-path
                         (throw (ex-info "cypher needs a file." {})))
                       (with-open [driver (neo4j/connect)]
                         (doseq [{:keys [statement rows]} (neo4j/run-file driver cypher-path)]
                           (println (first (str/split-lines statement)))
                           (print-rows rows))))}
   "ask"       {:doc "A question to rows, a local model choosing a Cypher template: ask [--agent ID] Who owns 2U Inc?"
                :run (fn [arguments]
                       (let [[agent-id words] (if (= "--agent" (first arguments))
                                                [(second arguments) (drop 2 arguments)]
                                                [nil arguments])]
                         (when (empty? words)
                           (println "ask needs a question. Agents:")
                           (doseq [{:keys [id about example]} (agent/registry)]
                             (println (format "  %-8s %s  e.g. %s" id about example)))
                           (throw (ex-info "no question." {})))
                         (let [{:keys [agent-id source tool parameters resolved problem candidates rows choice]}
                               (agent/ask-agent agent-id (str/join " " words))]
                           (println "agent:     " agent-id "on" source)
                           (println "tool:      " (or tool (:tool choice)))
                           (println "parameters:" (pr-str parameters))
                           (when (seq resolved) (println "resolved:  " (pr-str resolved)))
                           (when (seq (:dropped choice))
                             (println "dropped:   " (pr-str (:dropped choice)) "(not in the question)"))
                           (if problem
                             (do (println "no answer: " problem)
                                 (run! #(println "  " %) candidates))
                             (print-rows rows)))))}
   "books-load" {:doc "MERGE the shelf's books and outlines into the server the env file names"
                 :run (fn [_]
                        (with-open [driver (neo4j/connect)]
                          (pprint/pprint (books/load! driver (books/graph)))))}
   "mbta-load" {:doc "MERGE the cached rail network into the server the env file names"
                :run (fn [_]
                       (with-open [driver (neo4j/connect)]
                         (pprint/pprint (mbta/load! driver (mbta/graph)))))}
   "papers-load" {:doc "MERGE the cached papers into the server the env file names"
                  :run (fn [_]
                         (with-open [driver (neo4j/connect)]
                           (pprint/pprint (papers/load! driver (papers/graph)))))}
   "status"    {:doc "Every connection check, once, as a table"
                :run (fn [_]
                       (print-rows (map #(select-keys % [:name :state :millis :summary])
                                        (status/all-checks))))}
   "dashboard" {:doc "Serve the status page on 127.0.0.1: dashboard [port]"
                :run (fn [[port]]
                       (let [listen-port (if port (parse-long port) dashboard/default-port)]
                         (dashboard/start listen-port)
                         (println (str "http://127.0.0.1:" listen-port "/  (ctrl-c stops it)"))
                         @(promise)))}
   "form13"    {:doc "The Form 13 day file, locally: form13 [summary|top|degree|variants|odd|verify|load]"
                :run (fn [[view]]
                       (let [holdings @form13/rows]
                         (case (or view "summary")
                           "summary" (pprint/pprint (form13/summary holdings))
                           "top" (print-rows (form13/top-holdings holdings))
                           "degree" (do (print-rows (form13/manager-degree holdings))
                                        (print-rows (form13/company-degree holdings)))
                           "variants" (print-rows (form13/name-variants holdings))
                           "odd" (print-rows (form13/odd-prices holdings))
                           "verify" (with-open [driver (neo4j/connect)]
                                      (pprint/pprint (form13/verify driver holdings)))
                           "load" (with-open [driver (neo4j/connect)]
                                    (pprint/pprint (form13/load! driver holdings)))
                           (throw (ex-info (str "form13 has no view " view) {})))))}
   "msa"       {:doc "Similarity edges for a census file, as TSV: msa data/census_demographics_by_metro_area.csv"
                :run (fn [[csv-path]]
                       (when-not csv-path
                         (throw (ex-info "msa needs a census csv." {})))
                       (doseq [[source-name target-name edge-similarity] (msa/edges csv-path)]
                         (println (format "%s\t%s\t%.9f" source-name target-name edge-similarity))))}
   "redact"    {:doc "Cover emails and .env secrets in a screenshot: redact IN.png OUT.png"
                :run (fn [[image-path output-path]]
                       (when-not (and image-path output-path)
                         (throw (ex-info "redact needs an input and an output image." {})))
                       (let [{:keys [still-readable] :as outcome} (redact/redact! image-path output-path)]
                         (prn outcome)
                         (when (pos? still-readable)
                           (throw (ex-info "something sensitive is still readable in the output." {})))))}
   "labs"      {:doc "Each lab by code, as a table. Reads only: labs [detail]"
                :run (fn [[view]]
                       (let [results (labs/run-all)]
                         (print (labs/report results))
                         (when (= view "detail")
                           (pprint/pprint (mapv #(select-keys % [:lab :title :status :evidence]) results)))
                         (when (pos? (:failed (labs/overall results)))
                           (throw (ex-info "a lab step failed." {})))))}
   "audit"     {:doc "What is true beside what the notes say. Reads only."
                :run (fn [_]
                       (let [checks (audit/run-all)]
                         (print-rows (audit/overview checks))
                         (pprint/pprint (audit/discrepancies checks))))}
   "datasets"  {:doc "List the workshop's datasets in Cloud Storage"
                :run (fn [_]
                       (print-rows (gcp/list-objects (gcp/public-storage))))}
   "peek"      {:doc "First lines of a dataset: peek hands-on-lab/form13-2023.csv [lines]"
                :run (fn [[object-name line-count]]
                       (when-not object-name
                         (throw (ex-info "peek needs an object name." {})))
                       (run! println
                             (gcp/peek-object (gcp/public-storage) gcp/dataset-bucket object-name
                                              (if line-count (parse-long line-count) 5))))}})

(defn- usage
  []
  (println "usage: clojure -M:run <command> [arguments]")
  (doseq [[command-name {:keys [doc]}] (sort-by key commands)]
    (println (format "  %-10s %s" command-name doc))))

(defn -main
  [& [command-name & arguments]]
  (if-let [{:keys [run]} (get commands command-name)]
    (try
      (run arguments)
      (catch Exception failure
        (binding [*out* *err*]
          (println (str command-name ": " (ex-message failure))))
        (System/exit 1)))
    (do (usage)
        (System/exit (if command-name 1 0))))
  (shutdown-agents))
