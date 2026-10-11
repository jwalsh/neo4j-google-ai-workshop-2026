(ns workshop.telemetry
  "Spans, kept in memory. A span is a named piece of work with a start, a
  duration, a parent and a few attributes: the shape OpenTelemetry uses,
  without the dependency. `span` wraps a body; spans opened inside it,
  on this thread or in a `future` started from it, become its children.

  The last `capacity` spans are held for the dashboard's /traces page and
  for a REPL: (traces), (slowest 10). Nothing leaves the process from here:
  `on-finish!` takes a function to be told of each span as it ends, and
  `workshop.otlp` is the one that uses it, to send them to a collector.

  Attributes are for the reader of a trace. Put a statement's first line
  in one; never a parameter value, a credential or a row."
  (:require [clojure.string :as str]))

(def capacity 4000)

(defonce ^:private recorded (atom clojure.lang.PersistentQueue/EMPTY))

;; The span being timed on this thread, as {:trace-id :span-id :attributes},
;; where :attributes is an atom the body can add to.
(def ^:dynamic *current* nil)

(defn- new-id [] (subs (str (java.util.UUID/randomUUID)) 0 8))

(defonce ^:private listener (atom nil))

(defn on-finish!
  "Has listen-fn called with each span as it ends, or with nil stops that.
  One listener; what it throws is dropped, so that sending a span somewhere
  can never fail the work the span timed."
  [listen-fn]
  (reset! listener listen-fn)
  nil)

(defn- record!
  [finished-span]
  (swap! recorded (fn [queue]
                    (let [grown (conj queue finished-span)]
                      (if (> (count grown) capacity) (pop grown) grown))))
  (when-let [listen-fn @listener]
    (try (listen-fn finished-span) (catch Throwable _ nil))))

(defn annotate!
  "Adds attributes to the span being timed. Does nothing outside one."
  [attributes]
  (when *current*
    (swap! (:attributes *current*) merge attributes))
  nil)

(defn run-span
  "Calls body-fn inside a span. The function behind the `span` macro."
  [span-name attributes body-fn]
  (let [parent *current*
        context {:trace-id (or (:trace-id parent) (new-id))
                 :span-id (new-id)
                 :attributes (atom (or attributes {}))}
        started-at-ms (System/currentTimeMillis)
        started-nanos (System/nanoTime)
        finish (fn [status]
                 (record! {:trace-id (:trace-id context)
                           :span-id (:span-id context)
                           :parent-id (:span-id parent)
                           :name span-name
                           :start-ms started-at-ms
                           :duration-ms (/ (- (System/nanoTime) started-nanos) 1e6)
                           :status status
                           :attributes @(:attributes context)}))]
    (binding [*current* context]
      (try
        (let [result (body-fn)]
          (finish :ok)
          result)
        (catch Throwable failure
          (annotate! {:error (or (ex-message failure) (str (class failure)))})
          (finish :error)
          (throw failure))))))

(defmacro span
  "Times body as a span named span-name with a map of attributes."
  [span-name attributes & body]
  `(run-span ~span-name ~attributes (fn [] ~@body)))

(defn spans
  "Every span held, oldest first."
  []
  (vec @recorded))

(defn clear! [] (reset! recorded clojure.lang.PersistentQueue/EMPTY) nil)

(defn first-line
  "A statement cut to its first line and to length, for an attribute."
  ([text] (first-line text 90))
  ([text length]
   (let [line (str/trim (first (str/split-lines (str/trim (str text)))))]
     (if (> (count line) length) (str (subs line 0 (dec length)) "…") line))))

(defn- depth-first
  "The spans of one trace in the order a waterfall draws them, each with a
  :depth. A span whose parent was dropped from the buffer is drawn as a root."
  [trace-spans]
  (let [known-ids (set (map :span-id trace-spans))
        children (group-by #(when (known-ids (:parent-id %)) (:parent-id %)) trace-spans)
        walk (fn walk [depth span-row]
               (cons (assoc span-row :depth depth)
                     (mapcat #(walk (inc depth) %)
                             (sort-by :start-ms (children (:span-id span-row))))))]
    (vec (mapcat #(walk 0 %) (sort-by :start-ms (children nil))))))

(defn- as-trace
  [trace-id trace-spans]
  (let [ordered (depth-first trace-spans)
        started (apply min (map :start-ms ordered))
        ended (apply max (map #(+ (:start-ms %) (:duration-ms %)) ordered))]
    {:trace-id trace-id
     :name (:name (first ordered))
     :start-ms started
     :duration-ms (- ended started)
     :errors (count (filter #(= :error (:status %)) ordered))
     :spans (mapv #(assoc % :offset-ms (- (:start-ms %) started)) ordered)}))

(defn traces
  "The held spans as traces, newest first:
  {:trace-id :name :start-ms :duration-ms :errors :spans}, where :spans are
  ordered for drawing and carry :depth and :offset-ms from the trace's start."
  ([] (traces 20))
  ([limit]
   (->> (group-by :trace-id @recorded)
        (map (fn [[trace-id trace-spans]] (as-trace trace-id trace-spans)))
        (sort-by :start-ms >)
        (take limit)
        vec)))

(defn so-far
  "The spans that have ended inside the span being timed on this thread, as
  one trace in the shape `traces` gives, or nil when there are none or no
  span is open. The open span itself has not ended and is not in it: this
  is what a page can show of the request that is drawing it."
  []
  (when-let [trace-id (:trace-id *current*)]
    (when-let [ended (seq (filter #(= trace-id (:trace-id %)) @recorded))]
      (as-trace trace-id ended))))

(defn slowest
  "The slowest held spans with the given name prefix, as rows for a table."
  ([limit] (slowest limit ""))
  ([limit name-prefix]
   (->> @recorded
        (filter #(str/starts-with? (:name %) name-prefix))
        (sort-by :duration-ms >)
        (take limit)
        (mapv #(select-keys % [:name :duration-ms :status :attributes])))))

(defn summary
  "Per span name: how many, how many failed, and the median, 95th
  percentile and longest duration in ms."
  []
  (->> (group-by :name @recorded)
       (map (fn [[span-name named-spans]]
              (let [durations (vec (sort (map :duration-ms named-spans)))
                    at (fn [fraction] (nth durations (min (dec (count durations))
                                                          (int (* fraction (count durations))))))]
                {:name span-name
                 :count (count durations)
                 :errors (count (filter #(= :error (:status %)) named-spans))
                 :p50-ms (at 0.5)
                 :p95-ms (at 0.95)
                 :max-ms (peek durations)})))
       (sort-by :p95-ms >)
       vec))

(comment
 (span "outer" {:why "review"} (span "inner" {} (Thread/sleep 20) :done))
 ;; the inner span's :parent-id is the outer's id
 (map #(select-keys % [:name :duration-ms :parent-id]) (take-last 2 (spans)))
 (count (spans))
 (summary)
 (slowest 5)
 (first (traces))
 (clear!)
:review)
