(ns workshop.dashboard
  "Pages on 127.0.0.1, rendered on the server. / is an overview: every data
  source in agents/registry.edn with what its graph holds and one
  projection of it, and the pages on offer. /map draws the transit graph's
  stations where they are. /data is a few questions asked of whatever graph
  the env file names, /ask puts a question to an agent, /traces is what the
  others cost as waterfalls, and /config is workshop.status: every
  connection the lab depends on.
  The JDK's own HTTP server. Loopback only: it shows the instance name and
  the lab browser's tab titles."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.agent :as agent]
            [workshop.env :as env]
            [workshop.mbta :as mbta]
            [workshop.neo4j :as neo4j]
            [workshop.status :as status]
            [workshop.telemetry :as telemetry])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.time LocalTime)
           (java.time.format DateTimeFormatter)))

(def default-port 8377)
(def refresh-seconds 20)

(defn- escape-html
  [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(def ^:private stylesheet
  ":root { --page: #f6f7f8; --card: #ffffff; --ink: #1c2126; --muted: #66707a;
          --line: #dfe3e7; --up: #1a7f4b; --down: #b3261e; --unknown: #66707a; }
   @media (prefers-color-scheme: dark) {
     :root { --page: #14171a; --card: #1d2125; --ink: #e6e9ec; --muted: #98a2ac;
             --line: #2e343a; --up: #57c38a; --down: #f2867e; --unknown: #98a2ac; } }
   * { box-sizing: border-box; }
   body { margin: 0; padding: 24px 16px; background: var(--page); color: var(--ink);
          font: 15px/1.45 system-ui, sans-serif; }
   header { max-width: 1100px; margin: 0 auto 16px; display: flex; flex-wrap: wrap;
            justify-content: space-between; align-items: baseline; gap: 8px; }
   h1 { font-size: 18px; margin: 0; font-weight: 600; }
   header span { color: var(--muted); font-size: 13px; }
   main { max-width: 1100px; margin: 0 auto; display: grid; gap: 12px;
          grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }
   section { background: var(--card); border: 1px solid var(--line); border-radius: 8px;
             padding: 14px 16px; min-width: 0; }
   h2 { font-size: 15px; margin: 0; font-weight: 600; display: flex;
        justify-content: space-between; gap: 8px; }
   .state { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; }
   .up { color: var(--up); } .down { color: var(--down); } .unknown { color: var(--unknown); }
   .summary { margin: 6px 0 0; overflow-wrap: anywhere; }
   .millis { color: var(--muted); font-size: 12px; }
   table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
   td { padding: 3px 0; border-top: 1px solid var(--line); vertical-align: top;
        overflow-wrap: anywhere; }
   td:first-child { color: var(--muted); padding-right: 12px; width: 45%; }
   td:last-child { font-variant-numeric: tabular-nums; }
   nav { max-width: 1100px; margin: 0 auto 14px; display: flex; gap: 16px; font-size: 14px; }
   nav a { color: var(--muted); text-decoration: none; padding-bottom: 2px; }
   nav a.here { color: var(--ink); border-bottom: 2px solid var(--ink); }
   main.cards { grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)); }
   h3 { font-size: 13px; font-weight: 600; margin: 12px 0 0; color: var(--muted); }
   .tally, .links { font-size: 13px; margin: 8px 0 0; }
   .tally { color: var(--muted); font-variant-numeric: tabular-nums; }
   code { font: 12px ui-monospace, monospace; background: var(--page); padding: 1px 5px; border-radius: 4px; }
   .map { width: 100%; height: auto; display: block; margin-top: 10px; }
   .map line { stroke-width: 4; stroke-linecap: round; }
   .map circle { fill: var(--card); stroke: var(--ink); stroke-width: 1.5; }
   .map circle.meet { stroke-width: 2.5; }
   .map text { font-size: 11px; fill: var(--ink); paint-order: stroke; stroke: var(--card); stroke-width: 3; }
   main.wide { display: block; }
   main.wide section { margin-bottom: 12px; }
   .data th { text-align: left; font-weight: 600; font-size: 12px; color: var(--muted);
              padding: 3px 12px 3px 0; }
   .data td, .data td:first-child { width: auto; color: var(--ink); padding-right: 12px; }
   .data td.number { text-align: right; font-variant-numeric: tabular-nums; }
   .statement { font: 12px/1.4 ui-monospace, monospace; color: var(--muted); margin: 6px 0 0;
                overflow-wrap: anywhere; }
   form { display: flex; gap: 8px; }
   select { font: inherit; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px;
            background: var(--card); color: var(--ink); }
   a { color: inherit; }
   input[type=text] { flex: 1; font: inherit; padding: 6px 10px; border: 1px solid var(--line);
                      border-radius: 6px; background: var(--card); color: var(--ink); }
   button { font: inherit; padding: 6px 14px; border: 1px solid var(--line); border-radius: 6px;
            background: var(--page); color: var(--ink); }
   .trace-row { display: grid; grid-template-columns: minmax(180px, 34%) 1fr 70px; gap: 10px;
                align-items: center; font-size: 13px; padding: 2px 0; border-top: 1px solid var(--line); }
   .trace-row .label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
   .trace-row .label small { color: var(--muted); }
   .lane { position: relative; height: 12px; background: var(--page); border-radius: 3px; }
   .bar { position: absolute; top: 0; height: 12px; min-width: 2px; border-radius: 3px; background: var(--up); }
   .bar.error { background: var(--down); }
   .bar.cypher { background: #4a7fd6; }
   .bar.lab { background: #b07cc6; }
   .bar.model { background: #d9912b; }
   .trace-row .ms { text-align: right; color: var(--muted); font-variant-numeric: tabular-nums; }")

(def ^:private state-labels {:up "up" :down "down" :unknown "info"})

(defn- render-check
  [{:keys [state summary details millis] check-name :name}]
  (str "<section><h2><span>" (escape-html check-name) "</span>"
       "<span class=\"state " (name state) "\">" (state-labels state) "</span></h2>"
       "<p class=\"summary\">" (escape-html summary)
       " <span class=\"millis\">" millis " ms</span></p>"
       (when (seq details)
         (str "<table>"
              (str/join (map (fn [[detail-name detail-value]]
                               (str "<tr><td>" (escape-html detail-name) "</td><td>"
                                    (escape-html detail-value) "</td></tr>"))
                             details))
              "</table>"))
       "</section>"))

(defn- page
  "The frame every page shares: title, navigation, an optional reload."
  [current-path heading aside main-class body reload?]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       (when reload? (str "<meta http-equiv=\"refresh\" content=\"" refresh-seconds "\">"))
       "<title>Workshop status</title><style>" stylesheet "</style></head><body>"
       "<header><h1>" (escape-html heading) "</h1><span>" (escape-html aside) "</span></header><nav>"
       (str/join (map (fn [[path label]]
                        (str "<a href=\"" path "\"" (when (= path current-path) " class=\"here\"")
                             ">" label "</a>"))
                      [["/" "Overview"] ["/map" "Map"] ["/data" "Data"] ["/ask" "Ask"]
                       ["/traces" "Traces"] ["/config" "Config"]]))
       "</nav><main" (when main-class (str " class=\"" main-class "\"")) ">" body "</main></body></html>"))

(defn- clock [] (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss")))

(defn render-page
  [checks]
  (let [down-count (count (filter #(= :down (:state %)) checks))]
    (page "/config" "Neo4j and Google workshop: config"
          (str (if (zero? down-count) "nothing down" (str down-count " down"))
               ", checked " (clock) ", reloads every " refresh-seconds " s")
          nil
          (str/join (map render-check checks))
          true)))

;; /data: questions, each one statement. The rows are whatever the server
;; holds; nothing here is cached, so every load of the page is a trace.
(def data-questions
  [{:title "What is in the graph"
    :statement "MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS nodes ORDER BY nodes DESC"}
   {:title "Managers by number of companies held"
    :statement (str "MATCH (m:Manager)-[:OWNS]->(c:Company) "
                    "RETURN m.managerName AS manager, count(DISTINCT c) AS companies "
                    "ORDER BY companies DESC LIMIT 10")}
   {:title "How widely a company is held"
    :statement (str "MATCH (c:Company)<-[:OWNS]-(m:Manager) WITH c, count(DISTINCT m) AS owners "
                    "RETURN owners, count(*) AS companies ORDER BY owners LIMIT 12")}
   {:title "Largest holdings by reported value"
    :statement (str "MATCH (m:Manager)-[o:OWNS]->(c:Company) "
                    "RETURN m.managerName AS manager, c.companyName AS company, o.shares AS shares, "
                    "o.value AS value ORDER BY o.value DESC LIMIT 10")}
   {:title "Pairs of managers with the most companies in common"
    :statement (str "MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager) "
                    "WHERE a.managerName < b.managerName "
                    "RETURN a.managerName AS first, b.managerName AS second, count(DISTINCT c) AS shared "
                    "ORDER BY shared DESC LIMIT 8")}])

(defn answer
  "One question asked of the driver: its rows, or the reason there are none."
  [driver {:keys [statement] :as question}]
  (try (assoc question :rows (neo4j/query driver statement))
       (catch Exception failure
         (assoc question :error (or (ex-message failure) (str (class failure)))))))

(defn- render-cell
  [cell-value]
  (if (number? cell-value)
    (str "<td class=\"number\">"
         (if (integer? cell-value) (format "%,d" cell-value) (format "%,.0f" (double cell-value)))
         "</td>")
    (str "<td>" (escape-html cell-value) "</td>")))

(defn- render-rows
  "Rows as a table, with the keys of the first row as its columns."
  [rows]
  (let [columns (keys (first rows))]
    (str "<table class=\"data\"><tr>"
         (str/join (map #(str "<th>" (escape-html (name %)) "</th>") columns))
         "</tr>"
         (str/join (map (fn [row]
                          (str "<tr>" (str/join (map #(render-cell (row %)) columns)) "</tr>"))
                        rows))
         "</table>")))

(defn- render-answer
  [{:keys [title statement rows error]}]
  (str "<section><h2><span>" (escape-html title) "</span>"
       (if error
         "<span class=\"state down\">failed</span>"
         (str "<span class=\"millis\">" (count rows) " rows</span>"))
       "</h2><p class=\"statement\">" (escape-html statement) "</p>"
       (cond
         error (str "<p class=\"summary\">" (escape-html error) "</p>")
         (seq rows) (render-rows rows))
       "</section>"))

(defn render-data
  [answers]
  (page "/data" "Neo4j and Google workshop: data"
        (str "asked " (clock) " of the server the env file names; each question is one statement")
        "wide"
        (str/join (map render-answer answers))
        false))

;; /: every data source the registry names, asked what it holds. Each has
;; its own server, so each gets its own driver for the one page; a server
;; that is not running is a card that says how to start it.

(defn source-overview
  "One registry entry with what its graph holds: :counts and :glance-rows,
  or :problem when its server does not answer."
  [{:keys [glance] env-file :env :as source}]
  (telemetry/span (str "source: " (:id source)) {}
    (try
      (with-open [driver (neo4j/connect (env/load-env env-file))]
        (assoc source
               :counts (neo4j/counts driver)
               :glance-rows (when glance (neo4j/query driver (:statement glance)))))
      (catch Exception failure
        (assoc source :problem (or (ex-message failure) (str (class failure))))))))

(defn overview
  "Every source in the registry, asked at once."
  []
  (mapv deref (mapv #(future (source-overview %)) (agent/registry))))

(defn- ask-link
  [agent-id question]
  (str "<a href=\"/ask?agent=" agent-id "&q=" (java.net.URLEncoder/encode ^String question "UTF-8") "\">"
       (escape-html question) "</a>"))

(defn- render-source
  [{:keys [id title about shape start counts glance glance-rows problem pages example]}]
  (str "<section><h2><span>" (escape-html (or title id)) "</span>"
       (if problem
         "<span class=\"state down\">not running</span>"
         (str "<span class=\"millis\">"
              (format "%,d nodes, %,d relationships"
                      (reduce + (map :count (:nodes counts)))
                      (reduce + (map :count (:relationships counts))))
              "</span>"))
       "</h2><p class=\"summary\">" (escape-html about) "</p>"
       "<p class=\"statement\">" (escape-html shape) "</p>"
       (if problem
         (str "<p class=\"summary\">Start it with <code>" (escape-html start) "</code>. "
              "<span class=\"millis\">" (escape-html problem) "</span></p>")
         (str "<p class=\"tally\">"
              (str/join " · " (map (fn [{:keys [label count]}] (str (escape-html label) " " (format "%,d" count)))
                                   (:nodes counts)))
              "</p>"
              (when (seq glance-rows)
                (str "<h3>" (escape-html (:title glance)) "</h3>" (render-rows glance-rows)))))
       "<p class=\"links\">Ask: " (ask-link id example)
       (str/join (map (fn [[path label]] (str " · <a href=\"" path "\">" (escape-html label) "</a>")) pages))
       "</p></section>"))

(def ^:private pages-on-offer
  [["/map" "Map" "The transit graph's stations drawn where they are, with the routes between them."]
   ["/data" "Data" "Five questions asked of the graph the env file names, each one statement."]
   ["/ask" "Ask" "A question to any source's agent: a local model chooses a Cypher template."]
   ["/traces" "Traces" "What the other pages cost: every statement and model call as a waterfall."]
   ["/config" "Config" "Every connection the lab depends on, checked: the database, the bucket, the browser."]])

(defn render-overview
  [sources]
  (page "/" "Neo4j and Google workshop"
        (str (count (remove :problem sources)) " of " (count sources) " data sources running, asked " (clock))
        "cards"
        (str (str/join (map render-source sources))
             "<section><h2><span>Pages</span></h2><table>"
             (str/join (map (fn [[path label what]]
                              (str "<tr><td><a href=\"" path "\">" label "</a></td><td>" (escape-html what) "</td></tr>"))
                            pages-on-offer))
             "</table></section>")
        false))

;; /map: stations where they are. Latitude and longitude become x and y by
;; the plainest projection that looks right at a city's scale: longitude
;; shrunk by the cosine of the latitude, north up.

(def ^:private map-width 1000)

(defn- render-map-drawing
  [{:keys [stations segments]}]
  (let [latitudes (map :latitude stations)
        longitudes (map :longitude stations)
        south (apply min latitudes) north (apply max latitudes)
        west (apply min longitudes) east (apply max longitudes)
        squeeze (Math/cos (Math/toRadians (/ (+ south north) 2)))
        margin 40
        ;; room on the right for the name of the easternmost station
        label-room 110
        scale (/ (- map-width (* 2 margin) label-room) (max 1e-9 (* squeeze (- east west))))
        height (+ (* 2 margin) (* scale (- north south)))
        place (fn [{:keys [latitude longitude]}]
                [(+ margin (* scale squeeze (- longitude west)))
                 (+ margin (* scale (- north latitude)))])
        placed (into {} (map (juxt :id place)) stations)
        ;; a name is drawn where lines meet and where a route ends: enough
        ;; to find one's way, few enough to read
        degree (frequencies (mapcat (juxt :from :to) segments))
        named? (fn [{:keys [id lines]}] (or (>= lines 2) (<= (degree id 0) 1)))]
    (str "<svg class=\"map\" viewBox=\"0 0 " map-width " " (long height) "\" role=\"img\" "
         "aria-label=\"Stations and the routes between them\">"
         (str/join (for [{:keys [from to color]} segments
                         :let [[from-x from-y] (placed from) [to-x to-y] (placed to)]
                         :when (and from-x to-x)]
                     (format "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\"/>"
                             (double from-x) (double from-y) (double to-x) (double to-y) (escape-html color))))
         (str/join (for [{:keys [id lines lineNames] station-name :name :as station} stations
                         :let [[x y] (placed id)]]
                     (str (format "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%s\"%s>" (double x) (double y)
                                  (if (>= lines 2) "5" "3") (if (>= lines 2) " class=\"meet\"" ""))
                          "<title>" (escape-html station-name) ": " (escape-html (str/join ", " lineNames)) "</title></circle>"
                          (when (named? station)
                            (format "<text x=\"%.1f\" y=\"%.1f\">%s</text>" (double (+ x 7)) (double (+ y 4))
                                    (escape-html station-name))))))
         "</svg>")))

(def ^:private map-views
  {"rapid" {:label "Subway and light rail" :kinds mbta/rapid-transit}
   "rail" {:label "Commuter rail" :kinds #{"commuter rail"}}
   "all" {:label "Everything" :kinds #{"subway" "light rail" "commuter rail"}}})

(defn render-map
  "The map page for one view, or the reason there is no map."
  [view-id]
  (let [view-id (if (map-views view-id) view-id "rapid")
        {:keys [kinds]} (map-views view-id)
        source (first (filter #(= "mbta" (:id %)) (agent/registry)))
        drawn (try (with-open [driver (neo4j/connect (env/load-env (:env source)))]
                     (mbta/map-data driver kinds))
                   (catch Exception failure {:problem (or (ex-message failure) (str (class failure)))}))]
    (page "/map" "Neo4j and Google workshop: map"
          (if (:problem drawn)
            "the transit graph is not running"
            (str (count (:stations drawn)) " stations, " (count (:segments drawn))
                 " connections, from the MBTA's GTFS feed; hover a station for its lines"))
          "wide"
          (str "<section><p class=\"links\">"
               (str/join " · " (map (fn [[id {:keys [label]}]]
                                      (if (= id view-id)
                                        (str "<strong>" label "</strong>")
                                        (str "<a href=\"/map?view=" id "\">" label "</a>")))
                                    map-views))
               "</p>"
               (cond
                 (:problem drawn) (str "<p class=\"summary\">Start it with <code>" (escape-html (:start source))
                                       "</code>, then <code>bb mbta network</code> and <code>bb mbta-load</code>. "
                                       "<span class=\"millis\">" (escape-html (:problem drawn)) "</span></p>")
                 (empty? (:stations drawn)) "<p class=\"summary\">The graph is empty. <code>bb mbta-load</code>.</p>"
                 :else (render-map-drawing drawn))
               "<p class=\"statement\">MATCH (a:Station)-[c:CONNECTS]-&gt;(b:Station) WHERE c.kind IN $kinds "
               "RETURN a, b, c.color · a name is drawn where lines meet and where a route ends</p></section>")
          false)))

(defn- query-parameter
  "One parameter of a raw query string, decoded."
  [raw-query parameter-name]
  (some (fn [pair]
          (let [[pair-name pair-value] (str/split pair #"=" 2)]
            (when (= parameter-name pair-name)
              (java.net.URLDecoder/decode (str pair-value) "UTF-8"))))
        (str/split (str raw-query) #"&")))

(defn render-ask
  "The question form and, when a question was asked, what the agent did with
  it: the tool, the parameters as run, what was dropped or resolved, and the
  rows or the reason there are none."
  [agent-id question {:keys [tool parameters resolved problem candidates rows choice source] :as outcome}]
  (page "/ask" "Neo4j and Google workshop: ask"
        "a local model chooses a Cypher template; the rows are the answer"
        "wide"
        (str "<section><form method=\"get\" action=\"/ask\"><select name=\"agent\">"
             (str/join (map (fn [{:keys [id]}]
                              (str "<option" (when (= id agent-id) " selected") ">" (escape-html id) "</option>"))
                            (agent/registry)))
             "</select><input type=\"text\" name=\"q\" value=\""
             (escape-html question) "\" placeholder=\"" (escape-html (:example (agent/entry agent-id))) "\">"
             "<button>Ask</button></form><table>"
             (str/join (map (fn [{:keys [id about example]}]
                              (str "<tr><td>" (escape-html id) "</td><td>" (escape-html about)
                                   " <a href=\"/ask?agent=" id "&q=" (java.net.URLEncoder/encode ^String example "UTF-8")
                                   "\">" (escape-html example) "</a></td></tr>"))
                            (agent/registry)))
             "</table></section>"
             (when outcome
               (str "<section><h2><span>" (escape-html (or tool (:tool choice) "no tool")) "</span>"
                    (if problem
                      "<span class=\"state unknown\">no answer</span>"
                      (str "<span class=\"millis\">" (count rows) " rows</span>"))
                    "</h2><table>"
                    (str/join
                     (for [[row-name row-value]
                           [["agent and source" (str agent-id " on " source)]
                            ["model" (str (:model choice) ", "
                                          (some->> (:seconds choice) double (format "%.2f s")))]
                            ["parameters as run" (when (seq parameters) (pr-str parameters))]
                            ["resolved against the graph" (when (seq resolved) (pr-str resolved))]
                            ["dropped, not in the question" (when (seq (:dropped choice)) (pr-str (:dropped choice)))]
                            ["why no rows" problem]
                            ["stored values that match" (when (seq candidates) (str/join "; " candidates))]]
                           :when row-value]
                       (str "<tr><td>" (escape-html row-name) "</td><td>" (escape-html row-value) "</td></tr>")))
                    "</table></section>"
                    (when (seq rows)
                      (render-answer {:title "Rows" :rows rows
                                      :statement (some #(when (= tool (:name %)) (:template %))
                                                       (:tools (agent/load-agent (:agent (agent/entry agent-id)))))})))))
        false))

(defn- bar-class
  [{:keys [status] span-name :name}]
  (cond (= :error status) "bar error"
        (= "cypher" span-name) "bar cypher"
        (= "ollama" span-name) "bar model"
        (str/starts-with? span-name "lab ") "bar lab"
        :else "bar"))

(defn- span-detail
  [{:keys [attributes]}]
  (or (:statement attributes)
      (:error attributes)
      (when (:model attributes)
        (str (:model attributes) ", " (:prompt-tokens attributes) " tokens in, "
             (:answer-tokens attributes) " out"))
      (some->> (dissoc attributes :parameters :auto-commit?) seq
               (map (fn [[attribute-name attribute-value]]
                      (str (name attribute-name) " " (if (keyword? attribute-value)
                                                       (name attribute-value)
                                                       attribute-value))))
               (str/join ", "))))

(defn- render-trace
  [{:keys [trace-id duration-ms errors spans] trace-name :name}]
  (let [total-ms (max duration-ms 0.001)]
    (str "<section><h2><span>" (escape-html trace-name)
         " <span class=\"millis\">" (count spans) " spans, trace " trace-id "</span></span>"
         "<span class=\"state " (if (pos? errors) "down" "up") "\">"
         (format "%.0f ms" (double duration-ms)) "</span></h2>"
         (str/join
          (map (fn [{:keys [depth offset-ms attributes] span-name :name span-ms :duration-ms :as span-row}]
                 (str "<div class=\"trace-row\"><div class=\"label\" style=\"padding-left:" (* 14 depth) "px\">"
                      (escape-html span-name)
                      (when-let [detail (span-detail span-row)]
                        (str " <small>" (escape-html detail)
                             (when (contains? attributes :rows) (str " → " (:rows attributes) " rows"))
                             "</small>"))
                      "</div><div class=\"lane\"><div class=\"" (bar-class span-row) "\" style=\"left:"
                      (format "%.2f" (* 100.0 (/ offset-ms total-ms))) "%;width:"
                      (format "%.2f" (* 100.0 (/ span-ms total-ms))) "%\"></div></div>"
                      "<div class=\"ms\">" (format "%.1f" (double span-ms)) "</div></div>"))
               spans))
         "</section>")))

(defn- render-summary
  [summary-rows]
  (str "<section><h2><span>By span name</span><span class=\"millis\">ms</span></h2>"
       "<table class=\"data\"><tr><th>span</th><th>count</th><th>errors</th><th>p50</th><th>p95</th><th>max</th></tr>"
       (str/join (map (fn [{:keys [count errors p50-ms p95-ms max-ms] span-name :name}]
                        (str "<tr><td>" (escape-html span-name) "</td>"
                             (str/join (map #(str "<td class=\"number\">" % "</td>")
                                            [count errors (format "%.1f" (double p50-ms))
                                             (format "%.1f" (double p95-ms)) (format "%.1f" (double max-ms))]))
                             "</tr>"))
                      summary-rows))
       "</table></section>"))

(defn render-traces
  [trace-list summary-rows]
  (page "/traces" "Neo4j and Google workshop: traces"
        (str (count (telemetry/spans)) " spans held in memory, newest trace first; this page records none")
        "wide"
        (if (seq trace-list)
          (str (render-summary summary-rows) (str/join (map render-trace trace-list)))
          "<section><p class=\"summary\">No spans yet. Load Overview or Data, then come back.</p></section>")
        false))

(defn- respond
  [^HttpExchange exchange status-code content-type ^String body]
  (let [body-bytes (.getBytes body "UTF-8")]
    (.add (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange status-code (alength body-bytes))
    (with-open [response-body (.getResponseBody exchange)]
      (.write response-body body-bytes))))

(defn- handler
  [driver]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (case (.getPath (.getRequestURI exchange))
          "/" (respond exchange 200 "text/html; charset=utf-8"
                       (telemetry/span "GET /" {}
                         (render-overview (overview))))
          "/config" (respond exchange 200 "text/html; charset=utf-8"
                             (telemetry/span "GET /config" {}
                               (render-page (status/all-checks driver))))
          "/map" (respond exchange 200 "text/html; charset=utf-8"
                          (telemetry/span "GET /map" {}
                            (render-map (query-parameter (.getRawQuery (.getRequestURI exchange)) "view"))))
          "/status.edn" (respond exchange 200 "application/edn; charset=utf-8"
                                 (with-out-str (pprint/pprint (status/all-checks driver))))
          "/data" (respond exchange 200 "text/html; charset=utf-8"
                           (telemetry/span "GET /data" {}
                             (render-data (mapv #(answer driver %) data-questions))))
          "/ask" (let [raw-query (.getRawQuery (.getRequestURI exchange))
                       question (query-parameter raw-query "q")
                       agent-id (:id (agent/entry (query-parameter raw-query "agent")))]
                   (respond exchange 200 "text/html; charset=utf-8"
                            (if (str/blank? question)
                              (render-ask agent-id "" nil)
                              (telemetry/span "GET /ask" {:agent-id agent-id}
                                (render-ask agent-id question
                                            (try (agent/ask-agent agent-id question)
                                                 (catch Exception failure
                                                   {:problem (or (ex-message failure) (str (class failure)))})))))))
          "/traces" (respond exchange 200 "text/html; charset=utf-8"
                             (render-traces (telemetry/traces 12) (telemetry/summary)))
          "/spans.edn" (respond exchange 200 "application/edn; charset=utf-8"
                                (with-out-str (pprint/pprint (telemetry/spans))))
          (respond exchange 404 "text/plain" "not found\n"))
        (catch Exception failure
          (respond exchange 500 "text/plain" (str (ex-message failure) "\n")))))))

(defn start
  "Starts the page on loopback and returns a no-argument function that stops
  it. One driver is held for the life of the server; if .env has no
  connection yet, the Neo4j check opens its own each time and reports why
  it cannot."
  ([] (start default-port))
  ([port]
   (let [driver (try (neo4j/connect) (catch Exception _ nil))
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     (.createContext server "/" (handler driver))
     (.setExecutor server nil)
     (.start server)
     (fn stop []
       (.stop server 0)
       (some-> ^java.lang.AutoCloseable driver .close)))))
