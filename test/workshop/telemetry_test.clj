(ns workshop.telemetry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workshop.telemetry :as telemetry]))

(use-fixtures :each (fn [run-test] (telemetry/clear!) (run-test) (telemetry/clear!)))

(deftest nesting-test
  (is (= :done (telemetry/span "outer" {:kind "test"}
                 (telemetry/span "inner" {} (telemetry/annotate! {:rows 3}))
                 @(future (telemetry/span "in a future" {} nil))
                 :done)))
  (let [[trace :as all-traces] (telemetry/traces)
        by-name (into {} (map (juxt :name identity)) (:spans trace))]
    (is (= 1 (count all-traces)))
    (is (= "outer" (:name trace)))
    (is (= ["outer" "inner" "in a future"] (map :name (:spans trace))))
    (is (= [0 1 1] (map :depth (:spans trace))))
    (testing "a child, on this thread or in a future, names its parent"
      (is (= (:span-id (by-name "outer"))
             (:parent-id (by-name "inner"))
             (:parent-id (by-name "in a future")))))
    (is (= {:rows 3} (:attributes (by-name "inner"))))
    (is (= {:kind "test"} (:attributes (by-name "outer"))))))

(deftest error-test
  (is (thrown? IllegalStateException
               (telemetry/span "fails" {} (throw (IllegalStateException. "no server")))))
  (let [[failed] (telemetry/spans)]
    (is (= :error (:status failed)))
    (is (= "no server" (:error (:attributes failed))))
    (is (= 1 (:errors (first (telemetry/traces)))))))

(deftest buffer-test
  (dotimes [_ (+ telemetry/capacity 5)] (telemetry/span "tick" {} nil))
  (is (= telemetry/capacity (count (telemetry/spans))))
  (is (= [{:name "tick" :count telemetry/capacity :errors 0}]
         (map #(select-keys % [:name :count :errors]) (telemetry/summary)))))

(deftest first-line-test
  (is (= "MATCH (n)" (telemetry/first-line "\n  MATCH (n)\nRETURN n")))
  (is (= 10 (count (telemetry/first-line "MATCH (n) RETURN n" 10)))))
