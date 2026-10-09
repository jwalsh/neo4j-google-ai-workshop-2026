(ns query-ui.app
  "The replica's front end: plain DOM, one atom, the stream redrawn whole on
  every change. No React and no CodeMirror; the editor is a textarea that
  carries the real editor's role and aria-label.

  The data-testid, role and aria-label values are the Query tool's, so a
  driver or a Bombadil specification written for one finds the same things
  in the other. Two hooks are ours because the snapshot of the real page
  had no frame in that state to copy from: frame-spinner (a statement still
  running) and frame-error (a single statement that failed). success-icon in
  the multi-statement table is a guess by analogy with error-icon and
  aborted-icon, which are real."
  (:require [clojure.string :as str]))

(defonce app-state
  (atom {:next-frame-number 1
         ;; newest first
         :frames []}))

;; ------------------------------------------------------------------- DOM

(defn- element
  "A DOM element. attributes is a map of attribute name to value (nil values
  are skipped; :on-click takes a function); children are elements or
  strings."
  [tag-name attributes & children]
  (let [dom-element (.createElement js/document tag-name)]
    (doseq [[attribute-name attribute-value] attributes]
      (cond
        (nil? attribute-value) nil
        (= :on-click attribute-name) (.addEventListener dom-element "click" attribute-value)
        :else (.setAttribute dom-element (name attribute-name) (str attribute-value))))
    (doseq [child (flatten children)]
      (when (some? child)
        (.appendChild dom-element
                      (if (string? child) (.createTextNode js/document child) child))))
    dom-element))

(defn- to-json
  [value]
  (.stringify js/JSON (clj->js value) nil 2))

(defn- cell-text
  [value]
  (if (or (map? value) (sequential? value))
    (.stringify js/JSON (clj->js value))
    (str value)))

;; ----------------------------------------------------------------- state

(defn- update-frame
  [state frame-id update-function]
  (update state :frames
          (fn [frames]
            (mapv (fn [frame] (if (= frame-id (:id frame)) (update-function frame) frame))
                  frames))))

(defn remove-frame!
  [frame-id]
  (swap! app-state update :frames
         (fn [frames] (vec (remove #(= frame-id (:id %)) frames)))))

(defn select-view!
  [frame-id view-name]
  (swap! app-state update-frame frame-id #(assoc % :view view-name)))

(defn- default-view
  [outcome]
  (if (:graph outcome) "graphViz" "table"))

(defn- finish-frame
  "The frame with the server's answer laid over it."
  [frame frame-data]
  (let [outcome (:outcome frame-data)]
    (-> frame
        (assoc :running? false
               :kind (:kind frame-data)
               :outcome outcome
               :statements (:statements frame-data)
               :view (default-view outcome)))))

(defn- fail-frame
  [frame message]
  (assoc frame
         :running? false
         :kind "single"
         :outcome {:status "error" :code "Replica.BackEndUnreachable" :message message}))

(def run-timeout-ms
  "How long a frame may stay in its running state before the page gives up
  on the answer and shows an error instead. Added after Bombadil, on the
  live back end, showed that nothing bounded it (README, Log 9)."
  30000)

(defn run-editor-text!
  "Runs the editor's text: a frame goes on top of the stream in its running
  state, the editor is emptied, and the answer fills the frame in when it
  comes. Blank text does nothing."
  [editor-text]
  (when-not (str/blank? editor-text)
    (let [frame-id (str "frame-" (:next-frame-number @app-state))]
      (swap! app-state
             (fn [state]
               (-> state
                   (update :next-frame-number inc)
                   (update :frames #(into [{:id frame-id
                                            :command (str/trim editor-text)
                                            :running? true}]
                                          %)))))
      (let [abort-controller (js/AbortController.)
            timeout-id (js/setTimeout #(.abort abort-controller) run-timeout-ms)]
        (-> (js/fetch "/api/run" #js {:method "POST"
                                      :body editor-text
                                      :signal (.-signal abort-controller)})
            (.then (fn [response]
                     (if (.-ok response)
                       (.text response)
                       (throw (js/Error. (str "HTTP " (.-status response)))))))
            (.then (fn [response-text]
                     (let [frame-data (:frame (js->clj (.parse js/JSON response-text)
                                                       :keywordize-keys true))]
                       (swap! app-state update-frame frame-id
                              (if frame-data
                                #(finish-frame % frame-data)
                                #(fail-frame % "The back end returned no frame."))))))
            (.catch (fn [failure]
                      (swap! app-state update-frame frame-id
                             #(fail-frame % (str "No answer from the back end: "
                                                 (.-message failure))))))
            (.finally #(js/clearTimeout timeout-id)))))))

;; ------------------------------------------------------------- rendering

(def ^:private view-names
  [["graphViz" "Graph"] ["table" "Table"] ["raw" "Raw"]])

(defn- render-view-selector
  [{frame-id :id selected-view :view} outcome]
  (element "div" {:role "tablist" :aria-label "Frame view selector"}
           (for [[view-name view-label] view-names
                 :let [unavailable? (and (= "graphViz" view-name) (nil? (:graph outcome)))]]
             (element "button"
                      {:type "button"
                       :role "tab"
                       :id (str "tab-" frame-id "-" view-name)
                       :data-testid (str "result-view-" view-name)
                       :aria-controls (str "tabpanel-" frame-id)
                       :aria-selected (str (= view-name selected-view))
                       :aria-disabled (str unavailable?)
                       :on-click (when-not unavailable?
                                   (fn [_] (select-view! frame-id view-name)))}
                      view-label))))

(defn- render-table
  [{:keys [columns rows]}]
  (if (empty? rows)
    (element "p" {:data-testid "no-records"} "(no records)")
    (element "table" {}
             (element "thead" {}
                      (element "tr" {}
                               (for [column columns] (element "th" {} (name column)))))
             (element "tbody" {}
                      (for [row rows]
                        (element "tr" {}
                                 (for [column columns]
                                   (element "td" {} (cell-text (get row (keyword column)))))))))))

(defn- render-view-panel
  [{frame-id :id selected-view :view} outcome]
  (element "span" {:id (str "tabpanel-" frame-id)
                   :role "tabpanel"
                   :aria-labelledby (str "tab-" frame-id "-" selected-view)}
           (case selected-view
             "graphViz" (element "pre" {:role "img" :aria-label "Graph visualization"}
                                 (:graph outcome))
             "raw" (element "pre" {} (to-json (:rows outcome)))
             (render-table outcome))))

(defn- render-error
  [{:keys [code message]}]
  (element "div" {:data-testid "frame-error" :role "alert"}
           (element "span" {:data-testid "error-icon" :title "Error"} "error ")
           (element "strong" {} code)
           (element "pre" {} message)))

(def ^:private statement-status-titles
  {"success" "Success" "error" "Error" "aborted" "Aborted"})

(defn- render-statement-row
  [{:keys [number statement outcome]}]
  (let [status-name (:status outcome)]
    (element "div" {:role "row" :data-testid "multi-statement"}
             (element "div" {:role "cell"} (str number))
             (element "div" {:role "cell"}
                      (element "span" {:title (statement-status-titles status-name)
                                       :class (str "status-" status-name)}
                               (element "span" {:data-testid (str status-name "-icon")}
                                        status-name)))
             (element "div" {:role "cell"} (element "pre" {} statement)))))

(defn- render-multi-statement
  [{frame-id :id statements :statements}]
  [(element "div" {:role "tablist" :aria-label "Frame view selector"}
            (element "span" {:role "tab"
                             :aria-selected "true"
                             :data-testid "result-view-emptyResult"
                             :id (str "multistatement-overview-tab-" frame-id)
                             :aria-controls (str "multistatement-overview-" frame-id)}
                     "Results"))
   (element "div" {:id (str "multistatement-overview-" frame-id)
                   :role "tabpanel"
                   :aria-labelledby (str "multistatement-overview-tab-" frame-id)}
            (element "div" {:role "table"}
                     (element "div" {:role "rowgroup"}
                              (element "div" {:role "row"}
                                       (for [heading ["#" "Status" "Cypher query"]]
                                         (element "div" {:role "columnheader"}
                                                  (element "div" {:title heading} heading)))))
                     (element "div" {:role "rowgroup"}
                              (map render-statement-row statements))))])

(defn- render-results
  [{:keys [running? kind outcome] :as frame}]
  (element "div" {:data-testid "results-panel-content"}
           (cond
             running?
             (element "div" {:data-testid "frame-spinner" :role "progressbar"
                             :aria-label "Running"}
                      "running ...")

             (= "multi" kind)
             (render-multi-statement frame)

             (= "success" (:status outcome))
             [(render-view-selector frame outcome)
              (render-view-panel frame outcome)
              (element "span" {:data-testid "simple-frame-status-bar-right"}
                       (:status-line outcome))]

             :else
             (render-error outcome))))

(defn- render-frame
  [{frame-id :id command :command :as frame}]
  (element "div" {:data-testid "frame" :data-frame-id frame-id}
           (element "div" {:class "frame-head"}
                    (element "div" {:data-testid "frame-command"}
                             (element "span" {:class "prompt"} "neo4j$")
                             (element "span" {} command))
                    (element "button" {:type "button"
                                       :aria-label "Remove frame"
                                       :title "Remove frame"
                                       :id frame-id
                                       :on-click (fn [_] (remove-frame! frame-id))}
                             "x"))
           (render-results frame)))

(defn- render-stream!
  [stream-element frames]
  (.replaceChildren stream-element)
  (doseq [frame frames]
    (.appendChild stream-element (render-frame frame))))

;; ------------------------------------------------------------------ init

(defn- build-shell!
  "The editor, Run and an empty stream, inside query-app. Returns the
  stream element."
  [query-app-element]
  (let [editor-element (element "textarea"
                                {:role "textbox"
                                 :aria-label "Cypher Editor"
                                 ;; The class drivers find the real editor by.
                                 :class "cm-content"
                                 :spellcheck "false"
                                 :placeholder "Cypher; several statements with ; between them"})
        run-button (element "button"
                            {:type "button"
                             :data-testid "run-button"
                             :title "Run"
                             :on-click (fn [_]
                                         (let [editor-text (.-value editor-element)]
                                           (when (run-editor-text! editor-text)
                                             (set! (.-value editor-element) ""))))}
                            "Run")
        stream-element (element "div" {:data-testid "stream"})]
    (.replaceChildren query-app-element)
    (.appendChild query-app-element
                  (element "div" {:role "tabpanel" :id "tabpanel-stream"}
                           (element "div" {:data-testid "main-editor"}
                                    editor-element
                                    run-button)
                           stream-element))
    stream-element))

(defn ^:export init!
  []
  (when-let [query-app-element (.querySelector js/document "[data-testid=query-app]")]
    (let [stream-element (build-shell! query-app-element)]
      (remove-watch app-state ::render)
      (add-watch app-state ::render
                 (fn [_ _ previous-state current-state]
                   (when (not= (:frames previous-state) (:frames current-state))
                     (render-stream! stream-element (:frames current-state)))))
      (render-stream! stream-element (:frames @app-state)))))

(init!)
