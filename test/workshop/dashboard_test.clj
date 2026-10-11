(ns workshop.dashboard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workshop.agent :as agent]
            [workshop.dashboard :as dashboard]))

(deftest format-statement-test
  (testing "a clause to a line"
    (is (= "MATCH (a:Station)\nWHERE a.name = $name\nRETURN a.name AS name\nORDER BY name\nLIMIT 5"
           (dashboard/format-statement
            "MATCH (a:Station) WHERE a.name = $name RETURN a.name AS name ORDER BY name LIMIT 5"))))
  (testing "a subquery's body is indented under its CALL"
    (is (= "MATCH (o:Place)\nCALL {\n  WITH o\n  MATCH (n:Station)\n  WHERE n.open\n  RETURN collect(n) AS near\n}\nRETURN near"
           (dashboard/format-statement
            "MATCH (o:Place) CALL { WITH o MATCH (n:Station) WHERE n.open RETURN collect(n) AS near } RETURN near"))))
  (testing "nothing inside a list, a map, a call's arguments or a string is broken"
    (is (= "WITH [x IN xs WHERE x.walk < 3 | x.station] AS near, {a: 1, b: 2} AS m\nRETURN all(leg IN legs WHERE leg.kind <> 'a WITH b'), near, m"
           (dashboard/format-statement
            "WITH [x IN xs WHERE x.walk < 3 | x.station] AS near, {a: 1, b: 2} AS m RETURN all(leg IN legs WHERE leg.kind <> 'a WITH b'), near, m"))))
  (testing "STARTS WITH is an operator, not a clause"
    (is (= "MATCH (n)\nWHERE n.name STARTS WITH 'Park'\n  AND n.code ENDS WITH 'x'\nRETURN n"
           (dashboard/format-statement
            "MATCH (n) WHERE n.name STARTS WITH 'Park' AND n.code ENDS WITH 'x' RETURN n"))))
  (testing "OPTIONAL MATCH is one clause, and a word that only begins like one is not"
    (is (= "MATCH (a)\nOPTIONAL MATCH (a)-[:R]->(b)\nRETURN a, b AS MATCHED, a.SETTING"
           (dashboard/format-statement "MATCH (a)   OPTIONAL MATCH (a)-[:R]->(b) RETURN a, b AS MATCHED, a.SETTING"))))
  (testing "in a wide clause names passed on stay together; each named value gets a line"
    (is (= "WITH boardAt, alightAt,\n     size(relationships(journey)) AS stops,\n     walkToBoard, walkFromAlight\nRETURN\n  CASE WHEN stops > 0 AND boardAt.open THEN 'x, y' ELSE alightAt.name END AS start,\n  stops AS stops\nORDER BY stops"
           (dashboard/format-statement
            (str "WITH boardAt, alightAt, size(relationships(journey)) AS stops, walkToBoard, walkFromAlight "
                 "RETURN CASE WHEN stops > 0 AND boardAt.open THEN 'x, y' ELSE alightAt.name END AS start, "
                 "stops AS stops ORDER BY stops")))))
  (testing "a long statement has a blank line between its stages, not inside a subquery"
    (let [laid-out (dashboard/format-statement
                    (str "MATCH (o:Place) WHERE o.open AND o.near CALL { WITH o MATCH (n:Station) WHERE n.open "
                         "RETURN collect(n) AS near } UNWIND near AS station WITH station, o WHERE station <> o "
                         "WITH station.name AS name, o.name AS place ORDER BY name RETURN name, place LIMIT 3"))]
      (is (= ["MATCH (o:Place)" "WHERE o.open" "  AND o.near" ""
              "CALL {" "  WITH o" "  MATCH (n:Station)" "  WHERE n.open" "  RETURN collect(n) AS near" "}" ""
              "UNWIND near AS station" "WITH station, o" "WHERE station <> o" ""
              "WITH station.name AS name, o.name AS place" "ORDER BY name" ""
              "RETURN name, place" "LIMIT 3"]
             (str/split-lines laid-out)))))
  (testing "a statement written over several lines is left as written"
    (is (= "MATCH (n)\n   RETURN n" (dashboard/format-statement "  MATCH (n)\n   RETURN n  ")))))

(deftest every-agent-statement-keeps-its-text-test
  (testing "laying a statement out changes its whitespace and nothing else"
    (doseq [{:keys [agent]} (agent/registry)
            {:keys [template] tool-name :name} (:tools (agent/load-agent agent))
            :let [squeezed #(str/replace % #"\s+" "")]]
      (is (= (squeezed template) (squeezed (dashboard/format-statement template))) tool-name))))
