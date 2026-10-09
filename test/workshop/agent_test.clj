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
