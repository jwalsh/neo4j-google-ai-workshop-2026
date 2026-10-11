(ns workshop.otlp-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.otlp :as otlp]))

(deftest endpoint-test
  (is (nil? (otlp/endpoint {})))
  (is (= "http://collector:4318/v1/traces"
         (otlp/endpoint {"OTEL_EXPORTER_OTLP_ENDPOINT" "http://collector:4318/"})))
  (testing "a traces endpoint is used as given, and wins"
    (is (= "http://other/traces"
           (otlp/endpoint {"OTEL_EXPORTER_OTLP_ENDPOINT" "http://collector:4318"
                           "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" "http://other/traces"})))))

(deftest resource-attributes-test
  (is (= {"service.name" "workshop" "deployment.environment.name" "dev"}
         (otlp/resource-attributes {})))
  (is (= {"service.name" "dashboard" "deployment.environment.name" "staging" "team" "graphs"}
         (otlp/resource-attributes
          {"OTEL_SERVICE_NAME" "dashboard"
           "OTEL_RESOURCE_ATTRIBUTES" "deployment.environment.name=staging, team=graphs,broken"}))))

(deftest payload-test
  (let [root {:trace-id "aaaa1111" :span-id "root0001" :parent-id nil :name "GET /ask"
              :start-ms 1000 :duration-ms 12.5 :status :ok :attributes {:agent-id "mbta"}}
        child {:trace-id "aaaa1111" :span-id "chld0001" :parent-id "root0001" :name "cypher"
               :start-ms 1002 :duration-ms 3.0 :status :error
               :attributes {:rows 2 :statement "MATCH (n)" :error "refused" :parameters {"secret" 1}}}
        body (otlp/payload {"service.name" "workshop"} [child root])
        [sent-child sent-root] (get-in body [:resourceSpans 0 :scopeSpans 0 :spans])]
    (is (= [{:key "service.name" :value {:stringValue "workshop"}}]
           (get-in body [:resourceSpans 0 :resource :attributes])))
    (testing "ids have the lengths OTLP wants, and a child names its parent"
      (is (re-matches #"[0-9a-f]{32}" (:traceId sent-root)))
      (is (re-matches #"[0-9a-f]{16}" (:spanId sent-root)))
      (is (= (:traceId sent-root) (:traceId sent-child)))
      (is (= (:spanId sent-root) (:parentSpanId sent-child)))
      (is (not (contains? sent-root :parentSpanId))))
    (testing "times are nanoseconds, as text"
      (is (= "1000000000" (:startTimeUnixNano sent-root)))
      (is (= "1012500000" (:endTimeUnixNano sent-root))))
    (testing "a request's span is a server span; the rest are internal"
      (is (= 2 (:kind sent-root)))
      (is (= 1 (:kind sent-child))))
    (testing "status, and attributes by type"
      (is (= {:code 1} (:status sent-root)))
      (is (= {:code 2 :message "refused"} (:status sent-child)))
      (is (some #{{:key "rows" :value {:intValue "2"}}} (:attributes sent-child))))
    (testing "parameter values are never sent"
      (is (not-any? #(= "parameters" (:key %)) (:attributes sent-child))))))
