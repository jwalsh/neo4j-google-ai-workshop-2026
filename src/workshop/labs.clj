(ns workshop.labs
  "The labs again, by code where code can do them and by name where it
  cannot. Each step returns

    {:lab 4 :title \"...\" :status :ok :evidence {...} :how \"...\"}

  with :status one of :ok, :skipped, :blocked, :failed.

    :ok       ran here and the result was checked
    :skipped  could run from here and was deliberately not
    :blocked  cannot run from here; :how names the endpoint and what is missing
    :failed   ran and the check did not hold, or threw

  Nothing here deletes or overwrites. The one write a step can reach is
  workshop.form13/load!, and only through `lab-4` with :allow-load? true.
  Without that option every statement sent is a read, and a database that
  does not match the file is reported and left alone.

  Made for a REPL: every step is a function that returns its map, with a
  no-argument form that opens its own driver, and the statements each one
  sends are in `statements` to run by hand. dev/labs_explore.clj walks
  them. `as-check` reshapes a run for workshop.status.

    (run-all)                          ; a vector of step maps
    (lab-5)                            ; one step
    (println (report (run-all)))       ; the same as text
    clojure -M -m workshop.labs        ; and from a shell

  notes/v2-cli-path.org is the prose beside this, with the documentation
  each :blocked step was read from."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.form13 :as form13]
            [workshop.neo4j :as neo4j]
            [workshop.telemetry :as telemetry]))

;; Pure parts

(def statuses #{:ok :skipped :blocked :failed})

(defn step
  "A result map. Throws on a status that is not one of the four, so a typo
  cannot print as a pass."
  [lab title status evidence how]
  (when-not (statuses status)
    (throw (ex-info (str "not a status: " status) {:lab lab :status status})))
  {:lab lab :title title :status status :evidence evidence :how how})

(defn scrub
  "text with every value from env-map that is long enough to be a secret
  replaced by its variable name. An exception from the driver can carry the
  host; a result map must not."
  [env-map text]
  (reduce (fn [scrubbed [variable-name value]]
            (if (and (string? value) (>= (count value) 8))
              (str/replace scrubbed value (str "<" variable-name ">"))
              scrubbed))
          (str text)
          (sort-by (comp - count str val) env-map)))

(def secret-variables
  "The .env names whose values `scrub` looks for. Not the whole process
  environment: a PATH entry is not a secret and is long."
  ["NEO4J_URI" "NEO4J_PASSWORD" "AURA_INSTANCEID" "GOOGLE_CLOUD_PROJECT"
   "LAB_EMAIL" "LAB_PASSWORD" "LAB_EMPLOYEE_ID"
   "AURA_CLIENT_ID" "AURA_CLIENT_SECRET" "AURA_PROJECT_ID"])

(defn secrets-of
  "The part of env-map `scrub` should cover, with the URI's host and the
  host's first label added, since a driver error names the host alone."
  [env-map]
  (let [chosen (select-keys env-map secret-variables)
        host (some-> (get chosen "NEO4J_URI") (str/split #"://") last)]
    (cond-> chosen
      host (assoc "NEO4J_HOST" host))))

(def expected-databases
  "From the lab text, Lab 3: two databases, neo4j the default."
  #{{:name "neo4j" :currentStatus "online" :default true}
    {:name "system" :currentStatus "online" :default false}})

(defn databases-as-expected?
  [database-rows]
  (= expected-databases
     (set (map #(select-keys % [:name :currentStatus :default]) database-rows))))

(def expected-constraints
  "Lab 4's two node keys, as SHOW CONSTRAINTS reports them."
  #{{:name "unique_company_id" :type "NODE_KEY" :labelsOrTypes ["Company"] :properties ["cusip"]}
    {:name "unique_manager" :type "NODE_KEY" :labelsOrTypes ["Manager"] :properties ["managerName"]}})

(defn missing-constraints
  [constraint-rows]
  (let [present (set (map #(select-keys % [:name :type :labelsOrTypes :properties])
                          constraint-rows))]
    (vec (sort (map :name (set/difference expected-constraints present))))))

(defn empty-of-lab-4?
  "True when the database holds none of what Lab 4 loads."
  [{:keys [companies managers owns]}]
  (every? #(zero? (or % 0)) [companies managers owns]))

(defn lab-4-decision
  "What to do about the load, from a verification and whether loading was
  asked for. :verify-only leaves the database as it is."
  [{:keys [loaded? actual]} allow-load?]
  (cond
    loaded? :already-loaded
    (and allow-load? (empty-of-lab-4? actual)) :load
    (empty-of-lab-4? actual) :empty-and-not-asked
    :else :mismatch))

(defn degree-map
  "Rows as a map of key-name to count-name, for comparing two sources."
  [rows key-name count-name]
  (into {} (map (juxt key-name count-name)) rows))

(defn compare-degrees
  "Where two name-to-degree maps differ. :agree? is true when they are the
  same map; :differences lists up to ten names with both answers."
  [file-degrees graph-degrees]
  (let [names (sort (set/union (set (keys file-degrees)) (set (keys graph-degrees))))
        differences (for [entity-name names
                          :let [from-file (get file-degrees entity-name)
                                from-graph (get graph-degrees entity-name)]
                          :when (not= from-file from-graph)]
                      {:name entity-name :file from-file :graph from-graph})]
    {:agree? (empty? differences)
     :compared (count names)
     :differences (vec (take 10 differences))}))

(defn company-degrees-from-file
  "Every CUSIP with how many distinct managers hold it."
  [holdings]
  (into {}
        (map (fn [[cusip positions]] [cusip (count (distinct (map :manager positions)))]))
        (group-by :cusip holdings)))

(def aura-api
  "The Aura API, from its documentation (URLs in notes/v2-cli-path.org).
  Field names are the API's: a project is still tenant_id there."
  {:token-url "https://api.neo4j.io/oauth/token"
   :base-url "https://api.neo4j.io/v1"})

(defn aura-instance-request
  "The request that would create an instance like the one Lab 2 made in the
  console. Data only: nothing here sends it. tenant-id is the Aura project,
  from GET /v1/tenants."
  [tenant-id instance-name]
  {:method "POST"
   :url (str (:base-url aura-api) "/instances")
   :headers {"Authorization" "Bearer <access_token>"
             "Content-Type" "application/json"
             "Accept" "application/json"}
   :body {:version "5"
          :region "us-east1"
          :memory "2GB"
          :name instance-name
          :type "professional-db"
          :tenant_id tenant-id
          :cloud_provider "gcp"}})

(def graph-analytics-statements
  "Lab 5's algorithm through the Cypher API for Aura Graph Analytics, which
  the instance lists (labs/audit/05-graph-analytics.cypher). Never run from
  here: the first statement starts a billed session. Stream mode writes
  nothing to the database."
  ["CALL gds.graph.project('lab5', ['Manager', 'Company'], 'OWNS', {memory: '4GB'}) YIELD nodeCount, relationshipCount"
   "CALL gds.degree.stream('lab5') YIELD nodeId, score RETURN gds.util.asNode(nodeId).managerName AS manager, score ORDER BY score DESC LIMIT 10"
   "CALL gds.session.list() YIELD name, memory, status"
   "CALL gds.session.delete('<session name from the list>') YIELD deleted"])

(defn overall
  "How many steps ended in each status."
  [results]
  (merge (zipmap [:ok :skipped :blocked :failed] (repeat 0))
         (frequencies (map :status results))))

(defn table-rows
  [results]
  (mapv (fn [{:keys [lab title status evidence]}]
          {:lab lab :step title :status (name status) :evidence (:summary evidence "")})
        results))

(def statements
  "Every statement the running steps send, by name, to evaluate one at a
  time: (workshop.neo4j/query driver (:lab-5/manager-degree statements)).
  All of them read."
  {:lab-3/databases "SHOW DATABASES YIELD name, currentStatus, default"
   :lab-4/counts (str "MATCH (c:Company) WITH count(c) AS companies "
                      "MATCH (m:Manager) WITH companies, count(m) AS managers "
                      "MATCH ()-[r:OWNS]->() RETURN companies, managers, count(r) AS owns")
   :lab-4/constraints "SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties"
   :lab-4/other-nodes (str "MATCH (n) WHERE NOT n:Company AND NOT n:Manager "
                           "UNWIND labels(n) AS label RETURN label, count(*) AS nodes ORDER BY label")
   :lab-4/other-relationships (str "MATCH ()-[r]->() WHERE type(r) <> 'OWNS' "
                                   "RETURN type(r) AS type, count(*) AS relationships ORDER BY type")
   :lab-4/other-indexes (str "SHOW INDEXES YIELD name, type, owningConstraint "
                             "WHERE type <> 'LOOKUP' AND owningConstraint IS NULL "
                             "RETURN name ORDER BY name")
   :lab-5/manager-degree (str "MATCH (m:Manager) OPTIONAL MATCH (m)-[o:OWNS]->(c:Company) "
                              "RETURN m.managerName AS manager, count(DISTINCT c) AS companies, count(o) AS degree")
   :lab-5/company-degree (str "MATCH (c:Company) OPTIONAL MATCH (c)<-[o:OWNS]-(m:Manager) "
                              "RETURN c.cusip AS cusip, c.companyName AS company, "
                              "count(DISTINCT m) AS managers, count(o) AS degree")
   :lab-6/stand-in (str "OPTIONAL MATCH (d:Document) WITH count(d) AS documents "
                        "OPTIONAL MATCH (c:Chunk) WITH documents, count(c) AS chunks "
                        "OPTIONAL MATCH (e:__Entity__) RETURN documents, chunks, count(e) AS entities")
   ;; labs/lab-06/01-home-category.cypher, less the columns not needed here.
   :lab-6/home (str "MATCH (category:__Entity__)-[]->(member:__Entity__) "
                    "WHERE toLower(category.id) CONTAINS 'home' "
                    "RETURN DISTINCT member.id AS member ORDER BY member")
   :lab-5/procedures "SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'gds.' RETURN name"
   :lab-5/sessions "CALL gds.session.list() YIELD name, memory, status RETURN name, memory, status"})

(defn as-check
  "A run in the shape workshop.status and the dashboard show:
  {:state :summary :details}. :down when a step failed, :up otherwise;
  blocked and skipped steps are the expected state, not a fault."
  [results]
  (let [totals (overall results)]
    {:state (if (pos? (:failed totals)) :down :up)
     :summary (str/join ", " (keep (fn [status]
                                     (when (pos? (totals status))
                                       (str (totals status) " " (name status))))
                                   [:ok :skipped :blocked :failed]))
     :details (mapv (fn [{:keys [lab title status evidence]}]
                      [(str "Lab " lab ", " title)
                       (str (name status) ": " (:summary evidence ""))])
                    results)}))

(defn- with-driver
  "Calls driver-fn with a driver opened from .env and closed after."
  [driver-fn]
  (with-open [driver (neo4j/connect)]
    (driver-fn driver)))

;; Steps that cannot run from here

(defn lab-1
  "Sign-in. A person's, by design."
  []
  (step 1 "Sign In" :blocked
        {:summary "no API: an interactive Google sign-in to an ephemeral Vocareum account"}
        (str "UI only. Google's sign-in takes a password and an Employee ID challenge and then "
             "terms to accept (finding 3); none of that has a service endpoint. The non-interactive "
             "equivalent is a service-account key for the lab project (gcloud auth "
             "activate-service-account --key-file), which Vocareum does not hand out. Not attempted: "
             "this machine's gcloud is the owner's and stays out of the lab.")))

(defn lab-2
  "Provisioning. The request is known; the credential to send it is not here."
  ([] (lab-2 (env/load-env)))
  ([env-map]
   (let [credential-names ["AURA_CLIENT_ID" "AURA_CLIENT_SECRET" "AURA_PROJECT_ID"]
         instance-request (aura-instance-request "<AURA_PROJECT_ID>" "My instance")]
     (step 2 "Deploy Neo4j" :blocked
           {:summary "Aura API POST /v1/instances; no client credentials here"
            :request (dissoc instance-request :headers)
            :credentials (into {} (map (fn [variable-name]
                                         [variable-name (if (env-map variable-name) "set" "unset")]))
                               credential-names)}
           (str "Create a client credential in the Aura console (Account settings, Client "
                "credentials, Aura API). POST " (:token-url aura-api) " with basic auth "
                "client_id:client_secret and grant_type=client_credentials for a one-hour bearer "
                "token. GET " (:base-url aura-api) "/tenants for the project id. Then the request in "
                ":evidence. The response carries the password, once. Poll GET /v1/instances/<id> "
                "until status is running, and do not resolve the hostname before then (finding 12). "
                "Not sent from here by instruction: the credential is made in the console, and an "
                "instance made this way lands in whichever organization the credential belongs to, "
                "which is the open question of findings 13 and 22. Nothing in this namespace sends it.")))))

(defn lab-6
  "Document Intelligence itself is the console's. With a driver, also reads
  what the stand-in left: Neo4j Labs' LLM Graph Builder, the tool the lab
  text places just before this one, driven through its API by
  workshop.graph-builder. The lab ends on the Home category holding the
  HomePod, the HomePod mini and Apple TV; that is looked for. Reads only:
  this step never calls the builder."
  ([] (lab-6 nil))
  ([driver]
   (let [stand-in (when driver (first (neo4j/query driver (:lab-6/stand-in statements))))
         home-members (when driver (mapv :member (neo4j/query driver (:lab-6/home statements))))
         home-found (filterv (fn [wanted] (some #(re-find wanted %) home-members))
                             [#"(?i)homepod mini" #"(?i)homepod(?! mini)" #"(?i)apple tv"])]
     (step 6 "Document Intelligence" :blocked
           {:summary (str "console only, and not enabled for this account (finding 22)"
                          (when (some-> stand-in :documents pos?)
                            (format "; Graph Builder stand-in: %d document, %d chunks, %d entities, %d of 3 under Home"
                                    (:documents stand-in) (:chunks stand-in) (:entities stand-in)
                                    (count home-found))))
            :pdf-fetched? (.exists (io/file "data/Apple_10-K_2025_2_pages.pdf"))
            :stand-in stand-in
            :home-members home-members}
           (str "Document Intelligence has no documented API. Neo4j's announcement lists API, "
                "command line and MCP access as what comes next, not what exists. It needs the "
                "preview on this organization, then a person in the console. The stand-in is "
                "scriptable and was run by another session: (workshop.graph-builder/run-lab-06!) "
                "sends the same PDF and prompt to the LLM Graph Builder's backend "
                "(experiments/005-graph-builder-api). That writes, so it is not called from here; "
                "what it left is counted in :evidence. It is not Document Intelligence, and the "
                "step stays blocked on that account.")))))

(defn lab-7
  []
  (step 7 "Deploy Neo4j Agent" :blocked
        {:summary "Google Cloud console as the lab account; marketplace add has no documented API"}
        (str "The lab adds a marketplace agent to a new Gemini Enterprise app. Google documents "
             "that path as console steps only. A custom A2A agent can be registered by REST: POST "
             "https://<location>-discoveryengine.googleapis.com/v1alpha/projects/<project>/locations/"
             "<location>/collections/default_collection/engines/<app>/assistants/default_assistant/agents "
             "with displayName, description and a2aAgentDefinition.jsonAgentCard. That needs a "
             "Google access token for the lab account with the Gemini Enterprise Admin role, an "
             "existing app, and the agent's card, which the marketplace listing does not publish. "
             "The lab account's token exists only in the lab browser.")))

(defn lab-8
  []
  (step 8 "Use Neo4j Agent" :blocked
        {:summary "a chat in Gemini Enterprise with an OAuth consent click; depends on Lab 7"}
        (str "The agent is authorized lazily, by a consent screen on the first prompt: a person's "
             "click. It talks to a shared Business Critical instance that is the instructor's, not "
             "the one in .env, so nothing in this repository can check its answers. Needs Lab 7 "
             "and the lab browser.")))

(defn lab-9
  []
  (step 9 "Aura Agents" :blocked
        {:summary "creation is console only; invoking is an API, with credentials not held here"}
        (str "\"Create with AI\" has no documented API; the nearest thing is importing an agent "
             "from JSON, also in the console. Once an agent exists and is set to External, it is "
             "one request: POST <agent endpoint> with a bearer token from "
             (:token-url aura-api) " and the body {\"input\": \"List managers investing in uncommon "
             "companies.\"}. Needs: the agent made in the console, External access (which is "
             "charged), and an Aura API client credential. The lab's question itself is answered "
             "in Cypher by the degree tables of Lab 5's step: uncommon is a company with few "
             "managers.")))

;; Steps that run

(defn lab-3
  "Connect and SHOW DATABASES."
  ([] (with-driver lab-3))
  ([driver]
   (let [answered? (neo4j/ping driver)
         database-rows (neo4j/query driver (:lab-3/databases statements))
         as-expected? (databases-as-expected? database-rows)]
     (step 3 "Connect to Neo4j" (if (and answered? as-expected?) :ok :failed)
           {:summary (str (count database-rows) " databases: "
                          (str/join ", " (map (fn [{:keys [name currentStatus default]}]
                                                (str name " " currentStatus (when default " default")))
                                              (sort-by :name database-rows))))
            :ping answered?
            :databases database-rows}
           "Bolt with the Java driver as user neo4j from .env, where the console's Query tool goes in by sign-on. bb ping, bb databases."))))

(defn contents-beyond-lab-4
  "What is in the database that Lab 4 did not put there: other labels and
  relationship types with their counts, and indexes no lab statement made.
  Reads only. Later labs add to this; it is reported, not judged."
  ([] (with-driver contents-beyond-lab-4))
  ([driver]
   {:other-nodes (degree-map (neo4j/query driver (:lab-4/other-nodes statements)) :label :nodes)
    :other-relationships (degree-map (neo4j/query driver (:lab-4/other-relationships statements))
                                     :type :relationships)
    :other-indexes (mapv :name (neo4j/query driver (:lab-4/other-indexes statements)))}))

(defn lab-4
  "The keyed load, verified against the file. With no options it only
  reads. With {:allow-load? true} it calls workshop.form13/load!, which
  runs nothing when the database already matches, and here is reached only
  for a matching database or one with nothing of Lab 4's in it."
  ([] (with-driver #(lab-4 % @form13/rows)))
  ([driver holdings] (lab-4 driver holdings {}))
  ([driver holdings {:keys [allow-load?]}]
   (let [before (form13/verify driver holdings)
         decision (lab-4-decision before allow-load?)
         ;; load! verifies again itself; for a matching database it takes
         ;; its already-loaded branch and sends nothing.
         after (if (and allow-load? (#{:already-loaded :load} decision))
                 (form13/load! driver holdings)
                 before)
         constraint-rows (neo4j/query driver (:lab-4/constraints statements))
         absent (missing-constraints constraint-rows)
         beyond (contents-beyond-lab-4 driver)
         {:keys [companies managers owns]} (:actual after)]
     (step 4 "Query" (if (and (:loaded? after) (empty? absent)) :ok :failed)
           {:summary (format "%s: %d companies, %d managers, %d OWNS; %d of 2 node keys%s"
                             (name (:action after decision)) companies managers owns
                             (- 2 (count absent))
                             (if (seq (:other-nodes beyond))
                               (str "; also here: " (str/join ", " (sort (keys (:other-nodes beyond)))))
                               ""))
            :decision decision
            :expected (:expected after)
            :actual (:actual after)
            :missing-constraints absent
            :beyond-lab-4 beyond}
           (case decision
             :already-loaded "The database matches the file, so there is nothing to load and nothing was sent but reads. bb form13 verify."
             :load "workshop.form13/load! ran labs/lab-04/03 to 06 into an empty database."
             :empty-and-not-asked "The database has none of Lab 4's data and nothing was loaded. (lab-4 driver holdings {:allow-load? true}) runs the keyed load."
             :mismatch "The database holds something other than the file's counts. Nothing was run: look before loading over it.")))))

(defn lab-5
  "Degree centrality without Bloom or a session: counted in Cypher over the
  whole graph and compared with the same count from the file."
  ([] (with-driver #(lab-5 % @form13/rows)))
  ([driver holdings]
   (let [manager-rows (neo4j/query driver (:lab-5/manager-degree statements))
         company-rows (neo4j/query driver (:lab-5/company-degree statements))
         manager-comparison (compare-degrees (degree-map (form13/manager-degree holdings) :manager :companies)
                                             (degree-map manager-rows :manager :companies))
         company-comparison (compare-degrees (company-degrees-from-file holdings)
                                             (degree-map company-rows :cusip :managers))
        ;; Degree as an algorithm counts relationships; the tables count
        ;; neighbours. They are the same while no pair has two OWNS.
         parallel-free? (every? #(= (:degree %) (or (:companies %) (:managers %)))
                                (concat manager-rows company-rows))
         top-managers (->> manager-rows (sort-by (juxt (comp - :companies) :manager)) (take 4)
                           (mapv #(select-keys % [:manager :companies])))
         top-companies (->> company-rows (sort-by (juxt (comp - :managers) :company)) (take 5)
                            (mapv #(select-keys % [:company :managers])))]
     (step 5 "Bloom: degree, in Cypher" (if (and (:agree? manager-comparison) (:agree? company-comparison)) :ok :failed)
           {:summary (format "file and graph agree on %d of %d managers and %d of %d companies; top %s (%d)"
                             (- (:compared manager-comparison) (count (:differences manager-comparison)))
                             (:compared manager-comparison)
                             (- (:compared company-comparison) (count (:differences company-comparison)))
                             (:compared company-comparison)
                             (:manager (first top-managers)) (:companies (first top-managers)))
            :managers manager-comparison
            :companies company-comparison
            :degree-equals-neighbours? parallel-free?
            :top-managers top-managers
            :top-companies top-companies}
           (str "Two aggregations over the whole graph, where Bloom ran the algorithm on a scene of "
                "1,000 paths. Bloom's search, scene and size scaling are a picture and have no "
                "equivalent here. bb form13 degree gives the file's side.")))))

(defn lab-5-graph-analytics
  "Lab 5's algorithm as the lab ran it, in a Graph Analytics session. Probed,
  not run: the procedures are listed and the sessions counted, both reads."
  ([] (with-driver lab-5-graph-analytics))
  ([driver]
   (let [wanted #{"gds.graph.project" "gds.degree.stream" "gds.session.list" "gds.session.delete"}
         offered (set (map :name (neo4j/query driver (:lab-5/procedures statements))))
         session-rows (neo4j/query driver (:lab-5/sessions statements))]
     (step 5 "Bloom: degree, in a session" :skipped
           {:summary (format "%d of %d procedures offered; %d sessions visible to this user; not started"
                             (count (set/intersection wanted offered)) (count wanted) (count session-rows))
            :procedures-missing (vec (sort (set/difference wanted offered)))
            :sessions session-rows
            :statements graph-analytics-statements}
           (str "Runnable from here with the database's own credentials: the Cypher API for Aura "
                "Graph Analytics starts a session with the first projection that names a memory "
                "size. Not run, because that starts billed compute on a trial nobody here can see "
                "the bill for, and the statements in :evidence have therefore never executed. The "
                "other route is the graphdatascience client's GdsSessions.get_or_create, which "
                "needs Aura API client credentials and a project id; that one is blocked the same "
                "way Lab 2 is.")))))

;; Running them

(defn- guarded
  "Runs step-fn. A throw becomes a :failed step with a scrubbed message."
  [lab title secrets step-fn]
  (telemetry/span (str "lab " lab ": " title) {}
    (let [result (try (step-fn)
                      (catch Exception failure
                        (step lab title :failed
                              {:summary (scrub secrets (or (ex-message failure) (str (class failure))))}
                              "The step threw. Nothing was written by it.")))]
      (telemetry/annotate! {:status (:status result)})
      result)))

(defn run-all
  "Every lab in order, as a vector of step results. options:
    :driver       an open driver to use and leave open; else one is opened
    :allow-load?  lets Lab 4 call workshop.form13/load!. Without it nothing
                  but reads is sent."
  ([] (run-all {}))
  ([{:keys [driver] :as options}]
   (let [env-map (env/load-env)
         secrets (secrets-of env-map)
         online-steps (fn [open-driver]
                        (let [holdings @form13/rows]
                          [(guarded 3 "Connect to Neo4j" secrets #(lab-3 open-driver))
                           (guarded 4 "Query" secrets #(lab-4 open-driver holdings options))
                           (guarded 5 "Bloom: degree, in Cypher" secrets #(lab-5 open-driver holdings))
                           (guarded 5 "Bloom: degree, in a session" secrets
                                    #(lab-5-graph-analytics open-driver))
                           (guarded 6 "Document Intelligence" secrets #(lab-6 open-driver))]))
         online (try
                  (if driver
                    (online-steps driver)
                    (with-open [fresh-driver (neo4j/connect env-map)]
                      (online-steps fresh-driver)))
                  (catch Exception failure
                    [(step 3 "Connect to Neo4j" :failed
                           {:summary (scrub secrets (or (ex-message failure) (str (class failure))))}
                           "No driver could be opened, so Labs 4 and 5 were not attempted.")
                     (lab-6)]))]
     (vec (concat [(lab-1) (lab-2 env-map)] online [(lab-7) (lab-8) (lab-9)])))))

(defn report
  "A run as text: the table, how each step was or would be done, the
  totals. Returns the string; printing it is the caller's."
  [results]
  (with-out-str
    (pprint/print-table [:lab :step :status :evidence] (table-rows results))
    (println)
    (doseq [{:keys [lab title status how]} results]
      (println (format "Lab %d, %s [%s]" lab title (name status)))
      (println (str "  " how)))
    (println)
    (println (:summary (as-check results)))))

(defn -main
  "clojure -M -m workshop.labs [detail]. `detail` adds the full evidence.
  Reads only. Exits 1 when a step failed."
  [& [view]]
  (let [results (run-all)]
    (print (report results))
    (when (= view "detail")
      (println)
      (pprint/pprint (mapv #(select-keys % [:lab :title :status :evidence]) results)))
    (flush)
    (shutdown-agents)
    (when (pos? (:failed (overall results)))
      (System/exit 1))))
