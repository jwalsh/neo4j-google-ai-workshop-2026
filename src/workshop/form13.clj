(ns workshop.form13
  "The lab's Form 13 day file, explored locally: downloaded once into data/,
  parsed into maps, and queried in memory with no database. `verify` and
  `load!` are where it meets Neo4j: the local file says what a correct load
  holds, so a load is checked against it and skipped when already right."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [workshop.gcp :as gcp]
            [workshop.neo4j :as neo4j]))

(def object-name "hands-on-lab/form13-2023-05-11.csv")
(def local-path "data/form13-2023-05-11.csv")

(def load-files
  "The keyed load from Lab 4, in order. Every statement is MERGE or
  IF NOT EXISTS, so running them twice changes nothing."
  ["labs/lab-04/03-constraints.cypher"
   "labs/lab-04/04-companies.cypher"
   "labs/lab-04/05-managers.cypher"
   "labs/lab-04/06-relationships.cypher"])

(defn fetch!
  "Downloads the file once. A transfer lands in a .part file and is renamed
  when whole, so a failed one never leaves a short CSV behind."
  []
  (when-not (.exists (io/file local-path))
    (let [partial-path (str local-path ".part")]
      (gcp/download-object (gcp/public-storage) gcp/dataset-bucket object-name partial-path)
      (.renameTo (io/file partial-path) (io/file local-path))))
  local-path)

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
  "Every row of the file as a map with typed :value and :shares."
  []
  (with-open [reader (io/reader (fetch!))]
    (let [[header & lines] (csv/read-csv reader)]
      (mapv #(parse-row (zipmap header %)) lines))))

(def rows
  "The parsed file, read on first use and kept."
  (delay (read-rows)))

(defn expected-counts
  "What the keyed load should leave in the database: companies by CUSIP,
  managers by name, one OWNS per manager, company and quarter."
  [holdings]
  {:companies (count (distinct (map :cusip holdings)))
   :managers (count (distinct (map :manager holdings)))
   :owns (count (distinct (map (juxt :manager :cusip :quarter) holdings)))})

(defn summary
  [holdings]
  (merge {:rows (count holdings)
          :quarters (sort (distinct (map :quarter holdings)))
          :total-value (reduce + (map :value holdings))
          :company-names (count (distinct (map (juxt :company :cusip) holdings)))}
         (expected-counts holdings)))

(defn top-holdings
  "The largest positions by reported value."
  ([holdings] (top-holdings holdings 10))
  ([holdings limit]
   (->> holdings
        (sort-by :value >)
        (take limit)
        (mapv #(select-keys % [:manager :company :value :shares])))))

(defn manager-degree
  "Managers by how many distinct companies they hold: degree centrality on
  the Manager side, which is what Lab 5 has Bloom compute."
  [holdings]
  (->> (group-by :manager holdings)
       (map (fn [[manager positions]]
              {:manager manager
               :companies (count (distinct (map :cusip positions)))
               :value (reduce + (map :value positions))}))
       (sort-by :companies >)
       vec))

(defn company-degree
  "Companies by how many distinct managers hold them."
  ([holdings] (company-degree holdings 10))
  ([holdings limit]
   (->> (group-by :cusip holdings)
        (map (fn [[cusip positions]]
               {:company (:company (first positions))
                :cusip cusip
                :managers (count (distinct (map :manager positions)))}))
        (sort-by :managers >)
        (take limit)
        vec)))

(defn name-variants
  "CUSIPs filed under more than one company name: why the lab keys Company
  on cusip and not on the name."
  [holdings]
  (->> (group-by :cusip holdings)
       (keep (fn [[cusip positions]]
               (let [names (sort (distinct (map :company positions)))]
                 (when (next names) {:cusip cusip :names (str/join " / " names)}))))
       (sort-by :cusip)
       vec))

(def value-scale
  "The file's value column divided by shares comes out a thousand times a
  share price throughout (Abbott at 101,000, Southern Co at 69,500). That is
  an inference from the numbers, not something the lab or the file states."
  1000.0)

(defn odd-prices
  "Positions whose implied share price, after value-scale, is still above a
  ceiling. The lab reads its one example as a bad filing; scaled, most of
  the file is plausible and these are what is left."
  ([holdings] (odd-prices holdings 10000.0))
  ([holdings price-ceiling]
   (->> holdings
        (filter #(pos? (:shares %)))
        (map #(assoc % :per-share (/ (:value %) (:shares %) value-scale)))
        (filter #(> (:per-share %) price-ceiling))
        (sort-by :per-share >)
        (mapv #(select-keys % [:manager :company :value :shares :per-share])))))

(defn holders-of
  "Who holds a company, by a fragment of its name."
  [holdings name-fragment]
  (->> holdings
       (filter #(str/includes? (str/upper-case (:company %)) (str/upper-case name-fragment)))
       (sort-by :value >)
       (mapv #(select-keys % [:manager :company :value :shares]))))

(defn database-counts
  [driver]
  (first (neo4j/query driver
                      (str "MATCH (c:Company) WITH count(c) AS companies "
                           "MATCH (m:Manager) WITH companies, count(m) AS managers "
                           "MATCH ()-[r:OWNS]->() RETURN companies, managers, count(r) AS owns"))))

(defn verify
  "The database's counts beside the file's. :loaded? is true when they agree."
  [driver holdings]
  (let [expected (expected-counts holdings)
        actual (or (database-counts driver) {:companies 0 :managers 0 :owns 0})]
    {:expected expected :actual actual :loaded? (= expected actual)}))

(defn load!
  "Runs the keyed load unless the database already holds what the file
  says it should. Returns the verification after."
  [driver holdings]
  (let [before (verify driver holdings)]
    (if (:loaded? before)
      (assoc before :action :already-loaded)
      (do (run! #(neo4j/run-file driver %) load-files)
          (assoc (verify driver holdings) :action :loaded)))))

(comment
 ;; the day file in memory, from data/; no database
 (summary @rows)
 (take 3 (top-holdings @rows))
 (take 5 (manager-degree @rows))
 (take 5 (company-degree @rows))
 (take 3 (name-variants @rows))
 (take 3 (odd-prices @rows))
 (holders-of @rows "ALLSTATE CORP")
:review)
