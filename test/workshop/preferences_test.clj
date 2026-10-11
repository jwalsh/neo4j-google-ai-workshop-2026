(ns workshop.preferences-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workshop.preferences :as preferences]))

(defn- temporary-store []
  (str (java.nio.file.Files/createTempDirectory "workshop-preferences" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest who-test
  (testing "with no header named there is one person, and no header is read"
    (is (= {:key "owner" :name nil :source :local}
           (preferences/who {} (fn [_] (throw (ex-info "a header was read" {})))))))
  (testing "a named header is believed, and its name is not the key"
    (let [person (preferences/who {"WORKSHOP_USER_HEADER" "X-Forwarded-User"} {"X-Forwarded-User" "Some-Student"})]
      (is (= "Some-Student" (:name person)))
      (is (= :proxy (:source person)))
      (is (re-matches #"[0-9a-f]{64}" (:key person)))
      (is (= (:key person)
             (:key (preferences/who {"WORKSHOP_USER_HEADER" "X-Forwarded-User"} {"X-Forwarded-User" "some-student"}))))))
  (testing "a proxy that says nothing, or something malformed, is nobody; never the owner"
    (doseq [given [nil "" "../../etc/passwd" "a b" "-leading" (apply str (repeat 40 "a"))]]
      (is (nil? (preferences/who {"WORKSHOP_USER_HEADER" "X-Forwarded-User"} {"X-Forwarded-User" given})) (pr-str given)))))

(deftest checked-test
  (is (= {:graph "mbta" :statements :open}
         (preferences/checked {"graph" "mbta" "statements" "open" "admin" "true"} ["form13" "mbta"])))
  (testing "what is not on offer becomes the default"
    (is (= {:graph nil :statements :auto}
           (preferences/checked {"graph" "no-such" "statements" "sideways"} ["form13" "mbta"])))))

(deftest store-test
  (let [store (temporary-store)]
    (testing "nothing kept reads as the defaults"
      (is (= preferences/defaults (preferences/recall store "owner"))))
    (testing "kept, read back, and the file holds no name"
      (let [person (preferences/who {"WORKSHOP_USER_HEADER" "X-Forwarded-User"} {"X-Forwarded-User" "Some-Student"})]
        (preferences/record! store (:key person) {:graph "papers" :statements :folded :name "Some-Student"})
        (is (= {:graph "papers" :statements :folded} (preferences/recall store (:key person))))
        (is (= preferences/defaults (preferences/recall store "owner")))
        (let [[kept-file & others] (.listFiles (io/file store))]
          (is (empty? others))
          (is (not (str/includes? (str/lower-case (str (.getName kept-file) (slurp kept-file))) "some-student"))))
        (testing "forgetting deletes the file"
          (is (true? (preferences/forget! store (:key person))))
          (is (empty? (.listFiles (io/file store))))
          (is (false? (preferences/forget! store (:key person)))))))
    (testing "a key that could leave the store is refused"
      (is (thrown? Exception (preferences/record! store "../owner" {})))
      (is (thrown? Exception (preferences/forget! store "a/b"))))
    (testing "a file that cannot be read is the defaults"
      (spit (io/file store "owner.edn") "{:graph")
      (is (= preferences/defaults (preferences/recall store "owner"))))))

(deftest same-origin-test
  (let [from (fn [headers] (preferences/same-origin? {} headers))]
    (is (true? (from {"Origin" "http://127.0.0.1:8377" "Host" "127.0.0.1:8377"})))
    (is (true? (from {"Referer" "http://127.0.0.1:8377/preferences" "Host" "127.0.0.1:8377"})))
    (is (false? (from {"Origin" "http://elsewhere.example" "Host" "127.0.0.1:8377"})))
    (is (false? (from {"Host" "127.0.0.1:8377"})) "neither header: no"))
  (testing "behind a proxy the public address counts"
    (is (true? (preferences/same-origin? {"WORKSHOP_PUBLIC_ORIGIN" "https://pages.example"}
                                         {"Origin" "https://pages.example" "Host" "127.0.0.1:9241"})))))

(deftest parse-form-test
  (is (= {"graph" "mbta" "statements" "always open"} (preferences/parse-form "graph=mbta&statements=always+open&=x"))))
