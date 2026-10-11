(ns workshop.preferences
  "What one person asked the pages to do for them: which graph comes first
  and whether a statement is shown folded. Nothing else is kept.

  Who is asking. The pages have no sign-in of their own and do not grow
  one. Alone on a laptop there is one person, the owner. Behind a proxy
  that signs people in, WORKSHOP_USER_HEADER names the header the proxy
  sets to the account's name, and that header is believed, which is only
  sound because the server listens on loopback and the proxy is the one
  way in. Unset, no header is ever read. Set, a request without a
  well-formed name has no identity: it is never taken for the owner.

  Where it is kept. WORKSHOP_PREFERENCES, else
  $XDG_DATA_HOME/workshop/preferences, else ~/.local/share/..., one file a
  person, written whole and then renamed. It is personal, so like the
  notes on books it is never in the repository. The file's name is a
  digest of the account's name and the name is not in the file: the store
  can be listed without learning who has used the pages. Forgetting is
  deleting the file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files StandardCopyOption)
           (java.security MessageDigest)
           (java.time Instant)))

(def defaults
  {:graph nil          ; an id in agents/registry.edn, shown first and asked by default
   :statements :auto}) ; :auto folds a long statement, :open never, :folded always

(def statement-choices [:auto :open :folded])

(defn store-root
  ([] (store-root (System/getenv)))
  ([settings]
   (or (not-empty (get settings "WORKSHOP_PREFERENCES"))
       (str (io/file (or (not-empty (get settings "XDG_DATA_HOME"))
                         (io/file (System/getProperty "user.home") ".local" "share"))
                     "workshop" "preferences")))))

(def ^:private well-formed-name #"[A-Za-z0-9][A-Za-z0-9-]{0,38}")

(defn- digest-of
  [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str text) "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn who
  "The person a request is from, or nil when a proxy is expected and has
  not said. `header-value` is a function of a header's name.
  {:key :name :source}: the key names the file, the name is only shown
  back to its owner."
  [settings header-value]
  (if-let [header-name (not-empty (get settings "WORKSHOP_USER_HEADER"))]
    (let [given (some-> (header-value header-name) str/trim)]
      (when (and given (re-matches well-formed-name given))
        {:key (digest-of (str/lower-case given)) :name given :source :proxy}))
    {:key "owner" :name nil :source :local}))

(defn checked
  "What a form sent, cut down to what is kept. Unknown names are dropped
  and a value that is not on offer becomes the default."
  [form graph-ids]
  (let [graph (get form "graph")
        statements (keyword (str (get form "statements")))]
    {:graph (when (some #{graph} graph-ids) graph)
     :statements (if (some #{statements} statement-choices) statements :auto)}))

(defn- file-of
  [root person-key]
  (when-not (re-matches #"[a-z0-9]{1,64}" (str person-key))
    (throw (ex-info "Not a key for a preferences file." {})))
  (io/file root (str person-key ".edn")))

(defn recall
  "This person's preferences, the defaults where they have said nothing or
  the file cannot be read."
  [root person-key]
  (let [stored (try (let [preferences-file (file-of root person-key)]
                      (when (.exists preferences-file) (edn/read-string (slurp preferences-file))))
                    (catch Exception _ nil))]
    (merge defaults (select-keys (when (map? stored) stored) (keys defaults)))))

(defn record!
  "Keeps these preferences for this person and returns them as kept."
  [root person-key preferences]
  (let [preferences-file (file-of root person-key)
        kept (assoc (merge defaults (select-keys preferences (keys defaults)))
                    :version 1 :at (str (Instant/now)))
        part-file (io/file root (str person-key "." (System/nanoTime) ".part"))]
    (.mkdirs (io/file root))
    (spit part-file (pr-str kept))
    (Files/move (.toPath part-file) (.toPath preferences-file)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                      StandardCopyOption/REPLACE_EXISTING]))
    kept))

(defn forget!
  "Deletes what is kept for this person. True when there was something."
  [root person-key]
  (.delete (file-of root person-key)))

(defn same-origin?
  "Whether a request that changes something came from one of these pages.
  The browser's Origin, or failing that its Referer, has to name the host
  the request was addressed to, or the public address when a proxy stands
  in front (WORKSHOP_PUBLIC_ORIGIN). Neither header: no."
  [settings header-value]
  (let [authority (fn [address]
                    (some-> address (->> (re-find #"^https?://([^/]+)")) second str/lower-case))
        came-from (or (authority (header-value "Origin")) (authority (header-value "Referer")))
        addressed-to (remove nil? [(some-> (header-value "Host") str/lower-case)
                                   (authority (get settings "WORKSHOP_PUBLIC_ORIGIN"))])]
    (boolean (and came-from (some #{came-from} addressed-to)))))

(defn parse-form
  "A urlencoded body as a map of names to values."
  [body]
  (into {}
        (keep (fn [pair]
                (let [[field-name field-value] (str/split pair #"=" 2)]
                  (when-not (str/blank? field-name)
                    [(java.net.URLDecoder/decode field-name "UTF-8")
                     (java.net.URLDecoder/decode (str field-value) "UTF-8")]))))
        (str/split (str body) #"&")))
