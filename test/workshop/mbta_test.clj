(ns workshop.mbta-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [workshop.agent :as agent]
            [workshop.mbta :as mbta]))

(def network
  "Two routes that meet at one station. Each station has a platform per
  direction, as the feed has."
  {:lines [{:id "line-R" :name "Red Line" :color "#DA291C"} {:id "line-B" :name "Blue Line" :color "#003DA5"}]
   :routes [{:id "R" :name "Red Line" :kind "subway" :color "#DA291C" :line "line-R" :order 1}
            {:id "B" :name "Blue Line" :kind "subway" :color "#003DA5" :line "line-B" :order 2}]
   :stations [{:id "s1" :name "One"} {:id "s2" :name "Two"} {:id "s3" :name "Three"}]
   :stops [{:id "1n" :parent "s1"} {:id "1s" :parent "s1"} {:id "2n" :parent "s2"} {:id "2s" :parent "s2"}
           {:id "2e" :parent "s2"} {:id "3e" :parent "s3"}]
   :calls [["R" 0 "r-south" 1 "1s"] ["R" 0 "r-south" 2 "2s"] ["R" 1 "r-north" 1 "2n"] ["R" 1 "r-north" 2 "1n"]
           ["B" 0 "b-main" 10 "2e"] ["B" 0 "b-main" 20 "3e"]]})

(def branching
  "One route, one direction, two branches after a shared first stop: two
  trips whose sequence numbers overlap, as the Red Line's do."
  {:lines [{:id "line-R" :name "Red Line"}]
   :routes [{:id "R" :name "Red Line" :kind "subway" :color "#DA291C" :line "line-R"}]
   :stops (for [id ["t" "a1" "a2" "b1"]] {:id id :parent (str "S" id)})
   :calls [["R" 0 "to-a" 1 "t"] ["R" 0 "to-a" 2 "a1"] ["R" 0 "to-a" 3 "a2"]
           ["R" 0 "to-b" 1 "t"] ["R" 0 "to-b" 2 "b1"]]})

(deftest graph-test
  (let [projection (mbta/graph network)]
    (testing "NEXT is between stops, in the order of each trip"
      (is (= #{["1s" "2s" "R" 0] ["2n" "1n" "R" 1] ["2e" "3e" "B" 0]} (set (:next projection)))))
    (testing "CONNECTS is the same edges between the stops' stations, with the line's name"
      (is (= #{["s1" "s2" "R" "Red Line" "subway" "#DA291C" "Red Line"]
               ["s2" "s1" "R" "Red Line" "subway" "#DA291C" "Red Line"]
               ["s2" "s3" "B" "Blue Line" "subway" "#003DA5" "Blue Line"]}
             (set (:connects projection)))))
    (testing "a station's place on a route is its place in direction 0"
      (is (= #{["R" "s1" 0] ["R" "s2" 1] ["B" "s2" 0] ["B" "s3" 1]} (set (:serves projection)))))))

(deftest branches-test
  (let [projection (mbta/graph branching)]
    (testing "stops follow one another within a trip, never from one branch to the other"
      (is (= #{["t" "a1" "R" 0] ["a1" "a2" "R" 0] ["t" "b1" "R" 0]} (set (:next projection))))
      (is (= #{["St" "Sa1"] ["Sa1" "Sa2"] ["St" "Sb1"]} (set (map #(subvec % 0 2) (:connects projection))))))
    (testing "the longer branch gives the order, the other's stations follow"
      (is (= [["R" "St" 0] ["R" "Sa1" 1] ["R" "Sa2" 2] ["R" "Sb1" 3]] (sort-by peek (:serves projection)))))))

(deftest mbta-agent-test
  (let [guide (agent/load-agent "agents/mbta.agent.json")
        template-of (fn [tool-name] (:template (first (filter #(= tool-name (:name %)) (:tools guide)))))]
    (is (= 6 (count (:tools guide))))
    (testing "every string parameter is compared with a property the graph can resolve"
      (is (= {:label "Station" :property "name"}
             (agent/lookup (template-of "Subway Journey Between Two Stations") "fromStation")
             (agent/lookup (template-of "Subway Journey Between Two Stations") "toStation")
             (agent/lookup (template-of "Which Routes Serve a Station") "stationName")
             (agent/lookup (template-of "Stations Near a Station") "nearStation")
             (agent/lookup (template-of "Platforms in a Station") "platformsOf")))
      (is (= {:label "Route" :property "name"}
             (agent/lookup (template-of "Stations on a Route in Order") "routeName")))
      (is (= {:label "Station" :property "municipality"}
             (agent/lookup (template-of "Stations in a Municipality") "municipalityName"))))))

(deftest registry-test
  (testing "every source names files that exist and has what the overview page draws"
    (doseq [{:keys [id title start shape glance example] agent-file :agent env-file :env}
            (edn/read-string (slurp agent/registry-path))]
      (is (.exists (java.io.File. ^String agent-file)) id)
      (is (.exists (java.io.File. ^String env-file)) id)
      (is (every? string? [title start shape example (:title glance) (:statement glance)]) id))))
