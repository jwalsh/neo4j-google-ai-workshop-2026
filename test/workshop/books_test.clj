(ns workshop.books-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.agent :as agent]
            [workshop.books :as books]))

(deftest words-test
  (is (= ["transducer" "kubernetes" "pipeline"]
         (books/words "8.2 Using transducers with Kubernetes: the pipelines")))
  (testing "front matter and stop words leave nothing"
    (is (empty? (books/words "about this book")))
    (is (empty? (books/words "Summary")))))

(deftest singular-test
  (is (= ["approach" "query" "macro" "anonymous" "kubernetes" "class" "analysis" "box"]
         (map books/singular ["approaches" "queries" "macros" "anonymous" "kubernetes" "class" "analysis" "boxes"]))))

(deftest phrases-test
  (is (= ["retrieval augmented" "augmented generation" "retrieval augmented generation"]
         (books/phrases "3.2 Retrieval augmented generation")))
  (testing "a stop word between two words breaks the run"
    (is (empty? (books/phrases "Graphs and the databases")))))

(deftest graph-test
  (let [shelf [{:id "a" :title "A" :shelf "x" :why "w"} {:id "b" :title "B" :shelf "y" :why "w"}]
        outlines {"a" [{:level 1 :page 1 :title "1 Telemetry pipelines"}
                       {:level 2 :page 3 :title "1.1 Emitting spans"}
                       {:level 1 :page 9 :title "2 Dashboards"}]
                  "b" [{:level 1 :page 1 :title "Telemetry for agents"}
                       {:level 2 :page 2 :title "Spans and traces"}]}
        projection (books/graph shelf outlines 2)]
    (is (= ["span" "telemetry"] (:terms projection)))
    (is (= [["a#0" "a#1"] ["b#0" "b#1"]] (:nesting projection)))
    (testing "a word one book alone uses is kept, with how many books use each"
      (is (= {"telemetry" 2 "span" 2 "pipeline" 1 "emitting" 1 "dashboard" 1 "agent" 1 "trace" 1}
             (:term-books projection)))
      (is (contains? (set (:about projection)) ["a#2" "dashboard"])))
    (testing "a run of words one book uses once is not"
      (is (not (contains? (:term-books projection) "telemetry pipeline"))))
    (is (= #{["a#0" "telemetry"] ["a#1" "span"] ["b#0" "telemetry"] ["b#1" "span"]}
           (set (filter (comp #{"telemetry" "span"} second) (:about projection)))))
    (is (= [0 1 0 0 1] (map :level (:headings projection))))))

(deftest concept-name-test
  (is (= ["retrieval-augmented generation" "retrieval-augmented generation" "vector database" "cypher" "clojure.core namespace"]
         (map books/concept-name ["RAG (retrieval-augmented generation)" "Retrieval-augmented generation (RAG)"
                                  "Vector databases" "Cypher" "clojure.core namespace"])))
  (testing "an expansion cut off by the column keeps the part before it"
    (is (= "rlhf" (books/concept-name "RLHF (reinforcement learning from human")))))

(deftest concepts-test
  (let [shelf [{:id "a" :title "A" :shelf "x" :why "w"} {:id "b" :title "B" :shelf "y" :why "w"}]
        projection (books/graph shelf {"a" [] "b" []} 2
                                {"a" [{:name "Knowledge graphs" :pages "12, 40-44"} {:name "Cypher" :pages "9"}]
                                 "b" [{:name "knowledge graph" :pages nil}]})]
    (is (= {"knowledge graph" 2 "cypher" 1} (:concept-books projection)))
    (is (= #{["a" "knowledge graph" "12, 40-44"] ["a" "cypher" "9"] ["b" "knowledge graph" nil]}
           (set (:indexes projection))))))

(deftest books-agent-test
  (let [reader (agent/load-agent "agents/books.agent.json")
        template-of (fn [tool-name] (:template (first (filter #(= tool-name (:name %)) (:tools reader)))))]
    (is (= 6 (count (:tools reader))))
    (is (= {:label "Concept" :property "name"}
           (agent/lookup (template-of "Look Up a Concept in the Books' Indexes") "conceptName")))
    (is (= {:label "Term" :property "name"} (agent/lookup (template-of "Which Books Mention a Term") "termName")))
    (is (= {:label "Book" :property "title"} (agent/lookup (template-of "Find a Term Inside One Book") "bookTitle")))
    (is (= {:label "Term" :property "name"} (agent/lookup (template-of "Find a Term Inside One Book") "subjectTerm")))
    (is (= {:label "Book" :property "title"} (agent/lookup (template-of "Books Similar to a Book") "similarTo")))
    (is (= {:label "Shelf" :property "name"} (agent/lookup (template-of "Books on a Shelf") "shelfName")))))
