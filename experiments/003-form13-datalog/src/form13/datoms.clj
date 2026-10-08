(ns form13.datoms
  "The Form 13 day file as DataScript datoms: [entity attribute value] tuples
  in memory, queried with Datalog and pull. No Neo4j, no network.

  The lab's property graph is (:Manager)-[:OWNS {value shares quarter}]->(:Company).
  A datom has no room for properties on an edge, so the OWNS relationship
  becomes an entity of its own, a holding, with a ref to each end."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [datascript.core :as d]))

(def default-csv-path
  "Relative to this experiment's directory, which is where `clojure` and
  cider-jack-in start the JVM."
  "../../data/form13-2023-05-11.csv")

;; ---------------------------------------------------------------------------
;; The file

(defn- parse-row
  [{:strs [managerName reportCalendarOrQuarter cusip value shares sourceFilingId companyName]}]
  {:manager managerName
   :quarter reportCalendarOrQuarter
   :cusip cusip
   :value (parse-double value)
   :shares (parse-long shares)
   :filing sourceFilingId
   :company companyName})

(defn read-rows
  "Every row of the CSV as a map with typed :value and :shares."
  ([] (read-rows default-csv-path))
  ([csv-path]
   (with-open [reader (io/reader csv-path)]
     (let [[header & lines] (csv/read-csv reader)]
       (mapv #(parse-row (zipmap header %)) lines)))))

;; ---------------------------------------------------------------------------
;; Schema

(def schema
  "DataScript only needs to be told what is not a plain single value: unique
  attributes, refs, and cardinality-many. Value types are not declared.

  :company/name is cardinality-many on purpose. CUSIP is the identity, and
  258 CUSIPs in this file are filed under more than one name. With the
  default cardinality (one), each upsert on the CUSIP would replace the name
  and the variants would be gone, silently: see `lossy-schema`. As a set,
  every spelling filed against the CUSIP is kept on the company.

  :holding/company-name keeps the spelling that one filing used, so which
  manager wrote which name is still answerable."
  {:manager/name {:db/unique :db.unique/identity}
   :company/cusip {:db/unique :db.unique/identity}
   :company/name {:db/cardinality :db.cardinality/many}
   :holding/manager {:db/valueType :db.type/ref}
   :holding/company {:db/valueType :db.type/ref}})

(def lossy-schema
  "The same schema with the default cardinality on :company/name. Kept to
  measure what the obvious modelling loses; not used for anything else."
  (dissoc schema :company/name))

;; ---------------------------------------------------------------------------
;; Transaction data

(defn identity-transaction
  "One manager map and one company map per row: 7,381 of each. None carries
  an entity id. The unique-identity attributes make each an upsert, so they
  collapse to 28 managers and 2,251 companies, and the company names
  accumulate under the cardinality-many attribute."
  [rows]
  (mapcat (fn [{:keys [manager cusip company]}]
            [{:manager/name manager}
             {:company/cusip cusip :company/name company}])
          rows))

(defn holding-transaction
  "One holding entity per row. The two refs are lookup refs: [attribute value]
  pairs that resolve through the unique attributes to the entities the
  identity transaction made."
  [rows]
  (map (fn [{:keys [manager cusip company quarter value shares filing]}]
         {:holding/manager [:manager/name manager]
          :holding/company [:company/cusip cusip]
          :holding/company-name company
          :holding/quarter quarter
          :holding/value value
          :holding/shares shares
          :holding/filing filing})
       rows))

(defn load-db
  "An immutable database value holding the rows. Two transactions, because a
  lookup ref has to resolve to an entity that already exists."
  ([rows] (load-db rows schema))
  ([rows database-schema]
   (-> (d/empty-db database-schema)
       (d/db-with (identity-transaction rows))
       (d/db-with (holding-transaction rows)))))

(defn load-file-db
  ([] (load-file-db default-csv-path))
  ([csv-path] (load-db (read-rows csv-path))))

;; ---------------------------------------------------------------------------
;; The tuples themselves

(defn tuples
  "The database as [entity attribute value] vectors, in entity order. This is
  all there is: every query below is a join over these."
  [database]
  (map (fn [datom] [(:e datom) (:a datom) (:v datom)])
       (d/datoms database :eavt)))

(defn tuples-of
  "The tuples for one attribute, from the attribute-first index."
  [database attribute]
  (map (fn [datom] [(:e datom) (:a datom) (:v datom)])
       (d/datoms database :aevt attribute)))

(defn attribute-counts
  "How many datoms each attribute has."
  [database]
  (->> (d/datoms database :aevt)
       (map :a)
       frequencies
       (sort-by key)
       (mapv (fn [[attribute datom-count]] {:attribute attribute :datoms datom-count}))))

;; ---------------------------------------------------------------------------
;; Counts

(defn counts
  "Entities of each kind, found by the attribute only that kind has."
  [database]
  (let [count-entities (fn [attribute]
                         (or (d/q '[:find (count ?entity) .
                                    :in $ ?attribute
                                    :where [?entity ?attribute]]
                                  database attribute)
                             0))]
    {:managers (count-entities :manager/name)
     :companies (count-entities :company/cusip)
     :holdings (count-entities :holding/manager)}))

;; ---------------------------------------------------------------------------
;; Degree on each side

(defn- top
  [limit sort-key result-maps]
  (->> result-maps (sort-by (juxt (comp - sort-key) str)) (take limit) vec))

(defn companies-per-manager
  "Managers by how many distinct companies they hold, with the total value.

  :with ?holding matters. A Datalog result is a set of tuples, so without it
  two holdings of the same manager with the same company and value would be
  one tuple and the sum would come out short."
  ([database] (companies-per-manager database 5))
  ([database limit]
   (->> (d/q '[:find ?manager-name (count-distinct ?company) (sum ?value)
               :keys manager companies value
               :with ?holding
               :where
               [?holding :holding/manager ?manager]
               [?holding :holding/company ?company]
               [?holding :holding/value ?value]
               [?manager :manager/name ?manager-name]]
             database)
        (top limit :companies))))

(defn managers-per-company
  "Companies by how many distinct managers hold them. A company has a set of
  names, so one is picked for display: the first alphabetically."
  ([database] (managers-per-company database 5))
  ([database limit]
   (->> (d/q '[:find ?cusip (min ?company-name) (count-distinct ?manager)
               :keys cusip company managers
               :where
               [?holding :holding/company ?company]
               [?holding :holding/manager ?manager]
               [?company :company/cusip ?cusip]
               [?company :company/name ?company-name]]
             database)
        (top limit :managers))))

(defn holders-of
  "Who holds the company with this CUSIP. The input is bound with :in, the
  Datalog counterpart of a Cypher $parameter."
  [database cusip]
  (->> (d/q '[:find ?manager-name ?filed-name ?value ?shares
              :keys manager company value shares
              :in $ ?cusip
              :where
              [?company :company/cusip ?cusip]
              [?holding :holding/company ?company]
              [?holding :holding/manager ?manager]
              [?manager :manager/name ?manager-name]
              [?holding :holding/company-name ?filed-name]
              [?holding :holding/value ?value]
              [?holding :holding/shares ?shares]]
            database cusip)
       (sort-by :value >)
       vec))

;; ---------------------------------------------------------------------------
;; Name variants

(defn name-variant-count
  "CUSIPs filed under more than one company name, entirely in Datalog: a
  company with two names that differ."
  [database]
  (or (d/q '[:find (count-distinct ?company) .
             :where
             [?company :company/name ?one-name]
             [?company :company/name ?another-name]
             [(not= ?one-name ?another-name)]]
           database)
      0))

(defn name-variants
  "The variants themselves: each CUSIP with every name filed against it.
  `distinct` is the aggregate that returns the set."
  [database]
  (->> (d/q '[:find ?cusip (distinct ?company-name)
              :keys cusip names
              :where
              [?company :company/cusip ?cusip]
              [?company :company/name ?company-name]]
            database)
       (filter #(next (:names %)))
       (map #(update % :names (comp vec sort)))
       (sort-by :cusip)
       vec))

(defn names-by-filer
  "For one CUSIP, which manager filed it under which name. Only answerable
  because the holding keeps the spelling it was filed with."
  [database cusip]
  (->> (d/q '[:find ?filed-name ?manager-name
              :keys company manager
              :in $ ?cusip
              :where
              [?company :company/cusip ?cusip]
              [?holding :holding/company ?company]
              [?holding :holding/company-name ?filed-name]
              [?holding :holding/manager ?manager]
              [?manager :manager/name ?manager-name]]
            database cusip)
       (sort-by (juxt :company :manager))
       vec))

;; ---------------------------------------------------------------------------
;; A rule

(def rules
  "Named, reusable clauses. `holds` hides the holding entity and gives back
  the edge the property graph had: manager to company. `co-holds` is built
  from it. Comparing the two entity ids keeps each pair once and drops a
  manager paired with itself."
  '[[(holds ?manager ?company)
     [?holding :holding/manager ?manager]
     [?holding :holding/company ?company]]
    [(co-holds ?first-manager ?second-manager ?company)
     (holds ?first-manager ?company)
     (holds ?second-manager ?company)
     [(< ?first-manager ?second-manager)]]])

(defn shared-company-pairs
  "Pairs of managers who hold at least `minimum-shared` of the same companies.
  The threshold is applied in Clojure: Datalog here has no HAVING, a filter
  on an aggregate needs a second query or a function over the result."
  ([database] (shared-company-pairs database 1))
  ([database minimum-shared]
   (->> (d/q '[:find ?first-name ?second-name (count-distinct ?company)
               :keys first-manager second-manager shared
               :in $ %
               :where
               (co-holds ?first-manager ?second-manager ?company)
               [?first-manager :manager/name ?first-name]
               [?second-manager :manager/name ?second-name]]
             database rules)
        (filter #(>= (:shared %) minimum-shared))
        (sort-by (juxt (comp - :shared) :first-manager :second-manager))
        vec)))

(defn shared-company-pairs-inline
  "The same question with the rule bodies written out, to time against the
  rule. Same answer."
  ([database] (shared-company-pairs-inline database 1))
  ([database minimum-shared]
   (->> (d/q '[:find ?first-name ?second-name (count-distinct ?company)
               :keys first-manager second-manager shared
               :where
               [?first-holding :holding/manager ?first-manager]
               [?first-holding :holding/company ?company]
               [?second-holding :holding/company ?company]
               [?second-holding :holding/manager ?second-manager]
               [(< ?first-manager ?second-manager)]
               [?first-manager :manager/name ?first-name]
               [?second-manager :manager/name ?second-name]]
             database)
        (filter #(>= (:shared %) minimum-shared))
        (sort-by (juxt (comp - :shared) :first-manager :second-manager))
        vec)))

;; ---------------------------------------------------------------------------
;; Pull

(def manager-pattern
  "A manager with its holdings and the company at the end of each.
  :holding/_manager is the ref followed backwards. A (:holding/_manager
  :limit 5) here is refused by DataScript 1.8.1: it checks the cardinality of
  the forward attribute, which is one. So the trimming is done in Clojure."
  '[:manager/name
    {:holding/_manager
     [:holding/value :holding/shares :holding/quarter
      {:holding/company [:company/cusip :company/name]}]}])

(defn pull-manager
  "The manager as a nested map, with at most `holding-limit` holdings."
  ([database manager-name] (pull-manager database manager-name 5))
  ([database manager-name holding-limit]
   (-> (d/pull database manager-pattern [:manager/name manager-name])
       (update :holding/_manager #(vec (take holding-limit %))))))
