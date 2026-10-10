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

(defn- string-keyed
  [arguments]
  (into {} (map (fn [[argument-name argument-value]] [(name argument-name) argument-value])) arguments))

(defn argument-schema
  "The JSON schema for one tool's arguments alone, with every parameter the
  tool cannot do without required. `choice-schema` offers every parameter
  of every tool and requires none, and a small model handed that tends to
  fill in a few at random and leave out the ones the tool needs.

  `domains` maps a parameter's name to the values stored for it. Such a
  parameter becomes an enum, so the model picks a station that exists
  where it would have copied a word and hoped."
  [{:keys [parameters]} domains]
  {"type" "object"
   "properties" (into {}
                      (for [{:keys [data_type] parameter-name :name} parameters]
                        [parameter-name (if-let [stored (seq (get domains parameter-name))]
                                          {"type" "string" "enum" (vec stored)}
                                          {"type" (json-types data_type "string")})]))
   "required" (vec (for [{:keys [is_optional] parameter-name :name} parameters
                         :when (not is_optional)]
                     parameter-name))})

(defn- argument-instructions
  [{:keys [description parameters] tool-name :name}]
  (str "Fill in the arguments of the tool \"" tool-name "\": " description "\n"
       "Copy each value from the question as written, or pick the listed value the question means. "
       "Do not invent one.\n\nArguments:\n"
       (str/join "\n" (for [{:keys [data_type] parameter-name :name parameter-description :description} parameters]
                        (str "- " parameter-name " (" data_type "): " parameter-description)))))

(defn- missing-arguments
  [{:keys [parameters]} arguments]
  (for [{:keys [is_optional] parameter-name :name} parameters
        :when (and (not is_optional) (nil? (get arguments parameter-name)))]
    parameter-name))

(defn choose
  "Asks the model which tool answers the question. Returns
  {:tool :arguments} as the model gave them, with the call's timing. When
  the tool chosen is left without an argument it needs, or the graph has a
  short list of values for one of its arguments, the model is asked once
  more for that tool's arguments alone (`argument-schema`); :asked-twice
  says so and :from-graph names the arguments picked from a list.

  The option :domains is a function of a tool to {parameter-name [values]};
  `ask` supplies it, since only it has a driver. :single-call true leaves
  the second question out, which is what experiment 013 measures against."
  ([agent question] (choose agent question {}))
  ([agent question options]
   (let [{:keys [parsed] :as answer}
         (ollama/chat [{:role "system" :content (instructions agent)}
                       {:role "user" :content question}]
                      (assoc (dissoc options :domains) :schema (choice-schema agent)))
         timing #(select-keys % [:model :seconds :prompt-tokens :answer-tokens])
         arguments (string-keyed (:arguments parsed))
         chosen (first (filter #(= (:tool parsed) (:name %)) (:tools agent)))
         domains (when (and chosen (:domains options)) ((:domains options) chosen))
         options (dissoc options :domains)]
     (if (and chosen (not (:single-call options))
              (or (seq domains) (seq (missing-arguments chosen arguments))))
       (let [second-answer (ollama/chat [{:role "system" :content (argument-instructions chosen)}
                                         {:role "user" :content question}]
                                        (assoc options :schema (argument-schema chosen domains)))]
         (merge {:tool (:tool parsed)
                 :arguments (string-keyed (:parsed second-answer))
                 :from-graph (set (keys domains))
                 :asked-twice true}
                (merge-with (fn [first-value second-value]
                              (if (number? first-value) (+ first-value second-value) second-value))
                            (timing answer) (timing second-answer))))
       (merge {:tool (:tool parsed) :arguments arguments} (timing answer))))))

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

(defn- stored-values
  [driver {:keys [label property]} written-value]
  (let [candidates (map :value
                        (neo4j/query driver
                                     (str "MATCH (n:`" label "`) WHERE toLower(n.`" property "`) CONTAINS toLower($written) "
                                          "RETURN DISTINCT n.`" property "` AS value ORDER BY value LIMIT 12")
                                     {:written written-value}))
        exact (filter #(= (str/lower-case %) (str/lower-case written-value)) candidates)]
    (vec (if (seq exact) exact candidates))))

(defn resolve-value
  "The stored values a loosely written one could mean: an exact match
  without regard to case if there is one, else every value containing it.
  The model copies a name from the question; the database knows how the
  name is spelled. A plural that matches nothing is tried once more
  without its s, since a question says \"macros\" where a term is \"macro\"."
  [driver place written-value]
  (let [candidates (stored-values driver place written-value)]
    (if (and (empty? candidates) (str/ends-with? written-value "s") (> (count written-value) 3))
      (stored-values driver place (subs written-value 0 (dec (count written-value))))
      candidates)))

(defn grounded
  "A choice with every argument the question does not support removed, and
  those removed listed under :dropped. A string must appear in the question,
  without regard to case; a number must appear as a number. A model asked
  for arguments tends to supply them, and an invented name or threshold is
  the one thing a schema cannot rule out. This can.

  An argument picked from a list of stored values (:from-graph) is spelled
  as the graph spells it, \"Kendall/MIT\" for a question's \"Kendall\", so
  it need only share a word of three letters or more with the question."
  [question {:keys [arguments from-graph] :as choice}]
  (let [lowered (str/lower-case question)
        numbers (set (re-seq #"\d+(?:\.\d+)?" question))
        shares-a-word? (fn [stored-value]
                         (some #(str/includes? lowered %)
                               (re-seq #"[a-z0-9]{3,}" (str/lower-case stored-value))))
        supported? (fn [[argument-name argument-value]]
                     (cond (and (string? argument-value) (contains? from-graph argument-name))
                           (shares-a-word? argument-value)
                           (string? argument-value) (and (not (str/blank? argument-value))
                                                         (str/includes? lowered (str/lower-case argument-value)))
                           (number? argument-value) (contains? numbers (str argument-value))
                           :else true))
        {kept true dropped false} (group-by (comp boolean supported?) arguments)]
    (assoc choice :arguments (into {} kept) :dropped (into {} dropped))))

(def largest-domain
  "A property with more stored values than this is not offered as a list:
  254 stations make a grammar, some thousands of company names do not."
  400)

(defn domains
  "For one tool, the stored values of each string parameter its template
  compares with a property, as {parameter-name [values]}, leaving out any
  property with more than `largest-domain` of them."
  [driver {:keys [template parameters]}]
  (into {}
        (for [{:keys [data_type] parameter-name :name} parameters
              :when (= "string" data_type)
              :let [{:keys [label property] :as place} (lookup template parameter-name)]
              :when place
              :let [stored (map :value
                                (neo4j/query driver
                                             (str "MATCH (n:`" label "`) WHERE n.`" property "` IS NOT NULL "
                                                  "RETURN DISTINCT n.`" property "` AS value ORDER BY value LIMIT "
                                                  (inc largest-domain))))]
              :when (<= 1 (count stored) largest-domain)]
          [parameter-name (vec stored)])))

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
     (let [choice (grounded question (choose agent question (assoc options :domains #(domains driver %))))
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

(comment
 ;; everything here but choose and ask runs without a model
 (map :name (:tools (load-agent)))
 (grounded "Which managers hold Allstate?"
           {:tool "x" :arguments {"companyName" "Allstate" "minOwners" 3}})
 (let [tool (first (:tools (load-agent)))]
   (lookup (:template tool) (:name (first (:parameters tool)))))
 (choice-schema (load-agent))
 (println (instructions (load-agent)))
 (validate (load-agent) {:tool "Get Companies by Manager" :arguments {}})
 (map (juxt :id :env :example) (registry))
 ;; needs OLLAMA_URL and the agent's server
 (ask-agent "books" "Which books mention telemetry?")
:review)
