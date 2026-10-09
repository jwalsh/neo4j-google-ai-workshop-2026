(ns books-explore
  "The books graph, a form at a time (C-x C-e in CIDER, or C-c C-c). Nothing
  runs when the file loads: every block is a comment.

  Start the graph first: bb local-books, then bb books-load. Then
  cider-jack-in here (the root's .dir-locals.el gives the :dev alias), or
  bb repl and cider-connect.

  The question this page is for is not \"what is similar\" but \"why\": every
  number here can be opened up into the headings it was counted from."
  (:require [clojure.pprint :refer [print-table]]
            [clojure.set :as set]
            [clojure.string :as str]
            [workshop.books :as books]
            [workshop.env :as env]
            [workshop.neo4j :as neo4j]
            [workshop.telemetry :as telemetry]))

(defonce ^:private books-driver (atom nil))

(defn driver
  "A driver for the books graph, whatever .env says: books-graph.env names it."
  []
  (or @books-driver (reset! books-driver (neo4j/connect (env/load-env "books-graph.env")))))

(defn q
  ([statement] (neo4j/query (driver) statement))
  ([statement parameters] (neo4j/query (driver) statement parameters)))

(defn terms-of
  "The set of terms a book's headings use."
  [book-title]
  (set (map :term (q "MATCH (b:Book {title: $title})-[:HAS*1..5]->(:Heading)-[:ABOUT]->(t:Term) RETURN DISTINCT t.name AS term"
                     {:title book-title}))))

(defn why-similar
  "What two books have in common, as the terms both use, most used first,
  with a heading from each: the similarity score, opened up."
  [first-title second-title]
  (q (str "MATCH (a:Book {title: $first})-[:HAS*1..5]->(x:Heading)-[:ABOUT]->(t:Term)"
          "<-[:ABOUT]-(y:Heading)<-[:HAS*1..5]-(b:Book {title: $second}) "
          "WITH t, collect(DISTINCT x.title) AS inFirst, collect(DISTINCT y.title) AS inSecond "
          "RETURN t.name AS term, size(inFirst) AS first, size(inSecond) AS second, "
          "left(inFirst[0], 38) AS exampleFirst, left(inSecond[0], 38) AS exampleSecond "
          "ORDER BY first + second DESC LIMIT 15")
     {:first first-title :second second-title}))

;; What is there
(comment
  (neo4j/counts (driver))
  (print-table (q "MATCH (s:Shelf)<-[:ON]-(b:Book) RETURN s.name AS shelf, count(b) AS books ORDER BY shelf"))
  (print-table (q "MATCH (b:Book) OPTIONAL MATCH (b)-[:HAS*1..5]->(h:Heading) RETURN b.title AS book, count(h) AS headings ORDER BY headings DESC"))
  :rcf)

;; The rule that makes a term, on one heading at a time. Change a heading
;; and evaluate again: this is the whole of the \"model\".
(comment
  (books/words "8.2 Using transducers with Kubernetes: the pipelines")
  (books/phrases "3.2 Retrieval augmented generation for agents")
  (map books/singular ["approaches" "queries" "anonymous" "kubernetes"])
  :rcf)

;; Similar, and why
(comment
  (print-table
   (q (str "MATCH (a:Book)-[:HAS*1..5]->(:Heading)-[:ABOUT]->(t:Term) WITH a, collect(DISTINCT t) AS aTerms "
           "MATCH (b:Book)-[:HAS*1..5]->(:Heading)-[:ABOUT]->(u:Term) WHERE a.id < b.id "
           "WITH a, aTerms, b, collect(DISTINCT u) AS bTerms "
           "WITH a, b, size([t IN aTerms WHERE t IN bTerms]) AS shared, size(aTerms) AS sa, size(bTerms) AS sb "
           "RETURN a.title AS first, b.title AS second, shared, round(1.0 * shared / (sa + sb - shared), 3) AS jaccard "
           "ORDER BY jaccard DESC LIMIT 10")))
  (print-table (why-similar "Linked Data" "Neo4j in Action"))
  (print-table (why-similar "Cloud Observability in Action" "Software Telemetry"))
  ;; the same two as sets, in Clojure: what one has that the other has not
  (take 30 (sort (set/difference (terms-of "Neo4j in Action") (terms-of "Linked Data"))))
  (take 30 (sort (set/difference (terms-of "Linked Data") (terms-of "Neo4j in Action"))))
  :rcf)

;; Terms that cross shelves: the vocabulary this project's subjects share
(comment
  (print-table
   (q (str "MATCH (s:Shelf)<-[:ON]-(:Book)-[:HAS*1..5]->(:Heading)-[:ABOUT]->(t:Term) "
           "WITH t, collect(DISTINCT s.name) AS shelves WHERE size(shelves) >= 4 "
           "MATCH (t)<-[:ABOUT]-(h:Heading) "
           "RETURN t.name AS term, size(shelves) AS shelves, count(h) AS headings ORDER BY headings DESC LIMIT 20")))
  ;; phrases only: runs of two or three words that two books use
  (print-table
   (q (str "MATCH (t:Term)<-[:ABOUT]-(:Heading)<-[:HAS*1..5]-(b:Book) WHERE t.name CONTAINS ' ' "
           "RETURN t.name AS phrase, count(DISTINCT b) AS books ORDER BY books DESC, phrase LIMIT 20")))
  ;; where the workshop's own words sit on the shelf
  (print-table
   (q (str "UNWIND ['graph', 'agent', 'telemetry', 'embedding', 'prompt', 'transducer', 'triple'] AS word "
           "MATCH (t:Term {name: word})<-[:ABOUT]-(h:Heading)<-[:HAS*1..5]-(b:Book)-[:ON]->(s:Shelf) "
           "RETURN word, s.name AS shelf, count(DISTINCT b) AS books, count(h) AS headings ORDER BY word, headings DESC")))
  :rcf)

;; One book, opened
(comment
  (print-table (q "MATCH (b:Book {title: 'Graph Algorithms for Data Science'})-[:HAS*1..2]->(h:Heading) RETURN h.page AS page, h.level AS level, h.title AS heading ORDER BY h.position LIMIT 40"))
  (print-table (q "MATCH (b:Book)-[:HAS*1..5]->(h:Heading)-[:ABOUT]->(:Term {name: 'centrality'}) RETURN b.title AS book, h.page AS page, h.title AS heading ORDER BY book, page"))
  :rcf)

;; What it cost
(comment
  (telemetry/summary)
  (telemetry/slowest 5 "cypher")
  :rcf)
