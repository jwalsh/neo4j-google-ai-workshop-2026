(ns local-graph.embedded
  "Neo4j Community inside this JVM, listening on Bolt.

  org.neo4j/neo4j from Maven Central is the whole Community server as a
  library (GPL-3.0). `start!` opens a store under scratch/ and turns the Bolt
  connector on, so the repository's own driver wrapper connects to it the way
  it connects to Aura. No Docker, no install, nothing left running when the
  REPL exits."
  (:require [clojure.java.io :as io])
  (:import (java.nio.file Path)
           (org.neo4j.configuration GraphDatabaseSettings)
           (org.neo4j.configuration.connectors BoltConnector)
           (org.neo4j.configuration.helpers SocketAddress)
           (org.neo4j.dbms.api DatabaseManagementService DatabaseManagementServiceBuilder)
           (org.neo4j.io ByteUnit)))

(set! *warn-on-reflection* true)

(def default-port
  "Not 7687, so a Neo4j Desktop or a container on the usual port is left alone."
  7688)

(def default-home "scratch/neo4j-embedded")

(defonce ^{:doc "The running service and how it was started, or nil."} running
  (atom nil))

(defn- elapsed-milliseconds
  [started-nanoseconds]
  (/ (- (System/nanoTime) started-nanoseconds) 1e6))

(defn start!
  "Starts the embedded server unless one is running. Returns
  {:home :port :startup-ms}. Authentication is off, which is the embedded
  default: the driver's username and password are accepted whatever they are.

  :page-cache is a size string such as \"64m\"; left out, Neo4j sizes its
  page cache from the machine's RAM."
  ([] (start! {}))
  ([{:keys [home port page-cache] :or {home default-home port default-port}}]
   (if-let [already-running @running]
     (dissoc already-running :service)
     (let [started-nanoseconds (System/nanoTime)
           home-directory (.toAbsolutePath (Path/of ^String home (make-array String 0)))
           ^DatabaseManagementServiceBuilder builder
           (-> (DatabaseManagementServiceBuilder. home-directory)
               (.setConfig BoltConnector/enabled true)
               (.setConfig BoltConnector/listen_address
                           (SocketAddress. "localhost" (int port))))
           ^DatabaseManagementServiceBuilder builder
           (if page-cache
             (.setConfig builder GraphDatabaseSettings/pagecache_memory
                         (Long/valueOf (ByteUnit/parse ^String page-cache)))
             builder)
           service (.build builder)
           started {:service service
                    :home (str home-directory)
                    :port port
                    :startup-ms (elapsed-milliseconds started-nanoseconds)}]
       (reset! running started)
       (dissoc started :service)))))

(defn stop!
  "Shuts the embedded server down. Returns how long that took, or nil when
  nothing was running."
  []
  (when-let [{:keys [^DatabaseManagementService service]} @running]
    (let [started-nanoseconds (System/nanoTime)]
      (.shutdown service)
      (reset! running nil)
      {:shutdown-ms (elapsed-milliseconds started-nanoseconds)})))

(defn wipe!
  "Deletes a stopped server's store, so the next start! is an empty database."
  ([] (wipe! default-home))
  ([home]
   (when @running
     (throw (ex-info "Stop the server before wiping its store." {:home home})))
   (let [home-directory (io/file home)]
     (when (.exists home-directory)
       (run! io/delete-file (reverse (file-seq home-directory))))
     (str home-directory))))

(defn connection-env
  "The env map workshop.neo4j/connect takes, naming the embedded server. This
  is how the hosted instance in .env is bypassed without editing .env:
  connect has a one-argument arity and nothing here calls the other one."
  ([] (connection-env (or (:port @running) default-port)))
  ([port]
   {"NEO4J_URI" (str "bolt://localhost:" port)
    "NEO4J_USERNAME" "neo4j"
    "NEO4J_PASSWORD" "not-checked"
    "NEO4J_DATABASE" "neo4j"}))

(defn -main
  "clojure -M:serve. Starts the server on the store already under scratch/
  and waits, so cypher-shell or an Org sh block has something to talk to.
  Ctrl-C stops it."
  [& _]
  (let [started (start!)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop!))))
    (println (str "bolt://localhost:" (:port started) " up in "
                  (long (:startup-ms started)) " ms; store " (:home started)))
    (flush)
    @(promise)))
