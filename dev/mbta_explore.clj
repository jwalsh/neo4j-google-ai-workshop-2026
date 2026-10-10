(ns mbta-explore
  "The transit graph, a form at a time (C-x C-e in CIDER, or C-c C-c). Nothing
  runs when the file loads: every block is a comment.

  Start the graph first: bb mbta network, bb local-mbta, then bb mbta-load.
  Then cider-jack-in here (the root's .dir-locals.el gives the :dev alias),
  or bb repl and cider-connect. M-x workshop-repl does the rest.

  The page goes the way a rider does: what is at a station, what is on a
  line, then how to get from one place to another, several ways."
  (:require [clojure.pprint :refer [print-table]]
            [workshop.agent :as agent]
            [workshop.env :as env]
            [workshop.mbta :as mbta]
            [workshop.neo4j :as neo4j]))

(defonce ^:private transit-driver (atom nil))

(defn driver
  "A driver for the transit graph, whatever .env says: mbta-graph.env names it."
  []
  (or @transit-driver
      (reset! transit-driver
              (neo4j/connect (env/load-env "mbta-graph.env")))))

(defn q
  ([statement] (neo4j/query (driver) statement))
  ([statement parameters] (neo4j/query (driver) statement parameters)))

(def guide
  "The transit agent: six Cypher templates, read from its file each time."
  #(agent/load-agent "agents/mbta.agent.json"))

(defn template
  "The Cypher of the agent's tool with this name."
  [tool-name]
  (:template (first (filter #(= tool-name (:name %)) (:tools (guide))))))

(defn ways
  "Up to four ways from one place to another, best first. A place is a
  station or a landmark, spelled as the graph spells it: (places \"fine\")."
  [from-place to-place]
  (q (template "Subway Journey Between Two Stations")
     {:fromStation from-place :toStation to-place :limit 4}))

(defn places
  "The places whose name contains this, landmarks marked."
  [written]
  (q (str "MATCH (p:Place) WHERE toLower(p.name) CONTAINS toLower($written) "
          "RETURN p.name AS place, p:Landmark AS landmark ORDER BY place")
     {:written written}))

(comment
  ;; what the graph holds
  (neo4j/counts (driver))

  ;; a station: the routes through it, and the platforms inside it
  (print-table
   (q (str "MATCH (l:Line)<-[:ON]-(r:Route)-[:SERVES]->(s:Station) "
           "WHERE s.name = $station "
           "RETURN r.name AS route, l.name AS line, r.kind AS kind "
           "ORDER BY r.order")
      {:station "Park Street"}))

  ;; where the most lines meet
  (print-table
   (q (str "MATCH (l:Line)<-[:ON]-(:Route)-[:SERVES]->(s:Station) "
           "RETURN s.name AS station, count(DISTINCT l) AS lines "
           "ORDER BY lines DESC, station LIMIT 8")))

  ;; a line, in the order a train reaches its stations
  (print-table
   (q (str "MATCH (r:Route)-[v:SERVES]->(s:Station) WHERE r.name = $route "
           "RETURN v.sequence + 1 AS position, s.name AS station "
           "ORDER BY position")
      {:route "Blue Line"})))

(comment
  ;; places a journey can start or end at: stations, and landmarks by hand
  (places "fine")
  (print-table (map #(select-keys % [:name :latitude :longitude])
                    (mbta/landmarks)))

  ;; several ways, best first
  (print-table [:ride :stops :changes :aboutMinutes]
               (ways "Park Street" "Maverick"))
  (print-table (ways "Park Street" "Maverick"))
  (print-table (ways "Alewife" "Prudential"))
  (print-table (ways "Maverick" "ICA (Institute of Contemporary Art)"))
  (print-table (ways "Maverick" "MFA museum (Museum of Fine Arts)"))
  (print-table (ways "Google Cambridge" "Fenway Park"))

  ;; the two lines that do not meet
  (print-table (ways "Maverick" "Kendall/MIT")))

(comment
  ;; the same through a model, which picks the tool and spells the places.
  ;; OLLAMA_MODEL names it; the default is not installed on this laptop.
  (def answer
    (agent/ask (driver) (guide) "How do I get from Maverick to the ICA?"))
  (select-keys answer [:tool :parameters :problem])
  (print-table (:rows answer))

  (reset! transit-driver nil))
