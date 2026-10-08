(ns workshop.env
  "Connection details, from .env and the process environment.

  .env is the same file direnv and the Makefile read: plain KEY=value lines,
  quotes around a value with spaces. A value in the file wins over one in the
  environment, as it does in the Makefile."
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
  ([] (load-env ".env"))
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
