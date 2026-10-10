(ns workshop.mbta
  "The MBTA's rail network as a graph, from the cut `bb mbta network` keeps
  in the XDG cache. GTFS's distinctions are kept, because they are the
  point: a rider thinks in stations and lines, a timetable is written in
  stops and routes, and the graph holds both and the edges between.

    (:Route)-[:ON]->(:Line)                     Green Line B is on the Green Line
    (:Stop)-[:IN]->(:Station)                   a platform is in Park Street
    (:Route)-[:CALLS_AT {direction sequence}]->(:Stop)
    (:Stop)-[:NEXT {route direction}]->(:Stop)  as a typical trip of each branch runs
    (:Route)-[:SERVES {sequence}]->(:Station)   derived: the stops' stations
    (:Station)-[:CONNECTS {route routeName line kind color}]->(:Station)

    (:Place)                                    every station, and each landmark

  NEXT is what the feed says. SERVES and CONNECTS are derived from it by
  replacing each stop with its station, and are what a map or a journey
  between stations asks. A station has a `location` point, so distance is
  a function in Cypher.

  A :Place is somewhere a journey can start or end. Every station is one.
  So is each :Landmark in agents/mbta-places.edn, entered by hand and not
  from the feed: the ICA is not a station, and is reached on foot from the
  stations near it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [workshop.neo4j :as neo4j]))

(defn cache-dir
  "The same rule as bb/mbta.clj: MBTA_CACHE, else the XDG cache."
  []
  (or (System/getenv "MBTA_CACHE")
      (str (io/file (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (io/file (System/getProperty "user.home") ".cache"))
                    "workshop-mbta"))))

(defn network
  "The cut of the feed, as `bb mbta network` wrote it."
  []
  (let [network-file (io/file (cache-dir) "network.edn")]
    (when-not (.exists network-file)
      (throw (ex-info "No network cached. bb mbta network." {:cache (cache-dir)})))
    (edn/read-string (slurp network-file))))

(def places-path "agents/mbta-places.edn")

(defn landmarks
  "The places that are not stations, as the file lists them."
  []
  (edn/read-string (slurp places-path)))

(defn graph
  "The projection as data: the network's own tables, and the edges derived
  from its calls. {:lines :routes :stations :stops :calls :next :serves
  :connects}."
  ([] (graph (network)))
  ([{:keys [lines routes stations stops calls]}]
   (let [station-of (into {} (map (juxt :id :parent)) stops)
         route-by-id (into {} (map (juxt :id identity)) routes)
         line-name (into {} (map (juxt :id :name)) lines)
         ;; one trip per typical pattern: a route with two branches has two
         ;; in each direction, and stops are in order only within a trip
         trips (group-by (fn [[route-id direction trip-id]] [route-id direction trip-id]) (sort calls))
         next-stops (vec (distinct (for [[[route-id direction] trip-calls] trips
                                         [from-call to-call] (partition 2 1 trip-calls)]
                                     [(peek from-call) (peek to-call) route-id direction])))]
     {:lines lines
      :routes routes
      :stations stations
      :stops stops
      :calls calls
      :next next-stops
      ;; a station's place on a route is its place in direction 0, or in
      ;; whichever direction the route has; a branch's stations follow the
      ;; trunk's
      :serves (vec (for [[route-id route-calls] (group-by first calls)
                         :let [direction (apply min (map second route-calls))
                               ;; the longest trip first, then what the branches add
                               branch-trips (->> route-calls
                                                 (filter #(= direction (second %)))
                                                 (group-by #(nth % 2))
                                                 vals
                                                 (sort-by (comp - count)))]
                         [position station-id] (->> branch-trips
                                                    (mapcat sort)
                                                    (map (comp station-of peek))
                                                    distinct
                                                    (map-indexed vector))]
                     [route-id station-id position]))
      :connects (vec (distinct (for [[from-stop to-stop route-id] next-stops
                                     :let [from-station (station-of from-stop)
                                           to-station (station-of to-stop)
                                           {:keys [kind color line] route-name :name} (route-by-id route-id)]
                                     :when (not= from-station to-station)]
                                 [from-station to-station route-id route-name kind color (line-name line)])))})))

(defn load!
  "MERGEs the network into the database. Running it twice leaves the same
  graph. Returns the counts it then holds. Nothing is deleted, so a later
  feed that drops a station leaves the station: start a fresh store for a
  new feed."
  [driver {:keys [lines routes stations stops calls serves connects] next-stops :next}]
  (doseq [[constraint-name label] [["line_id" "Line"] ["route_id" "Route"] ["station_id" "Station"] ["stop_id" "Stop"]]]
    (neo4j/query driver (str "CREATE CONSTRAINT " constraint-name " IF NOT EXISTS FOR (n:" label
                             ") REQUIRE n.id IS UNIQUE")))
  (neo4j/query driver "CREATE POINT INDEX station_location IF NOT EXISTS FOR (s:Station) ON (s.location)")
  (let [rows (fn [maps] (mapv #(update-keys % name) maps))]
    (neo4j/query driver "UNWIND $rows AS row MERGE (l:Line {id: row.id}) SET l.name = row.name, l.color = row.color"
                 {:rows (rows lines)})
    (neo4j/query driver
                 (str "UNWIND $rows AS row MERGE (r:Route {id: row.id}) "
                      "SET r.name = row.name, r.kind = row.kind, r.color = row.color, r.order = row.order "
                      "WITH r, row MATCH (l:Line {id: row.line}) MERGE (r)-[:ON]->(l)")
                 {:rows (rows routes)})
    (neo4j/query driver
                 (str "UNWIND $rows AS row MERGE (s:Station {id: row.id}) "
                      "SET s.name = row.name, s.municipality = row.municipality, s.accessible = row.accessible, "
                      "s.latitude = row.latitude, s.longitude = row.longitude, "
                      "s.location = point({latitude: row.latitude, longitude: row.longitude})")
                 {:rows (rows stations)})
    (neo4j/query driver
                 (str "UNWIND $rows AS row MERGE (p:Stop {id: row.id}) "
                      "SET p.name = row.name, p.platform = row.platform, p.accessible = row.accessible, "
                      "p.latitude = row.latitude, p.longitude = row.longitude "
                      "WITH p, row MATCH (s:Station {id: row.parent}) MERGE (p)-[:IN]->(s)")
                 {:rows (rows stops)}))
  (neo4j/query driver
               (str "UNWIND $rows AS row MATCH (r:Route {id: row[0]}), (p:Stop {id: row[4]}) "
                    "MERGE (r)-[c:CALLS_AT {direction: row[1]}]->(p) SET c.sequence = row[3]")
               {:rows calls})
  (neo4j/query driver
               (str "UNWIND $rows AS row MATCH (a:Stop {id: row[0]}), (b:Stop {id: row[1]}) "
                    "MERGE (a)-[:NEXT {route: row[2], direction: row[3]}]->(b)")
               {:rows next-stops})
  (neo4j/query driver
               (str "UNWIND $rows AS row MATCH (r:Route {id: row[0]}), (s:Station {id: row[1]}) "
                    "MERGE (r)-[v:SERVES]->(s) SET v.sequence = row[2]")
               {:rows serves})
  (neo4j/query driver
               (str "UNWIND $rows AS row MATCH (a:Station {id: row[0]}), (b:Station {id: row[1]}) "
                    "MERGE (a)-[c:CONNECTS {route: row[2]}]->(b) "
                    "SET c.routeName = row[3], c.kind = row[4], c.color = row[5], c.line = row[6]")
               {:rows connects})
  (neo4j/query driver "CREATE CONSTRAINT place_id IF NOT EXISTS FOR (n:Place) REQUIRE n.id IS UNIQUE")
  (neo4j/query driver "CREATE POINT INDEX place_location IF NOT EXISTS FOR (p:Place) ON (p.location)")
  (neo4j/query driver "MATCH (s:Station) SET s:Place")
  (neo4j/query driver
               (str "UNWIND $rows AS row MERGE (p:Place {id: row.id}) "
                    "SET p:Landmark, p.name = row.name, p.latitude = row.latitude, p.longitude = row.longitude, "
                    "p.location = point({latitude: row.latitude, longitude: row.longitude})")
               {:rows (mapv #(update-keys % name) (landmarks))})
  (neo4j/counts driver))

(def rapid-transit #{"subway" "light rail"})

(defn map-data
  "What a map needs, asked of the graph: the stations a kind of route
  serves, with how many lines meet at each, and each pair of connected
  stations once per route, with the route's colour.
  {:stations [{:id :name :latitude :longitude :lines}]
   :segments [{:from :to :route :color}]}"
  [driver route-kinds]
  {:stations (neo4j/query driver
                          (str "MATCH (l:Line)<-[:ON]-(r:Route)-[:SERVES]->(s:Station) WHERE r.kind IN $kinds "
                               "RETURN s.id AS id, s.name AS name, s.latitude AS latitude, s.longitude AS longitude, "
                               "count(DISTINCT l) AS lines, collect(DISTINCT l.name) AS lineNames ORDER BY name")
                          {:kinds (vec route-kinds)})
   :segments (neo4j/query driver
                          (str "MATCH (a:Station)-[c:CONNECTS]->(b:Station) WHERE c.kind IN $kinds AND a.id < b.id "
                               "RETURN DISTINCT a.id AS from, b.id AS to, c.route AS route, c.color AS color")
                          {:kinds (vec route-kinds)})})

(comment
 ;; needs the cache: bb mbta network
 (update-vals (dissoc (network) :feed) count)
 (:feed (network))
 (update-vals (graph) count)
 (take 5 (:connects (graph)))
 ;; needs the server: bb local-mbta, bb mbta-load
 (def transit-driver (neo4j/connect ((requiring-resolve 'workshop.env/load-env) "mbta-graph.env")))
 (neo4j/counts transit-driver)
 (update-vals (map-data transit-driver rapid-transit) count)
 (.close transit-driver)
:review)
