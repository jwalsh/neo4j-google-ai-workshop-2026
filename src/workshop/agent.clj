(ns workshop.agent
  "A question answered by choosing a tool, not by writing a query. The tools
  are the Cypher templates in an agent definition, the JSON Aura exports
  (labs/lab-09/*.agent.json): each has a name, a description, a template
  and typed parameters. A small local model is asked one thing, held to a
  JSON schema: which tool, with which arguments. The template is then run
  as written and its rows are the answer. No prose is generated, so there
  is nothing to be plausibly wrong: either a tool was chosen and its rows
  came from the database, or the answer is :none.

  This is the half of Lab 9's agent that was right (finding 26). The other
  half, free Text2Cypher, is left out on purpose.

  Nothing here knows the data. Another agent file over another graph works
  the same way."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.neo4j :as neo4j]
            [workshop.ollama :as ollama]
            [workshop.telemetry :as telemetry]))

(def default-agent-path "labs/lab-09/asset-manager-portfolio-analyst.agent.json")

(defn load-agent
  "An agent definition with only its Cypher-template tools, as
  {:name :description :tools [{:name :description :template :parameters}]}."
  ([] (load-agent default-agent-path))
  ([agent-path]
   (let [definition (json/read-str (slurp agent-path) :key-fn keyword)]
     {:name (:name definition)
      :description (:description definition)
      :tools (->> (:tools definition)
                  (filter #(and (= "cypherTemplate" (:type %)) (:enabled %)))
                  (mapv (fn [{:keys [config] :as tool}]
                          {:name (:name tool)
                           :description (:description tool)
                           :template (:template config)
                           :parameters (:parameters config)})))})))

(def no-tool "none")

(def ^:private json-types {"string" "string" "integer" "integer" "float" "number" "boolean" "boolean"})

(defn choice-schema
  "The JSON schema the model's answer is held to: a tool name from the
  agent's own list, or \"none\", and an arguments object whose properties
  are every parameter any tool takes. Which of them a tool needs is checked
  after, in `validate`; a schema with one branch per tool is more than a
  small model's grammar needs."
  [{:keys [tools]}]
  {"type" "object"
   "properties" {"tool" {"type" "string" "enum" (conj (mapv :name tools) no-tool)}
                 "arguments" {"type" "object"
                              "properties" (into {}
                                                 (for [tool tools
                                                       {:keys [data_type] parameter-name :name} (:parameters tool)]
                                                   [parameter-name {"type" (json-types data_type "string")}]))}}
   "required" ["tool" "arguments"]})

(defn instructions
  "The system message: what each tool is for and what it takes."
  [{:keys [tools] agent-name :name}]
  (str "You route a question to one tool of the agent \"" agent-name "\". "
       "Answer with the tool's exact name and its arguments. "
       "Copy names of people, companies or things from the question as written. "
       "Give a number only if the question states one. "
       "If no tool can answer the question, the tool is \"" no-tool "\".\n\nTools:\n"
       (str/join "\n"
                 (for [{:keys [description parameters] tool-name :name} tools]
                   (str "- " tool-name ": " description " Arguments: "
                        (if (seq parameters)
                          (str/join "; " (map (fn [{:keys [data_type is_optional] parameter-name :name
                                                     parameter-description :description}]
                                                (str parameter-name " (" data_type
                                                     (when is_optional ", optional") "): "
                                                     parameter-description))
                                              parameters))
                          "none")
                        ".")))))

(defn choose
  "Asks the model which tool answers the question. Returns
  {:tool :arguments} as the model gave them, with the call's timing."
  ([agent question] (choose agent question {}))
  ([agent question options]
   (let [{:keys [parsed] :as answer}
         (ollama/chat [{:role "system" :content (instructions agent)}
                       {:role "user" :content question}]
                      (assoc options :schema (choice-schema agent)))]
     (merge {:tool (:tool parsed)
             :arguments (into {} (map (fn [[argument-name argument-value]]
                                        [(name argument-name) argument-value]))
                              (:arguments parsed))}
            (select-keys answer [:model :seconds :prompt-tokens :answer-tokens])))))

(defn lookup
  "Where a template compares a string parameter with a property, as
  {:label :property}, read from the template's own text: `m.managerName =
  $managerName` with `(m:Manager)` earlier, or `(m:Manager {managerName:
  $managerName})`. nil when the template does neither."
  [template parameter-name]
  (let [quoted (java.util.regex.Pattern/quote (str "$" parameter-name))
        label-of (fn [variable]
                   (second (re-find (re-pattern (str "\\(" variable ":(\\w+)")) template)))]
    (or (when-let [[_ variable property] (re-find (re-pattern (str "(\\w+)\\.(\\w+)\\s*=\\s*" quoted)) template)]
          (when-let [label (label-of variable)] {:label label :property property}))
        (when-let [[_ label property] (re-find (re-pattern (str "\\(\\w+:(\\w+)\\s*\\{[^}]*?(\\w+):\\s*" quoted))
                                               template)]
          {:label label :property property}))))

(defn resolve-value
  "The stored values a loosely written one could mean: an exact match
  without regard to case if there is one, else every value containing it.
  The model copies a name from the question; the database knows how the
  name is spelled."
  [driver {:keys [label property]} written-value]
  (let [candidates (map :value
                        (neo4j/query driver
                                     (str "MATCH (n:`" label "`) WHERE toLower(n.`" property "`) CONTAINS toLower($written) "
                                          "RETURN DISTINCT n.`" property "` AS value ORDER BY value LIMIT 12")
                                     {:written written-value}))
        exact (filter #(= (str/lower-case %) (str/lower-case written-value)) candidates)]
    (vec (if (seq exact) exact candidates))))

(defn grounded
  "A choice with every argument the question does not support removed, and
  those removed listed under :dropped. A string must appear in the question,
  without regard to case; a number must appear as a number. A model asked
  for arguments tends to supply them, and an invented name or threshold is
  the one thing a schema cannot rule out. This can."
  [question {:keys [arguments] :as choice}]
  (let [lowered (str/lower-case question)
        numbers (set (re-seq #"\d+(?:\.\d+)?" question))
        supported? (fn [argument-value]
                     (cond (string? argument-value) (and (not (str/blank? argument-value))
                                                         (str/includes? lowered (str/lower-case argument-value)))
                           (number? argument-value) (contains? numbers (str argument-value))
                           :else true))
        {kept true dropped false} (group-by (comp boolean supported? val) arguments)]
    (assoc choice :arguments (into {} kept) :dropped (into {} dropped))))

(defn validate
  "Checks a choice against the tool it names and fills in defaults. Returns
  {:tool :parameters} ready to run, or {:problem ...}."
  [{:keys [tools]} {:keys [tool arguments]}]
  (let [chosen (first (filter #(= tool (:name %)) tools))]
    (cond
      (= no-tool tool) {:problem "the model chose no tool"}
      (nil? chosen) {:problem (str "no tool named " (pr-str tool))}
      :else
      (let [declared (:parameters chosen)
            missing (for [{:keys [is_optional] parameter-name :name} declared
                          :when (and (not is_optional) (nil? (get arguments parameter-name)))]
                      parameter-name)]
        (if (seq missing)
          {:tool chosen :problem (str "missing " (str/join ", " missing))}
          {:tool chosen
           :parameters (into {}
                             (for [{parameter-default :default parameter-name :name} declared
                                   :let [given (get arguments parameter-name)
                                         parameter-value (if (some? given) given parameter-default)]
                                   :when (some? parameter-value)]
                               [parameter-name parameter-value]))})))))

(defn ask
  "A question to rows. Returns
  {:question :choice :tool :parameters :resolved :rows} or the same with
  :problem in place of :rows. A string argument the template compares with
  a property is first resolved against the database; several candidates is
  a problem to show, not a guess to make. Arguments the question does not
  support are dropped before any of that (`grounded`)."
  ([driver question] (ask driver (load-agent) question {}))
  ([driver agent question] (ask driver agent question {}))
  ([driver agent question options]
   (telemetry/span "agent: ask" {:agent (:name agent)}
     (let [choice (grounded question (choose agent question options))
           {:keys [tool parameters problem]} (validate agent choice)
           base {:question question :choice choice :tool (:name tool)}]
       (telemetry/annotate! {:tool (:tool choice)})
       (if problem
         (assoc base :problem problem)
         (let [resolutions (into {}
                                 (for [[parameter-name parameter-value] parameters
                                       :when (string? parameter-value)
                                       :let [place (lookup (:template tool) parameter-name)]
                                       :when place]
                                   [parameter-name (resolve-value driver place parameter-value)]))
               unresolved (first (filter (fn [[_ candidates]] (not= 1 (count candidates))) resolutions))]
           (if unresolved
             (let [[parameter-name candidates] unresolved]
               (assoc base
                      :parameters parameters
                      :candidates candidates
                      :problem (if (empty? candidates)
                                 (str "nothing stored matches " parameter-name " " (pr-str (parameters parameter-name)))
                                 (str (count candidates) " stored values match " parameter-name " "
                                      (pr-str (parameters parameter-name))))))
             (let [resolved (merge parameters (update-vals resolutions first))]
               (assoc base
                      :parameters resolved
                      :resolved (into {} (filter (fn [[parameter-name stored]] (not= stored (parameters parameter-name)))
                                                 (update-vals resolutions first)))
                      :rows (neo4j/query driver (:template tool) resolved))))))))))

;; More than one agent, each over its own graph.

(def registry-path "agents/registry.edn")

(defn registry
  "The agents on offer, in order: [{:id :agent :env :about :example}]. The
  first is the default."
  []
  (edn/read-string (slurp registry-path)))

(defn entry
  "The registry entry with this id, or the first when id is blank."
  [agent-id]
  (let [entries (registry)]
    (if (str/blank? agent-id)
      (first entries)
      (or (first (filter #(= agent-id (:id %)) entries))
          (throw (ex-info (str "No agent " (pr-str agent-id) ". There are: "
                               (str/join ", " (map :id entries)))
                          {}))))))

(defn ask-agent
  "A question to the agent with this id, against that agent's own data
  source: its env file names the server, and a driver is opened for the one
  question. Returns what `ask` returns, with :agent-id and :source."
  ([agent-id question] (ask-agent agent-id question {}))
  ([agent-id question options]
   (let [{:keys [id agent] env-file :env} (entry agent-id)
         env-map (env/load-env env-file)]
     (with-open [driver (neo4j/connect env-map)]
       (assoc (ask driver (load-agent agent) question options)
              :agent-id id
              :source (get env-map "NEO4J_URI"))))))
