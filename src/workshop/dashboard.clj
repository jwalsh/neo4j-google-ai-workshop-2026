(ns workshop.dashboard
  "Three pages on 127.0.0.1, rendered on the server. / is workshop.status,
  /data is a few questions asked of whatever graph .env names, and /traces
  is what the other two cost: workshop.telemetry's spans as waterfalls.
  The JDK's own HTTP server. Loopback only: it shows the instance name and
  the lab browser's tab titles."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
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
   main.wide { display: block; }
   main.wide section { margin-bottom: 12px; }
   .data th { text-align: left; font-weight: 600; font-size: 12px; color: var(--muted);
              padding: 3px 12px 3px 0; }
   .data td, .data td:first-child { width: auto; color: var(--ink); padding-right: 12px; }
   .data td.number { text-align: right; font-variant-numeric: tabular-nums; }
   .statement { font: 12px/1.4 ui-monospace, monospace; color: var(--muted); margin: 6px 0 0;
                overflow-wrap: anywhere; }
   .trace-row { display: grid; grid-template-columns: minmax(180px, 34%) 1fr 70px; gap: 10px;
                align-items: center; font-size: 13px; padding: 2px 0; border-top: 1px solid var(--line); }
   .trace-row .label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
   .trace-row .label small { color: var(--muted); }
   .lane { position: relative; height: 12px; background: var(--page); border-radius: 3px; }
   .bar { position: absolute; top: 0; height: 12px; min-width: 2px; border-radius: 3px; background: var(--up); }
   .bar.error { background: var(--down); }
   .bar.cypher { background: #4a7fd6; }
   .bar.lab { background: #b07cc6; }
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
                      [["/" "Connections"] ["/data" "Data"] ["/traces" "Traces"]]))
       "</nav><main" (when main-class (str " class=\"" main-class "\"")) ">" body "</main></body></html>"))

(defn- clock [] (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss")))

(defn render-page
  [checks]
  (let [down-count (count (filter #(= :down (:state %)) checks))]
    (page "/" "Neo4j and Google workshop: connections"
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

(defn- render-answer
  [{:keys [title statement rows error]}]
  (str "<section><h2><span>" (escape-html title) "</span>"
       (if error
         "<span class=\"state down\">failed</span>"
         (str "<span class=\"millis\">" (count rows) " rows</span>"))
       "</h2><p class=\"statement\">" (escape-html statement) "</p>"
       (cond
         error (str "<p class=\"summary\">" (escape-html error) "</p>")
         (seq rows) (let [columns (keys (first rows))]
                      (str "<table class=\"data\"><tr>"
                           (str/join (map #(str "<th>" (escape-html (name %)) "</th>") columns))
                           "</tr>"
                           (str/join (map (fn [row]
                                            (str "<tr>" (str/join (map #(render-cell (row %)) columns)) "</tr>"))
                                          rows))
                           "</table>")))
       "</section>"))

(defn render-data
  [answers]
  (page "/data" "Neo4j and Google workshop: data"
        (str "asked " (clock) " of the server the env file names; each question is one statement")
        "wide"
        (str/join (map render-answer answers))
        false))

(defn- bar-class
  [{:keys [status] span-name :name}]
  (cond (= :error status) "bar error"
        (= "cypher" span-name) "bar cypher"
        (str/starts-with? span-name "lab ") "bar lab"
        :else "bar"))

(defn- span-detail
  [{:keys [attributes]}]
  (or (:statement attributes)
      (:error attributes)
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
          "<section><p class=\"summary\">No spans yet. Load Connections or Data, then come back.</p></section>")
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
                         (render-page (status/all-checks driver))))
          "/status.edn" (respond exchange 200 "application/edn; charset=utf-8"
                                 (with-out-str (pprint/pprint (status/all-checks driver))))
          "/data" (respond exchange 200 "text/html; charset=utf-8"
                           (telemetry/span "GET /data" {}
                             (render-data (mapv #(answer driver %) data-questions))))
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
