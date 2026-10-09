(ns workshop.audit-test
  "The parts of workshop.audit that need neither git nor a database."
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.audit :as audit]))

(deftest file-links-test
  (is (= ["img/a.png" "notes/lab-04-query.org" "diagrams/b.mmd"]
         (audit/file-links (str "[[file:img/a.png]]\n"
                                "- [[file:notes/lab-04-query.org][Lab 4 - Query]]: the load\n"
                                "as [[file:diagrams/b.mmd][diagrams/b.mmd]]. And https://example.org\n")))))

(deftest path-mentions-test
  (is (= ["labs/lab-04-query.cypher" "driver/bloom.py" "notes/img"]
         (audit/path-mentions (str "Run =labs/lab-04-query.cypher=, see `driver/bloom.py`,\n"
                                   "and again =labs/lab-04-query.cypher= in =notes/img=.\n"))))
  (testing "a pattern, a directory with a slash, a command and a URL are not paths"
    (is (= [] (audit/path-mentions (str "=assignment-N-*/lab-N-*/docs/README.md= =notes/= =labs/*.cypher=\n"
                                        "=gmake data/<name>= `bb form13 verify` =https://example.org/notes/a=\n"))))))

(deftest checklist-items-test
  (is (= [{:assignment "Assignment 1" :item "Lecture - Introduction" :ticked? false}
          {:assignment "Assignment 1" :item "Lab 1 - Sign In" :ticked? true}
          {:assignment "Assignment 2" :item "Lab 4 - Query" :ticked? true}]
         (audit/checklist-items (str "* Top\n- [ ] not under an assignment\n"
                                     "*** TODO Assignment 1\n- [ ] Lecture - Introduction\n- [X] Lab 1 - Sign In\n"
                                     "*** DONE Assignment 2\n- [x] Lab 4 - Query\n"))))
  (is (= 4 (audit/lab-number "Lab 4 - Query")))
  (is (nil? (audit/lab-number "Lecture - Moving Data"))))

(deftest value-hits-test
  (let [secrets {"NEO4J_PASSWORD" "correct-horse-battery" "AURA_INSTANCEID" "abc12345" "NEO4J_USERNAME" "neo4j"}
        texts [["notes/a.org" "the address p-abc12345-0001.example"]
               ["src/b.clj" "user neo4j"]
               ["notes/c.org" "abc12345 again"]]
        hits (audit/value-hits secrets texts)]
    (is (= {"AURA_INSTANCEID" ["notes/a.org" "notes/c.org"]} hits))
    (testing "what comes back names the variable and the file, never the value"
      (is (not (re-find #"abc12345|correct-horse" (pr-str hits)))))))

(deftest claim-test
  (is (= {:source "notes/x" :claim "two" :expected 2 :actual 2 :holds? true}
         (audit/claim "notes/x" "two" 2 2)))
  (is (false? (:holds? (audit/claim "notes/x" "two" 2 3))))
  (testing "an answer that cannot settle the claim is nil, not false"
    (is (nil? (:holds? (audit/claim "notes/x" "a session" "a session" [] nil))))))

(deftest naive-load-shape-test
  (testing "one CUSIP under two names is two companies; a repeated row is one relationship"
    (is (= {:companies 3 :nodes 5 :relationships 3 :properties 17}
           (audit/naive-load-shape
            [{:manager "A" :company "ACME INC" :cusip "1" :value 1.0 :shares 1 :quarter "q"}
             {:manager "A" :company "ACME INC" :cusip "1" :value 1.0 :shares 1 :quarter "q"}
             {:manager "B" :company "Acme, Inc." :cusip "1" :value 2.0 :shares 2 :quarter "q"}
             {:manager "B" :company "BOLT CO" :cusip "2" :value 3.0 :shares 3 :quarter "q"}])))))
