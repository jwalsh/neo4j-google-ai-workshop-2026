(ns query-ui.bombadil
  "Runs Bombadil (Antithesis's property-based tester for web UIs; the binary
  on PATH, 0.7.8 here) against the replica or against Aura's Query tool, and
  reads its trace.

    clojure -M:bombadil replica [seconds]
    clojure -M:bombadil witnesses [seconds]
    clojure -M:bombadil external-replica [seconds]
    clojure -M:bombadil aura QUERY-TOOL-URL [seconds]
    clojure -M:bombadil summary TRACE-DIRECTORY

  `replica` and `witnesses` let Bombadil start its own headless browser.
  `aura` cannot: `bombadil browser test` takes no profile directory, so the
  lab account's session has to come from a browser started here on a COPY
  of the lab profile, which Bombadil then joins with `test-external`.
  `external-replica` is that same path with an empty profile and the replica
  as the page, so the path can be exercised without the lab account.

  Every browser started here is headless. None is ever attached to on port
  9333, which belongs to the lab browser a person may be using."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.net HttpURLConnection URI)
           (java.util.concurrent TimeUnit)))

(def replica-origin "http://127.0.0.1:8378/")

(def replica-query-path-origin
  "The replica again, at a path shaped like the real tool's, so that the
  Aura specification's staysInQueryTool can hold there."
  "http://127.0.0.1:8378/projects/replica/studio/query")

(def browser-binary
  "The browser for `aura`. On this machine /opt/homebrew/bin/chromium reports
  itself as Google Chrome 154, the build the lab profile was written by."
  "/opt/homebrew/bin/chromium")

(def lab-profile-directory
  "The lab browser's profile. Read, never written, and only by `aura`."
  (str "/private/tmp/claude-501/-Users-jasonwalsh-ghq-github-com-jwalsh-"
       "neo4j-google-ai-workshop-2026/2924f98f-a0ba-4bb5-b3cd-04e2ca35011e/"
       "scratchpad/chromium-lab-profile"))

(def lab-browser-port
  "Not ours. Refused wherever a port is taken as an argument."
  9333)

(def own-debugging-port 9444)

(def profile-copy-directory "scratch/lab-profile-copy")

(def ^:private profile-copy-exclusions
  "Caches, and the locks that say another browser has the profile open."
  ["Cache" "Code Cache" "GPUCache" "GraphiteDawnCache" "GPUPersistentCache"
   "DawnGraphiteCache" "DawnWebGPUCache" "BrowserMetrics*" "Safe Browsing"
   "component_crx_cache" "Singleton*" "RunningChromeVersion" "LOCK"])

;; ------------------------------------------------------------- processes

(defn- start-process
  "Starts a command with its output appended to log-path. Returns the
  Process."
  ^Process [command-arguments log-path]
  (io/make-parents log-path)
  (-> (ProcessBuilder. ^java.util.List (mapv str command-arguments))
      (.redirectErrorStream true)
      (.redirectOutput (ProcessBuilder$Redirect/appendTo (io/file log-path)))
      (.start)))

(defn- run-process
  "Runs a command to its end, or kills it after timeout-seconds. Returns
  {:exit n} or {:exit :timed-out}."
  [command-arguments log-path timeout-seconds]
  (let [process (start-process command-arguments log-path)]
    (if (.waitFor process (long timeout-seconds) TimeUnit/SECONDS)
      {:exit (.exitValue process)}
      (do (.destroyForcibly process)
          {:exit :timed-out}))))

(defn- http-ok?
  [url]
  (try
    (let [connection ^HttpURLConnection (.openConnection (.toURL (URI. url)))]
      (.setConnectTimeout connection 500)
      (.setReadTimeout connection 500)
      (= 200 (.getResponseCode connection)))
    (catch Exception _ false)))

(defn- wait-until
  [ready? attempts]
  (loop [attempts-left attempts]
    (cond
      (ready?) true
      (zero? attempts-left) false
      :else (do (Thread/sleep 250) (recur (dec attempts-left))))))

;; ------------------------------------------------------------- commands

(defn managed-command
  "`bombadil browser test`: Bombadil starts and stops its own headless
  browser. This is the command for the replica."
  [{:keys [origin specification output-path seconds]}]
  ["bombadil" "browser" "test" "--headless"
   "--time-limit" (str seconds "s")
   "--output-path" output-path "--output-path-overwrite"
   origin specification])

(defn external-command
  "`bombadil browser test-external`: Bombadil joins a browser that is
  already running, opens its own tab there, and leaves the browser up."
  [{:keys [origin specification output-path seconds debugging-port]}]
  (when (= lab-browser-port debugging-port)
    (throw (ex-info "Port 9333 is the lab browser. Not attaching." {})))
  ["bombadil" "browser" "test-external"
   "--remote-debugger" (str "http://127.0.0.1:" debugging-port)
   "--create-target"
   "--time-limit" (str seconds "s")
   "--output-path" output-path "--output-path-overwrite"
   origin specification])

(defn headless-browser-command
  [{:keys [profile-directory debugging-port]}]
  (when (= lab-browser-port debugging-port)
    (throw (ex-info "Port 9333 is the lab browser. Not starting there." {})))
  (when (= (.getCanonicalPath (io/file profile-directory))
           (.getCanonicalPath (io/file lab-profile-directory)))
    (throw (ex-info "That is the lab profile itself. Work from a copy." {})))
  [browser-binary
   "--headless=new"
   (str "--user-data-dir=" (.getCanonicalPath (io/file profile-directory)))
   (str "--remote-debugging-port=" debugging-port)
   "--remote-debugging-address=127.0.0.1"
   "--no-first-run" "--no-default-browser-check"
   "--window-size=1280,900"
   "about:blank"])

(defn profile-copy-command
  [source-directory destination-directory]
  (-> ["rsync" "-a" "--delete"]
      (into (mapcat (fn [exclusion] ["--exclude" exclusion])) profile-copy-exclusions)
      (conj (str source-directory "/") (str destination-directory "/"))))

;; ----------------------------------------------------------------- trace

(defn trace-summary
  "What a run did, from OUTPUT-PATH/trace.jsonl: how many actions of each
  kind, and for each violated property how often and at which step first."
  [output-path]
  (let [trace-file (io/file output-path "trace.jsonl")]
    (when (.exists trace-file)
      (with-open [trace-reader (io/reader trace-file)]
        (let [entries (mapv #(json/read-str % :key-fn keyword) (line-seq trace-reader))
              action-kind (fn [{:keys [action]}]
                            (cond (map? action) (name (key (first action)))
                                  (nil? action) "(start)"
                                  :else (str action)))
              violations (for [[step-number entry] (map-indexed vector entries)
                               violation (:violations entry)]
                           {:property (:name violation) :step step-number})]
          {:steps (count entries)
           :actions (into (sorted-map) (frequencies (map action-kind entries)))
           :violations (into (sorted-map)
                             (map (fn [[property-name occurrences]]
                                    [property-name {:count (count occurrences)
                                                    :first-step (apply min (map :step occurrences))}]))
                             (group-by :property violations))})))))

;; ------------------------------------------------------------------ runs

(defn- report
  [run-name command result output-path log-path]
  (println run-name "exit" (:exit result))
  (println "  command:" (str/join " " command))
  (println "  log:    " log-path)
  (println "  inspect: bombadil browser inspect" output-path)
  (println "  summary:" (pr-str (trace-summary output-path)))
  (assoc result :summary (trace-summary output-path)))

(defn run-managed!
  "Bombadil's own headless browser against origin with a specification."
  [run-name origin specification seconds]
  (let [output-path (str "scratch/bombadil/" run-name)
        log-path (str "scratch/bombadil/" run-name ".log")
        command (managed-command {:origin origin :specification specification
                                  :output-path output-path :seconds seconds})]
    (io/delete-file log-path true)
    ;; Twice the limit plus a minute: Bombadil has been seen to outlive its
    ;; own time limit when a state capture stalls (README, Log).
    (report run-name command
            (run-process command log-path (+ 60 (* 2 seconds)))
            output-path log-path)))

(defn run-external!
  "A headless browser of ours on profile-directory, Bombadil joined to it,
  then the browser stopped whatever happened."
  [run-name origin specification seconds profile-directory]
  (let [output-path (str "scratch/bombadil/" run-name)
        log-path (str "scratch/bombadil/" run-name ".log")
        browser-log-path (str "scratch/bombadil/" run-name ".browser.log")
        browser-process (start-process
                         (headless-browser-command {:profile-directory profile-directory
                                                    :debugging-port own-debugging-port})
                         browser-log-path)
        command (external-command {:origin origin :specification specification
                                   :output-path output-path :seconds seconds
                                   :debugging-port own-debugging-port})]
    (io/delete-file log-path true)
    (try
      (if (wait-until #(http-ok? (str "http://127.0.0.1:" own-debugging-port "/json/version")) 60)
        (report run-name command
                (run-process command log-path (+ 60 (* 2 seconds)))
                output-path log-path)
        (do (println "The headless browser did not open port" own-debugging-port
                     "; see" browser-log-path)
            {:exit :no-browser}))
      (finally
        (.destroy browser-process)
        (when-not (.waitFor browser-process 5 TimeUnit/SECONDS)
          (.destroyForcibly browser-process))))))

(defn run-replica!
  ([] (run-replica! 60))
  ([seconds] (run-managed! "replica" replica-origin "spec/replica.ts" seconds)))

(defn run-witnesses!
  ([] (run-witnesses! 30))
  ([seconds] (run-managed! "witnesses" replica-origin "spec/witnesses.ts" seconds)))

(defn run-external-replica!
  "The Aura specification and the test-external path, pointed at the
  replica, with an empty profile. No lab account involved."
  ([] (run-external-replica! 30))
  ([seconds]
   (let [profile-directory "scratch/empty-profile"]
     (.mkdirs (io/file profile-directory))
     (run-external! "external-replica" replica-query-path-origin "spec/aura.ts" seconds profile-directory))))

(defn copy-lab-profile!
  "Copies the lab profile to scratch/lab-profile-copy, without caches or
  locks. The copy holds the lab account's signed-in session."
  []
  (.mkdirs (io/file profile-copy-directory))
  (run-process (profile-copy-command lab-profile-directory profile-copy-directory)
               "scratch/bombadil/profile-copy.log" 300))

(defn run-aura!
  "The Aura specification against the real Query tool at query-tool-url,
  as the lab account, for about two minutes by default. See README.org,
  'The real page', for what this has and has not been seen to do."
  ([query-tool-url] (run-aura! query-tool-url 120))
  ([query-tool-url seconds]
   (when-not (re-find #"^https://console(-preview)?\.neo4j\.io/.*/query" query-tool-url)
     (throw (ex-info "Not a Query tool URL on console.neo4j.io." {:url query-tool-url})))
   (let [copy-result (copy-lab-profile!)]
     (if (= 0 (:exit copy-result))
       (run-external! "aura" query-tool-url "spec/aura.ts" seconds profile-copy-directory)
       (do (println "Profile copy failed:" copy-result) copy-result)))))

(defn -main
  [& [target & arguments]]
  (let [seconds-from (fn [text default-seconds]
                       (if text (Integer/parseInt text) default-seconds))
        result (case target
                 "replica" (run-replica! (seconds-from (first arguments) 60))
                 "witnesses" (run-witnesses! (seconds-from (first arguments) 30))
                 "external-replica" (run-external-replica! (seconds-from (first arguments) 30))
                 "aura" (run-aura! (first arguments) (seconds-from (second arguments) 120))
                 "summary" (do (prn (trace-summary (first arguments))) {:exit 0})
                 (do (println "replica | witnesses | external-replica | aura URL | summary DIR")
                     {:exit 2}))]
    (shutdown-agents)
    (System/exit (if (number? (:exit result)) (:exit result) 1))))
