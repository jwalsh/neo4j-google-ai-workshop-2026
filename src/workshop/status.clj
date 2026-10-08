(ns workshop.status
  "One check per thing the work depends on. Each returns
  {:name :state :summary :details :millis}, where :state is :up, :down or
  :unknown, and never throws. No check puts a credential in its result."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.gcp :as gcp]
            [workshop.neo4j :as neo4j])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration OffsetDateTime)))

(def check-timeout-ms 10000)

;; The DevTools port the lab's own Chromium listens on, when it is running.
(def lab-browser-port 9333)

(def connection-variables
  ["NEO4J_URI" "NEO4J_USERNAME" "NEO4J_PASSWORD" "NEO4J_DATABASE"
   "GOOGLE_CLOUD_PROJECT" "GOOGLE_CLOUD_LOCATION"])

(defn- run-check
  "Runs check-fn, which returns {:state :summary :details}, with a time
  limit. A throw or a timeout is :down with the reason as the summary."
  [check-name check-fn]
  (let [started-at (System/nanoTime)
        pending (future (try (check-fn)
                             (catch Exception failure
                               {:state :down :summary (or (ex-message failure)
                                                          (str (class failure)))})))
        outcome (deref pending check-timeout-ms
                       {:state :down :summary (str "no answer in " check-timeout-ms " ms")})]
    (future-cancel pending)
    (assoc outcome
           :name check-name
           :millis (quot (- (System/nanoTime) started-at) 1000000))))

(defn- environment-check
  [env-map]
  (let [unset (remove env-map connection-variables)]
    {:state (if (every? env-map ["NEO4J_URI" "NEO4J_USERNAME" "NEO4J_PASSWORD"]) :up :down)
     :summary (if (seq unset) (str "unset: " (str/join ", " unset)) "all set")
     :details (mapv (fn [variable-name]
                      [variable-name (if (env-map variable-name) "set" "unset")])
                    connection-variables)}))

(defn- lab-expiry-check
  "LAB_EXPIRES_AT is an ISO-8601 time with an offset, written by hand from
  the lab page's countdown."
  [env-map]
  (if-let [expires-at-text (env-map "LAB_EXPIRES_AT")]
    (let [expires-at (OffsetDateTime/parse expires-at-text)
          minutes-left (.toMinutes (Duration/between (OffsetDateTime/now) expires-at))]
      {:state (if (pos? minutes-left) :up :down)
       :summary (if (pos? minutes-left)
                  (format "%dh %02dm left" (quot minutes-left 60) (rem minutes-left 60))
                  "expired")
       :details [["expires" expires-at-text]]})
    {:state :unknown :summary "LAB_EXPIRES_AT not set"}))

(defn- neo4j-check
  [driver env-map]
  (let [[{:keys [name versions edition]}]
        (neo4j/query driver "CALL dbms.components() YIELD name, versions, edition")
        databases (neo4j/databases driver)
        {:keys [nodes relationships]} (neo4j/counts driver)
        node-total (reduce + (map :count nodes))
        relationship-total (reduce + (map :count relationships))]
    {:state :up
     :summary (format "%s %s %s, %d nodes, %d relationships"
                      name (first versions) edition node-total relationship-total)
     :details (concat
               [["instance" (str (env-map "AURA_INSTANCENAME" "?")
                                 " (" (env-map "AURA_INSTANCEID" "?") ")")]]
               (map (fn [{:keys [name currentStatus default]}]
                      [(str "database " name) (str currentStatus (when default ", default"))])
                    databases)
               (map (fn [{:keys [label count]}] [(str "(:" label ")") (str count)]) nodes)
               (map (fn [{:keys [type count]}] [(str "[:" type "]") (str count)]) relationships))}))

(defn- datasets-check
  []
  (let [objects (gcp/list-objects (gcp/public-storage))]
    {:state (if (seq objects) :up :down)
     :summary (str (count objects) " entries under gs://" gcp/dataset-bucket "/" gcp/dataset-prefix
                   ", read without credentials")
     :details (mapv (fn [{:keys [name size]}]
                      [(subs name (count gcp/dataset-prefix))
                       (if (str/ends-with? name "/") "directory" (format "%,d bytes" size))])
                    objects)}))

(defn- http-get
  [url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 3))
                    (.build))]
    (.body (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString)))))

(defn- lab-browser-check
  "The DevTools endpoint answers JSON; the two fields wanted are pulled out
  with a pattern, which saves a JSON dependency."
  []
  (let [devtools-url (str "http://127.0.0.1:" lab-browser-port)
        version-json (try (http-get (str devtools-url "/json/version"))
                          (catch java.io.IOException _ nil))]
    (if-not version-json
      {:state :down :summary (str "nothing on 127.0.0.1:" lab-browser-port)}
      (let [tab-titles (->> (re-seq #"(?s)\{.*?\}" (http-get (str devtools-url "/json/list")))
                            (filter #(re-find #"\"type\":\s*\"page\"" %))
                            (keep #(second (re-find #"\"title\":\s*\"((?:[^\"\\]|\\.)*)\"" %))))]
        {:state :up
         :summary (str (second (re-find #"\"Browser\":\s*\"([^\"]+)\"" version-json))
                       ", " (count tab-titles) " tabs")
         :details (map-indexed (fn [tab-index tab-title] [(str "tab " (inc tab-index)) tab-title])
                               tab-titles)}))))

(defn- gcloud-check
  "The lab is driven in its own browser so that this stays the owner's
  business: unset is the intended state during the lab, not a fault."
  []
  (let [config-value (fn [property-name]
                       (let [value (str/trim (:out (shell/sh "gcloud" "config" "get-value"
                                                             property-name)))]
                         (if (str/blank? value) "unset" value)))
        account (config-value "account")
        project (config-value "project")]
    {:state :unknown
     :summary (str "account " account ", project " project)
     :details [["note" "the lab account lives in the lab browser only"]]}))

(defn checklist
  "Assignments from the org checklist as {:heading :state :ticked :total}."
  [checklist-text]
  (->> (str/split-lines checklist-text)
       (reduce (fn [assignments line]
                 (if-let [[_ todo-state heading] (re-matches #"\*\*\* (TODO|DONE) (.*)" line)]
                   (conj assignments {:heading heading :state todo-state :ticked 0 :total 0})
                   (if-let [[_ box] (and (seq assignments)
                                         (re-matches #"- \[([ Xx-])\] .*" line))]
                     (cond-> (update-in assignments [(dec (count assignments)) :total] inc)
                       (#{"X" "x"} box)
                       (update-in [(dec (count assignments)) :ticked] inc))
                     assignments)))
               [])))

(defn- checklist-check
  []
  (let [assignments (checklist (slurp (io/file "README.org")))
        ticked (reduce + (map :ticked assignments))
        total (reduce + (map :total assignments))]
    {:state :unknown
     :summary (format "%d of %d boxes ticked" ticked total)
     :details (mapv (fn [{:keys [heading ticked total]}] [heading (str ticked "/" total)])
                    assignments)}))

(defn- repository-check
  []
  (let [git (fn [& arguments] (str/trim (:out (apply shell/sh "git" arguments))))
        uncommitted (count (remove str/blank? (str/split-lines (git "status" "--short"))))]
    {:state :unknown
     :summary (format "%s at %s, %d uncommitted" (git "branch" "--show-current")
                      (git "rev-parse" "--short" "HEAD") uncommitted)
     :details [["last commit" (git "log" "-1" "--format=%s")]]}))

(defn all-checks
  "Every check, run side by side. driver may be nil, in which case one is
  opened and closed for the Neo4j check."
  ([] (all-checks nil))
  ([driver]
   (let [env-map (env/load-env)
         with-driver (fn [driver-fn]
                       (if driver
                         (driver-fn driver)
                         (with-open [fresh-driver (neo4j/connect env-map)]
                           (driver-fn fresh-driver))))]
     (->> [["Neo4j Aura" #(with-driver (fn [open-driver] (neo4j-check open-driver env-map)))]
           ["Dataset bucket" datasets-check]
           ["Lab browser" lab-browser-check]
           ["Lab time" #(lab-expiry-check env-map)]
           ["Environment" #(environment-check env-map)]
           ["gcloud" gcloud-check]
           ["Checklist" checklist-check]
           ["Repository" repository-check]]
          (mapv (fn [[check-name check-fn]] (future (run-check check-name check-fn))))
          (mapv deref)))))
