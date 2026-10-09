(ns labs-explore
  "The labs by code, one form at a time (C-x C-e in CIDER). Nothing here
  runs when the file is loaded: every block is a comment. Start a REPL with
  `bb repl` or cider-jack-in, then work down the page.

  Each block goes the same way: run the step, look at its evidence, then
  send the statement under it by hand and look at the rows. Everything on
  this page reads. The one form that can write is marked, and is not needed
  while the database matches the file.

  notes/v2-cli-path.org is the prose; .meta/audit-2026-10-08.md is one
  evaluation of the last block, written down."
  (:require [clojure.pprint :refer [pprint print-table]]
            [user :refer [driver holdings q]]
            [workshop.audit :as audit]
            [workshop.form13 :as form13]
            [workshop.labs :as labs]
            [workshop.status :as status]))

;; A place to keep the last answers, so a second evaluation can be compared
;; with the first and another connection to this REPL can read them.
(defonce !last-run (atom nil))
(defonce !last-audit (atom nil))

;; All of it, first
(comment
  (reset! !last-run (labs/run-all {:driver (driver)}))
  (print-table [:lab :step :status :evidence] (labs/table-rows @!last-run))
  (labs/overall @!last-run)                    ; => {:ok 3, :skipped 1, :blocked 6, :failed 0}
  (println (labs/report @!last-run))
  (map (juxt :lab :title :status) @!last-run)
  ;; one step out of the run, by lab
  (filter #(= 4 (:lab %)) @!last-run)
  ;; after editing workshop.labs
  #_:clj-kondo/ignore
  (require 'workshop.labs :reload)
  :rcf)

;; Lab 1 and Lab 2: what would do them, as data
(comment
  (labs/lab-1)
  (:how (labs/lab-1))
  (labs/lab-2)
  (get-in (labs/lab-2) [:evidence :credentials])   ; names, set or unset
  (labs/aura-instance-request "<project id>" "My instance")
  labs/aura-api
  :rcf)

;; Lab 3: connect, SHOW DATABASES
(comment
  (labs/lab-3 (driver))
  (:evidence (labs/lab-3 (driver)))
  (q (:lab-3/databases labs/statements))
  labs/expected-databases
  (labs/databases-as-expected? (q (:lab-3/databases labs/statements)))
  ;; what the step leaves out of its question
  (q "SHOW DATABASES YIELD name, type, access, role, currentStatus, default, home")
  (q "CALL dbms.components() YIELD name, versions, edition")
  :rcf)

;; Lab 4: the keyed load, verified. Reads only as written.
(comment
  (labs/lab-4 (driver) (holdings))
  (:evidence (labs/lab-4 (driver) (holdings)))
  (form13/verify (driver) (holdings))
  (labs/lab-4-decision (form13/verify (driver) (holdings)) false)
  (q (:lab-4/counts labs/statements))
  (q (:lab-4/constraints labs/statements))
  (labs/missing-constraints (q (:lab-4/constraints labs/statements)))
  ;; what later labs have added beside it
  (labs/contents-beyond-lab-4 (driver))
  (print-table (q (:lab-4/other-nodes labs/statements)))
  (print-table (q (:lab-4/other-relationships labs/statements)))
  ;; what the decision would be for other databases, with no database
  (labs/lab-4-decision {:loaded? false :actual {:companies 0 :managers 0 :owns 0}} true)      ; => :load
  (labs/lab-4-decision {:loaded? false :actual {:companies 2577 :managers 28 :owns 7381}} true) ; => :mismatch
  :rcf)

;; Lab 4, the one form on this page that can write. It calls
;; workshop.form13/load!, which verifies and sends nothing when the database
;; matches the file. Leave it alone unless (labs/lab-4-decision ...) above
;; says :empty-and-not-asked and the database is meant to be empty.
(comment
  (labs/lab-4 (driver) (holdings) {:allow-load? true})
  :rcf)

;; Lab 5: degree, in Cypher, beside the file
(comment
  (labs/lab-5 (driver) (holdings))
  (def lab-5-evidence (:evidence (labs/lab-5 (driver) (holdings))))
  (print-table (:top-managers lab-5-evidence))
  (print-table (:top-companies lab-5-evidence))
  (:managers lab-5-evidence)                   ; => {:agree? true, :compared 28, :differences []}
  ;; the two sides of the comparison, separately
  (def graph-managers (q (:lab-5/manager-degree labs/statements)))
  (keys (first graph-managers))
  (print-table (take 10 (sort-by :companies > graph-managers)))
  (print-table (take 10 (form13/manager-degree (holdings))))
  (labs/compare-degrees (labs/degree-map (form13/manager-degree (holdings)) :manager :companies)
                        (labs/degree-map graph-managers :manager :companies))
  ;; the distribution, which the top of the table hides
  (def graph-companies (q (:lab-5/company-degree labs/statements)))
  (sort (frequencies (map :managers graph-companies)))   ; managers holding it => companies
  ;; Lab 9's question, asked here: companies one manager holds, by manager
  (print-table
   (q "MATCH (c:Company)<-[:OWNS]-(m:Manager)
       WITH c, collect(m.managerName) AS holders WHERE size(holders) = 1
       RETURN holders[0] AS manager, count(c) AS heldByNobodyElse
       ORDER BY heldByNobodyElse DESC LIMIT 10"))
  :rcf)

;; Lab 5: the algorithm in a Graph Analytics session. Probed, not started.
(comment
  (labs/lab-5-graph-analytics (driver))
  (q (:lab-5/sessions labs/statements))
  (filter #(re-find #"session|degree|project" (:name %)) (q (:lab-5/procedures labs/statements)))
  (q "SHOW PROCEDURES YIELD name, signature WHERE name = 'gds.graph.project' RETURN signature")
  ;; Not for evaluating without deciding to: the first of these starts a
  ;; billed session. They are data here and have never been run.
  labs/graph-analytics-statements
  :rcf)

;; Labs 6 to 9: what each needs, and what the stand-in for Lab 6 left
(comment
  (labs/lab-6 (driver))
  (get-in (labs/lab-6 (driver)) [:evidence :stand-in])
  (q (:lab-6/stand-in labs/statements))
  (print-table (q "MATCH (e:__Entity__) UNWIND labels(e) AS label
                   WITH label WHERE label <> '__Entity__'
                   RETURN label, count(*) AS entities ORDER BY entities DESC"))
  (print-table (q "MATCH (source:__Entity__)-[r]->(target:__Entity__)
                   RETURN source.id AS source, type(r) AS type, target.id AS target LIMIT 15"))
  (map (juxt :lab :title (comp :summary :evidence)) [(labs/lab-7) (labs/lab-8) (labs/lab-9)])
  (println (:how (labs/lab-9)))
  :rcf)

;; The same run in the dashboard's shape
(comment
  (labs/as-check @!last-run)
  (labs/as-check (labs/run-all {:driver (driver)}))
  ;; beside the checks that are there already
  (print-table (map #(select-keys % [:name :state :summary]) (status/all-checks (driver))))
  :rcf)

;; The audit: what is true beside what the notes say
(comment
  (reset! !last-audit (audit/run-all))
  (print-table (audit/overview @!last-audit))
  (pprint (audit/discrepancies @!last-audit))
  ;; one check at a time, and into its data
  (audit/links)
  (audit/paths-mentioned)
  (audit/secrets-kept)                         ; names and paths, never a value
  (audit/checklist (labs/run-all {:driver (driver)}))
  (audit/lab-4-intact (driver) (holdings))
  (:data (audit/inventory (driver)))
  ;; the notes' numbers, one row each
  (def claim-rows (audit/claims (driver) (holdings)))
  (print-table [:source :claim :holds?] claim-rows)
  (remove :holds? claim-rows)
  ;; any one statement behind it
  (keys audit/statements)
  (print-table (audit/ask (driver) :labels))
  (print-table (audit/ask (driver) :indexes))
  (audit/naive-load-shape (holdings))          ; => {:companies 2577, :nodes 2605, ...}
  :rcf)
