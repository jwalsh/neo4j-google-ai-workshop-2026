(ns rdf-explore
  "Forms to evaluate one at a time from the editor (C-x C-e in CIDER, with
  point after the closing paren). Nothing here runs when the file is loaded:
  every block is a comment. cider-jack-in from this file starts a REPL in
  experiments/002-form13-rdf/ with the :dev alias; C-c C-k loads the
  namespace, then work down the page.

  C-c C-p instead of C-x C-e pretty-prints a result into a popup buffer;
  print-table output goes to the REPL buffer."
  (:require [clojure.pprint :refer [pprint print-table]]
            [form13.gate :as gate]
            [form13.rdf :as rdf])
  (:import (org.apache.jena.rdf.model Model)
           (org.apache.jena.riot RDFDataMgr RDFFormat)))

;; The file, before it is a graph
(comment
  (def rows (rdf/read-rows))                   ; ../../data/form13-2023-05-11.csv
  (count rows)                                 ; => 7381
  (first rows)
  (print-table [:managerName :cusip :companyName :value :shares] (take 5 rows))
  :rcf)

;; Rows to triples. One row becomes thirteen triples, most of them shared
;; with other rows.
(comment
  (def one-row-model (rdf/rows->model (take 1 rows)))
  (.size ^Model one-row-model)                 ; => 13
  (print-table (rdf/triples one-row-model 20))
  (RDFDataMgr/write *out* ^Model one-row-model RDFFormat/TURTLE_BLOCKS)

  (def model (rdf/rows->model rows))
  (.size ^Model model)                         ; => 66183
  ;; a graph is a set: adding every row again adds nothing
  (.size ^Model (rdf/rows->model (concat rows rows)))
  :rcf)

;; A few triples: a manager, a company with three names, one holding
(comment
  (print-table (rdf/triples model 5))
  (print-table (rdf/triples model 5 (rdf/manager-iri "M&T Bank Corp")))
  (print-table (rdf/triples model 10 (rdf/company-iri "037833100")))
  (print-table (rdf/triples model 10 (rdf/holding-iri "M&T Bank Corp" "037833100" "2023-03-31")))
  :rcf)

;; Turtle out and back
(comment
  (rdf/write-turtle! model)                    ; => "out/form13-2023-05-11.ttl"
  (def reread (rdf/read-turtle rdf/default-turtle-path))
  (.isIsomorphicWith ^Model model reread)      ; => true
  :rcf)

;; SPARQL. rdf/queries holds the text; rdf/ask runs one by name.
(comment
  (keys rdf/queries)
  (println (:counts rdf/queries))
  (print-table (rdf/ask model :counts))        ; 28, 2251, 7381

  (println (:companies-per-manager rdf/queries))
  (print-table (rdf/ask model :companies-per-manager))   ; ENVESTNET, 1615

  (println (:managers-per-company rdf/queries))
  (print-table (rdf/ask model :managers-per-company))    ; APPLE INC, 22

  (def name-variants (rdf/ask model :name-variants))
  (count name-variants)                        ; => 258
  (print-table (take 10 name-variants))

  (print-table (rdf/ask model :holders-of-apple))        ; 22 rows
  (print-table (rdf/ask model :companies-per-manager-bare))
  (print-table (rdf/ask model :shared-holdings))         ; about a second
  :rcf)

;; SPARQL written here. The prefixes f13:, manager:, company:, holding:,
;; xsd: and rdf: are already declared by rdf/select.
(comment
  ;; what does the vocabulary look like, judged only from the data?
  (print-table
   (rdf/select model "SELECT ?class (COUNT(*) AS ?instances)
                      WHERE { ?thing a ?class } GROUP BY ?class ORDER BY ?class"))
  (print-table
   (rdf/select model "SELECT ?predicate (COUNT(*) AS ?triples)
                      WHERE { ?subject ?predicate ?object }
                      GROUP BY ?predicate ORDER BY DESC(?triples)"))

  ;; form13/holders-of, by a fragment of any of the company's names
  (print-table
   (rdf/select model "SELECT DISTINCT ?managerName ?cusip ?value ?shares WHERE {
                        ?company f13:companyName ?name ; f13:cusip ?cusip .
                        FILTER CONTAINS(UCASE(?name), 'NVIDIA')
                        ?holding f13:company ?company ;
                                 f13:manager/f13:managerName ?managerName ;
                                 f13:value ?value ; f13:shares ?shares .
                      } ORDER BY DESC(?value)"))

  ;; form13/top-holdings
  (print-table
   (rdf/select model "SELECT ?managerName ?cusip ?value ?shares WHERE {
                        ?holding f13:manager/f13:managerName ?managerName ;
                                 f13:company/f13:cusip ?cusip ;
                                 f13:value ?value ; f13:shares ?shares .
                      } ORDER BY DESC(?value) LIMIT 10"))

  ;; a property path across the bare triple: who shares a company with M&T?
  (print-table
   (rdf/select model (str "SELECT DISTINCT ?otherManager WHERE {
                             <" (rdf/manager-iri "M&T Bank Corp") "> f13:owns/^f13:owns ?other .
                             ?other f13:managerName ?otherManager .
                           } ORDER BY ?otherManager")))
  :rcf)

;; RDF-star / RDF 1.2: the properties on the triple, no Holding node
(comment
  (println rdf/annotated-example)
  (def annotated-model (rdf/turtle-string->model rdf/annotated-example))
  (.size ^Model annotated-model)               ; => 4: the triple, a reifier, two properties
  (RDFDataMgr/write *out* ^Model annotated-model RDFFormat/TURTLE_BLOCKS)
  (println rdf/annotated-query)
  (print-table (rdf/select annotated-model rdf/annotated-query))
  :rcf)

;; The gate, from the REPL
(comment
  (print-table [:check :expected :actual :pass]
               (gate/checks rows model rdf/default-turtle-path))
  (pprint (gate/rdf-star-report))
  :rcf)
