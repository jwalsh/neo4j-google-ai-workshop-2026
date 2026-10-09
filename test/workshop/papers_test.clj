(ns workshop.papers-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [workshop.papers :as papers]))

(defn- paper
  "A paper map whose :text is a temporary file holding body."
  [arxiv-id title body]
  (let [text-file (java.io.File/createTempFile "paper" ".txt")]
    (.deleteOnExit text-file)
    (spit text-file body)
    {:arxiv arxiv-id :title title :authors ["Ada, A" "others"] :year "2024" :text (str text-file)}))

(deftest citations-test
  (let [first-paper (paper "2401.00001" "A Benchmark for Triage of Incidents in Cloud Services" "We introduce it.")
        second-paper (paper "2402.00002" "Agents Without Agents"
                            "As shown in [3] A Bench-\nmark for Triage of   Incidents in Cloud Services, 2024.")
        third-paper (paper "2403.00003" "Something Else Entirely About Routing" "See arXiv:2402.00002 for details.")]
    (testing "by title across a line break and a hyphen, and by identifier"
      (is (= [["2402.00002" "2401.00001"] ["2403.00003" "2402.00002"]]
             (papers/citations [first-paper second-paper third-paper]))))
    (testing "an author called others is not an author"
      (is (= ["Ada, A"] (:authors (papers/graph [first-paper]))))
      (is (= [["Ada, A" "2401.00001"]] (:wrote (papers/graph [first-paper])))))))
