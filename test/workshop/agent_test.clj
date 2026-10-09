(ns workshop.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.agent :as agent]))

(def analyst (agent/load-agent))

(deftest load-agent-test
  (is (= 4 (count (:tools analyst))))
  (testing "the free Text2Cypher tool is left out"
    (is (not-any? #(re-find #"Natural Language" (:name %)) (:tools analyst)))))

(deftest choice-schema-test
  (let [schema (agent/choice-schema analyst)]
    (is (= 5 (count (get-in schema ["properties" "tool" "enum"]))))
    (is (= agent/no-tool (last (get-in schema ["properties" "tool" "enum"]))))
    (is (= {"type" "integer"} (get-in schema ["properties" "arguments" "properties" "maxOwnersCount"])))))

(deftest grounded-test
  (is (= {:arguments {"managerName" "Guggenheim" "limit" 5}
          :dropped {"maxTotalOwners" 10 "companyName" "John Smith"}}
         (select-keys (agent/grounded "Top 5 companies of guggenheim?"
                                      {:arguments {"managerName" "Guggenheim" "limit" 5
                                                   "maxTotalOwners" 10 "companyName" "John Smith"}})
                      [:arguments :dropped]))))

(deftest validate-test
  (testing "defaults are filled in"
    (is (= {"managerName" "X" "limit" 25}
           (:parameters (agent/validate analyst {:tool "Get Companies by Manager"
                                                 :arguments {"managerName" "X"}})))))
  (is (= "missing managerName"
         (:problem (agent/validate analyst {:tool "Get Companies by Manager" :arguments {}}))))
  (is (:problem (agent/validate analyst {:tool agent/no-tool :arguments {}})))
  (is (:problem (agent/validate analyst {:tool "Drop Database" :arguments {}}))))

(deftest lookup-test
  (let [template-of (fn [tool-name] (:template (first (filter #(= tool-name (:name %)) (:tools analyst)))))]
    (is (= {:label "Manager" :property "managerName"}
           (agent/lookup (template-of "Get Companies by Manager") "managerName")))
    (is (= {:label "Company" :property "companyName"}
           (agent/lookup (template-of "Get Managers Owning Company") "companyName")))
    (testing "the inline-map form"
      (is (= {:label "Manager" :property "managerName"}
             (agent/lookup (template-of "Find Manager's Uncommon Companies") "managerName"))))
    (is (nil? (agent/lookup (template-of "Get Companies by Manager") "limit")))))

(deftest registry-test
  (let [entries (agent/registry)]
    (is (= ["form13" "papers"] (map :id entries)))
    (testing "every agent file and env file named is there, and loads"
      (doseq [{:keys [agent env]} entries]
        (is (.exists (java.io.File. ^String env)) env)
        (is (seq (:tools (agent/load-agent agent))) agent)))
    (is (= "form13" (:id (agent/entry nil))))
    (is (= "papers" (:id (agent/entry "papers"))))
    (is (thrown? clojure.lang.ExceptionInfo (agent/entry "nope")))))

(deftest second-agent-test
  (let [librarian (agent/load-agent "agents/papers.agent.json")
        template-of (fn [tool-name] (:template (first (filter #(= tool-name (:name %)) (:tools librarian)))))]
    (is (= 6 (count (:tools librarian))))
    (testing "every string parameter can be resolved against the graph"
      (is (= {:label "Paper" :property "title"} (agent/lookup (template-of "Who Cites This Paper") "paperTitle")))
      (is (= {:label "Category" :property "name"} (agent/lookup (template-of "Papers Filed Under a Category") "categoryCode")))
      (is (= {:label "Author" :property "name"} (agent/lookup (template-of "Papers by an Author") "authorName")))
      (is (= {:label "Paper" :property "verdict"} (agent/lookup (template-of "Papers With a Verification Verdict") "verdict"))))))
