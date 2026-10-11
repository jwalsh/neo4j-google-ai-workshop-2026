(ns workshop.otlp
  "Sends the spans `workshop.telemetry` records to an OpenTelemetry
  collector, as OTLP over HTTP in its JSON encoding. Off unless
  OTEL_EXPORTER_OTLP_ENDPOINT (or OTEL_EXPORTER_OTLP_TRACES_ENDPOINT) is
  set, in the process environment or in .env; `install!` says which.

  A trace is sent when its root span ends: one request per trace, on
  another thread, so the work being timed never waits for the collector. A
  collector that is down costs a line on stderr the first time and nothing
  after.

  Sent with curl, for the reason `workshop.ports` gives: on a Mac the
  system lets its own curl reach the local network and refuses a JVM, and a
  collector is usually on another machine.

  The resource says who is speaking: service.name from OTEL_SERVICE_NAME
  (else \"workshop\"), then OTEL_RESOURCE_ATTRIBUTES as given, and
  deployment.environment.name, \"dev\" unless those name another. What a
  span carries is what telemetry already holds, so the rule there holds
  here: a statement's first line, never a parameter value or a row."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [workshop.env :as env]
            [workshop.telemetry :as telemetry])
  (:import (java.security MessageDigest)))

(defn- settings
  "The process environment with .env over it, whichever graph is in use."
  []
  (env/load-env ".env"))

(defn endpoint
  "Where traces are posted, or nil when no collector is named."
  ([] (endpoint (settings)))
  ([settings-map]
   (or (not-empty (get settings-map "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
       (some-> (not-empty (get settings-map "OTEL_EXPORTER_OTLP_ENDPOINT"))
               (str/replace #"/+$" "")
               (str "/v1/traces")))))

(defn resource-attributes
  "The resource's attributes as a map of names to text."
  ([] (resource-attributes (settings)))
  ([settings-map]
   (let [given (into {}
                     (keep (fn [pair]
                             (let [[attribute-name attribute-value] (str/split pair #"=" 2)]
                               (when (and (not (str/blank? attribute-name)) attribute-value)
                                 [(str/trim attribute-name) (str/trim attribute-value)]))))
                     (str/split (str (get settings-map "OTEL_RESOURCE_ATTRIBUTES")) #","))]
     (merge {"deployment.environment.name" "dev"}
            given
            {"service.name" (or (not-empty (get settings-map "OTEL_SERVICE_NAME"))
                                (get given "service.name")
                                "workshop")}))))

(defn- hex-of
  "The first `length` hex digits of a digest of text. Telemetry's ids are
  eight characters; OTLP wants 32 for a trace and 16 for a span, and the
  same id has to give the same answer so that a parent is found."
  [text length]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str text) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) digest)) 0 length)))

(defn- attribute
  [[attribute-name attribute-value]]
  {:key (if (keyword? attribute-name) (name attribute-name) (str attribute-name))
   :value (cond
            (boolean? attribute-value) {:boolValue attribute-value}
            (integer? attribute-value) {:intValue (str attribute-value)}
            (number? attribute-value) {:doubleValue (double attribute-value)}
            (keyword? attribute-value) {:stringValue (name attribute-value)}
            :else {:stringValue (str attribute-value)})})

(defn- otlp-span
  [{:keys [trace-id span-id parent-id start-ms duration-ms status attributes] span-name :name}]
  (let [start-nanos (* (long start-ms) 1000000)]
    (cond-> {:traceId (hex-of trace-id 32)
             :spanId (hex-of (str trace-id "/" span-id) 16)
             :name span-name
             ;; 2 is SERVER, for the span a request opens; 1 is INTERNAL.
             :kind (if (and (nil? parent-id) (str/starts-with? span-name "GET ")) 2 1)
             :startTimeUnixNano (str start-nanos)
             :endTimeUnixNano (str (+ start-nanos (long (* (double duration-ms) 1e6))))
             :attributes (mapv attribute (dissoc attributes :parameters))
             :status (if (= :error status)
                       {:code 2 :message (str (:error attributes))}
                       {:code 1})}
      parent-id (assoc :parentSpanId (hex-of (str trace-id "/" parent-id) 16)))))

(defn payload
  "One trace's spans as the body of an OTLP/HTTP request."
  [resource trace-spans]
  {:resourceSpans
   [{:resource {:attributes (mapv attribute (sort-by key resource))}
     :scopeSpans [{:scope {:name "workshop.telemetry"}
                   :spans (mapv otlp-span trace-spans)}]}]})

;; What has ended in each trace whose root has not, so that the root's end
;; can send them together.
(defonce ^:private waiting (atom {}))
(defonce ^:private sending (atom []))
(defonce ^:private complained? (atom false))
(defonce counts (atom {:traces 0 :spans 0 :failed 0}))

(def ^:private most-waiting-traces 200)

(defn- post!
  "Posts one body. True when the collector took it."
  [url body]
  (let [process (.start (ProcessBuilder. ^java.util.List
                                         ["curl" "-s" "-m" "5" "-o" "/dev/null" "-w" "%{http_code}"
                                          "-X" "POST" "-H" "Content-Type: application/json"
                                          "--data-binary" "@-" url]))]
    (with-open [to-curl (.getOutputStream process)]
      (.write to-curl (.getBytes ^String body "UTF-8")))
    (let [status (str/trim (slurp (.getInputStream process)))]
      (.waitFor process)
      (str/starts-with? status "2"))))

(defn- send-trace!
  [url resource trace-spans]
  (let [sent? (try (post! url (json/write-str (payload resource trace-spans)))
                   (catch Exception _ false))]
    (if sent?
      (swap! counts #(-> % (update :traces inc) (update :spans + (count trace-spans))))
      (do (swap! counts update :failed inc)
          (when (compare-and-set! complained? false true)
            (binding [*out* *err*]
              (println (str "otlp: the collector at " url " did not take a trace; "
                            "carrying on without it"))))))))

(defn- on-finish
  [url resource {:keys [trace-id parent-id] :as finished-span}]
  (if parent-id
    (swap! waiting (fn [by-trace]
                     (let [grown (update by-trace trace-id (fnil conj []) finished-span)]
                       (if (> (count grown) most-waiting-traces) (dissoc grown (key (first grown))) grown))))
    (let [[before _] (swap-vals! waiting dissoc trace-id)
          trace-spans (conj (get before trace-id []) finished-span)]
      (swap! sending #(conj (filterv (complement realized?) %)
                            (future (send-trace! url resource trace-spans)))))))

(defn flush!
  "Waits, briefly, for what is being sent. For a command about to exit."
  []
  (doseq [in-flight @sending] (deref in-flight 6000 nil))
  nil)

(defn install!
  "Starts sending, when a collector is named. Says, as data, what it did:
  {:endpoint :resource} or {:endpoint nil}. Calling it again re-reads the
  settings, so a REPL picks up a change to .env."
  []
  (let [settings-map (settings)
        url (endpoint settings-map)
        resource (resource-attributes settings-map)]
    (reset! complained? false)
    (telemetry/on-finish! (when url #(on-finish url resource %)))
    (if url {:endpoint url :resource resource} {:endpoint nil})))

(comment
  (install!)
  @counts
  (payload (resource-attributes {}) (telemetry/spans))
  (flush!))
