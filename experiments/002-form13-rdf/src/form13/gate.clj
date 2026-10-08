(ns form13.gate
  "The gate: the SPARQL answers against the numbers already known for this
  file, and against the same figures computed from the CSV rows in plain
  Clojure with no RDF anywhere. Exits 1 if any check fails.

      clojure -M:gate [path-to-csv]"
  (:require [form13.rdf :as rdf]))

(def known
  "From src/workshop/form13.clj run over the same file, and from the lab."
  {:managers 28
   :companies 2251
   :holdings 7381
   :cusips-with-several-names 258
   :top-manager ["ENVESTNET ASSET MANAGEMENT INC" 1615]
   :apple-holders 22})

(defn- distinct-count-by
  "Rows grouped by one column, counting the distinct values of another:
  [[group-value distinct-count] ...], largest first, ties by group value."
  [rows group-column counted-column limit]
  (->> (group-by group-column rows)
       (map (fn [[group-value group-rows]]
              [group-value (count (distinct (map counted-column group-rows)))]))
       (sort-by (fn [[group-value distinct-count]] [(- distinct-count) group-value]))
       (take limit)
       vec))

(defn- expected-triple-count
  "What the mapping in form13.rdf/add-row! should produce, counted from the
  rows: type and name per manager, type and cusip per company, one
  companyName per distinct spelling, seven triples per holding, one bare
  owns per manager and company."
  [rows]
  (let [distinct-count (fn [& columns] (count (distinct (map (apply juxt columns) rows))))]
    (+ (* 2 (distinct-count :managerName))
       (* 2 (distinct-count :cusip))
       (distinct-count :cusip :companyName)
       (* 7 (distinct-count :managerName :cusip :reportCalendarOrQuarter))
       (distinct-count :managerName :cusip))))

(defn checks
  "Every check as {:check :expected :actual :pass}."
  [rows model turtle-path]
  (let [counts (first (rdf/ask model :counts))
        companies-per-manager (rdf/ask model :companies-per-manager)
        managers-per-company (rdf/ask model :managers-per-company)
        name-variants (rdf/ask model :name-variants)
        apple-holders (rdf/ask model :holders-of-apple)
        twice-loaded (rdf/rows->model (concat rows rows))
        reread (rdf/read-turtle turtle-path)
        check (fn [check-name expected actual]
                {:check check-name :expected expected :actual actual
                 :pass (= expected actual)})]
    [(check "managers" (:managers known) (:managers counts))
     (check "companies (distinct CUSIPs)" (:companies known) (:companies counts))
     (check "holdings" (:holdings known) (:holdings counts))
     (check "CUSIPs with more than one name"
            (:cusips-with-several-names known) (count name-variants))
     (check "manager holding most companies"
            (:top-manager known)
            ((juxt :managerName :companies) (first companies-per-manager)))
     (check "managers holding APPLE INC (037833100)"
            (:apple-holders known) (count apple-holders))
     (check "top company by managers"
            ["037833100" "APPLE INC" (:apple-holders known)]
            ((juxt :cusip :companyName :managers) (first managers-per-company)))
     ;; The rest are not from the brief: SPARQL against plain Clojure over
     ;; the same rows, and properties of the mapping itself.
     (check "top 5 companies per manager, SPARQL = rows"
            (distinct-count-by rows :managerName :cusip 5)
            (mapv (juxt :managerName :companies) companies-per-manager))
     (check "top 5 managers per company, SPARQL = rows"
            (distinct-count-by rows :cusip :managerName 5)
            (mapv (juxt :cusip :managers) managers-per-company))
     (check "top 5 from the bare owns triple = from Holding nodes"
            (mapv (juxt :managerName :companies) companies-per-manager)
            (mapv (juxt :managerName :companies) (rdf/ask model :companies-per-manager-bare)))
     (check "bare owns triples" (:holdings known) (:ownsTriples counts))
     (check "triples in the model" (expected-triple-count rows) (.size model))
     (check "triples after adding every row twice" (.size model) (.size twice-loaded))
     (check "Turtle file read back is the same graph" true (.isIsomorphicWith model reread))]))

(defn rdf-star-report
  "Not part of the gate. Says whether this Jena reads the annotated form."
  []
  (try
    (let [annotated-model (rdf/turtle-string->model rdf/annotated-example)]
      {:parsed true
       :triples (.size annotated-model)
       :rows (rdf/select annotated-model rdf/annotated-query)})
    (catch Exception exception
      {:parsed false :error (ex-message exception)})))

(defn -main
  [& [csv-path]]
  (let [csv-path (or csv-path rdf/default-csv-path)
        rows (rdf/read-rows csv-path)
        model (rdf/rows->model rows)
        turtle-path (rdf/write-turtle! model)
        results (checks rows model turtle-path)
        failures (remove :pass results)]
    (println "csv:   " csv-path (str "(" (count rows) " rows)"))
    (println "turtle:" turtle-path (str "(" (.size model) " triples)"))
    (doseq [{:keys [check expected actual pass]} results]
      (println (if pass "ok  " "FAIL") (str check ":") (pr-str actual))
      (when-not pass (println "     expected:" (pr-str expected))))
    (println)
    (println "RDF-star (not gated):" (pr-str (rdf-star-report)))
    (println)
    (if (seq failures)
      (do (println "GATE FAILED:" (count failures) "of" (count results) "checks")
          (System/exit 1))
      (println "GATE PASSED:" (count results) "of" (count results) "checks"))))
