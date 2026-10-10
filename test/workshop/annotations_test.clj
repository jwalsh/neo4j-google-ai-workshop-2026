(ns workshop.annotations-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workshop.annotations :as annotations])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- scratch-store
  []
  (str (Files/createTempDirectory "annotations-test" (into-array FileAttribute []))))

(deftest problems-test
  (is (empty? (annotations/problems {:book "graph-databases" :page 30 :text "a note"})))
  (is (empty? (annotations/problems {:book "graph-databases" :quote "a passage"})))
  (testing "a book id is a directory name, so it cannot climb out of the store"
    (is (seq (annotations/problems {:book "../outside" :text "a note"})))
    (is (seq (annotations/problems {:book "a/b" :text "a note"}))))
  (is (seq (annotations/problems {:book "a" :page 0 :text "a note"})))
  (is (seq (annotations/problems {:book "a" :text " "}))))

(deftest store-test
  (let [store (scratch-store)
        kept (annotations/annotate! store {:book "a" :page 12 :text "first" :concepts []})]
    (annotations/annotate! store {:book "b" :text "second" :by "an-agent"})
    (is (= #{:id :by :at :book :page :text} (set (keys kept))) "nothing empty is kept")
    (is (.exists (io/file store "a" (str (:id kept) ".edn"))))
    (is (= ["first" "second"] (sort (map :text (annotations/annotations store nil)))))
    (is (= [kept] (annotations/annotations store "a")))
    (is (= ["an-agent"] (map :by (annotations/annotations store "b"))))
    (testing "nothing unfinished is left behind"
      (is (empty? (filter #(str/ends-with? (str %) ".part") (file-seq (io/file store))))))
    (testing "a note that cannot be kept is not"
      (is (thrown? clojure.lang.ExceptionInfo (annotations/annotate! store {:book "../a" :text "no"}))))))

(deftest isolation-test
  (testing "two stores do not see each other until one is merged into the other"
    (let [owners (scratch-store)
          agents (scratch-store)
          owners-note (annotations/annotate! owners {:book "a" :text "the owner's"})
          agents-note (annotations/annotate! agents {:book "a" :page 3 :text "an agent's" :by "agent-1"})]
      (is (= [owners-note] (annotations/annotations owners nil)))
      (is (= {:copied 1 :already-here 0 :into owners} (annotations/merge! owners agents)))
      (is (= #{owners-note agents-note} (set (annotations/annotations owners nil))) "id, author and time survive")
      (is (= {:copied 0 :already-here 1 :into owners} (annotations/merge! owners agents)) "a second merge copies nothing")
      (is (= [agents-note] (annotations/annotations agents nil)) "the other store is left as it was"))))

(deftest concurrent-writers-test
  (let [store (scratch-store)
        written (doall (pmap (fn [number] (annotations/annotate! store {:book "a" :page (inc number) :text (str "note " number)}))
                             (range 200)))]
    (is (= 200 (count (distinct (map :id written)))))
    (is (= 200 (count (annotations/annotations store "a"))))))

(deftest rows-test
  (let [outlines {"a" [{:level 1 :page 5 :title "1 Graphs"} {:level 2 :page 9 :title "1.1 Paths"}
                       {:level 1 :page 20 :title "2 Agents"}]}
        note (fn [page] {:id (str "n" page) :book "a" :page page :text "t" :by "b" :at "t"})
        by-id (fn [rows] (into {} (map (juxt #(get % "id") #(get % "heading"))) rows))]
    (is (= {"n1" nil "n5" "a#0" "n8" "a#0" "n9" "a#1" "n19" "a#1" "n400" "a#2"}
           (by-id (annotations/rows (map note [1 5 8 9 19 400]) outlines))))
    (testing "no page, or a book with no outline, is a note at no heading"
      (is (= [nil nil] (map #(get % "heading")
                            (annotations/rows [{:id "x" :book "a" :text "t"} {:id "y" :book "z" :page 3 :text "t"}]
                                              outlines)))))
    (testing "concepts take the name the indexes are joined on"
      (is (= ["knowledge graph"]
             (get (first (annotations/rows [{:id "x" :book "a" :text "t" :concepts ["Knowledge graphs" "knowledge graph"]}]
                                           outlines))
                  "concepts"))))))

(deftest org-test
  (let [text (annotations/org "a" [{:id "n1" :book "a" :page 12 :text "first line\nsecond" :quote "the passage"
                                    :by "b" :at "t" :concepts ["cypher"]}])]
    (is (str/includes? text "* p. 12  first line\n"))
    (is (str/includes? text ":ID: n1\n"))
    (is (str/includes? text "#+begin_quote\nthe passage\n#+end_quote"))))
