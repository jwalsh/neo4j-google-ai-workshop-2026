(ns form13.logic
  "The lab's Form 13 day file as core.logic facts (pldb), and the lab's
  questions as relations over them.

  The split that runs through this file: a goal (a name ending in o) says
  what is true and is run with run/run*; everything that counts, sorts,
  dedupes or takes a top N is plain Clojure over the vector run* returns.
  core.logic has no aggregation. Each query function below is written as a
  run* form followed by the Clojure that finishes it, so the line is visible.

  run* returns one answer per derivation, not a set. A CUSIP filed under
  three names is three answers to \"which CUSIPs exist\". Hence `distinct`."
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :as logic :refer [!= == conde fresh run run*]]
            [clojure.core.logic.fd :as fd]
            [clojure.core.logic.pldb :as pldb]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.pprint :refer [print-table]]
            [clojure.set :as set]
            [clojure.string :as str]))

(def default-csv-path
  "Relative to the experiment directory, which is where `clojure` and
  cider-jack-in start the JVM."
  "../../data/form13-2023-05-11.csv")

;; Relations
;;
;; pldb keeps, per relation, the set of all facts plus one map per indexed
;; position from value to the facts carrying it. A call uses the index of the
;; LEFTMOST position that is both indexed and ground at that moment, and only
;; that one; with no such position it unifies against every fact. (That is
;; pldb/index-for-query in core.logic 1.1.1, read from the jar.)
;;
;; So which positions are indexed matters, and so does their order.

;; One position, indexed: makes "is this a manager" a lookup, not a scan of 28.
(pldb/db-rel manager ^:index manager-name)

;; A CUSIP and one name it was filed under: 2,577 facts for 2,251 CUSIPs.
;; Both indexed: cusip for "what is this called", company-name for "which
;; CUSIP is APPLE INC". cusip is leftmost because when both are ground the
;; cusip bucket is the smaller (at most 6 names; a name has at most 3 CUSIPs,
;; so here it hardly matters).
(pldb/db-rel company ^:index cusip ^:index company-name)

;; One row of the file. cusip is LEFTMOST on purpose. In a join the second
;; call usually has both cusip and manager ground; pldb then picks the
;; leftmost index. A CUSIP's bucket holds at most 28 facts; a manager's holds
;; up to 1,615. With manager-name first the same join is about a hundred
;; times slower (see index-order-comparison). value and shares are not
;; indexed: nobody asks for a position by its exact value.
(pldb/db-rel holds ^:index cusip ^:index manager-name value shares)

;; Loading

(defn read-rows
  "Every row of the file as a map. value is integral throughout the file, so
  it is kept as a long; finite-domain constraints need integers."
  ([] (read-rows default-csv-path))
  ([csv-path]
   (with-open [reader (io/reader csv-path)]
     (let [[header & lines] (csv/read-csv reader)]
       (mapv (fn [line]
               (let [{:strs [managerName cusip value shares companyName]} (zipmap header line)]
                 {:manager-name managerName
                  :cusip cusip
                  :company-name companyName
                  :value (long (parse-double value))
                  :shares (parse-long shares)}))
             lines)))))

(defn facts-database
  "The rows as a pldb database. pldb stores each relation as a set, so a
  manager named on 1,615 rows is one manager fact. The row keys are
  :manager-name and :company-name, not :manager and :company: destructured,
  those would shadow the relations of the same name, and db-fact given a
  string for a relation files the fact under nil without complaint."
  [rows]
  (reduce (fn [database {:keys [manager-name cusip company-name value shares]}]
            (-> database
                (pldb/db-fact manager manager-name)
                (pldb/db-fact company cusip company-name)
                (pldb/db-fact holds cusip manager-name value shares)))
          pldb/empty-db
          rows))

(defn load-facts
  ([] (load-facts default-csv-path))
  ([csv-path] (facts-database (read-rows csv-path))))

;; Goals: the relational part

(defn holdso
  "manager-name holds cusip. Either, both or neither may be ground."
  [manager-name cusip]
  (fresh [value shares]
    (holds cusip manager-name value shares)))

(defn holds-companyo
  "manager-name holds something filed under exactly company-name."
  [manager-name company-name]
  (fresh [cusip]
    (company cusip company-name)
    (holdso manager-name cusip)))

(defn both-holdo
  "Both managers hold cusip."
  [first-manager second-manager cusip]
  (fresh []
    (holdso first-manager cusip)
    (holdso second-manager cusip)))

(defn renamedo
  "cusip was filed under two different names. != is a disequality
  constraint: it may be stated before either name is known. That freedom
  is paid for: stated last, where it is a plain comparison of two ground
  strings, the same query measured about three times faster."
  [cusip first-name second-name]
  (fresh []
    (!= first-name second-name)
    (company cusip first-name)
    (company cusip second-name)))

(defn co-holdero
  "Two different managers with at least one holding in common. The managers
  are enumerated first, then onceo stops at the first shared CUSIP: the
  answer is the pair, not one answer per shared company. onceo commits to a
  first answer, so this is only sound because both names are ground by then."
  [first-manager second-manager]
  (fresh []
    (manager first-manager)
    (manager second-manager)
    (!= first-manager second-manager)
    (logic/onceo (fresh [cusip] (both-holdo first-manager second-manager cusip)))))

(defn co-holder-per-companyo
  "The same without onceo: one answer per shared company. Kept to show what
  that costs."
  [first-manager second-manager]
  (fresh [cusip]
    (!= first-manager second-manager)
    (both-holdo first-manager second-manager cusip)))

(defn chaino
  "start-manager reaches end-manager through at most hop-limit co-holder
  links; path is the managers passed through, ends included. The bound is a
  Clojure integer consumed while the goal is built, so the recursion
  terminates by construction. A path may revisit a manager."
  [start-manager end-manager hop-limit path]
  (if (zero? hop-limit)
    logic/fail
    (conde
      [(co-holdero start-manager end-manager)
       (== path [start-manager end-manager])]
      [(fresh [between-manager remaining-path]
         (co-holdero start-manager between-manager)
         (logic/conso start-manager remaining-path path)
         (chaino between-manager end-manager (dec hop-limit) remaining-path))])))

;; Queries: a run* and then the Clojure that finishes it

(defn managers
  [database]
  (pldb/with-db database
    (vec (run* [manager-name] (manager manager-name)))))

(defn cusips
  "run* gives 2,577 answers, one per (cusip, name) fact. distinct is Clojure."
  [database]
  (let [answers (pldb/with-db database
                  (vec (run* [cusip] (fresh [company-name] (company cusip company-name)))))]
    (vec (distinct answers))))

(defn all-holdings
  "holdso with nothing ground: every (manager, cusip) pair."
  [database]
  (pldb/with-db database
    (vec (run* [manager-name cusip] (holdso manager-name cusip)))))

(defn holdings-of
  "holdso with the manager ground."
  [database manager-name]
  (pldb/with-db database
    (vec (run* [cusip] (holdso manager-name cusip)))))

(defn holders-of-cusip
  "holdso with the cusip ground."
  [database cusip]
  (pldb/with-db database
    (vec (run* [manager-name] (holdso manager-name cusip)))))

(defn holds?
  "holdso with both ground: a yes or no."
  [database manager-name cusip]
  (pldb/with-db database
    (boolean (seq (run 1 [answer] (holdso manager-name cusip) (== answer true))))))

(defn holders-of
  "Who holds a company, by its exact name, largest first. The join is
  relational; the ordering is sort-by."
  [database company-name]
  (let [answers (pldb/with-db database
                  (vec (run* [manager-name cusip value shares]
                         (company cusip company-name)
                         (holds cusip manager-name value shares))))]
    (->> answers
         (map #(zipmap [:manager :cusip :value :shares] %))
         (sort-by :value >)
         vec)))

(defn common-holdings
  "CUSIPs two managers both hold, with every name each was filed under.
  The join is relational; folding the names of one CUSIP into one row is
  group-by."
  [database first-manager second-manager]
  (let [answers (pldb/with-db database
                  (vec (run* [cusip company-name]
                         (both-holdo first-manager second-manager cusip)
                         (company cusip company-name))))]
    (->> (group-by first answers)
         (map (fn [[cusip named]] {:cusip cusip :names (str/join " / " (sort (map second named)))}))
         (sort-by :cusip)
         vec)))

(defn name-variants
  "CUSIPs filed under more than one name. renamedo answers once per ordered
  pair of names; grouping back to one row per CUSIP is Clojure."
  [database]
  (let [answers (pldb/with-db database
                  (vec (run* [cusip first-name second-name]
                         (renamedo cusip first-name second-name))))]
    (->> (group-by first answers)
         (map (fn [[cusip pairs]] {:cusip cusip :names (str/join " / " (sort (distinct (map second pairs))))}))
         (sort-by :cusip)
         vec)))

(defn manager-degree
  "Managers by number of holdings. The pairs come from run*; the counting
  and ordering are frequencies and sort-by."
  [database]
  (->> (all-holdings database)
       (map first)
       frequencies
       (map (fn [[manager-name holding-count]] {:manager manager-name :companies holding-count}))
       (sort-by :companies >)
       vec))

(defn top-holdings
  "The largest positions. All of it but the first line is Clojure."
  [database limit]
  (let [answers (pldb/with-db database
                  (vec (run* [manager-name cusip value shares]
                         (holds cusip manager-name value shares))))]
    (->> answers
         (map #(zipmap [:manager :cusip :value :shares] %))
         (sort-by :value >)
         (take limit)
         vec)))

(defn held-by-first-only
  "CUSIPs the first manager holds and the second does not. nafc is negation
  as failure, as a constraint: it waits until its arguments are ground."
  [database first-manager second-manager]
  (pldb/with-db database
    (vec (run* [cusip]
           (holdso first-manager cusip)
           (logic/nafc holdso second-manager cusip)))))

(defn positions-with-shares-between
  "A manager's positions whose share count lies in a closed interval, as a
  finite-domain constraint stated before the fact is looked up."
  [database manager-name least-shares most-shares]
  (pldb/with-db database
    (vec (run* [cusip shares]
           (fresh [value]
             (fd/in shares (fd/interval least-shares most-shares))
             (holds cusip manager-name value shares))))))

(defn co-holder-pairs
  "Ordered pairs of managers sharing at least one holding."
  [database]
  (pldb/with-db database
    (vec (run* [first-manager second-manager]
           (co-holdero first-manager second-manager)))))

(defn co-holder-derivations
  "The same pairs the slow way: one answer per shared company, at most
  answer-limit of them."
  [database answer-limit]
  (pldb/with-db database
    (vec (run answer-limit [first-manager second-manager]
           (co-holder-per-companyo first-manager second-manager)))))

(defn chains-from
  "Every path of at most hop-limit links out of one manager."
  [database start-manager hop-limit]
  (pldb/with-db database
    (vec (run* [end-manager path]
           (chaino start-manager end-manager hop-limit path)))))

(defn fewest-hops
  "For each manager reachable from start-manager within hop-limit links, the
  fewest links and one path that long. The paths are relational; choosing
  the shortest per destination is Clojure."
  [database start-manager hop-limit]
  (->> (chains-from database start-manager hop-limit)
       (remove (fn [[end-manager _]] (= end-manager start-manager)))
       (group-by first)
       (map (fn [[end-manager answers]]
              (let [shortest-path (apply min-key count (map second answers))]
                {:manager end-manager
                 :hops (dec (count shortest-path))
                 :via (str/join " -> " (butlast (rest shortest-path)))})))
       (sort-by (juxt :hops :manager))
       vec))

;; The index-order finding, reproducible

(pldb/db-rel holds-manager-first ^:index manager-name ^:index cusip value shares)

(defn index-order-comparison
  "The same two-manager join over the same facts stored with the positions
  in the other order. Returns the answer count and milliseconds of each."
  [rows first-manager second-manager]
  (let [manager-first-database (reduce (fn [database {:keys [manager-name cusip value shares]}]
                                         (pldb/db-fact database holds-manager-first manager-name cusip value shares))
                                       pldb/empty-db
                                       rows)
        cusip-first-database (facts-database rows)
        milliseconds (fn [thunk]
                       (let [started (System/nanoTime)
                             answer-count (count (thunk))]
                         {:answers answer-count
                          :ms (/ (- (System/nanoTime) started) 1e6)}))
        manager-first-join #(pldb/with-db manager-first-database
                              (vec (run* [cusip]
                                     (fresh [first-value first-shares second-value second-shares]
                                       (holds-manager-first first-manager cusip first-value first-shares)
                                       (holds-manager-first second-manager cusip second-value second-shares)))))
        cusip-first-join #(pldb/with-db cusip-first-database
                            (vec (run* [cusip] (both-holdo first-manager second-manager cusip))))]
    (dotimes [_ 3] (manager-first-join) (cusip-first-join))
    {:manager-first (milliseconds manager-first-join)
     :cusip-first (milliseconds cusip-first-join)}))

;; The gate

(def envestnet "ENVESTNET ASSET MANAGEMENT INC")
(def guggenheim "GUGGENHEIM CAPITAL LLC")
(def westwood "Westwood Global Investments, LLC")
(def apple-cusip "037833100")

(defn- timed
  "Runs thunk five times. Milliseconds of the first run, which pays for JIT,
  and of the fastest."
  [thunk]
  (let [run-once (fn []
                   (let [started (System/nanoTime)
                         result (thunk)]
                     [result (/ (- (System/nanoTime) started) 1e6)]))
        [[result first-milliseconds] & later-runs] (repeatedly 5 run-once)]
    {:result result
     :first-ms first-milliseconds
     :best-ms (apply min first-milliseconds (map second later-runs))}))

(defn- direct-pairs-from-rows
  "Ordered co-holder pairs worked out with sets, no core.logic: something
  for the relational answer to be checked against."
  [rows]
  (let [cusips-by-manager (update-vals (group-by :manager-name rows) #(set (map :cusip %)))]
    (set (for [[first-manager first-cusips] cusips-by-manager
               [second-manager second-cusips] cusips-by-manager
               :when (and (not= first-manager second-manager)
                          (seq (set/intersection first-cusips second-cusips)))]
           [first-manager second-manager]))))

(defn gate
  "Loads the file, runs every query, and returns {:checks :timings}. A check
  is :ok when the actual value equals the expected one."
  ([] (gate default-csv-path))
  ([csv-path]
   (let [read-timing (timed #(read-rows csv-path))
         rows (:result read-timing)
         build-timing (timed #(facts-database rows))
         database (:result build-timing)
         measured (atom [{:query "read and parse the CSV" :answers (count rows)
                          :first-ms (:first-ms read-timing) :best-ms (:best-ms read-timing)}
                         {:query "build the pldb database" :answers (count rows)
                          :first-ms (:first-ms build-timing) :best-ms (:best-ms build-timing)}])
         measure (fn [label thunk]
                   (let [{:keys [result first-ms best-ms]} (timed thunk)]
                     (swap! measured conj {:query label
                                           :answers (if (coll? result) (count result) result)
                                           :first-ms first-ms
                                           :best-ms best-ms})
                     result))
         manager-names (measure "managers" #(managers database))
         distinct-cusips (measure "cusips (2,577 answers, then distinct)" #(cusips database))
         holding-pairs (measure "holdso, nothing ground: all pairs" #(all-holdings database))
         envestnet-cusips (measure "holdso, manager ground" #(holdings-of database envestnet))
         apple-by-cusip (measure "holdso, cusip ground" #(holders-of-cusip database apple-cusip))
         envestnet-holds-apple (measure "holdso, both ground" #(holds? database envestnet apple-cusip))
         apple-holders (measure "holders-of APPLE INC" #(holders-of database "APPLE INC"))
         shared-holdings (measure "common-holdings, the two largest managers"
                                  #(common-holdings database envestnet guggenheim))
         variants (measure "name-variants" #(name-variants database))
         degrees (measure "manager-degree (run*, then frequencies)" #(manager-degree database))
         largest-positions (measure "top-holdings 10 (run*, then sort-by)" #(top-holdings database 10))
         guggenheim-only (measure "held-by-first-only (nafc)" #(held-by-first-only database guggenheim envestnet))
         million-share-positions (measure "positions-with-shares-between (fd)"
                                          #(positions-with-shares-between database envestnet 1000000 100000000))
         linked-pairs (measure "co-holder-pairs (onceo)" #(co-holder-pairs database))
         derivations (measure "co-holder pairs, one answer per shared company"
                              #(co-holder-derivations database 1000000))
         westwood-direct (measure "fewest-hops from Westwood, limit 1" #(fewest-hops database westwood 1))
         westwood-two (measure "fewest-hops from Westwood, limit 2" #(fewest-hops database westwood 2))
         westwood-three-paths (measure "chains-from Westwood, limit 3 (paths)" #(chains-from database westwood 3))
         index-order (index-order-comparison rows envestnet guggenheim)
         envestnet-cusip-set (set (map :cusip (filter #(= envestnet (:manager-name %)) rows)))
         guggenheim-cusip-set (set (map :cusip (filter #(= guggenheim (:manager-name %)) rows)))
         expected-direct-pairs (direct-pairs-from-rows rows)
         check (fn [label expected actual]
                 {:check label :expected expected :actual actual :ok (= expected actual)})]
     {:checks
      [(check "managers" 28 (count manager-names))
       (check "distinct CUSIPs" 2251 (count distinct-cusips))
       (check "holdings" 7381 (count holding-pairs))
       (check "CUSIPs under more than one name" 258 (count variants))
       (check "manager with most companies" envestnet (:manager (first degrees)))
       (check "companies that manager holds" 1615 (:companies (first degrees)))
       (check "the same, holdso with manager ground" 1615 (count envestnet-cusips))
       (check "managers holding APPLE INC" 22 (count apple-holders))
       (check "the same, holdso with cusip ground" 22 (count apple-by-cusip))
       (check "Envestnet holds Apple" true envestnet-holds-apple)
       (check "common holdings equal the set intersection"
              (count (set/intersection envestnet-cusip-set guggenheim-cusip-set))
              (count shared-holdings))
       (check "nafc equals the set difference"
              (count (set/difference guggenheim-cusip-set envestnet-cusip-set))
              (count guggenheim-only))
       (check "fd interval equals a filter"
              (count (filter #(and (= envestnet (:manager-name %)) (<= 1000000 (:shares %) 100000000)) rows))
              (count million-share-positions))
       (check "top holding equals max-key over the rows"
              (:value (apply max-key :value rows))
              (:value (first largest-positions)))
       (check "co-holder pairs equal the set version" expected-direct-pairs (set linked-pairs))
       (check "per-company derivations collapse to the same pairs" expected-direct-pairs (set derivations))
       (check "Westwood reaches all 27 others within 2 links" 27 (count westwood-two))]
      :timings @measured
      :index-order index-order
      :detail {:apple-holders apple-holders
               :shared-holdings shared-holdings
               :variants variants
               :degrees degrees
               :largest-positions largest-positions
               :linked-pairs (count linked-pairs)
               :derivations (count derivations)
               :westwood-direct westwood-direct
               :westwood-two westwood-two
               :westwood-three-paths (count westwood-three-paths)}})))

(defn- shorten
  "Sets of pairs are too wide for a table cell."
  [value]
  (if (coll? value) (str (count value) " items") value))

(defn -main
  [& [csv-path]]
  (let [{:keys [checks timings index-order detail]} (gate (or csv-path default-csv-path))
        round-milliseconds #(format "%.1f" (double %))]
    (print-table [:check :expected :actual :ok]
                 (map #(-> % (update :expected shorten) (update :actual shorten)) checks))
    (print-table [:query :answers :first-ms :best-ms]
                 (map #(-> % (update :first-ms round-milliseconds) (update :best-ms round-milliseconds)) timings))
    (println)
    (println "Index order, Envestnet and Guggenheim both hold, warm:")
    (println (format "  holds(manager, cusip, ...) %d answers in %.1f ms"
                     (get-in index-order [:manager-first :answers])
                     (double (get-in index-order [:manager-first :ms]))))
    (println (format "  holds(cusip, manager, ...) %d answers in %.1f ms"
                     (get-in index-order [:cusip-first :answers])
                     (double (get-in index-order [:cusip-first :ms]))))
    (println)
    (println (format "Co-holder pairs: %d ordered, from %d per-company derivations."
                     (:linked-pairs detail) (:derivations detail)))
    (println "Westwood Global, direct co-holders:")
    (print-table (:westwood-direct detail))
    (println "Westwood Global, by fewest links (limit 2):" (frequencies (map :hops (:westwood-two detail))))
    (println "Westwood Global, paths within 3 links:" (:westwood-three-paths detail))
    (let [failed (remove :ok checks)]
      (println)
      (if (seq failed)
        (do (println "GATE FAILED:" (count failed) "of" (count checks) "checks") (shutdown-agents) (System/exit 1))
        (println "GATE PASSED:" (count checks) "of" (count checks) "checks")))))
