(ns workshop.ports-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.ports :as ports]))

(deftest block-ports-test
  (is (= {"dashboard" 11020 "filings" 11021 "papers" 11022 "books" 11023 "transit" 11024} (ports/block-ports 11020)))
  (is (= {"dashboard" 8377 "filings" 7688 "papers" 7689 "books" 7690 "transit" 7691} (ports/main-ports))))

(deftest preferred-slot-test
  (testing "a branch's slot is the same every time, and inside the range"
    (is (= (ports/preferred-slot "proto/ui") (ports/preferred-slot "proto/ui")))
    (is (every? #(< -1 % ports/block-count)
                (map ports/preferred-slot ["main" "proto/ui" "proto/build" "feat/a-long-branch-name" ""]))))
  (testing "the six branches of 2026-10-10 land on six slots"
    (is (= 6 (count (distinct (map ports/preferred-slot
                                   ["proto/build" "proto/libs" "proto/ui" "proto/secrets"
                                    "proto/deploy-gke" "proto/deploy-clouds"])))))))

(deftest free-block-test
  (is (= 11000 (ports/free-block #{})))
  (is (= 11070 (ports/free-block #{} 7)))
  (testing "one port held anywhere in a slot takes the slot, and the next is offered"
    (is (= 11010 (ports/free-block #{11009})))
    (is (= 11090 (ports/free-block #{11070 11085} 7))))
  (testing "looking wraps round from the last slot to the first"
    (is (= 11000 (ports/free-block #{11995} 99))))
  (testing "a port outside every slot takes none"
    (is (= 11000 (ports/free-block #{7690 8377 10999 12000}))))
  (is (nil? (ports/free-block (set (range 11000 12000)) 42))))

(deftest names-test
  (is (= "macbookpro-workshop-books" (ports/service-name "macbookpro" "main" "books")))
  (is (= "macbookpro-workshop-books-proto-ui" (ports/service-name "MacBookPro" "proto/ui" "books")))
  (is (re-find #"standing service$" (ports/mandate "the books graph" "macbookpro" "main")))
  (is (re-find #"until proto/ui merges$" (ports/mandate "the books graph" "macbookpro" "proto/ui"))))
