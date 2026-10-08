(ns form13.explore
  "Forms to evaluate one at a time from Emacs (C-x C-e in CIDER), top to
  bottom. Nothing here runs when the file is loaded: every block is a comment.
  cider-jack-in from this file starts `clojure -M:dev` in this experiment's
  directory (see .dir-locals.el), so the default CSV path resolves."
  (:require [asami.core :as asami]
            [clojure.pprint :refer [pprint print-table]]
            [datascript.core :as d]
            [form13.datoms :as datoms]
            [form13.gate :as gate]
            [form13.triples :as triples]))

;; Load. A DataScript database is a value: def it once and query it forever.
(comment
  (def rows (datoms/read-rows))
  (count rows)                                  ; => 7381
  (first rows)
  (def database (datoms/load-db rows))
  (datoms/counts database)                      ; => {:managers 28, :companies 2251, :holdings 7381}
  :rcf)

;; The tuples. This is the whole database: [entity attribute value].
(comment
  (take 12 (datoms/tuples database))
  (count (datoms/tuples database))              ; => 56523
  (print-table (datoms/attribute-counts database))
  ;; one attribute, from the attribute-first index
  (take 5 (datoms/tuples-of database :manager/name))
  ;; one entity: the fourth, Apple, with three names against one CUSIP
  (filter #(= 4 (first %)) (datoms/tuples database))
  ;; a holding is seven tuples; two of the values are other entity ids
  (let [holding-id (:e (first (d/datoms database :aevt :holding/manager)))]
    (filter #(= holding-id (first %)) (datoms/tuples database)))
  ;; the raw datom also carries the transaction that asserted it
  (first (d/datoms database :eavt))
  ;; the other indexes: by attribute and value, which is how a lookup ref resolves
  (first (d/datoms database :avet :company/cusip "037833100"))
  :rcf)

;; The schema, and what unique identity does on the way in
(comment
  datoms/schema
  (take 4 (datoms/identity-transaction rows))   ; maps with no ids
  (take 1 (datoms/holding-transaction rows))    ; refs as [attribute value] lookups
  ;; upsert by hand: same CUSIP, new name, same entity, one more name
  (-> database
      (d/db-with [{:company/cusip "037833100" :company/name "APPLE COMPUTER INC"}])
      (d/pull '[*] [:company/cusip "037833100"]))
  ;; and `database` has not changed
  (d/pull database '[*] [:company/cusip "037833100"])
  :rcf)

;; Pull: a tree out of the tuples, by pattern, no query
(comment
  (d/pull database '[*] [:manager/name "BANK OZK"])
  (d/pull database '[*] [:company/cusip "037833100"])
  (pprint (datoms/pull-manager database "BANK OZK" 3))
  ;; the entity API: a lazy map over the same tuples, navigable both ways
  (def apple (d/entity database [:company/cusip "037833100"]))
  (:company/name apple)
  (count (:holding/_company apple))             ; => 22
  (->> (:holding/_company apple) (map (comp :manager/name :holding/manager)) sort)
  :rcf)

;; Datalog, written out here before the named versions
(comment
  (d/q '[:find ?manager-name
         :where [?manager :manager/name ?manager-name]]
       database)
  ;; a join is a logic variable used twice
  (d/q '[:find ?manager-name ?value
         :in $ ?cusip
         :where
         [?company :company/cusip ?cusip]
         [?holding :holding/company ?company]
         [?holding :holding/manager ?manager]
         [?holding :holding/value ?value]
         [?manager :manager/name ?manager-name]]
       database "037833100")
  ;; the attribute can be a variable too: which attributes does anything have
  (d/q '[:find ?attribute (count ?entity)
         :where [?entity ?attribute]]
       database)
  :rcf)

;; The gate's questions
(comment
  (print-table (datoms/companies-per-manager database 5))
  (print-table (datoms/managers-per-company database 5))
  (print-table (datoms/holders-of database "037833100"))
  (count (datoms/holders-of database "037833100"))    ; => 22
  :rcf)

;; One CUSIP, several names
(comment
  (datoms/name-variant-count database)                ; => 258
  (print-table (take 10 (datoms/name-variants database)))
  (print-table (datoms/names-by-filer database "037833100"))
  ;; what cardinality one on :company/name does to the same data
  (def lossy-database (datoms/load-db rows datoms/lossy-schema))
  (datoms/name-variant-count lossy-database)          ; => 0
  (d/pull lossy-database '[*] [:company/cusip "037833100"])  ; whichever name came last
  :rcf)

;; A rule
(comment
  datoms/rules
  (print-table (take 10 (datoms/shared-company-pairs database 1)))
  (count (datoms/shared-company-pairs database 1))    ; => 281
  (print-table (datoms/shared-company-pairs database 500))
  ;; the rule on its own: the property graph's edge, recovered
  (d/q '[:find (count ?company) .
         :in $ % ?manager-name
         :where
         [?manager :manager/name ?manager-name]
         (holds ?manager ?company)]
       database datoms/rules "ENVESTNET ASSET MANAGEMENT INC")   ; => 1615
  (time (count (datoms/shared-company-pairs database 1)))
  (time (count (datoms/shared-company-pairs-inline database 1)))
  :rcf)

;; The comparison store: Asami. No schema, the loader names the nodes.
(comment
  (take 9 (triples/rows->triples rows))
  (def triple-database (triples/load-db rows))
  (take 9 (triples/tuples triple-database))
  (triples/counts triple-database)
  (asami/entity triple-database :company/cusip-037833100)
  (print-table (triples/companies-per-manager triple-database 5))
  (print-table (triples/managers-per-company triple-database 5))
  (triples/name-variant-count triple-database)        ; => 258
  (print-table (take 10 (triples/shared-company-pairs triple-database 1)))
  ;; three that are wrong or do not return, and say nothing: see the README log
  (triples/count-with-bound-attribute triple-database :manager/name)  ; => 9660, not 28
  (triples/total-value-as-summed triple-database)     ; the distinct values only
  (asami/q '[:find (count-distinct ?company)
             :where
             [?company :company/name ?one-name]
             [?company :company/name ?another-name]
             [(not= ?one-name ?another-name)]]
           triple-database)                           ; => ((800)), not 258
  :rcf)

;; The whole gate, from the REPL
(comment
  (def gate-result (gate/run datoms/default-csv-path))
  (gate/report gate-result)
  (:failures gate-result)                             ; => ()
  :rcf)
