(ns workshop.graph-builder
  "Neo4j Labs' LLM Knowledge Graph Builder, driven through its backend API
  and not its page. The hosted app is a React front end over a FastAPI
  service; every call is a multipart form carrying the database's
  connection details, with the Neo4j login's bearer token in the header.

  The token is the one thing code cannot get by itself: it comes from
  logging in to the page once. It is read from GRAPH_BUILDER_TOKEN, or from
  the file GRAPH_BUILDER_TOKEN_FILE names, and lasts a day.

  Field names and endpoints are from backend/score.py and
  backend/src/entities in neo4j-labs/llm-graph-builder."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [workshop.env :as env])
  (:import (java.util Base64 UUID)))

(def default-backend "https://prod-backend-967196130891.us-central1.run.app")

;; The identifier the front end sends for "Gemini 3.5 flash", its default.
(def default-model "gemini_3.5_flash")

(defn- bearer-token
  [env-map]
  (or (env-map "GRAPH_BUILDER_TOKEN")
      (some-> (env-map "GRAPH_BUILDER_TOKEN_FILE") slurp str/trim)
      (throw (ex-info "No token. Log in at llm-graph-builder.neo4jlabs.com and set GRAPH_BUILDER_TOKEN or GRAPH_BUILDER_TOKEN_FILE."
                      {}))))

(defn- connection-fields
  "The four fields every call carries. The front end base64-encodes the
  password and appends the Bolt port to the URI; this does the same."
  [env-map]
  (let [{:strs [NEO4J_URI NEO4J_USERNAME NEO4J_PASSWORD]}
        (env/require-vars env-map ["NEO4J_URI" "NEO4J_USERNAME" "NEO4J_PASSWORD"])]
    {"uri" (if (re-find #":\d+$" NEO4J_URI) NEO4J_URI (str NEO4J_URI ":7687"))
     "database" (get env-map "NEO4J_DATABASE" "neo4j")
     "userName" NEO4J_USERNAME
     "password" (.encodeToString (Base64/getEncoder) (.getBytes ^String NEO4J_PASSWORD "UTF-8"))}))

(defn call
  "POSTs a multipart form to an endpoint and returns the parsed answer.
  form-fields is a map of field name to string; file-fields a map of field
  name to a local path. curl does the multipart encoding."
  ([endpoint form-fields] (call endpoint form-fields {}))
  ([endpoint form-fields file-fields] (call endpoint form-fields file-fields (env/load-env)))
  ([endpoint form-fields file-fields env-map]
   (let [backend (get env-map "GRAPH_BUILDER_BACKEND" default-backend)
         all-fields (merge (connection-fields env-map) form-fields)
         arguments (concat ["curl" "-sS" "--max-time" "600" "-X" "POST" (str backend "/" endpoint)
                            "-H" (str "Authorization: Bearer " (bearer-token env-map))]
                           (mapcat (fn [[field-name field-value]]
                                     ["--form-string" (str field-name "=" field-value)])
                                   all-fields)
                           (mapcat (fn [[field-name file-path]] ["-F" (str field-name "=@" file-path)])
                                   file-fields))
         {:keys [exit out err]} (apply shell/sh arguments)]
     (when-not (zero? exit)
       (throw (ex-info (str endpoint ": curl failed: " (str/trim err)) {:endpoint endpoint})))
     (let [answer (try (json/read-str out :key-fn keyword)
                       (catch Exception _ {:status "Unparsed" :body (subs out 0 (min 400 (count out)))}))]
       (when-not (= "Success" (:status answer))
         (throw (ex-info (str endpoint ": " (or (:message answer) (:error answer) (:status answer)))
                         {:endpoint endpoint :answer answer})))
       answer))))

(defn connect
  "Checks the backend can reach the database. Returns what it found there."
  []
  (:data (call "connect" {})))

(defn sources
  "The documents the builder has recorded in the database."
  []
  (:data (call "sources_list" {})))

(defn token-limits [] (:data (call "get_token_limits" {})))

(defn upload!
  "Sends a local file as one chunk. The front end splits at 1 MB; a file
  larger than that needs the same splitting, which this does not do."
  ([file-path] (upload! file-path default-model))
  ([file-path model]
   (let [upload-file (io/file file-path)]
     (when (> (.length upload-file) (* 1024 1024))
       (throw (ex-info "File is over 1 MB; chunked upload is not written." {:bytes (.length upload-file)})))
     (call "upload"
           {"chunkNumber" "1" "totalChunks" "1" "originalname" (.getName upload-file)
            "model" model "uploadId" (str (UUID/randomUUID))}
           {"file" file-path}))))

(defn extract!
  "Runs the extraction for an uploaded file. allowed-nodes and
  allowed-relationships are comma-separated label and type names, or blank
  to let the model choose; instructions is free text added to the prompt."
  [{:keys [file-name model allowed-nodes allowed-relationships instructions
           token-chunk-size chunk-overlap chunks-to-combine embedding-provider embedding-model]
    :or {model default-model allowed-nodes "" allowed-relationships "" instructions ""
         token-chunk-size 200 chunk-overlap 20 chunks-to-combine 1
         ;; What /fetch_embedding_model reports for the hosted app.
         embedding-provider "sentence-transformer" embedding-model "all-MiniLM-L6-v2"}}]
  (call "extract"
        {"model" model "source_type" "local file" "file_name" file-name
         "allowedNodes" allowed-nodes "allowedRelationship" allowed-relationships
         "additional_instructions" instructions
         "token_chunk_size" (str token-chunk-size) "chunk_overlap" (str chunk-overlap)
         "chunks_to_combine" (str chunks-to-combine)
         ;; Left out, the backend fails with "'NoneType' object has no
         ;; attribute 'lower'" after it has already written the chunks.
         "embedding_provider" embedding-provider "embedding_model" embedding-model
         "language" ""}))

(def lab-06
  "Lab 6's document and prompt. The prompt is the lab's, kept in prompts/;
  the lab gives it to Document Intelligence, and here it goes to the Graph
  Builder as extra instructions, with the things it names as the allowed
  labels."
  {:object "Apple_10-K_2025_2_pages.pdf"
   :local-path "data/Apple_10-K_2025_2_pages.pdf"
   :prompt-path "prompts/lab-06-generate-model.txt"
   ;; Not Company: Lab 4 put a node key on (:Company {cusip}), and a
   ;; Company the model extracts from a 10-K has no CUSIP. The extraction
   ;; fails on the constraint. The filer and its rivals are Organization.
   :allowed-nodes "Organization,Product,Service,Segment,Competitor"})

(defn run-lab-06!
  "Uploads the lab's PDF and extracts a graph from it, unless the builder
  already lists the file as Completed. Returns the source's row."
  []
  (let [{:keys [object local-path prompt-path allowed-nodes]} lab-06
        instructions (str/trim (slurp prompt-path))
        find-source (fn [] (first (filter #(= object (:fileName %)) (sources))))]
    (when-not (= "Completed" (:status (find-source)))
      ;; Upload every time: the backend drops its copy of the file when an
      ;; extraction fails, and then answers "File does not exist".
      (upload! local-path)
      (extract! {:file-name object :instructions instructions :allowed-nodes allowed-nodes}))
    (find-source)))
