(ns workshop.cli
  "clojure -M:run <command>. The tasks in bb.edn call this."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.dashboard :as dashboard]
            [workshop.gcp :as gcp]
            [workshop.neo4j :as neo4j]
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
