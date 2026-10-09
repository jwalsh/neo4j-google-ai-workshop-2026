(ns workshop.ollama
  "A model served by Ollama, asked through its chat API. The `format` field
  takes a JSON schema and constrains the answer to it, which is what makes a
  small model usable as a component: the answer parses or the call failed.

  OLLAMA_URL says where Ollama listens, default this machine. OLLAMA_MODEL
  names the model, default a small one. Both can be in the env file. A
  machine without room for a model sets OLLAMA_URL to one that has it."
  (:require [clojure.data.json :as json]
            [workshop.env :as env]
            [workshop.telemetry :as telemetry])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def default-url "http://127.0.0.1:11434")
(def default-model "llama3.2:3b")

(defn url [] (get (env/load-env) "OLLAMA_URL" default-url))
(defn model [] (get (env/load-env) "OLLAMA_MODEL" default-model))

(defn- request-json
  [method endpoint body timeout-seconds]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str (url) endpoint)))
                    (.timeout (Duration/ofSeconds timeout-seconds))
                    (.header "Content-Type" "application/json"))
        request (.build (if (= :post method)
                          (.POST builder (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                          (.GET builder)))
        response (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "Ollama answered " (.statusCode response) ": " (.body response)) {})))
    (json/read-str (.body response) :key-fn keyword)))

(defn models
  "The models the server has, as {:name :parameters :bytes}."
  []
  (mapv (fn [{model-name :name :keys [size details]}]
          {:name model-name :parameters (:parameter_size details) :bytes size})
        (:models (request-json :get "/api/tags" nil 10))))

(defn chat
  "Sends messages, [{:role :content}], and returns
  {:content :parsed :model :seconds :prompt-tokens :answer-tokens}. With
  :schema, a JSON schema as a map, the answer is constrained to it and
  :parsed is the answer read as JSON, or nil when it does not parse.
  Options: :model, :schema, :context-tokens, :timeout-seconds. One span per
  call, with the model and the token counts and none of the text."
  ([messages] (chat messages {}))
  ([messages {:keys [schema context-tokens timeout-seconds] chosen-model :model
              :or {context-tokens 8192 timeout-seconds 600}}]
   (let [model-name (or chosen-model (model))]
     (telemetry/span "ollama" {:model model-name :structured? (some? schema)}
       (let [answer (request-json :post "/api/chat"
                                  (cond-> {:model model-name
                                           :messages messages
                                           :stream false
                                           :think false
                                           :options {:temperature 0 :num_ctx context-tokens}}
                                    schema (assoc :format schema))
                                  timeout-seconds)
             content (get-in answer [:message :content])
             result {:content content
                     :parsed (when schema
                               (try (json/read-str content :key-fn keyword)
                                    (catch Exception _ nil)))
                     :model model-name
                     :seconds (some-> (:total_duration answer) (/ 1e9))
                     :prompt-tokens (:prompt_eval_count answer)
                     :answer-tokens (:eval_count answer)}]
         (telemetry/annotate! (select-keys result [:prompt-tokens :answer-tokens]))
         result)))))

(comment
 (url)
 (model)
 ;; these call the server (url) names
 (models)
 (chat [{:role "user" :content "One word: the capital of France."}]
       {:schema {"type" "object" "properties" {"city" {"type" "string"}} "required" ["city"]}})
:review)
