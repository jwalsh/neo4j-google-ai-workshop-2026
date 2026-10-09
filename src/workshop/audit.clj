(ns workshop.audit
  "What is true now beside what the notes say. Every check is a function
  that returns data in the shape workshop.status uses,

    {:name :state :summary :details}

  with :state :up when what was checked holds, :down when it does not and
  :unknown when it could not be decided from here, plus :data with the
  full answer for a REPL. Nothing here writes: to the repository, or to
  the database, where every statement is in `statements` and is a read.

    (run-all)                 ; every check
    (discrepancies (run-all)) ; the ones that are :down
    (claims)                  ; the notes' numbers, one row each

  .meta/audit-2026-10-08.md is one evaluation of this, written down. No
  check returns a credential: values from .env are compared in memory and
  only the variable's name and the file's path come back."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.labs :as labs]
            [workshop.neo4j :as neo4j]))

;; Pure parts

(defn file-links
  "The targets of [[file:...]] links in org text, in order."
  [org-text]
  (mapv second (re-seq #"\[\[file:([^\]]+)\]" org-text)))

(def path-roots
  "Directories a mention has to start with to be read as a path here."
  #{"labs" "notes" "src" "dev" "test" "driver" "experiments" "resources" "data" ".meta"})

(defn path-mentions
  "Paths into this repository named in prose, between = = or backticks.
  A mention with a placeholder or a wildcard in it is not a path."
  [prose]
  (->> (re-seq #"[=`]([A-Za-z0-9_.][A-Za-z0-9_./-]*)[=`]" prose)
       (map second)
       (filter #(str/includes? % "/"))
       (filter #(path-roots (first (str/split % #"/"))))
       (remove #(re-find #"[<>*]|/$" %))
       distinct
       vec))

(defn checklist-items
  "Every box in the org checklist as {:assignment :item :ticked?}."
  [checklist-text]
  (:items
   (reduce (fn [{:keys [assignment] :as state} line]
             (if-let [[_ heading] (re-matches #"\*\*\* (?:TODO|DONE) (.*)" line)]
               (assoc state :assignment heading)
               (if-let [[_ box item] (and assignment (re-matches #"- \[([ Xx-])\] (.*)" line))]
                 (update state :items conj {:assignment assignment
                                            :item item
                                            :ticked? (contains? #{"X" "x"} box)})
                 state)))
           {:assignment nil :items []}
           (str/split-lines checklist-text))))

(defn lab-number
  "The lab a checklist item names, or nil for a lecture."
  [item]
  (some-> (re-find #"^Lab (\d+)" item) second parse-long))

(defn value-hits
  "Where secret values appear in texts. secrets is name to value, texts is
  path to content. Returns name to sorted paths, for names with a hit, and
  never a value. A value shorter than eight characters is not searched for:
  `neo4j` is a username here and a word everywhere else."
  [secrets texts]
  (into (sorted-map)
        (keep (fn [[variable-name value]]
                (when (and (string? value) (>= (count value) 8))
                  (when-let [paths (seq (sort (keep (fn [[path content]]
                                                      (when (str/includes? content value) path))
                                                    texts)))]
                    [variable-name (vec paths)]))))
        secrets))

(defn claim
  "One thing a note says, beside what was found. :holds? is nil when what
  was found cannot settle it."
  ([source statement expected actual]
   (claim source statement expected actual (= expected actual)))
  ([source statement expected actual holds?]
   {:source source :claim statement :expected expected :actual actual :holds? holds?}))

(defn naive-load-shape
  "What Lab 4's first statement makes of the file: a company per name and
  CUSIP together, a manager per name, an OWNS per distinct manager, company
  node, value, shares and quarter, and the properties those carry."
  [holdings]
  (let [companies (count (distinct (map (juxt :company :cusip) holdings)))
        managers (count (distinct (map :manager holdings)))
        relationships (count (distinct (map (juxt :manager :company :cusip :value :shares :quarter)
                                            holdings)))]
    {:companies companies
     :nodes (+ companies managers)
     :relationships relationships
     :properties (+ managers (* 2 companies) (* 3 relationships))}))

(defn check
  [check-name state summary details data]
  {:name check-name :state state :summary summary :details (vec details) :data data})

;; The repository

(def prose-files
  "Where the claims and the links are. Written-down audits are left out."
  (delay (->> (concat [(io/file "README.org") (io/file "CLAUDE.md") (io/file "labs/README.org")
                       (io/file "bb.edn") (io/file "Makefile")]
                      (file-seq (io/file "notes"))
                      (file-seq (io/file ".meta")))
              (filter #(.isFile ^java.io.File %))
              (filter #(re-find #"\.(org|md|edn)$|Makefile$" (.getName ^java.io.File %)))
              ;; An audit quotes the stale paths it found; reading it back
              ;; would find them again.
              (remove #(str/starts-with? (.getName ^java.io.File %) "audit-"))
              (mapv #(.getPath ^java.io.File %))
              sort)))

(defn- git
  [& arguments]
  (str/trim-newline (:out (apply shell/sh "git" arguments))))

(defn tracked-paths [] (remove str/blank? (str/split-lines (git "ls-files"))))

(defn repository
  "Branch, head, and what is uncommitted. Informational."
  []
  (let [uncommitted (remove str/blank? (str/split-lines (git "status" "--short")))]
    (check "Repository" :unknown
           (format "%s at %s, %d uncommitted" (git "branch" "--show-current")
                   (git "rev-parse" "--short" "HEAD") (count uncommitted))
           (map (fn [status-line] [(subs status-line 3) (str/trim (subs status-line 0 2))]) uncommitted)
           {:uncommitted (vec uncommitted)})))

(defn links
  "Every [[file:...]] link in README.org and the notes, resolved from the
  file that holds it."
  []
  (let [org-paths (filter #(str/ends-with? % ".org") @prose-files)
        resolved (for [org-path org-paths
                       target (file-links (slurp org-path))
                       :let [target-file (io/file (.getParentFile (.getAbsoluteFile (io/file org-path))) target)]]
                   {:from org-path :target target :exists? (.exists target-file)})
        broken (remove :exists? resolved)]
    (check "Links" (if (seq broken) :down :up)
           (format "%d links in %d org files, %d broken" (count resolved) (count org-paths) (count broken))
           (map (juxt :from :target) broken)
           {:links (vec resolved)})))

(defn notes-indexed
  "CLAUDE.md: a note is not done until README.org links to it. And an image
  in notes/img that no note links to is a leftover."
  []
  (let [readme-text (slurp "README.org")
        notes-text (str/join "\n" (map slurp (filter #(str/starts-with? % "notes/") @prose-files)))
        file-names (fn [directory pattern]
                     (->> (.listFiles (io/file directory))
                          (map #(.getName ^java.io.File %))
                          (filter #(re-find pattern %))
                          sort))
        unindexed (remove #(str/includes? readme-text (str "notes/" %)) (file-names "notes" #"\.org$"))
        unlinked (remove #(str/includes? notes-text %) (file-names "notes/img" #"\.png$"))]
    (check "Notes indexed" (if (or (seq unindexed) (seq unlinked)) :down :up)
           (format "%d notes not in README.org, %d images no note links" (count unindexed) (count unlinked))
           (concat (map (fn [note-name] [(str "notes/" note-name) "not linked from README.org"]) unindexed)
                   (map (fn [image-name] [(str "notes/img/" image-name) "linked from no note"]) unlinked))
           {:unindexed (vec unindexed) :unlinked (vec unlinked)})))

(defn paths-mentioned
  "Paths the prose names that are not on disk."
  []
  (let [mentions (for [prose-path @prose-files
                       mentioned (path-mentions (slurp prose-path))]
                   {:from prose-path :path mentioned :exists? (.exists (io/file mentioned))})
        absent (remove :exists? mentions)]
    (check "Paths in prose" (if (seq absent) :down :up)
           (format "%d paths named, %d not on disk" (count mentions) (count absent))
           (map (juxt :from :path) absent)
           {:absent (vec absent)})))

(defn secrets-kept
  "Whether .env is tracked or in history, and whether any of its values is
  in a tracked file or in any commit's diff. Names and paths only."
  []
  (let [secrets (labs/secrets-of (env/parse-dotenv (slurp ".env")))
        tracked (tracked-paths)
        env-tracked (filter #{".env" ".envrc.local"} tracked)
        env-in-history (git "log" "--all" "--format=%h" "--" ".env" ".envrc.local")
        in-files (value-hits secrets (map (fn [path] [path (slurp path :encoding "ISO-8859-1")])
                                          (filter #(.isFile (io/file %)) tracked)))
        in-history (value-hits secrets [["history" (git "log" "--all" "-p" "--no-color")]])
        clean? (and (empty? env-tracked) (str/blank? env-in-history) (empty? in-files) (empty? in-history))]
    (check "Secrets" (if clean? :up :down)
           (format ".env %s; %d of %d values found in tracked files, %d in history"
                   (if (or (seq env-tracked) (not (str/blank? env-in-history))) "tracked or in history" "untracked")
                   (count in-files) (count secrets) (count in-history))
           (concat (map (fn [[variable-name paths]] [variable-name (str/join ", " paths)]) in-files)
                   (map (fn [[variable-name _]] [variable-name "in a commit's diff"]) in-history))
           {:searched (vec (sort (keys secrets))) :in-files in-files :in-history (vec (keys in-history))})))

(defn lint
  "clj-kondo over src, dev and test. :unknown when it is not installed."
  []
  (let [{:keys [exit out]} (try (shell/sh "clj-kondo" "--lint" "src" "dev" "test")
                                (catch java.io.IOException _ {:exit nil :out "clj-kondo not installed"}))
        last-line (last (str/split-lines (str/trim out)))]
    (check "Lint" (case exit 0 :up nil :unknown :down) last-line
           (when (and exit (pos? exit)) (map (fn [line] ["clj-kondo" line]) (butlast (str/split-lines out))))
           {:exit exit})))

(defn checklist
  "README.org's boxes beside what workshop.labs can show for each lab. A
  ticked lab whose step is blocked was done in a console: that is recorded,
  not a fault. An unticked lab whose step is :ok would be one."
  ([] (checklist (labs/run-all)))
  ([lab-results]
   (let [status-by-lab (reduce (fn [by-lab {:keys [lab status]}] (update by-lab lab (fnil conj []) status))
                               {} lab-results)
         rows (mapv (fn [{:keys [item] :as box}]
                      (assoc box :by-code (get status-by-lab (lab-number item))))
                    (checklist-items (slurp "README.org")))
         unticked-but-run (filter #(and (not (:ticked? %)) (some #{:ok} (:by-code %))) rows)]
     (check "Checklist" (if (seq unticked-but-run) :down :up)
            (format "%d of %d boxes ticked" (count (filter :ticked? rows)) (count rows))
            (map (fn [{:keys [item ticked? by-code]}]
                   [item (str (if ticked? "ticked" "unticked")
                              (when by-code (str "; by code: " (str/join ", " (map name by-code)))))])
                 rows)
            {:rows rows}))))

;; The database

(def statements
  "What the database checks send. All reads."
  {:components "CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition"
   :labels "MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS nodes ORDER BY label"
   :label-tokens "CALL db.labels() YIELD label RETURN label ORDER BY label"
   :relationships (str "MATCH (source)-[r]->(target) "
                       "RETURN labels(source) AS source, type(r) AS type, labels(target) AS target, "
                       "count(*) AS relationships ORDER BY relationships DESC")
   :node-keys (str "MATCH (n) UNWIND labels(n) AS label UNWIND keys(n) AS property "
                   "RETURN label, property, count(*) AS nodes ORDER BY label, property")
   :relationship-keys (str "MATCH ()-[r]->() UNWIND keys(r) AS property "
                           "RETURN type(r) AS type, property, count(*) AS relationships ORDER BY type, property")
   :constraints "SHOW CONSTRAINTS YIELD name, type, entityType, labelsOrTypes, properties"
   :indexes (str "SHOW INDEXES YIELD name, type, state, labelsOrTypes, properties, owningConstraint "
                 "RETURN name, type, state, labelsOrTypes, properties, owningConstraint ORDER BY name")
   :lab-4-isolated "MATCH (n) WHERE (n:Company OR n:Manager) AND NOT (n)--() RETURN count(n) AS nodes"
   :lab-4-extra-keys (str "MATCH (n) WHERE n:Company OR n:Manager UNWIND keys(n) AS property "
                          "WITH property WHERE NOT property IN ['companyName', 'cusip', 'managerName'] "
                          "RETURN property, count(*) AS nodes")
   :owns-extra-keys (str "MATCH ()-[r:OWNS]->() UNWIND keys(r) AS property "
                         "WITH property WHERE NOT property IN ['value', 'shares', 'reportCalendarOrQuarter'] "
                         "RETURN property, count(*) AS relationships")
   :owns-quarters "MATCH ()-[r:OWNS]->() RETURN toString(r.reportCalendarOrQuarter) AS quarter, count(*) AS relationships"
   :parallel-owns (str "MATCH (m:Manager)-[r:OWNS]->(c:Company) WITH m, c, count(r) AS parallel "
                       "WHERE parallel > 1 RETURN count(*) AS pairs")
   :shared-holdings (str "MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager) "
                         "WHERE a.managerName < b.managerName "
                         "RETURN a.managerName AS first, b.managerName AS second, count(DISTINCT c) AS shared "
                         "ORDER BY shared DESC LIMIT 3")
   :sessions "CALL gds.session.list() YIELD name, memory, status RETURN name, memory, status"})

(defn- with-driver
  [driver-fn]
  (with-open [driver (neo4j/connect)]
    (driver-fn driver)))

(defn ask
  "Runs one of `statements` by name and returns its rows."
  ([statement-name] (with-driver #(ask % statement-name)))
  ([driver statement-name] (neo4j/query driver (statements statement-name))))

(defn inventory
  "Everything the database says it holds. Informational: what belongs is a
  question for `lab-4-intact` and `claims`."
  ([] (with-driver inventory))
  ([driver]
   (let [answers (into {} (map (fn [statement-name] [statement-name (ask driver statement-name)]))
                       [:components :labels :label-tokens :relationships :node-keys
                        :relationship-keys :constraints :indexes])
         kernel (first (:components answers))]
     (check "Database inventory" :unknown
            (format "%s %s; %d labels in use, %d relationship patterns, %d constraints, %d indexes"
                    (:name kernel) (first (:versions kernel)) (count (:labels answers))
                    (count (:relationships answers)) (count (:constraints answers)) (count (:indexes answers)))
            (concat (map (fn [{:keys [label nodes]}] [(str "(:" label ")") (str nodes)]) (:labels answers))
                    (map (fn [{:keys [source type target relationships]}]
                           [(str (first source) " " type " " (first target)) (str relationships)])
                         (:relationships answers))
                    (map (fn [{:keys [name type labelsOrTypes properties]}]
                           [(str "index " name) (str type " " labelsOrTypes " " properties)])
                         (:indexes answers)))
            answers))))

(defn lab-4-intact
  "Lab 4's load as it was left: the file's counts, both node keys, no
  Company or Manager without an OWNS, no property on them the lab did not
  set, one quarter, no pair with two OWNS. What later labs add under other
  labels is not this check's business."
  ([] (with-driver #(lab-4-intact % @form13/rows)))
  ([driver holdings]
   (let [verification (form13/verify driver holdings)
         absent (labs/missing-constraints (ask driver :constraints))
         isolated (:nodes (first (ask driver :lab-4-isolated)))
         extra-keys (concat (ask driver :lab-4-extra-keys) (ask driver :owns-extra-keys))
         quarters (mapv :quarter (ask driver :owns-quarters))
         parallel (:pairs (first (ask driver :parallel-owns)))
         intact? (and (:loaded? verification) (empty? absent) (zero? isolated)
                      (empty? extra-keys) (= ["2023-03-31"] quarters) (zero? parallel))]
     (check "Lab 4 intact" (if intact? :up :down)
            (format "%s; %d node keys missing, %d isolated, %d properties not from the lab, quarters %s"
                    (if (:loaded? verification) "counts match the file" "counts differ from the file")
                    (count absent) isolated (count extra-keys) quarters)
            [["expected" (pr-str (:expected verification))]
             ["actual" (pr-str (:actual verification))]]
            {:verification verification :missing-constraints absent :isolated isolated
             :extra-keys (vec extra-keys) :quarters quarters :parallel-pairs parallel}))))

;; The notes' claims

(defn claims
  "The numbers and names the notes state, each beside what the file or the
  database gives now. One row per claim."
  ([] (with-driver #(claims % @form13/rows)))
  ([driver holdings]
   (let [verification (form13/verify driver holdings)
         naive (naive-load-shape holdings)
         variants (form13/name-variants holdings)
         lab-5-evidence (:evidence (labs/lab-5 driver holdings))
         shared (first (ask driver :shared-holdings))
         session-rows (ask driver :sessions)
         boxes (checklist-items (slurp "README.org"))
         ticked-lectures (mapv :item (filter #(and (:ticked? %) (nil? (lab-number (:item %)))) boxes))
         label-tokens (set (map :label (ask driver :label-tokens)))]
     [(claim "notes/lab-03" "two databases, neo4j the default, both online"
             true (labs/databases-as-expected? (neo4j/databases driver)))
      (claim "notes/lab-04, .meta/README" "2,251 companies, 28 managers, 7,381 OWNS"
             {:companies 2251 :managers 28 :owns 7381} (:actual verification))
      (claim "notes/lab-04" "the file gives the same counts" {:companies 2251 :managers 28 :owns 7381}
             (:expected verification))
      (claim "notes/lab-04" "constraints unique_company_id and unique_manager, both NODE KEY"
             [] (labs/missing-constraints (ask driver :constraints)))
      (claim "notes/lab-04" "naive load: 2,605 nodes, 7,381 relationships, 27,325 properties, 2,577 companies"
             {:companies 2577 :nodes 2605 :relationships 7381 :properties 27325} naive)
      (claim "notes/lab-04" "258 CUSIPs under more than one name" 258 (count variants))
      (claim "notes/lab-04" "AT&T under four names" 4
             (some->> variants
                      (filter #(re-find #"(?i)AT&T|AT & T" (:names %)))
                      (map #(count (str/split (:names %) #" / ")))
                      seq (apply max)))
      (claim "notes/lab-04" "7,381 rows, one quarter, 2023-03-31"
             {:rows 7381 :quarters ["2023-03-31"]}
             (select-keys (form13/summary holdings) [:rows :quarters]))
      (claim "notes/lab-05" "most companies held: Envestnet 1615, Guggenheim 1318, Nomura 1019, M&T 972"
             [1615 1318 1019 972] (mapv :companies (:top-managers lab-5-evidence)))
      (claim "notes/lab-05" "held by most managers: 22, 21, 21, 20, 20, Apple first"
             ["APPLE INC" [22 21 21 20 20]]
             [(:company (first (:top-companies lab-5-evidence)))
              (mapv :managers (:top-companies lab-5-evidence))])
      (claim "notes/lab-05" "Envestnet and Guggenheim share 1,099 companies" 1099 (:shared shared))
      (claim "notes/lab-05, .meta/README" "a 4GB Graph Analytics session was left running"
             "a session" (mapv #(select-keys % [:memory :status]) session-rows)
             ;; An empty list as user neo4j does not show the console user's sessions are gone.
             (when (seq session-rows) true))
      (claim ".meta/README" "the lectures are unticked" [] ticked-lectures)
      (claim "notes/lab-06" "Lab 6 not done: no label from a document in the database"
             #{} (set (filter label-tokens ["Document" "Chunk" "__Entity__" "__Community__"])))])))

(defn claims-check
  ([] (with-driver #(claims-check % @form13/rows)))
  ([driver holdings]
   (let [rows (claims driver holdings)
         broken (filter #(false? (:holds? %)) rows)
         undecided (filter #(nil? (:holds? %)) rows)]
     (check "Claims in the notes" (if (seq broken) :down :up)
            (format "%d claims: %d hold, %d do not, %d undecided" (count rows)
                    (count (filter :holds? rows)) (count broken) (count undecided))
            (map (fn [{:keys [source claim actual holds?]}]
                   [(str source ": " claim)
                    (case holds? true "holds" false (str "found " (pr-str actual)) "undecided")])
                 rows)
            {:claims rows}))))

;; All of it

(defn- guarded
  [check-name check-fn]
  (try (check-fn)
       (catch Exception failure
         (check check-name :unknown
                (labs/scrub (labs/secrets-of (env/load-env))
                            (str "could not be checked: " (or (ex-message failure) (class failure))))
                [] {}))))

(defn run-all
  "Every check, as a vector. The repository's first, then the database's
  on one driver. A check that throws comes back :unknown with the reason."
  []
  (let [offline [(guarded "Repository" repository)
                 (guarded "Links" links)
                 (guarded "Notes indexed" notes-indexed)
                 (guarded "Paths in prose" paths-mentioned)
                 (guarded "Secrets" secrets-kept)
                 (guarded "Lint" lint)]
        online (try
                 (with-open [driver (neo4j/connect)]
                   (let [holdings @form13/rows]
                     [(guarded "Checklist" #(checklist (labs/run-all {:driver driver})))
                      (guarded "Database inventory" #(inventory driver))
                      (guarded "Lab 4 intact" #(lab-4-intact driver holdings))
                      (guarded "Claims in the notes" #(claims-check driver holdings))]))
                 (catch Exception _
                   [(check "Database" :unknown "no driver could be opened" [] {})]))]
    (vec (concat offline online))))

(defn discrepancies
  "The checks that are :down, with their details."
  [checks]
  (mapv #(select-keys % [:name :summary :details]) (filter #(= :down (:state %)) checks)))

(defn overview
  "One row per check, for print-table."
  [checks]
  (mapv #(select-keys % [:name :state :summary]) checks))
