(ns explore-logic
  "Forms to evaluate one at a time from the editor (C-x C-e in CIDER).
  Nothing here runs when the file is loaded: every block is a comment.
  cider-jack-in from this directory (the .dir-locals.el here picks the :dev
  alias), or `clojure -M:dev:repl` and cider-connect, then work down the
  page. The JVM must start in experiments/004-form13-core-logic: the default
  CSV path is relative to it.

  Timings in the margins are from one machine on 2026-10-08, warm."
  (:refer-clojure :exclude [==])
  (:require [clojure.core.logic :as logic :refer [!= == conde fresh run run*]]
            [clojure.core.logic.fd :as fd]
            [clojure.core.logic.pldb :as pldb]
            [clojure.pprint :refer [print-table]]
            [form13.logic :as form13]))

;; Load. `database` is an ordinary immutable map; def it once.
(comment
  (def rows (time (form13/read-rows)))                 ; ~45 ms
  (def database (time (form13/facts-database rows)))   ; ~45 ms
  (count rows)                                         ; => 7381
  (keys database)        ; one key per relation, named ns/relation_arity
  ;; a relation's storage: the set of facts, plus a map per indexed position
  (keys (get database "form13.logic/holds_4"))
  :rcf)

;; The counts. Left of the comma is relational; `count` and `distinct` are not.
(comment
  (count (form13/managers database))                   ; => 28
  (count (form13/cusips database))                     ; => 2251
  (count (form13/all-holdings database))               ; => 7381
  ;; the bag, undisguised: one answer per (cusip, name) fact
  (pldb/with-db database
    (count (run* [cusip] (fresh [company-name] (form13/company cusip company-name)))))  ; => 2577
  :rcf)

;; One relation, four questions. Nothing about holdso says which argument
;; is the input.
(comment
  ;; what does a manager hold
  (pldb/with-db database
    (run* [cusip] (form13/holdso form13/westwood cusip)))
  ;; who holds a CUSIP (Apple)
  (pldb/with-db database
    (run* [manager-name] (form13/holdso manager-name form13/apple-cusip)))
  ;; every pair; run 5 stops after five
  (pldb/with-db database
    (run 5 [manager-name cusip] (form13/holdso manager-name cusip)))
  ;; is it so
  (form13/holds? database form13/envestnet form13/apple-cusip)   ; => true
  (form13/holds? database form13/westwood form13/apple-cusip)    ; => false
  ;; the same through the name: three answers for two holdings, because
  ;; Credicorp was filed under two names
  (pldb/with-db database
    (run* [company-name] (form13/holds-companyo form13/westwood company-name)))
  ;; and backwards: who holds APPLE INC
  (pldb/with-db database
    (run* [manager-name] (form13/holds-companyo manager-name "APPLE INC")))
  :rcf)

;; The lab's questions
(comment
  (print-table (form13/holders-of database "APPLE INC"))          ; 22 rows
  (print-table (take 10 (form13/manager-degree database)))        ; Envestnet, 1615
  (print-table (form13/top-holdings database 10))
  (count (form13/common-holdings database form13/envestnet form13/guggenheim))  ; => 1099
  (print-table (take 10 (form13/common-holdings database form13/envestnet form13/guggenheim)))
  (count (form13/name-variants database))                         ; => 258
  (print-table (take 10 (form13/name-variants database)))
  :rcf)

;; Constraints
(comment
  ;; disequality, stated before either name is bound
  (pldb/with-db database
    (run* [first-name second-name]
      (!= first-name second-name)
      (form13/company "00206R102" first-name)
      (form13/company "00206R102" second-name)))
  ;; negation as failure: what Guggenheim holds and Envestnet does not
  (count (form13/held-by-first-only database form13/guggenheim form13/envestnet))  ; => 219
  ;; a finite domain on shares, stated before the lookup. It works, and it is
  ;; a filter: nothing is solved for.
  (print-table [:cusip :shares]
               (map #(zipmap [:cusip :shares] %)
                    (take 10 (form13/positions-with-shares-between
                              database form13/envestnet 1000000 100000000))))
  :rcf)

;; Chains of shared holdings, with a bound
(comment
  (count (form13/co-holder-pairs database))            ; => 562 ordered; ~200 ms
  ;; Westwood Global holds two companies and shares them with two managers
  (print-table (form13/fewest-hops database form13/westwood 1))
  ;; one more link reaches everyone
  (print-table (form13/fewest-hops database form13/westwood 2))   ; 27 rows; ~50 ms
  ;; the path is an answer like any other; ask for one that ends somewhere
  (pldb/with-db database
    (run 3 [path]
      (form13/chaino form13/westwood "NYL Investors LLC" 3 path)))
  ;; or one that passes through somewhere, end unknown
  (pldb/with-db database
    (run 3 [end-manager]
      (form13/chaino form13/westwood end-manager 2
                     [form13/westwood "NOMURA ASSET MANAGEMENT CO LTD" end-manager])))
  :rcf)

;; Where it stops being practical. Evaluate knowingly.
(comment
  ;; index order: the same join, positions swapped. ~820 ms against ~9 ms.
  (form13/index-order-comparison rows form13/envestnet form13/guggenheim)

  ;; every pair within two links, onceo at each link: 784 pairs (all 28 x 28,
  ;; a manager reaching itself included), about 20 SECONDS
  (time
   (count (distinct
           (pldb/with-db database
             (vec (run* [start-manager end-manager]
                    (fresh [path] (form13/chaino start-manager end-manager 2 path))))))))

  ;; without onceo a pair is answered once per shared company, and two links
  ;; multiply: 114,962,418 derivations for those pairs (worked out from sets,
  ;; never enumerated). So bound it. A million answers took ~16 s and had
  ;; reached 417 distinct pairs.
  (time
   (count (distinct
           (pldb/with-db database
             (vec (run 100000 [start-manager end-manager]
                    (fresh [between-manager]
                      (form13/co-holder-per-companyo start-manager between-manager)
                      (form13/co-holder-per-companyo between-manager end-manager))))))))
  :rcf)

;; The whole gate, as data
(comment
  (def gate-result (form13/gate))
  (print-table [:check :ok] (:checks gate-result))
  (every? :ok (:checks gate-result))                   ; => true
  (print-table [:query :answers :best-ms] (:timings gate-result))
  :rcf)
