(ns workshop.extract
  "Entities and relationships out of plain text, with a local model held to
  a JSON schema. The model is asked through Ollama's chat API, whose
  `format` field takes a JSON schema and constrains the answer to it.

  Three files in prompts/ are the whole instruction: the lab's prompt, a
  hint that says what each type means and which way each relationship
  points, and the schema. Nothing is written to a database: the result is
  data, checked here and drawn by workshop.diagram.

  experiments/006-structured-extraction has what was tried."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn ollama-url
  "Where Ollama listens: OLLAMA_URL, else this machine. A larger model on
  another host on the network is the usual reason to set it."
  []
  (or (System/getenv "OLLAMA_URL") "http://127.0.0.1:11434"))

(def default-model "llama3.2:3b")

(def prompt-path "prompts/lab-06-generate-model.txt")
(def hint-path "prompts/lab-06-extraction-hint.txt")
(def schema-path "prompts/lab-06-extraction-schema.json")

(defn schema [] (json/read-str (slurp schema-path)))

(defn- post-json
  [url body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMinutes 10))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))
        response (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "Ollama answered " (.statusCode response) ": " (.body response)) {})))
    (json/read-str (.body response) :key-fn keyword)))

(defn messages
  "The conversation sent: the hint as the system message, then the lab's
  prompt with the text under it."
  [text]
  [{:role "system" :content (str/trim (slurp hint-path))}
   {:role "user" :content (str (str/trim (slurp prompt-path)) "\n\nText:\n" text)}])

(defn extract
  "Asks the model for the graph in text. Returns the parsed answer,
  {:entities [...] :relationships [...]}, with the timing and token counts
  Ollama reports as metadata. :structured? false sends no schema, to see
  what the model does when it is only asked nicely."
  ([text] (extract text {}))
  ([text {:keys [model structured? context-tokens]
          :or {model default-model structured? true context-tokens 8192}}]
   (let [answer (post-json (str (ollama-url) "/api/chat")
                           (cond-> {:model model
                                    :messages (messages text)
                                    :stream false
                                    :options {:temperature 0 :num_ctx context-tokens}}
                             structured? (assoc :format (schema))))
         content (get-in answer [:message :content])
         parsed (try (json/read-str content :key-fn keyword)
                     (catch Exception _ {:unparsed content}))]
     (with-meta parsed
       {:model model
        :structured? structured?
        :seconds (some-> (:total_duration answer) (/ 1e9))
        :prompt-tokens (:prompt_eval_count answer)
        :answer-tokens (:eval_count answer)}))))

(defn check
  "What is wrong with an answer, measured against the schema's enums and
  against itself. Empty collections throughout mean the answer is usable."
  [{:keys [entities relationships] :as answer}]
  (let [schema-map (schema)
        entity-types (set (get-in schema-map ["properties" "entities" "items" "properties" "type" "enum"]))
        relationship-types (set (get-in schema-map ["properties" "relationships" "items" "properties" "type" "enum"]))
        entity-names (set (map :name entities))]
    {:unparsed? (contains? answer :unparsed)
     :entities (count entities)
     :relationships (count relationships)
     :unknown-entity-types (vec (remove (comp entity-types :type) entities))
     :unknown-relationship-types (vec (remove (comp relationship-types :type) relationships))
     :duplicate-entities (vec (keep (fn [[entity-name occurrences]] (when (> occurrences 1) entity-name))
                                    (frequencies (map :name entities))))
     :dangling (vec (filter (fn [{:keys [source target]}]
                              (not (and (entity-names source) (entity-names target))))
                            relationships))}))

(defn unsupported
  "Relationships whose evidence is not a phrase in the text. The hint asks
  for the phrase copied exactly; this is how to see whether it was."
  [text {:keys [relationships]}]
  (let [squash (fn [phrase] (str/lower-case (str/replace (str phrase) #"\s+" " ")))
        squashed-text (squash text)]
    (vec (remove #(str/includes? squashed-text (squash (:evidence %))) relationships))))

(defn included-in
  "The names a segment INCLUDES."
  [{:keys [relationships]} segment-name]
  (->> relationships
       (filter #(and (= "INCLUDES" (:type %)) (= segment-name (:source %))))
       (map :target)
       sort
       vec))

(defn edges
  "The answer as the edge rows workshop.diagram draws."
  [{:keys [entities relationships]}]
  (let [type-of (into {} (map (juxt :name :type)) entities)]
    (mapv (fn [{:keys [source type target]}]
            {:source source :sourceLabel (type-of source)
             :relationship type
             :target target :targetLabel (type-of target)})
          relationships)))
