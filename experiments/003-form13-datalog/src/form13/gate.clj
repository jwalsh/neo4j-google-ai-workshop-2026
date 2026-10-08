(ns form13.gate
  "The gate: load the file into each store, run every query, and compare with
  the known numbers and with the same answers computed from the rows by plain
  Clojure. Prints timings. Exits 1 if anything differs.

  clojure -M:gate [path-to-csv]"
  (:require [clojure.pprint :refer [print-table]]
            [form13.datoms :as datoms]
            [form13.triples :as triples]))

(def known
  "From src/workshop/form13.clj over the same file, as given in the brief."
  {:managers 28
   :companies 2251
   :holdings 7381
   :name-variants 258
   :top-manager ["ENVESTNET ASSET MANAGEMENT INC" 1615]
   :apple-holders 22})

(def apple-cusip "037833100")

;; ---------------------------------------------------------------------------
;; The same answers without a database

(defn reference
  "What the queries should return, from group-by over the parsed rows."
  [rows]
  (let [by-manager (group-by :manager rows)
        by-cusip (group-by :cusip rows)
        ranked (fn [limit count-key result-maps]
                 (->> result-maps
                      (sort-by (juxt (comp - count-key) str))
                      (take limit)
                      vec))
        companies-of (into {} (map (fn [[manager positions]]
                                     [manager (set (map :cusip positions))])
                                   by-manager))
        manager-names (sort (keys by-manager))]
    {:companies-per-manager
     (ranked 5 :companies
             (map (fn [[manager positions]]
                    {:manager manager
                     :companies (count (distinct (map :cusip positions)))
                     :value (reduce + (map :value positions))})
                  by-manager))
     :managers-per-company
     (ranked 5 :managers
             (map (fn [[cusip positions]]
                    {:cusip cusip
                     :company (first (sort (distinct (map :company positions))))
                     :managers (count (distinct (map :manager positions)))})
                  by-cusip))
     :shared-company-pairs
     (->> (for [first-manager manager-names
                second-manager manager-names
                :when (neg? (compare first-manager second-manager))
                :let [shared (count (filter (companies-of first-manager)
                                            (companies-of second-manager)))]
                :when (pos? shared)]
            {:first-manager first-manager :second-manager second-manager :shared shared})
          (sort-by (juxt (comp - :shared) :first-manager :second-manager))
          vec)
     :total-value (reduce + (map :value rows))}))

;; ---------------------------------------------------------------------------
;; Timing

(defn- milliseconds-since
  [start-nanoseconds]
  (/ (- (System/nanoTime) start-nanoseconds) 1e6))

(defn timed
  "Runs the thunk once cold, then five more times. Returns the first result,
  the first time, and the fastest of the five."
  [thunk]
  (let [start (System/nanoTime)
        result (thunk)
        first-milliseconds (milliseconds-since start)
        warm-milliseconds (apply min (repeatedly 5 #(let [warm-start (System/nanoTime)]
                                                      (thunk)
                                                      (milliseconds-since warm-start))))]
    {:result result :first-ms first-milliseconds :warm-ms warm-milliseconds}))

(defn timed-once
  [thunk]
  (let [start (System/nanoTime)
        result (thunk)]
    {:result result :first-ms (milliseconds-since start)}))

(defn- timing-row
  [store step {:keys [first-ms warm-ms]}]
  {:store store
   :step step
   :first-ms (format "%.1f" first-ms)
   :best-of-5-ms (if warm-ms (format "%.1f" warm-ms) "-")})

(defn- check
  [store description expected actual]
  {:store store :check description :expected expected :actual actual
   :pass (if (= expected actual) "ok" "FAIL")})

(defn- unordered-pair
  "Pair order differs between stores (entity id here, name there); the gate
  compares pairs as sets of two names."
  [{:keys [first-manager second-manager shared]}]
  [(set [first-manager second-manager]) shared])

;; ---------------------------------------------------------------------------
;; DataScript

(defn datascript-run
  [rows expected]
  (let [load-timing (timed-once #(datoms/load-db rows))
        database (:result load-timing)
        lossy-database (datoms/load-db rows datoms/lossy-schema)
        counts (timed #(datoms/counts database))
        per-manager (timed #(datoms/companies-per-manager database 5))
        per-company (timed #(datoms/managers-per-company database 5))
        variant-count (timed #(datoms/name-variant-count database))
        variants (timed #(datoms/name-variants database))
        apple (timed #(datoms/holders-of database apple-cusip))
        pairs (timed #(datoms/shared-company-pairs database 1))
        pairs-inline (timed #(datoms/shared-company-pairs-inline database 1))
        top-manager (first (:result per-manager))
        store "datascript"]
    {:database database
     :timings [(timing-row store "transact (two transactions)" load-timing)
               (timing-row store "counts" counts)
               (timing-row store "companies per manager" per-manager)
               (timing-row store "managers per company" per-company)
               (timing-row store "name variant count" variant-count)
               (timing-row store "name variants listed" variants)
               (timing-row store "holders of Apple" apple)
               (timing-row store "shared companies, rule" pairs)
               (timing-row store "shared companies, inline" pairs-inline)]
     :checks [(check store "datoms" (+ 28 2251 (* 7 7381)
                                       (count (distinct (map (juxt :cusip :company) rows))))
                     (count (datoms/tuples database)))
              (check store "managers" (:managers known) (:managers (:result counts)))
              (check store "companies (CUSIPs)" (:companies known) (:companies (:result counts)))
              (check store "holdings" (:holdings known) (:holdings (:result counts)))
              (check store "CUSIPs with more than one name" (:name-variants known) (:result variant-count))
              (check store "same, by listing them" (:name-variants known) (count (:result variants)))
              (check store "same, :company/name cardinality one" 0
                     (datoms/name-variant-count lossy-database))
              (check store "top manager" (:top-manager known)
                     [(:manager top-manager) (:companies top-manager)])
              (check store "managers holding Apple" (:apple-holders known) (count (:result apple)))
              (check store "companies per manager, top 5 = rows"
                     (:companies-per-manager expected) (:result per-manager))
              (check store "managers per company, top 5 = rows"
                     (:managers-per-company expected) (:result per-company))
              (check store "manager pairs sharing a company"
                     (count (:shared-company-pairs expected)) (count (:result pairs)))
              (check store "every pair's shared count = rows"
                     (set (map unordered-pair (:shared-company-pairs expected)))
                     (set (map unordered-pair (:result pairs))))
              (check store "rule = inline" (:result pairs-inline) (:result pairs))]}))

;; ---------------------------------------------------------------------------
;; Asami

(defn asami-run
  [rows expected]
  (let [load-timing (timed-once #(triples/load-db rows))
        database (:result load-timing)
        counts (timed #(triples/counts database))
        per-manager (timed #(triples/companies-per-manager database 5))
        per-company (timed #(triples/managers-per-company database 5))
        variant-count (timed #(triples/name-variant-count database))
        pairs (timed #(triples/shared-company-pairs database 1))
        summed-value (triples/total-value-as-summed database)
        bound-attribute-count (triples/count-with-bound-attribute database :manager/name)
        top-manager (first (:result per-manager))
        store "asami"]
    {:database database
     :summed-value summed-value
     :bound-attribute-count bound-attribute-count
     :timings [(timing-row store "transact (one, triples)" load-timing)
               (timing-row store "counts" counts)
               (timing-row store "companies per manager" per-manager)
               (timing-row store "managers per company" per-company)
               (timing-row store "name variant count" variant-count)
               (timing-row store "shared companies, join + Clojure" pairs)]
     :checks [(check store "triples" (+ 28 2251 (* 6 7381)
                                        (count (distinct (map (juxt :cusip :company) rows))))
                     (count (triples/tuples database)))
              (check store "managers" (:managers known) (:managers (:result counts)))
              (check store "companies (CUSIPs)" (:companies known) (:companies (:result counts)))
              (check store "holdings" (:holdings known) (:holdings (:result counts)))
              (check store "CUSIPs with more than one name" (:name-variants known) (:result variant-count))
              (check store "top manager" (:top-manager known)
                     [(:manager top-manager) (:companies top-manager)])
              (check store "companies per manager, top 5 = rows"
                     (mapv #(dissoc % :value) (:companies-per-manager expected))
                     (:result per-manager))
              (check store "managers per company, top 5 = rows"
                     (:managers-per-company expected) (:result per-company))
              (check store "every pair's shared count = rows"
                     (set (map unordered-pair (:shared-company-pairs expected)))
                     (set (map unordered-pair (:result pairs))))]}))

;; ---------------------------------------------------------------------------

(defn- summarise
  "A check's expected and actual can be whole result sets; print a short form."
  [value]
  (cond
    (and (coll? value) (> (count value) 2)) (str (count value) " items")
    :else (pr-str value)))

(defn run
  [csv-path]
  (let [read-timing (timed-once #(datoms/read-rows csv-path))
        rows (:result read-timing)
        expected (reference rows)
        datascript-result (datascript-run rows expected)
        asami-result (asami-run rows expected)
        checks (concat (:checks datascript-result) (:checks asami-result))
        failures (filter #(= "FAIL" (:pass %)) checks)]
    {:rows rows
     :expected expected
     :datascript datascript-result
     :asami asami-result
     :read-timing read-timing
     :checks checks
     :failures failures}))

(defn report
  [{:keys [rows expected datascript asami read-timing checks failures]}]
  (println "rows read:" (count rows) (format "(%.1f ms)" (:first-ms read-timing)))
  (println "\nThe first tuples, [entity attribute value]:")
  (run! prn (take 9 (datoms/tuples (:database datascript))))
  (println "\nCompanies per manager, top 5 (DataScript):")
  (print-table (datoms/companies-per-manager (:database datascript) 5))
  (println "\nManagers per company, top 5 (DataScript):")
  (print-table (datoms/managers-per-company (:database datascript) 5))
  (println "\nManagers sharing the most companies, by rule (DataScript):")
  (print-table (take 5 (datoms/shared-company-pairs (:database datascript) 1)))
  (println "\nTimings, milliseconds, one JVM, no warm-up before the first run:")
  (print-table [:store :step :first-ms :best-of-5-ms]
               (concat (:timings datascript) (:timings asami)))
  (println "\nGate:")
  (print-table [:store :check :expected :actual :pass]
               (map #(-> % (update :expected summarise) (update :actual summarise)) checks))
  (println "\nNot gated, reported: Asami (sum ?value) over all holdings returned"
           (:summed-value asami))
  (println "  total from the rows:              " (:total-value expected))
  (println "  sum of the distinct values only:  "
           (reduce + (distinct (map :value rows))))
  (println "Not gated, reported: Asami (count ?entity) with :manager/name passed through :in returned"
           (:bound-attribute-count asami) "; with the attribute written in, 28")
  (println (if (empty? failures)
             (str "\nGATE PASSED: " (count checks) " checks")
             (str "\nGATE FAILED: " (count failures) " of " (count checks) " checks"))))

(defn -main
  [& [csv-path]]
  (let [result (run (or csv-path datoms/default-csv-path))]
    (report result)
    (shutdown-agents)
    (System/exit (if (empty? (:failures result)) 0 1))))
