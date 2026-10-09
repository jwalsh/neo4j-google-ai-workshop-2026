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

(defn load-env
  "The process environment with .env laid over it. Empty values are dropped."
  ([] (load-env (or (System/getenv "WORKSHOP_ENV") ".env")))
  ([dotenv-path]
   (let [dotenv-file (io/file dotenv-path)
         from-file (if (.exists dotenv-file) (parse-dotenv (slurp dotenv-file)) {})]
     (into {}
           (remove (comp str/blank? val))
           (merge (into {} (System/getenv)) from-file)))))

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
