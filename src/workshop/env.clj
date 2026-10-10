(ns workshop.env
  "Connection details, from .env and the process environment.

  .env is the same file direnv and the Makefile read: plain KEY=value lines,
  quotes around a value with spaces. A value in the file wins over one in the
  environment, as it does in the Makefile.

  WORKSHOP_ENV names another file to read in place of .env: the way to
  point every command at a different server without editing the lab's
  credentials, e.g. WORKSHOP_ENV=local-graph.env bb dashboard."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- unquote-value
  [raw-value]
  (let [trimmed (str/trim raw-value)]
    (if-let [[_ inner] (or (re-matches #"\"(.*)\"" trimmed)
                           (re-matches #"'(.*)'" trimmed))]
      inner
      trimmed)))

(defn parse-dotenv
  "A map of name to value from dotenv text. Blank lines and # comments are
  skipped; a value is taken whole, so `A=My instance` keeps its space."
  [dotenv-text]
  (into {}
        (keep (fn [line]
                (when-let [[_ variable-name raw-value]
                           (re-matches #"\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)" line)]
                  [variable-name (unquote-value raw-value)])))
        (str/split-lines dotenv-text)))

(def default-ports
  "The ports the main checkout's servers listen on, which the graph profiles
  and the notes name."
  {"DASHBOARD_PORT" 8377 "FILINGS_PORT" 7688 "PAPERS_PORT" 7689 "BOOKS_PORT" 7690 "TRANSIT_PORT" 7691})

(defn assigned-ports
  "The ports .env.ports gives this checkout, by variable; empty without the
  file. `bb ports claim` writes it in a git worktree, so that two checkouts
  do not share a server (see `workshop.ports`)."
  ([] (assigned-ports ".env.ports"))
  ([ports-path]
   (let [ports-file (io/file ports-path)]
     (if (.exists ports-file)
       (into {}
             (keep (fn [[variable-name value]]
                     (when (and (default-ports variable-name) (re-matches #"\d+" value))
                       [variable-name (parse-long value)])))
             (parse-dotenv (slurp ports-file)))
       {}))))

(defn local-uri
  "A URI with its port replaced by this checkout's, when it names one of the
  main checkout's ports on this machine. Any other URI is returned as it is:
  a hosted instance is not a port of ours."
  [uri assigned]
  (if-let [[_ before port after] (re-matches #"(.*://(?:localhost|127\.0\.0\.1):)(\d+)(.*)" (str uri))]
    (let [variable-name (some (fn [[candidate default-port]] (when (= default-port (parse-long port)) candidate))
                              default-ports)]
      (str before (get assigned variable-name port) after))
    uri))

(defn load-env
  "The process environment with .env laid over it. Empty values are dropped.
  A NEO4J_URI on this machine takes this checkout's port for that graph."
  ([] (load-env (or (System/getenv "WORKSHOP_ENV") ".env")))
  ([dotenv-path]
   (let [dotenv-file (io/file dotenv-path)
         from-file (if (.exists dotenv-file) (parse-dotenv (slurp dotenv-file)) {})
         merged (into {}
                      (remove (comp str/blank? val))
                      (merge (into {} (System/getenv)) from-file))]
     (cond-> merged
       (merged "NEO4J_URI") (update "NEO4J_URI" local-uri (assigned-ports))))))

(defn require-vars
  "The values of variable-names from env, or an exception naming the missing
  ones. Never puts a value in the message."
  [env variable-names]
  (let [missing (remove env variable-names)]
    (when (seq missing)
      (throw (ex-info (str (str/join ", " missing)
                           " not set. gmake .env, then fill it in.")
                      {:missing (vec missing)})))
    (select-keys env variable-names)))

(comment
 (parse-dotenv "A=1\nB=\"two words\"\n# no\nC=")
 ;; names only: the values include the lab password
 (sort (filter #(re-find #"^NEO4J" %) (keys (load-env))))
 (sort (filter #(re-find #"^NEO4J" %) (keys (load-env "books-graph.env"))))
 (get (load-env "books-graph.env") "NEO4J_URI")
 (require-vars {"A" "1"} ["A" "B"])   ; throws, naming B and no value
:review)
