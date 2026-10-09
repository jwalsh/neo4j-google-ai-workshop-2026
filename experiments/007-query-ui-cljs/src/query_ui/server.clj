(ns query-ui.server
  "The replica's page and its one endpoint, on 127.0.0.1, from the JDK's own
  HTTP server (as workshop.dashboard does it).

  GET  /            the page: a shell the ClojureScript fills in
  GET  /projects/replica/studio/query
                    the same page, at a path shaped like the real tool's,
                    for a specification that checks the path
  GET  /js/main.js  the compiled front end (clojure -M:cljs)
  GET  /api/backend {\"backend\": \"fixture\"}
  POST /api/run     body is the editor's text; answers the frame's data"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [query-ui.backend :as backend])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(def default-port 8378)

(def compiled-script-path "scratch/public/js/main.js")

(def page-paths #{"/" "/projects/replica/studio/query"})

(def ^:private stylesheet
  ":root { --page: #f6f7f8; --card: #ffffff; --ink: #1c2126; --muted: #66707a;
          --line: #dfe3e7; --good: #1a7f4b; --bad: #b3261e; --accent: #2b5fa8; }
   @media (prefers-color-scheme: dark) {
     :root { --page: #14171a; --card: #1d2125; --ink: #e6e9ec; --muted: #98a2ac;
             --line: #2e343a; --good: #57c38a; --bad: #f2867e; --accent: #8ab4f0; } }
   * { box-sizing: border-box; }
   body { margin: 0; padding: 16px; background: var(--page); color: var(--ink);
          font: 14px/1.45 system-ui, sans-serif; }
   header { display: flex; flex-wrap: wrap; gap: 12px; align-items: baseline;
            max-width: 960px; margin: 0 auto 12px; }
   h1 { font-size: 16px; margin: 0; }
   header span { color: var(--muted); font-size: 13px; }
   [data-testid=query-app] { max-width: 960px; margin: 0 auto; }
   [data-testid=main-editor] { display: flex; gap: 8px; align-items: flex-start;
          background: var(--card); border: 1px solid var(--line); border-radius: 6px;
          padding: 8px; }
   textarea { flex: 1; min-height: 64px; resize: vertical; border: 0; outline: 0;
          background: transparent; color: var(--ink);
          font: 13px/1.4 ui-monospace, Menlo, monospace; }
   button { font: inherit; color: var(--ink); background: var(--card);
          border: 1px solid var(--line); border-radius: 4px; padding: 3px 10px;
          cursor: pointer; }
   [data-testid=run-button] { color: var(--card); background: var(--accent);
          border-color: var(--accent); }
   [data-testid=frame] { background: var(--card); border: 1px solid var(--line);
          border-radius: 6px; margin-top: 10px; padding: 8px; }
   .frame-head { display: flex; gap: 8px; align-items: flex-start; }
   [data-testid=frame-command] { flex: 1; font: 13px/1.4 ui-monospace, Menlo, monospace;
          white-space: pre-wrap; overflow-wrap: anywhere; }
   .prompt { color: var(--muted); margin-right: 6px; }
   [role=tablist] { display: flex; gap: 4px; margin: 8px 0; }
   [role=tab][aria-selected=true] { border-color: var(--accent); color: var(--accent); }
   [role=tab][aria-disabled=true] { color: var(--muted); cursor: default; }
   table, [role=table] { width: 100%; border-collapse: collapse; font-size: 13px; }
   th, td { text-align: left; padding: 3px 8px 3px 0; border-top: 1px solid var(--line);
          vertical-align: top; overflow-wrap: anywhere; }
   [role=table] { display: table; } [role=rowgroup] { display: table-row-group; }
   [role=row] { display: table-row; }
   [role=columnheader], [role=cell] { display: table-cell; padding: 3px 8px 3px 0;
          border-top: 1px solid var(--line); }
   [role=columnheader] { font-weight: 600; }
   pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere;
         font: 12px/1.4 ui-monospace, Menlo, monospace; }
   [data-testid=simple-frame-status-bar-right] { display: block; margin-top: 8px;
          color: var(--muted); font-size: 12px; }
   [data-testid=frame-error] { color: var(--bad); }
   [data-testid=frame-spinner] { color: var(--muted); }
   .status-success { color: var(--good); } .status-error { color: var(--bad); }
   .status-aborted { color: var(--muted); }")

(defn page
  "The shell. Everything inside query-app is drawn by query-ui.app. The
  back end's name is in the markup as well as in /api/backend, so the page
  says which one it is on before any script has run."
  [backend-name]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>Query replica (" backend-name ")</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       "<style>" stylesheet "</style></head><body>"
       "<header><h1>Query replica</h1>"
       "<span>back end: <strong data-testid=\"backend-name\">" backend-name "</strong></span>"
       "<span>experiments/007-query-ui-cljs; ours, not Aura's</span></header>"
       "<div data-testid=\"query-app\" data-backend=\"" backend-name "\"></div>"
       "<script src=\"/js/main.js\"></script>"
       "</body></html>"))

(defn- respond
  [^HttpExchange exchange status-code content-type ^String body]
  (let [body-bytes (.getBytes body "UTF-8")]
    (.add (.getResponseHeaders exchange) "Content-Type" content-type)
    (.add (.getResponseHeaders exchange) "Cache-Control" "no-store")
    (.sendResponseHeaders exchange status-code (alength body-bytes))
    (with-open [response-body (.getResponseBody exchange)]
      (.write response-body body-bytes))))

(defn run-response
  "The JSON text POST /api/run answers for the editor's text. Callable from
  the REPL with no server running."
  [active-backend editor-text]
  (json/write-str {:frame (backend/frame-data active-backend editor-text)}))

(defn- handler
  [backend-atom]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [request-path (.getPath (.getRequestURI exchange))
              request-method (.getRequestMethod exchange)
              active-backend @backend-atom]
          (case [request-method (if (page-paths request-path) "/" request-path)]
            ["GET" "/"]
            (respond exchange 200 "text/html; charset=utf-8"
                     (page (backend/backend-name active-backend)))

            ["GET" "/js/main.js"]
            (let [compiled-script (io/file compiled-script-path)]
              (if (.exists compiled-script)
                (respond exchange 200 "text/javascript; charset=utf-8" (slurp compiled-script))
                (respond exchange 503 "text/javascript; charset=utf-8"
                         "console.error('front end not compiled: clojure -M:cljs');")))

            ["GET" "/api/backend"]
            (respond exchange 200 "application/json"
                     (json/write-str {:backend (backend/backend-name active-backend)}))

            ["POST" "/api/run"]
            (respond exchange 200 "application/json"
                     (run-response active-backend (slurp (.getRequestBody exchange) :encoding "UTF-8")))

            (respond exchange 404 "text/plain" "not found\n")))
        (catch Exception failure
          (respond exchange 500 "text/plain" (str (ex-message failure) "\n")))))))

(defonce ^{:doc "The running server, or nil: {:server :backend-atom :port}."}
  running (atom nil))

(defn stop!
  []
  (when-let [{:keys [^HttpServer server backend-atom]} @running]
    (.stop server 0)
    (backend/close-backend @backend-atom)
    (reset! running nil)
    :stopped))

(defn start!
  "Starts the replica on loopback. backend-kind is :fixture (the default) or
  :live. Stops a server that is already running."
  ([] (start! :fixture default-port))
  ([backend-kind] (start! backend-kind default-port))
  ([backend-kind port]
   (stop!)
   (let [backend-atom (atom (backend/open-backend backend-kind))
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     (.createContext server "/" (handler backend-atom))
     ;; More than one thread: a slow statement must not hold up the next
     ;; request, or a spinner would never be seen beside a finished frame.
     (.setExecutor server (Executors/newFixedThreadPool 4))
     (.start server)
     (reset! running {:server server :backend-atom backend-atom :port port})
     (str "http://127.0.0.1:" port "/ on " (name backend-kind)))))

(defn switch-backend!
  "Changes the back end of the running server in place. Reload the page:
  it prints the back end it was served with."
  [backend-kind]
  (let [{:keys [backend-atom]} (or @running (throw (ex-info "not running; (start!)" {})))
        previous-backend @backend-atom]
    (reset! backend-atom (backend/open-backend backend-kind))
    (backend/close-backend previous-backend)
    (backend/backend-name @backend-atom)))

(defn -main
  [& [backend-kind port]]
  (println (start! (or backend-kind "fixture")
                   (if port (Integer/parseInt port) default-port)))
  @(promise))
