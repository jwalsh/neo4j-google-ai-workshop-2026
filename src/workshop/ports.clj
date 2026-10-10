(ns workshop.ports
  "Which ports this checkout's servers listen on, claimed in portclaim, the
  estate's registry of who holds which dev port (aygp-dr/portclaim).

  The main checkout keeps the ports the notes name: 8377 for the dashboard
  and 7688 to 7691 for the graphs. A git worktree takes a slot of ten of its
  own from 11000, so two checkouts can each run a books graph and neither
  loads its notes into the other's. The slot a branch is offered first is a
  hash of its name, so the same branch gets the same ports on any machine
  and they can be said aloud. The hash only says where to start looking:
  the registry warns against deriving a port from a name, since two names
  can land on one slot, so a slot in which the registry holds anything or
  anything listens here is passed over for the next one.

  What a checkout was given is in .env.ports, which is ignored. Every
  command reads it through `workshop.env`: a graph profile's loopback port
  is replaced by the block's, so books-graph.env stays as written and means
  this checkout's books graph. bb.edn's local-* tasks read the same file.

  Only the services asked for are claimed. The registry's rule is that a
  reservation nobody uses is worse than none, so a block's other ports are
  left unclaimed until something is going to listen on them; the one claim
  is what marks the block as taken. PORTCLAIM is the registry's address,
  default this machine: it is a fact about a network and is in no file here."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [workshop.env :as env])
  (:import (java.net InetAddress InetSocketAddress Socket URLEncoder)
           (java.util.zip CRC32)))

(def services
  "Each service's place in a block and what it is, in the order of a block."
  [["dashboard" "the status page, HTTP"]
   ["filings" "the filings graph, embedded Neo4j, Bolt"]
   ["papers" "the papers graph, embedded Neo4j, Bolt"]
   ["books" "the books graph, embedded Neo4j, Bolt"]
   ["transit" "the transit graph, embedded Neo4j, Bolt"]])

(def block-size 10)
(def first-block 11000)
(def block-count 100)

(def ports-file ".env.ports")

(defn variable [service] (str (str/upper-case service) "_PORT"))

(defn block-ports
  "The ports of the block starting at base, by service."
  [base]
  (into {} (map-indexed (fn [offset [service]] [service (+ base offset)])) services))

(defn main-ports
  "The main checkout's ports, by service: the ones `workshop.env` has as
  defaults."
  []
  (into {} (map (fn [[service]] [service (env/default-ports (variable service))])) services))

(defn assigned
  "This checkout's ports by service: .env.ports where it gives one, else the
  main checkout's."
  []
  (let [given (env/assigned-ports)]
    (into {} (map (fn [[service default-port]] [service (get given (variable service) default-port)]))
          (main-ports))))

(defn preferred-slot
  "Which slot a branch is offered first: a checksum of its name. The same on
  every machine and in every process, which Clojure's own hash is not
  promised to be."
  [branch]
  (let [checksum (doto (CRC32.) (.update (.getBytes (str branch) "UTF-8")))]
    (int (mod (.getValue checksum) block-count))))

(defn free-block
  "The base of the first slot, looking from the preferred one and round
  again to it, with none of its ten ports in taken, a set; nil when every
  slot has something in it."
  ([taken] (free-block taken 0))
  ([taken preferred]
   (first (for [step (range block-count)
                :let [base (+ first-block (* block-size (mod (+ preferred step) block-count)))]
                :when (not-any? taken (range base (+ base block-size)))]
            base))))

(defn listening?
  "Whether something on this machine accepts connections on the port. The
  registry records what was said, not what is bound, so both are asked."
  [port]
  (try (with-open [probe (Socket.)]
         (.connect probe (InetSocketAddress. "127.0.0.1" (int port)) 200)
         true)
       (catch Exception _ false)))

(defn slug
  [text]
  (-> (str/lower-case (str text)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn service-name
  "The name a claim is made under: host, workshop, service and, off main,
  the branch. What is asserted, not what the directory happens to be."
  [host branch service]
  (str/join "-" (cond-> [(slug host) "workshop" service]
                  (not (#{"main" "master"} branch)) (conj (slug branch)))))

(defn mandate
  [what host branch]
  (str what " — " host " " (System/getProperty "os.name") " " (System/getProperty "os.version") " — "
       (if (#{"main" "master"} branch) "standing service" (str "until " branch " merges"))))

;; The checkout and the registry.

(defn- git
  [& arguments]
  (let [process (.start (ProcessBuilder. ^java.util.List (into ["git"] arguments)))]
    (str/trim (slurp (.getInputStream process)))))

(defn checkout
  "{:branch :main? :repo :host}. A worktree's git directory is not the
  common one; that is the whole test for main."
  []
  {:branch (git "rev-parse" "--abbrev-ref" "HEAD")
   :main? (= (.getCanonicalPath (io/file (git "rev-parse" "--git-dir")))
             (.getCanonicalPath (io/file (git "rev-parse" "--git-common-dir"))))
   :repo (or (second (re-find #"[:/]([^/:]+/[^/]+?)(?:\.git)?$" (git "remote" "get-url" "origin"))) "")
   :host (first (str/split (.getHostName (InetAddress/getLocalHost)) #"\."))})

(defn registry [] (or (not-empty (System/getenv "PORTCLAIM")) "http://127.0.0.1:4700"))

(defn- request
  "{:status :body}, the body read as JSON where it is JSON. Sent with curl,
  as the registry's own instructions do, and for a reason on a Mac: the
  system lets its own curl reach the local network and refuses a JVM, so
  Java's client cannot see a registry on another machine and curl can."
  [method path body]
  (let [command (cond-> ["curl" "-s" "-m" "8" "-w" "\n%{http_code}"
                         "-X" (str/upper-case (name method))
                         "-H" "Content-Type: application/json"]
                  body (into ["-d" (json/write-str body)])
                  true (conj (str (registry) path)))
        process (.start (ProcessBuilder. ^java.util.List command))
        output (slurp (.getInputStream process))
        [_ text status] (re-matches #"(?s)(.*)\n(\d{3})" output)]
    (when (or (not (zero? (.waitFor process))) (nil? status) (= "000" status))
      (throw (ex-info (str "No registry at " (registry) ": set PORTCLAIM to where portclaim runs.") {})))
    {:status (parse-long status)
     :body (try (json/read-str text :key-fn keyword) (catch Exception _ text))}))

(defn claims
  "Every claim the registry holds, as it gives them."
  []
  (:body (request :get "/ports" nil)))

(defn status
  "This checkout's ports with what the registry and this machine say of each:
  [{:service :port :held-by :ours? :listening?}]."
  []
  (let [{:keys [host branch]} (checkout)
        held (into {} (map (juxt :port :service)) (claims))]
    (vec (for [[service port] (sort-by val (assigned))]
           {:service service :port port
            :held-by (held port)
            :ours? (= (held port) (service-name host branch service))
            :listening? (listening? port)}))))

(defn- write-ports-file!
  [ports-by-service]
  (spit ports-file
        (str "# This checkout's ports, as bb ports claim found them. Ignored by git.\n"
             (str/join (for [[service] services] (str (variable service) "=" (ports-by-service service) "\n"))))))

(defn claim!
  "Claims the named services' ports for this checkout and returns one row
  each. The main checkout claims the ports it has always had. A worktree
  with no .env.ports is first given a slot, its branch's own if that is free
  in the registry and on this machine and else the next that is, and the file is written once a claim in it
  has been accepted. A port already held under this checkout's own name is
  left alone: claiming it again would reset when it was taken."
  [wanted-services]
  (let [known (set (map first services))
        _ (when-let [unknown (seq (remove known wanted-services))]
            (throw (ex-info (str "No service " (str/join ", " unknown) ". There are: " (str/join ", " (map first services)) ".") {})))
        _ (when (empty? wanted-services)
            (throw (ex-info (str "Name what will listen: " (str/join ", " (map first services))
                                 ". Only what is named is claimed.") {})))
        {:keys [host branch main? repo]} (checkout)
        held (into {} (map (juxt :port :service)) (claims))
        has-file? (.exists (io/file ports-file))
        ports-by-service (cond
                           has-file? (assigned)
                           main? (main-ports)
                           :else (let [busy (into (set (keys held))
                                                  (filter listening?)
                                                  (range first-block (+ first-block (* block-size block-count))))]
                                   (block-ports (or (free-block busy (preferred-slot branch))
                                                    (throw (ex-info "No block is free." {}))))))
        rows (vec (for [service wanted-services]
                    (let [port (ports-by-service service)
                          claim-name (service-name host branch service)]
                      (if (= (held port) claim-name)
                        {:service service :port port :claim "already ours"}
                        (let [{:keys [status body]}
                              (request :post "/claim"
                                       {:port port :service claim-name :repo repo :branch branch
                                        :mandate (mandate (some (fn [[known-service what]] (when (= known-service service) what)) services)
                                                          host branch)
                                        :harness_version "bb ports" :kind "real"})]
                          {:service service :port port
                           :claim (case (int status)
                                    201 "claimed"
                                    409 (str "held by " (or (held port) (:detail body) "another service"))
                                    403 "reserved"
                                    (str "refused, " status))})))))]
    (when (and (not main?) (some (comp #{"claimed" "already ours"} :claim) rows))
      (write-ports-file! ports-by-service))
    rows))

(defn release!
  "Gives back the named services' ports, or all this checkout holds, and
  removes .env.ports once it holds none. Only a claim under this checkout's
  own name is released; nothing is forced."
  [wanted-services]
  (let [{:keys [host branch]} (checkout)
        ours (filter :ours? (status))
        chosen (if (seq wanted-services) (filter (comp (set wanted-services) :service) ours) ours)
        rows (vec (for [{:keys [service port]} chosen]
                    {:service service :port port
                     :release (let [{:keys [status]}
                                    (request :delete
                                             (str "/claim/" port "?service="
                                                  (URLEncoder/encode (service-name host branch service) "UTF-8"))
                                             nil)]
                                (if (<= 200 status 299) "released" (str "refused, " status)))}))]
    (when (and (.exists (io/file ports-file)) (not-any? :ours? (status)))
      (io/delete-file ports-file))
    rows))

(comment
 ;; no registry, no git
 (block-ports 11020)
 (preferred-slot "proto/ui")
 (free-block #{11000 11015} 0)
 (service-name "macbookpro" "proto/ui" "books")
 (mandate "the books graph" "macbookpro" "main")
 ;; this checkout
 (assigned)
 (checkout)
 ;; needs the registry: PORTCLAIM
 (status)
 :review)
