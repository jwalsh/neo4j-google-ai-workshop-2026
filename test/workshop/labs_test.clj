(ns workshop.labs-test
  "The parts of workshop.labs that need no database. No test here opens a
  driver or touches the network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workshop.labs :as labs]))

(deftest step-test
  (is (= {:lab 3 :title "Connect" :status :ok :evidence {:summary "fine"} :how "bolt"}
         (labs/step 3 "Connect" :ok {:summary "fine"} "bolt")))
  (testing "a status outside the four is refused, not printed"
    (is (thrown? clojure.lang.ExceptionInfo (labs/step 3 "Connect" :okay {} "bolt")))))

(deftest scrub-test
  (let [secrets {"NEO4J_PASSWORD" "correct-horse-battery"
                 "NEO4J_HOST" "abc12345.databases.neo4j.io"
                 "AURA_INSTANCEID" "abc12345"
                 "NEO4J_DATABASE" "neo4j"}]
    (testing "the longer value goes first, so a host is not left half covered"
      (is (= "Unable to connect to <NEO4J_HOST>:7687"
             (labs/scrub secrets "Unable to connect to abc12345.databases.neo4j.io:7687"))))
    (is (= "auth failed for <NEO4J_PASSWORD>"
           (labs/scrub secrets "auth failed for correct-horse-battery")))
    (testing "a short value is not a secret and is left alone"
      (is (= "database neo4j" (labs/scrub secrets "database neo4j"))))
    (is (= "" (labs/scrub secrets nil)))))

(deftest secrets-of-test
  (is (= {"NEO4J_URI" "neo4j+s://abc12345.databases.neo4j.io"
          "NEO4J_HOST" "abc12345.databases.neo4j.io"
          "LAB_PASSWORD" "something-long"}
         (labs/secrets-of {"NEO4J_URI" "neo4j+s://abc12345.databases.neo4j.io"
                           "LAB_PASSWORD" "something-long"
                           "PATH" "/usr/local/bin:/usr/bin"})))
  (is (= {} (labs/secrets-of {"PATH" "/usr/bin"}))))

(deftest databases-as-expected-test
  (is (labs/databases-as-expected?
       [{:name "system" :currentStatus "online" :default false :home false}
        {:name "neo4j" :currentStatus "online" :default true :home true}]))
  (is (not (labs/databases-as-expected?
            [{:name "neo4j" :currentStatus "offline" :default true}
             {:name "system" :currentStatus "online" :default false}])))
  (is (not (labs/databases-as-expected? [{:name "neo4j" :currentStatus "online" :default true}]))))

(deftest missing-constraints-test
  (let [company-key {:name "unique_company_id" :type "NODE_KEY" :labelsOrTypes ["Company"] :properties ["cusip"]}
        manager-key {:name "unique_manager" :type "NODE_KEY" :labelsOrTypes ["Manager"] :properties ["managerName"]}]
    (is (= [] (labs/missing-constraints [company-key manager-key])))
    (is (= ["unique_manager"] (labs/missing-constraints [company-key])))
    (testing "the right name on the wrong kind of constraint is still missing"
      (is (= ["unique_company_id"]
             (labs/missing-constraints [(assoc company-key :type "UNIQUENESS") manager-key]))))))

(deftest lab-4-decision-test
  (let [matching {:loaded? true :actual {:companies 2 :managers 2 :owns 3}}
        empty-database {:loaded? false :actual {:companies 0 :managers 0 :owns 0}}
        something-else {:loaded? false :actual {:companies 2577 :managers 28 :owns 7381}}]
    (is (= :already-loaded (labs/lab-4-decision matching false)))
    (is (= :already-loaded (labs/lab-4-decision matching true)))
    (is (= :empty-and-not-asked (labs/lab-4-decision empty-database false)))
    (is (= :load (labs/lab-4-decision empty-database true)))
    (testing "a database that holds something else is never loaded over, asked or not"
      (is (= :mismatch (labs/lab-4-decision something-else false)))
      (is (= :mismatch (labs/lab-4-decision something-else true))))))

(deftest compare-degrees-test
  (is (= {:agree? true :compared 2 :differences []}
         (labs/compare-degrees {"A" 2 "B" 1} {"B" 1 "A" 2})))
  (is (= {:agree? false :compared 3
          :differences [{:name "B" :file 1 :graph 3} {:name "C" :file nil :graph 1}]}
         (labs/compare-degrees {"A" 2 "B" 1} {"A" 2 "B" 3 "C" 1}))))

(deftest degrees-from-rows-test
  (is (= {"A" 2 "B" 1}
         (labs/degree-map [{:manager "A" :companies 2} {:manager "B" :companies 1}] :manager :companies)))
  (testing "a manager holding one company twice counts once"
    (is (= {"1" 2 "2" 1}
           (labs/company-degrees-from-file
            [{:manager "A" :cusip "1"} {:manager "A" :cusip "1"}
             {:manager "B" :cusip "1"} {:manager "A" :cusip "2"}])))))

(deftest aura-instance-request-test
  (let [{:keys [method url body headers]} (labs/aura-instance-request "project-id" "My instance")]
    (is (= "POST" method))
    (is (= "https://api.neo4j.io/v1/instances" url))
    (is (= {:version "5" :region "us-east1" :memory "2GB" :name "My instance"
            :type "professional-db" :tenant_id "project-id" :cloud_provider "gcp"}
           body))
    (testing "the token is a placeholder: the request is data, not a call"
      (is (= "Bearer <access_token>" (get headers "Authorization"))))))

(deftest graph-analytics-statements-test
  (testing "stream mode only: nothing that writes a property back to the database"
    (is (not-any? #(re-find #"(?i)\.(write|mutate)\b|\bDELETE\s+\w|\bSET\b|\bMERGE\b|\bDROP\b" %)
                  (remove #(str/includes? % "gds.session.delete") labs/graph-analytics-statements)))))

(deftest blocked-steps-test
  (let [results [(labs/lab-1) (labs/lab-2 {}) (labs/lab-6) (labs/lab-7) (labs/lab-8) (labs/lab-9)]]
    (is (= [1 2 6 7 8 9] (map :lab results)))
    (is (every? #(= :blocked (:status %)) results))
    (testing "each says what it would take"
      (is (every? #(and (string? (:how %)) (> (count (:how %)) 80)) results))
      (is (every? #(string? (get-in % [:evidence :summary])) results)))
    (testing "Lab 2 reports which credential names are set, never a value"
      (is (= {"AURA_CLIENT_ID" "set" "AURA_CLIENT_SECRET" "unset" "AURA_PROJECT_ID" "unset"}
             (get-in (labs/lab-2 {"AURA_CLIENT_ID" "not-to-be-printed"}) [:evidence :credentials])))
      (is (not (str/includes? (pr-str (labs/lab-2 {"AURA_CLIENT_ID" "not-to-be-printed"}))
                              "not-to-be-printed"))))))

(deftest overall-and-table-test
  (let [results [(labs/step 1 "Sign In" :blocked {:summary "no API"} "by hand")
                 (labs/step 3 "Connect" :ok {:summary "2 databases"} "bolt")
                 (labs/step 4 "Query" :ok {} "load")]]
    (is (= {:ok 2 :skipped 0 :blocked 1 :failed 0} (labs/overall results)))
    (is (= [{:lab 1 :step "Sign In" :status "blocked" :evidence "no API"}
            {:lab 3 :step "Connect" :status "ok" :evidence "2 databases"}
            {:lab 4 :step "Query" :status "ok" :evidence ""}]
           (labs/table-rows results)))))

(deftest as-check-test
  (let [blocked (labs/step 1 "Sign In" :blocked {:summary "no API"} "by hand")
        passed (labs/step 3 "Connect" :ok {:summary "2 databases"} "bolt")
        failed (labs/step 4 "Query" :failed {:summary "counts differ"} "verify")]
    (testing "blocked is the expected state of a lab that has no API, not a fault"
      (is (= {:state :up
              :summary "1 ok, 1 blocked"
              :details [["Lab 1, Sign In" "blocked: no API"]
                        ["Lab 3, Connect" "ok: 2 databases"]]}
             (labs/as-check [blocked passed]))))
    (is (= :down (:state (labs/as-check [blocked passed failed]))))
    (is (= "1 ok, 1 blocked, 1 failed" (:summary (labs/as-check [blocked passed failed]))))))

(deftest report-test
  (let [text (labs/report [(labs/step 1 "Sign In" :blocked {:summary "no API"} "by hand")])]
    (is (string? text))
    (is (str/includes? text "Lab 1, Sign In [blocked]"))
    (is (str/includes? text "by hand"))))

(deftest statements-read-only-test
  (testing "no statement a step sends can change the database"
    (is (not-any? #(re-find #"(?i)\b(CREATE|MERGE|DELETE|SET|REMOVE|DROP|LOAD CSV)\b|\.(write|mutate)\b|gds\.graph\.project|gds\.session\.(delete|getOrCreate)" %)
                  (vals labs/statements)))))
