(ns workshop.env-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.diagram :as diagram]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.neo4j :as neo4j]
            [workshop.redact :as redact]
            [workshop.status :as status]))

(deftest parse-dotenv-test
  (testing "plain, quoted and commented lines"
    (is (= {"NEO4J_URI" "neo4j+s://abc.databases.neo4j.io"
            "AURA_INSTANCENAME" "My instance"
            "LAB_PASSWORD" "a$b#c"
            "EMPTY" ""}
           (env/parse-dotenv (str "# a comment\n"
                                  "NEO4J_URI=neo4j+s://abc.databases.neo4j.io\n"
                                  "\n"
                                  "AURA_INSTANCENAME=\"My instance\"\n"
                                  "LAB_PASSWORD='a$b#c'\n"
                                  "EMPTY=\n")))))
  (testing "Aura's own file leaves a value with a space unquoted"
    (is (= {"AURA_INSTANCENAME" "My instance"}
           (env/parse-dotenv "AURA_INSTANCENAME=My instance\n")))))

(deftest require-vars-test
  (is (= {"A" "1"} (env/require-vars {"A" "1" "B" "2"} ["A"])))
  (testing "the message names what is missing and no values"
    (let [failure (try (env/require-vars {"A" "secret"} ["A" "B" "C"])
                       (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= ["B" "C"] (:missing (ex-data failure))))
      (is (not (re-find #"secret" (ex-message failure)))))))

(deftest checklist-test
  (is (= [{:heading "Assignment 1" :state "TODO" :ticked 1 :total 2}
          {:heading "Assignment 2" :state "DONE" :ticked 1 :total 1}]
         (status/checklist (str "* Top\n- [ ] not under an assignment\n"
                                "*** TODO Assignment 1\n- [X] Lab 1\n- [ ] Lab 2\n"
                                "*** DONE Assignment 2\n- [x] Lab 3\n")))))

(def sample-holdings
  [{:manager "A" :cusip "1" :company "ACME INC" :quarter "2023-03-31" :value 2000.0 :shares 1}
   {:manager "A" :cusip "2" :company "BOLT CO" :quarter "2023-03-31" :value 50000.0 :shares 1000}
   {:manager "B" :cusip "1" :company "Acme, Inc." :quarter "2023-03-31" :value 100000.0 :shares 2000}])

(deftest form13-test
  (is (= {:companies 2 :managers 2 :owns 3} (form13/expected-counts sample-holdings)))
  (is (= [{:cusip "1" :names "ACME INC / Acme, Inc."}] (form13/name-variants sample-holdings)))
  (is (= ["A" "B"] (map :manager (form13/manager-degree sample-holdings))))
  (is (= [{:company "ACME INC" :cusip "1" :managers 2}] (form13/company-degree sample-holdings 1)))
  (is (= ["ACME INC"] (map :company (form13/odd-prices sample-holdings 1.0)))))

(deftest redact-test
  (let [fragments (redact/secret-fragments {"LAB_EMAIL" "user123456@example.com" "LAB_EMPLOYEE_ID" "42"})
        word (fn [line text] {:line line :text text :left 0 :top 0 :width 1 :height 1})]
    (is (= ["user123456@example.com" "user123456"] fragments))
    (testing "an email, a fragment inside a word, and a value split across a line"
      (is (= ["(someone@example.org)" "USER123456" "user12" "3456"]
             (map :text (redact/words-to-cover
                         fragments
                         [(word [1 1 1] "User:") (word [1 1 1] "(someone@example.org)")
                          (word [1 1 2] "id") (word [1 1 2] "USER123456")
                          (word [1 1 3] "user12") (word [1 1 3] "3456")
                          (word [1 1 4] "Instances")])))))))

(deftest diagram-test
  (let [mermaid (diagram/edges->mermaid
                 [{:source "Home" :sourceLabel "Segment" :relationship "PRODUCT"
                   :target "Homepod Mini" :targetLabel "Product"}])]
    (is (re-find #"flowchart LR" mermaid))
    (is (re-find #"n_home\[\"Home<br/><small>Segment</small>\"\]:::segment" mermaid))
    (is (re-find #"n_home -- PRODUCT --> n_homepod_mini" mermaid))
    (is (re-find #"classDef product" mermaid))))

(deftest statements-test
  (is (= ["RETURN 1 AS ok" "MATCH (n)\nRETURN count(n)"]
         (neo4j/statements (str "// a comment\n"
                                "RETURN 1 AS ok;\n"
                                "\n"
                                "MATCH (n)\n"
                                "RETURN count(n);\n"))))
  (testing "a semicolon inside a line does not split"
    (is (= ["RETURN 'a;b' AS text"]
           (neo4j/statements "RETURN 'a;b' AS text\n")))))

(deftest auto-commit-test
  (is (neo4j/auto-commit? "LOAD CSV FROM 'x' AS row CALL (row) { MERGE (n) } IN TRANSACTIONS OF 1000 ROWS"))
  (is (neo4j/auto-commit? "CALL { RETURN 1 } IN 4 CONCURRENT TRANSACTIONS"))
  (is (not (neo4j/auto-commit? "MATCH (n) RETURN count(n) AS transactions"))))
