(ns workshop.env-test
  (:require [clojure.test :refer [deftest is testing]]
            [workshop.env :as env]
            [workshop.neo4j :as neo4j]))

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
