(ns query-ui.backend
  "What answers a statement. One protocol, two implementations: `fixture`
  has canned answers and touches no network; `live` runs the statement on
  the instance .env names, through the Neo4j Java driver.

  `run-editor-text` is what the page's Run calls: it splits the editor's
  text into statements and returns the data one frame is drawn from."
  (:require [clojure.string :as str]
            [workshop.diagram :as diagram]
            [workshop.env :as env]
            [workshop.neo4j :as neo4j]))

(defprotocol Backend
  (backend-name [backend]
    "\"fixture\" or \"live\"; the page prints it.")
  (run-statement [backend statement]
    "One statement in, one outcome out:
     {:status :success :columns [..] :rows [{..}] :started-ms n :completed-ms n}
     or {:status :error :code \"Neo...\" :message \"...\"}.")
  (close-backend [backend]))

;; ---------------------------------------------------------------- fixture

(defn normalize-statement
  "A statement as the fixture looks it up: trimmed, without its final
  semicolon, whitespace collapsed, lower case."
  [statement]
  (-> statement
      str/trim
      (str/replace #";\s*$" "")
      (str/replace #"\s+" " ")
      str/lower-case))

(def fixture-answers
  "Statement to canned outcome. Timings are fixed so the status line of a
  fixture frame is the same text on every run. :latency-ms is how long the
  fixture sleeps before answering, so that the running state can be seen."
  {"RETURN 1 AS ok"
   {:columns [:ok] :rows [{:ok 1}] :started-ms 3 :completed-ms 4 :latency-ms 20}

   "MATCH (n) RETURN count(n) AS nodes"
   {:columns [:nodes] :rows [{:nodes 2360}] :started-ms 12 :completed-ms 13 :latency-ms 20}

   "MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS count ORDER BY label"
   {:columns [:label :count]
    :rows [{:label "Company" :count 2251}
           {:label "Manager" :count 28}
           {:label "Product" :count 36}]
    :started-ms 46 :completed-ms 49 :latency-ms 40}

   "MATCH (m:Manager) RETURN m LIMIT 2"
   {:columns [:m]
    :rows [{:m {:labels ["Manager"] :properties {:name "FIXTURE CAPITAL LLC"}}}
           {:m {:labels ["Manager"] :properties {:name "CANNED PARTNERS LP"}}}]
    :started-ms 30 :completed-ms 31 :latency-ms 30}

   ;; Rows in the shape workshop.diagram draws, so this one has a Graph view.
   "MATCH (home {id: 'Home'})-[r]->(member) RETURN home.id AS source, 'Segment' AS sourceLabel, type(r) AS relationship, member.id AS target, 'Product' AS targetLabel"
   {:columns [:source :sourceLabel :relationship :target :targetLabel]
    :rows [{:source "Home" :sourceLabel "Segment" :relationship "PRODUCT"
            :target "Homepod" :targetLabel "Product"}
           {:source "Home" :sourceLabel "Segment" :relationship "PRODUCT"
            :target "Homepod Mini" :targetLabel "Product"}
           {:source "Home" :sourceLabel "Segment" :relationship "PRODUCT"
            :target "Apple Tv 4K" :targetLabel "Product"}]
    :started-ms 44 :completed-ms 49 :latency-ms 40}

   "CREATE (:Scratch {name: 'bombadil'})"
   {:columns [] :rows [] :started-ms 8 :completed-ms 8 :latency-ms 20}

   "MATCH (s:Scratch) DETACH DELETE s"
   {:columns [] :rows [] :started-ms 9 :completed-ms 9 :latency-ms 20}

   ;; Not Cypher the instance has: a fixture-only statement that answers
   ;; slowly, so a frame stays in its running state long enough to be seen.
   "CALL fixture.slow()"
   {:columns [:waited] :rows [{:waited 400}] :started-ms 400 :completed-ms 401 :latency-ms 400}

   "RETURN 1/0 AS impossible"
   {:error {:code "Neo.ClientError.Statement.ArithmeticError"
            :message "/ by zero"}
    :latency-ms 20}})

(def ^:private fixture-index
  (into {} (map (fn [[statement answer]] [(normalize-statement statement) answer]))
        fixture-answers))

(defn fixture-outcome
  "The canned outcome for a statement, without the sleep. Anything the
  fixture does not know is a syntax error, which is what the instance says
  to text that is not Cypher."
  [statement]
  (let [answer (get fixture-index (normalize-statement statement))]
    (cond
      (nil? answer)
      {:status :error
       :code "Neo.ClientError.Statement.SyntaxError"
       :message (str "The fixture has no answer for: "
                     (subs statement 0 (min 80 (count statement))))}

      (:error answer)
      (assoc (:error answer) :status :error)

      :else
      (-> answer (dissoc :latency-ms) (assoc :status :success)))))

(defrecord FixtureBackend []
  Backend
  (backend-name [_] "fixture")
  (run-statement [_ statement]
    (let [latency-ms (get-in fixture-index [(normalize-statement statement) :latency-ms] 20)]
      (Thread/sleep (long latency-ms))
      (fixture-outcome statement)))
  (close-backend [_] nil))

(defn fixture-backend [] (->FixtureBackend))

;; ------------------------------------------------------------------- live

(defn- error-code
  [^Throwable failure]
  (if (instance? org.neo4j.driver.exceptions.Neo4jException failure)
    (.code ^org.neo4j.driver.exceptions.Neo4jException failure)
    (.getName (class failure))))

(defrecord LiveBackend [driver]
  Backend
  (backend-name [_] "live")
  (run-statement [_ statement]
    (let [started-nanos (System/nanoTime)]
      (try
        (let [rows (neo4j/query driver statement)
              elapsed-ms (quot (- (System/nanoTime) started-nanos) 1000000)]
          {:status :success
           ;; workshop.neo4j/query returns maps, which lose the column order
           ;; of the RETURN clause; the first row's keys stand in for it.
           :columns (vec (keys (first rows)))
           :rows rows
           ;; The driver call returns when every record has arrived, so one
           ;; measurement serves for both numbers in the status line.
           :started-ms elapsed-ms
           :completed-ms elapsed-ms})
        (catch Exception failure
          {:status :error
           :code (error-code failure)
           :message (or (ex-message failure) (str failure))}))))
  (close-backend [_] (.close ^java.lang.AutoCloseable driver)))

(def repository-dotenv
  "This directory is two below the repository root, where .env is."
  "../../.env")

(defn live-backend
  "A backend on the instance .env names. Throws if the variables are unset."
  ([] (live-backend repository-dotenv))
  ([dotenv-path]
   (->LiveBackend (neo4j/connect (env/load-env dotenv-path)))))

(defn open-backend
  [backend-kind]
  (case (name backend-kind)
    "fixture" (fixture-backend)
    "live" (live-backend)))

;; ------------------------------------------------------------ frame data

(defn split-statements
  "The statements in the editor's text. Unlike workshop.neo4j/statements this
  splits at every semicolon outside a quoted string, not only at a line's
  end: the stray `thi` of finding 16 followed its semicolon on the same line
  and the Query tool ran it as a second statement."
  [editor-text]
  (loop [remaining (seq editor-text)
         open-quote nil
         current-statement []
         finished-statements []]
    (if-let [character (first remaining)]
      (cond
        (and open-quote (= character \\) (next remaining))
        (recur (nnext remaining) open-quote
               (conj current-statement character (second remaining))
               finished-statements)

        open-quote
        (recur (next remaining)
               (when-not (= character open-quote) open-quote)
               (conj current-statement character)
               finished-statements)

        (#{\' \" \`} character)
        (recur (next remaining) character (conj current-statement character)
               finished-statements)

        (= character \;)
        (recur (next remaining) nil []
               (conj finished-statements (apply str current-statement)))

        :else
        (recur (next remaining) nil (conj current-statement character)
               finished-statements))
      (->> (conj finished-statements (apply str current-statement))
           (map str/trim)
           (remove str/blank?)
           vec))))

(defn status-line
  "The sentence under a successful result, worded as the Query tool words it."
  [{:keys [rows started-ms completed-ms]}]
  (let [record-count (count rows)]
    (format "Started streaming %d record%s after %d ms and completed after %d ms."
            record-count (if (= 1 record-count) "" "s") started-ms completed-ms)))

(def ^:private edge-row-keys #{:source :relationship :target})

(defn graph-text
  "Mermaid for rows that are edges in workshop.diagram's shape, else nil.
  This is the whole of the replica's Graph view."
  [rows]
  (when (and (seq rows)
             (every? (fn [row] (and (map? row) (every? #(string? (get row %)) edge-row-keys)))
                     rows))
    (diagram/edges->mermaid rows)))

(defn- decorate
  [outcome]
  (if (= :success (:status outcome))
    (assoc outcome
           :status-line (status-line outcome)
           :graph (graph-text (:rows outcome)))
    outcome))

(defn frame-data
  "What one Run produces. For one statement, :kind :single and its outcome.
  For several, :kind :multi and a row per statement: they run in order, and
  after the first that fails the rest are :aborted without being sent. Text
  that is only semicolons is an error of its own. Blank text gives nil: no
  frame."
  [backend editor-text]
  (let [statements (split-statements editor-text)]
    (case (count statements)
      ;; Text with no statement in it, such as ";;". Bombadil found the
      ;; first version answering nil here, which the page drew as an error
      ;; frame with nothing in it (README, Log 10).
      0 (when-not (str/blank? editor-text)
          {:kind :single
           :command (str/trim editor-text)
           :outcome {:status :error
                     :code "Replica.NoStatement"
                     :message (str "Nothing to run in: " (str/trim editor-text))}})
      1 {:kind :single
         :command (str/trim editor-text)
         :outcome (decorate (run-statement backend (first statements)))}
      {:kind :multi
       :command (str/trim editor-text)
       :statements
       (loop [remaining statements
              statement-number 1
              failed? false
              statement-rows []]
         (if-let [statement (first remaining)]
           (let [outcome (if failed?
                           {:status :aborted}
                           (decorate (run-statement backend statement)))]
             (recur (next remaining)
                    (inc statement-number)
                    (or failed? (= :error (:status outcome)))
                    (conj statement-rows {:number statement-number
                                          :statement statement
                                          :outcome outcome})))
           statement-rows))})))
