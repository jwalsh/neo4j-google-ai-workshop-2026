(ns form13.triples
  "The same file in Asami, the comparison store: a schemaless in-memory triple
  store, also queried with Datalog. Briefer than form13.datoms on purpose;
  enough to see what changes when there is no schema and no upsert.

  Nothing is declared. A node is whatever value stands in the first position,
  so the loader mints the node names itself and identity is the loader's
  business: the same CUSIP gives the same keyword, and a repeated triple is
  the same triple. Every attribute is multi-valued by default, so the name
  variants survive without anyone asking."
  (:require [asami.core :as asami]
            [asami.graph :as graph]))

(def database-uri "asami:mem://form13")

;; ---------------------------------------------------------------------------
;; Triples

(defn- manager-node
  [manager-ordinal]
  (keyword "manager" (str "m" manager-ordinal)))

(defn- company-node
  "The CUSIP is the identity, so it is the node name. The prefix is there
  because a keyword name that starts with a digit cannot be typed back in."
  [cusip]
  (keyword "company" (str "cusip-" cusip)))

(defn- holding-node
  [row-number]
  (keyword "holding" (str "h" row-number)))

(defn rows->triples
  "Nine triples per row, as [subject predicate object]. The manager and
  company triples repeat from row to row; the store keeps each once."
  [rows]
  (let [manager-ordinals (zipmap (distinct (map :manager rows)) (range))]
    (mapcat (fn [row-number {:keys [manager cusip company quarter value shares]}]
              (let [manager-entity (manager-node (manager-ordinals manager))
                    company-entity (company-node cusip)
                    holding (holding-node row-number)]
                [[manager-entity :manager/name manager]
                 [company-entity :company/cusip cusip]
                 [company-entity :company/name company]
                 [holding :holding/manager manager-entity]
                 [holding :holding/company company-entity]
                 [holding :holding/company-name company]
                 [holding :holding/quarter quarter]
                 [holding :holding/value value]
                 [holding :holding/shares shares]]))
            (range)
            rows)))

(defn load-db
  "A fresh in-memory database holding the rows. Asami databases are named by
  URI and kept in a registry, so the old one is deleted first."
  [rows]
  (asami/delete-database database-uri)
  (asami/create-database database-uri)
  (let [connection (asami/connect database-uri)]
    @(asami/transact connection {:tx-triples (rows->triples rows)})
    (asami/db connection)))

(defn tuples
  "Every triple in the store."
  [database]
  (graph/resolve-triple (asami/graph database) '?subject '?predicate '?object))

;; ---------------------------------------------------------------------------
;; Queries

(defn counts
  "Three queries with the attribute written in. One query with the attribute
  passed through :in was the first attempt and is wrong in Asami 2.3.4: with
  an aggregate in :find the :in binding is ignored, and all three counts came
  back 9,660, every subject in the store. No error. See
  `count-with-bound-attribute`.

  `count` here counted distinct values of the variable, which is what is
  wanted. See the README log for what `count-distinct` did."
  [database]
  {:managers (ffirst (asami/q '[:find (count ?manager)
                                :where [?manager :manager/name ?manager-name]]
                              database))
   :companies (ffirst (asami/q '[:find (count ?company)
                                 :where [?company :company/cusip ?cusip]]
                               database))
   :holdings (ffirst (asami/q '[:find (count ?holding)
                                :where [?holding :holding/manager ?manager]]
                              database))})

(defn count-with-bound-attribute
  "The query `counts` should have been. Kept so the wrong answer can be seen:
  the gate reports it without gating on it."
  [database attribute]
  (ffirst (asami/q '[:find (count ?entity)
                     :in $ ?attribute
                     :where [?entity ?attribute ?any-value]]
                   database attribute)))

(defn- top
  [limit sort-key result-maps]
  (->> result-maps (sort-by (juxt (comp - sort-key) str)) (take limit) vec))

(defn companies-per-manager
  ([database] (companies-per-manager database 5))
  ([database limit]
   (->> (asami/q '[:find ?manager-name (count ?company)
                   :where
                   [?holding :holding/manager ?manager]
                   [?holding :holding/company ?company]
                   [?manager :manager/name ?manager-name]]
                 database)
        (map (fn [[manager-name company-count]]
               {:manager manager-name :companies company-count}))
        (top limit :companies))))

(defn managers-per-company
  "The display name is looked up afterwards: `min` over the names in the same
  query was not tried, the entity is a map with the names as a set."
  ([database] (managers-per-company database 5))
  ([database limit]
   (->> (asami/q '[:find ?company ?cusip (count ?manager)
                   :where
                   [?holding :holding/company ?company]
                   [?holding :holding/manager ?manager]
                   [?company :company/cusip ?cusip]]
                 database)
        (map (fn [[company cusip manager-count]]
               {:node company :cusip cusip :managers manager-count}))
        (top limit :managers)
        (mapv (fn [{:keys [node] :as result}]
                (let [names (:company/name (asami/entity database node))
                      name-set (if (set? names) names #{names})]
                  (-> result
                      (dissoc :node)
                      (assoc :company (first (sort name-set))))))))))

(defn name-variant-count
  [database]
  (ffirst (asami/q '[:find (count ?company)
                     :where
                     [?company :company/name ?one-name]
                     [?company :company/name ?another-name]
                     [(not= ?one-name ?another-name)]]
                   database)))

(defn shared-company-pairs
  "No rules: Asami's query takes none (rules are a separate engine, Naga,
  that writes inferred triples back). So the join is written out. The count
  is done in Clojure, because the grouped aggregate over this join did not
  come back; and the pair is ordered in Clojure, because `<` in a filter is
  clojure.core/< and throws on strings and keywords."
  ([database] (shared-company-pairs database 1))
  ([database minimum-shared]
   (let [manager-names (into {} (asami/q '[:find ?manager ?manager-name
                                           :where [?manager :manager/name ?manager-name]]
                                         database))]
     (->> (asami/q '[:find ?first-manager ?second-manager ?company
                     :where
                     [?first-holding :holding/manager ?first-manager]
                     [?first-holding :holding/company ?company]
                     [?second-holding :holding/company ?company]
                     [?second-holding :holding/manager ?second-manager]
                     [(not= ?first-manager ?second-manager)]]
                   database)
          (map (fn [[first-manager second-manager company]]
                 [(sort [(manager-names first-manager) (manager-names second-manager)])
                  company]))
          distinct
          (map first)
          frequencies
          (map (fn [[[first-name second-name] shared]]
                 {:first-manager first-name :second-manager second-name :shared shared}))
          (filter #(>= (:shared %) minimum-shared))
          (sort-by (juxt (comp - :shared) :first-manager :second-manager))
          vec))))

(defn total-value-as-summed
  "(sum ?value) over every holding, as Asami returns it. Kept because it is
  wrong for this file in an instructive way: see form13.gate."
  [database]
  (ffirst (asami/q '[:find (sum ?value)
                     :where [?holding :holding/value ?value]]
                   database)))
