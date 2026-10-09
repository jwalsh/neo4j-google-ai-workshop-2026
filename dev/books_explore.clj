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

(defn concepts-of
  "The set of concepts a book's own index lists."
  [book-title]
  (set (map :concept (q "MATCH (b:Book {title: $title})-[:INDEXES]->(c:Concept) RETURN c.name AS concept"
                        {:title book-title}))))

(defn indexed-by-both
  "The concepts two books' indexes both list, with the pages each gives,
  rarest on the shelf first: a concept ten books index says less about two
  of them than one only they do."
  [first-title second-title]
  (q (str "MATCH (a:Book {title: $first})-[x:INDEXES]->(c:Concept)<-[y:INDEXES]-(b:Book {title: $second}) "
          "RETURN c.name AS concept, c.books AS books, left(coalesce(x.pages, ''), 22) AS first, "
          "left(coalesce(y.pages, ''), 22) AS second ORDER BY c.books, concept LIMIT 25")
     {:first first-title :second second-title}))

(defn where-indexed
  "Every book that indexes a concept, with its shelf and pages."
  [concept]
  (q (str "MATCH (s:Shelf)<-[:ON]-(b:Book)-[i:INDEXES]->(c:Concept {name: $concept}) "
          "RETURN s.name AS shelf, b.title AS book, left(coalesce(i.pages, ''), 40) AS pages ORDER BY shelf, book")
     {:concept concept}))

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
   ;; shared terms only: a term one book alone uses says nothing about a pair
   (q (str "MATCH (a:Book)-[:HAS*1..5]->(:Heading)-[:ABOUT]->(t:Term) WHERE t.books >= 2 "
           "WITH a, collect(DISTINCT t) AS aTerms "
           "MATCH (b:Book)-[:HAS*1..5]->(:Heading)-[:ABOUT]->(u:Term) WHERE a.id < b.id AND u.books >= 2 "
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

;; Concepts: what each author put in the index (bb books index). A term is
;; a word from a chapter title; a concept is a thing somebody thought a
;; reader would look up.
(comment
  ;; the rule that joins two books' entries, on one entry at a time
  (map books/concept-name ["RAG (retrieval-augmented generation)" "Retrieval-augmented generation (RAG)"
                           "Vector databases"])
  (print-table (q "MATCH (c:Concept) RETURN c.books AS books, count(*) AS concepts ORDER BY books DESC"))
  ;; the workshop's subjects, by how many indexes list each
  (print-table
   (q (str "UNWIND ['knowledge graph','graph database','neo4j','cypher','retrieval-augmented generation',"
           "'embedding','vector database','cosine similarity','agent','model context protocol','gemini',"
           "'opentelemetry','langchain','macro','repl'] AS concept "
           "OPTIONAL MATCH (s:Shelf)<-[:ON]-(b:Book)-[:INDEXES]->(:Concept {name: concept}) "
           "RETURN concept, count(DISTINCT b) AS books, collect(DISTINCT s.name) AS shelves ORDER BY books DESC")))
  (print-table (where-indexed "knowledge graph"))
  (print-table (where-indexed "model context protocol"))
  ;; two books, by what both index
  (print-table (indexed-by-both "AI Engineering" "Hands-On Large Language Models"))
  (print-table (indexed-by-both "Neo4j in Action" "Graph Algorithms for Data Science"))
  ;; concepts on four shelves or more: what the whole workshop stands on
  (print-table
   (q (str "MATCH (s:Shelf)<-[:ON]-(:Book)-[:INDEXES]->(c:Concept) WITH c, collect(DISTINCT s.name) AS shelves "
           "WHERE size(shelves) >= 4 RETURN c.name AS concept, c.books AS books, shelves ORDER BY books DESC")))
  ;; what a book indexes that its headings never say: the index's own gain
  (take 40 (sort (set/difference (concepts-of "Graph Algorithms for Data Science")
                                 (terms-of "Graph Algorithms for Data Science"))))
  ;; a concept's neighbours: what the books that index it also index, and no others do
  (print-table
   (q (str "MATCH (:Concept {name: 'knowledge graph'})<-[:INDEXES]-(b:Book) WITH collect(b) AS holders "
           "MATCH (c:Concept) WHERE c.books >= 2 AND c.name <> 'knowledge graph' "
           "AND all(b IN [(x:Book)-[:INDEXES]->(c) | x] WHERE b IN holders) "
           "RETURN c.name AS concept, c.books AS books ORDER BY books DESC, concept LIMIT 20")))
  :rcf)

;; What it cost
(comment
  (telemetry/summary)
  (telemetry/slowest 5 "cypher")
  :rcf)
