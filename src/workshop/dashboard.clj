(ns workshop.dashboard
  "A status page on 127.0.0.1, rendered on the server from workshop.status.
  The JDK's own HTTP server; the page reloads itself. Loopback only: it
  shows the instance name and the lab browser's tab titles."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.neo4j :as neo4j]
            [workshop.status :as status])
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
   td:last-child { font-variant-numeric: tabular-nums; }")

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

(defn render-page
  [checks]
  (let [down-count (count (filter #(= :down (:state %)) checks))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
         "<meta http-equiv=\"refresh\" content=\"" refresh-seconds "\">"
         "<title>Workshop status</title><style>" stylesheet "</style></head><body>"
         "<header><h1>Neo4j and Google workshop: connections</h1><span>"
         (if (zero? down-count) "nothing down" (str down-count " down"))
         ", checked " (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss"))
         ", reloads every " refresh-seconds " s</span></header><main>"
         (str/join (map render-check checks))
         "</main></body></html>")))

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
                       (render-page (status/all-checks driver)))
          "/status.edn" (respond exchange 200 "application/edn; charset=utf-8"
                                 (with-out-str (pprint/pprint (status/all-checks driver))))
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
