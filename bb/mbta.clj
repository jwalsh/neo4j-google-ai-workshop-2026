(ns mbta
  "The MBTA's GTFS feed, fetched once into the XDG cache and cut down to a
  rail network. Runs in babashka: bb mbta <command>.

  The feed is 25 MB zipped and its stop_times.txt is 146 MB, nearly all of
  it buses. `network` reads the zip once and writes network.edn, a few
  hundred kilobytes: the subway, light rail and commuter rail routes, one
  representative trip for each typical pattern of each, the stops those
  trips call at and the stations the stops are in.

  GTFS's own words are kept. A station (location_type 1) is a place with a
  name, Park Street. A stop (location_type 0) is where a vehicle stops: one
  platform, for one direction. A stop names its parent_station. A route is
  what a timetable is for, Green Line B; a line groups routes, Green Line.

  MBTA_CACHE names another cache; MBTA_GTFS_URL another feed."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.util.zip ZipFile)))

(def default-url "https://cdn.mbta.com/MBTA_GTFS.zip")

(def kinds
  "GTFS route_type for the routes kept, as this project's words for them."
  {"0" "light rail" "1" "subway" "2" "commuter rail"})

(defn cache-dir
  []
  (or (System/getenv "MBTA_CACHE")
      (str (fs/path (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (fs/path (fs/home) ".cache"))
                    "workshop-mbta"))))

(defn- zip-path [] (fs/path (cache-dir) "MBTA_GTFS.zip"))
(defn- network-path [] (fs/path (cache-dir) "network.edn"))

(defn fetch!
  "Downloads the feed unless it is already cached. Returns :cached or
  :fetched. A download lands in a .part file and is renamed when whole."
  []
  (fs/create-dirs (cache-dir))
  (if (and (fs/exists? (zip-path)) (pos? (fs/size (zip-path))))
    :cached
    (let [partial-file (str (zip-path) ".part")]
      (io/copy (:body (http/get (or (System/getenv "MBTA_GTFS_URL") default-url) {:as :stream}))
               (io/file partial-file))
      (fs/move partial-file (zip-path) {:replace-existing true})
      :fetched)))

(defn- with-table
  "Calls (use-rows rows) with one file of the feed as a lazy sequence of
  maps keyed by its header, while the file is open."
  [^ZipFile feed file-name use-rows]
  (with-open [reader (io/reader (.getInputStream feed (.getEntry feed file-name)))]
    (let [[header & lines] (csv/read-csv reader)]
      (use-rows (map #(zipmap header %) lines)))))

(defn network
  "The rail network from the cached feed, as data:
  {:feed :lines :routes :stations :stops :calls}. A call is one stop of one
  representative trip: [route-id direction-id sequence stop-id]."
  []
  (with-open [feed (ZipFile. (str (zip-path)))]
    (let [table (fn [file-name keep-row] (with-table feed file-name #(into [] (keep keep-row) %)))
          routes (table "routes.txt"
                        (fn [row]
                          (when-let [kind (kinds (row "route_type"))]
                            {:id (row "route_id") :kind kind :line (row "line_id")
                             :name (or (not-empty (row "route_long_name")) (row "route_short_name"))
                             :color (str "#" (row "route_color"))
                             :order (parse-long (row "route_sort_order"))})))
          route-ids (set (map :id routes))
          ;; typicality 1 is the pattern the MBTA calls typical: no short
          ;; turns, no diversions
          patterns (table "route_patterns.txt"
                          (fn [row]
                            (when (and (route-ids (row "route_id")) (= "1" (row "route_pattern_typicality")))
                              {:route (row "route_id") :direction (row "direction_id")
                               :name (row "route_pattern_name") :trip (row "representative_trip_id")})))
          pattern-of (into {} (map (juxt :trip identity)) patterns)
          calls (table "stop_times.txt"
                       (fn [row]
                         (when-let [{:keys [route direction]} (pattern-of (row "trip_id"))]
                           [route (parse-long direction) (parse-long (row "stop_sequence")) (row "stop_id")])))
          called (set (map peek calls))
          all-stops (table "stops.txt"
                           (fn [row]
                             {:id (row "stop_id") :name (row "stop_name") :type (row "location_type")
                              :parent (not-empty (row "parent_station")) :platform (not-empty (row "platform_name"))
                              :latitude (parse-double (row "stop_lat")) :longitude (parse-double (row "stop_lon"))
                              :municipality (not-empty (row "municipality"))
                              :accessible (case (row "wheelchair_boarding") "1" true "2" false nil)}))
          stops (filterv (comp called :id) all-stops)
          parents (set (keep :parent stops))
          line-ids (set (map :line routes))]
      {:feed (first (table "feed_info.txt" #(select-keys % ["feed_version" "feed_start_date" "feed_end_date"])))
       :lines (table "lines.txt"
                     (fn [row] (when (line-ids (row "line_id"))
                                 {:id (row "line_id") :name (row "line_long_name") :color (str "#" (row "line_color"))})))
       :routes routes
       :patterns patterns
       :stations (mapv #(dissoc % :type :parent :platform) (filter (comp parents :id) all-stops))
       :stops (mapv #(dissoc % :type) stops)
       :calls (vec (sort calls))})))

(defn network!
  "Writes network.edn from the cached feed and returns the network."
  []
  (let [cut (network)]
    (spit (str (network-path) ".part") (pr-str cut))
    (fs/move (str (network-path) ".part") (network-path) {:replace-existing true})
    cut))

(defn -main
  [& [command]]
  (case command
    "fetch" (println (name (fetch!)) (str (zip-path)))
    "network" (let [{:keys [feed lines routes patterns stations stops calls]} (do (fetch!) (network!))]
                (println (feed "feed_version"))
                (pprint/print-table (for [[kind kind-routes] (sort-by key (group-by :kind routes))]
                                      {:kind kind :routes (count kind-routes)}))
                (println (count lines) "lines," (count routes) "routes," (count patterns) "typical patterns,"
                         (count stations) "stations," (count stops) "stops," (count calls) "calls")
                (println "stops with no station:" (count (remove :parent stops)))
                (println (str (network-path))))
    "where" (println (cache-dir))
    (do (println "usage: bb mbta fetch | network | where")
        (println "  MBTA_CACHE     where the feed is kept (default: $XDG_CACHE_HOME/workshop-mbta)")
        (println "  MBTA_GTFS_URL  the feed (default:" default-url ")"))))
