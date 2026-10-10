(ns workshop.annotations
  "Notes on the shelf's books, kept outside the repository and projected
  into the books graph:

    (:Annotation)-[:ANNOTATES]->(:Book)
    (:Annotation)-[:AT]->(:Heading)       the section its page falls in
    (:Annotation)-[:ABOUT]->(:Concept)    an entry of some book's index

  The store is BOOKS_ANNOTATIONS, else $XDG_DATA_HOME/workshop-books/annotations,
  else ~/.local/share/workshop-books/annotations. It is data, not cache: the
  outlines can be read out of the PDFs again and a note cannot. A note may
  quote a book, so like the PDFs it is never in the repository, and a git
  worktree, which has none of the ignored files, reaches the same store.

  Each note is one file, <book-id>/<id>.edn, written whole and then renamed.
  No file is ever rewritten, so two writers at once cannot lose each other's
  work, and one store is merged into another by copying the files it lacks.
  An agent that should not touch the owner's notes is given its own
  BOOKS_ANNOTATIONS, and `merge!` brings back what is worth keeping."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [workshop.books :as books]
            [workshop.neo4j :as neo4j])
  (:import (java.nio.file CopyOption Files StandardCopyOption)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(defn store-dir
  "Where notes are kept: BOOKS_ANNOTATIONS, else the XDG data directory."
  []
  (or (not-empty (System/getenv "BOOKS_ANNOTATIONS"))
      (str (io/file (or (not-empty (System/getenv "XDG_DATA_HOME"))
                        (io/file (System/getProperty "user.home") ".local" "share"))
                    "workshop-books" "annotations"))))

(defn author
  "Who a note is by: WORKSHOP_AUTHOR, else the account's name. An agent is
  given its own, so a merged store still says who wrote what."
  []
  (or (not-empty (System/getenv "WORKSHOP_AUTHOR")) (System/getProperty "user.name")))

(def ^:private id-time (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") ZoneOffset/UTC))

(defn new-id
  "A note's id: when it was written, to the second, and eight random hex
  digits. Ids sort by time and two writers do not make the same one."
  [^Instant written-at]
  (str (.format id-time written-at) "-" (subs (str (random-uuid)) 0 8)))

(defn problems
  "What is wrong with a note, as sentences; empty when it can be kept. The
  book's id becomes a directory name, so it is held to the shelf's own
  alphabet."
  [{:keys [book page text concepts] note-quote :quote}]
  (cond-> []
    (not (and (string? book) (re-matches #"[a-z0-9][a-z0-9-]*" book)))
    (conj "book must be a shelf id: lower-case letters, digits and hyphens")
    (not (or (nil? page) (pos-int? page)))
    (conj "page must be a whole number from 1, the PDF's own count, or left out")
    (and (str/blank? text) (str/blank? note-quote))
    (conj "a note needs text or a quote")
    (not (every? string? concepts))
    (conj "concepts must be strings")))

(defn- note-file [directory book note-id] (io/file directory book (str note-id ".edn")))

(defn annotate!
  "Keeps one note and returns it as kept. The note is
  {:book id, :page n, :text \"...\", :quote \"...\", :concepts [\"...\"]}; only
  :book and one of :text and :quote are needed. :id, :by and :at are added
  unless given, which is how `merge!` and a test keep theirs."
  ([note] (annotate! (store-dir) note))
  ([directory note]
   (when-let [found (seq (problems note))]
     (throw (ex-info (str "Not kept: " (str/join "; " found) ".") {:note note})))
   (let [written-at (.truncatedTo (Instant/now) ChronoUnit/SECONDS)
         kept (merge {:id (new-id written-at) :by (author) :at (str written-at)}
                     (into {} (remove (comp #(or (nil? %) (and (coll? %) (empty? %))) val)) note))
         target (note-file directory (:book kept) (:id kept))
         partial-file (io/file (str target ".part"))]
     (io/make-parents target)
     (spit partial-file (with-out-str (pprint/pprint kept)))
     (Files/move (.toPath partial-file) (.toPath target)
                 (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
     kept)))

(defn annotations
  "Every note in the store, oldest first, or one book's."
  ([] (annotations (store-dir) nil))
  ([book] (annotations (store-dir) book))
  ([directory book]
   (->> (file-seq (io/file directory))
        (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".edn")))
        (map (comp edn/read-string slurp))
        (filter #(or (nil? book) (= book (:book %))))
        (sort-by :id)
        vec)))

(defn merge!
  "Copies into the store every note of another store that it does not have,
  by id. Nothing is overwritten and nothing is removed from either."
  ([from-directory] (merge! (store-dir) from-directory))
  ([directory from-directory]
   (let [incoming (annotations from-directory nil)
         copied (vec (for [note incoming
                           :when (not (.exists (note-file directory (:book note) (:id note))))]
                       (:id (annotate! directory note))))]
     {:copied (count copied) :already-here (- (count incoming) (count copied)) :into (str directory)})))

(defn heading-position
  "Which of a book's outline entries a page falls under: the last one that
  starts on or before it. nil before the first heading or with no page."
  [outline-entries page]
  (when page
    (last (keep-indexed (fn [position entry] (when (<= (:page entry) page) position)) outline-entries))))

(defn rows
  "The notes as the graph takes them: each with the id of the heading its
  page falls under, as `workshop.books/graph` numbers headings, and its
  concepts under the names the books' indexes are joined on."
  [notes outlines-by-id]
  (vec (for [{:keys [book page concepts] :as note} notes]
         (let [position (heading-position (outlines-by-id book) page)]
           {"id" (:id note) "book" book "page" page "text" (:text note) "quote" (:quote note)
            "by" (:by note) "at" (:at note)
            "heading" (when position (str book "#" position))
            "concepts" (vec (distinct (map books/concept-name concepts)))}))))

(defn load!
  "Replaces the graph's notes with the store's: the store is what is true,
  and a note deleted there goes from the graph. Run after the books are
  loaded, since that load replaces the concepts a note points at. Returns
  how many notes there are and how many found a book, a heading and a
  concept; a note on a book the shelf does not have is kept and joins
  nothing."
  [driver note-rows]
  (neo4j/query driver "CREATE CONSTRAINT annotation_id IF NOT EXISTS FOR (a:Annotation) REQUIRE a.id IS UNIQUE")
  (neo4j/query driver "MATCH (a:Annotation) DETACH DELETE a")
  (doseq [batch (partition-all 500 note-rows)]
    (neo4j/query driver
                 (str "UNWIND $rows AS row CREATE (a:Annotation {id: row.id}) "
                      "SET a.book = row.book, a.page = row.page, a.text = row.text, a.quote = row.quote, "
                      "a.by = row.by, a.at = row.at, a.concepts = row.concepts")
                 {:rows (vec batch)}))
  (neo4j/query driver "MATCH (a:Annotation) MATCH (b:Book {id: a.book}) MERGE (a)-[:ANNOTATES]->(b)")
  (neo4j/query driver
               "UNWIND $rows AS row MATCH (a:Annotation {id: row.id}) MATCH (h:Heading {id: row.heading}) MERGE (a)-[:AT]->(h)"
               {:rows (filterv #(get % "heading") note-rows)})
  (neo4j/query driver
               "MATCH (a:Annotation) UNWIND a.concepts AS name MATCH (c:Concept {name: name}) MERGE (a)-[:ABOUT]->(c)")
  (first (neo4j/query driver
                      (str "MATCH (a:Annotation) RETURN count(a) AS annotations, "
                           "sum(CASE WHEN exists { (a)-[:ANNOTATES]->() } THEN 1 ELSE 0 END) AS onABook, "
                           "sum(CASE WHEN exists { (a)-[:AT]->() } THEN 1 ELSE 0 END) AS atAHeading, "
                           "sum(CASE WHEN exists { (a)-[:ABOUT]->() } THEN 1 ELSE 0 END) AS aboutAConcept"))))

(defn org
  "One book's notes as org text, a heading each, for Emacs to show. A view:
  the store is the files."
  [book notes]
  (str "#+TITLE: Notes on " book "\n\n"
       (str/join
        "\n"
        (for [{:keys [id page text by at concepts] note-quote :quote} notes]
          (str "* " (if page (str "p. " page) "no page") "  "
               (first (str/split-lines (or (not-empty text) note-quote))) "\n"
               ":PROPERTIES:\n:ID: " id "\n:BY: " by "\n:AT: " at "\n"
               (when (seq concepts) (str ":CONCEPTS: " (str/join "; " concepts) "\n"))
               ":END:\n"
               (when-not (str/blank? text) (str text "\n"))
               (when-not (str/blank? note-quote)
                 (str "#+begin_quote\n" note-quote "\n#+end_quote\n")))))))

(comment
 ;; where notes go; no server
 (store-dir)
 (problems {:book "../etc" :page 0})
 ;; a scratch store, so the owner's is not touched
 (def scratch (str (Files/createTempDirectory "annotations" (into-array java.nio.file.attribute.FileAttribute []))))
 (annotate! scratch {:book "graph-databases" :page 30 :text "Where the model is drawn before it is typed."})
 (annotations scratch nil)
 (rows (annotations scratch nil) (books/outlines))
 (print (org "graph-databases" (annotations scratch "graph-databases")))
 ;; needs the books graph: bb local-books, bb books-load
 :review)
