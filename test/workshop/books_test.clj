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
    (is (= #{["a#0" "telemetry"] ["a#1" "span"] ["b#0" "telemetry"] ["b#1" "span"]} (set (:about projection))))
    (is (= [0 1 0 0 1] (map :level (:headings projection))))))

(deftest books-agent-test
  (let [reader (agent/load-agent "agents/books.agent.json")
        template-of (fn [tool-name] (:template (first (filter #(= tool-name (:name %)) (:tools reader)))))]
    (is (= 5 (count (:tools reader))))
    (is (= {:label "Term" :property "name"} (agent/lookup (template-of "Which Books Mention a Term") "termName")))
    (is (= {:label "Book" :property "title"} (agent/lookup (template-of "Find a Term Inside One Book") "bookTitle")))
    (is (= {:label "Term" :property "name"} (agent/lookup (template-of "Find a Term Inside One Book") "subjectTerm")))
    (is (= {:label "Book" :property "title"} (agent/lookup (template-of "Books Similar to a Book") "similarTo")))
    (is (= {:label "Shelf" :property "name"} (agent/lookup (template-of "Books on a Shelf") "shelfName")))))
