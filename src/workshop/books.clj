(ns workshop.books
  "A shelf of books as a graph, from their tables of contents. `bb books
  fetch` and `bb books outline` keep the PDFs and their bookmark outlines
  in a cache outside the repository; this reads a shelf file and that
  cache and projects them:

    (:Shelf)<-[:ON]-(:Book)-[:HAS]->(:Heading)-[:HAS]->(:Heading)
    (:Heading)-[:ABOUT]->(:Term)

  A Term is a word, or a run of two or three words, that the headings of
  at least two books use. Nothing
  here reads a book's text and no model is involved: similarity by meaning
  is another tool's job, and this is the part of a library a graph database
  holds without one. Which books share vocabulary, which term bridges two
  shelves, where in a book a subject sits."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [workshop.neo4j :as neo4j]))

(def default-shelf "shelves/homage.edn")

(defn cache-dir
  "The same rule as bb/books.clj: BOOKS_CACHE, else the XDG cache."
  []
  (or (System/getenv "BOOKS_CACHE")
      (str (io/file (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (io/file (System/getProperty "user.home") ".cache"))
                    "workshop-books"))))

(defn outlines
  "Each cached book's outline by id, as `bb books outline` wrote them."
  []
  (let [outlines-file (io/file (cache-dir) "outlines.edn")]
    (when-not (.exists outlines-file)
      (throw (ex-info "No outlines cached. bb books fetch, then bb books outline." {:cache (cache-dir)})))
    (into {} (map (juxt :id :entries)) (edn/read-string (slurp outlines-file)))))

(def stop-words
  "Words a heading uses that say nothing about its subject, and the words
  every technical book's front and back matter is made of."
  (set (str/split
        (str "about with from into your what when where which this that these those using used use and the for are how why "
             "more than other over under between through within without them they their there here have has been being "
             "will can not all any each some most many much one two three first second next part chapter section appendix "
             "summary introduction conclusion overview preface foreword acknowledgments contents index references further "
             "reading resources exercises exercise answers solutions notes note book author cover illustration brief "
             "getting started basics basic simple example examples working work works make making take look does "
             "putting together going beyond understanding know need things thing really just also back up out new way ways "
             "edition online liveBook forum discussion code table figure copyright dedication colophon glossary organized "
             "should read who roadmap acknowledgment content illustration learning.oreilly")
        #"\s+")))

(def singular-in-s
  "Words that end in s and are not plurals."
  #{"kubernetes" "analysis" "series" "status" "bias" "redis" "postgres" "basis" "synthesis" "devops"
    "llmops" "mlops" "nerves" "kinesis" "lens" "atlas" "pandas" "keras" "axis" "always" "across"
    "alias" "canvas" "thesis" "emphasis" "diagnosis" "chaos" "corpus" "focus" "census" "consensus"})

(defn singular
  "A word with a plural ending folded: -ies to -y, -ches -shes -xes -sses
  to their stem, a plain -s dropped. Words in -ous, -ss, -us and -is are
  left alone, as are the known exceptions. Found wanting by experiment 011:
  the first rule dropped every final s and made \"anonymou\" and
  \"approache\"."
  [word]
  (cond
    (or (singular-in-s word) (< (count word) 5)) word
    (re-find #"(ous|ss|us|is)$" word) word
    (str/ends-with? word "ies") (str (subs word 0 (- (count word) 3)) "y")
    (re-find #"(ch|sh|x|ss|z)es$" word) (subs word 0 (- (count word) 2))
    (str/ends-with? word "s") (subs word 0 (dec (count word)))
    :else word))

(defn- tokens
  "A heading's words in order, lower-case and singular, with nil where a
  stop word or a number stood, so that neighbours stay neighbours."
  [heading-title]
  (->> (re-seq #"[A-Za-z0-9][A-Za-z0-9+#.-]*[A-Za-z0-9+#]|[A-Za-z]" (str heading-title))
       (map str/lower-case)
       (map #(str/replace % #"[.-]+$" ""))
       (map (fn [word]
              (when (and (re-find #"^[a-z]" word) (not (stop-words word)))
                (let [folded (singular word)]
                  (when (and (not (stop-words folded)) (>= (count folded) 4)) folded)))))))

(defn words
  "The subject words of a heading: lower-case, four letters or more, no
  numbers, no stop words, plurals folded."
  [heading-title]
  (distinct (remove nil? (tokens heading-title))))

(defn phrases
  "Runs of two or three subject words that stand next to each other in a
  heading: \"retrieval augmented generation\", \"graph database\". The rule's
  stand-in for a noun chunk (experiment 011). On its own a run is noise; it
  becomes a term only when two books use it."
  [heading-title]
  (let [in-order (vec (tokens heading-title))]
    (distinct (for [length [2 3]
                    run (partition length 1 in-order)
                    :when (every? some? run)]
                (str/join " " run)))))

(defn graph
  "The projection as data:
  {:books [...] :headings [...] :nesting [[parent-id child-id]] :terms [...]
   :about [[heading-id term]]}. minimum-books is how many books must use a
  word in a heading for it to be a term."
  ([] (graph (edn/read-string (slurp default-shelf)) (outlines) 2))
  ([shelf-books outlines-by-id minimum-books]
   (let [headings (vec (for [{:keys [id]} shelf-books
                             [position {:keys [level page title]}] (map-indexed vector (outlines-by-id id))]
                         ;; pdftohtml's outline nests from 1; a book's own headings from 0
                         {:id (str id "#" position) :book id :position position
                          :level (dec level) :page page :title title}))
         nesting (vec (for [[_ book-headings] (group-by :book headings)
                            [child parent] (keep (fn [child]
                                                   (when-let [parent (->> book-headings
                                                                          (filter #(and (< (:position %) (:position child))
                                                                                        (= (:level %) (dec (:level child)))))
                                                                          last)]
                                                     [child parent]))
                                                 book-headings)]
                        [(:id parent) (:id child)]))
         uses (for [heading headings
                    word (concat (words (:title heading)) (phrases (:title heading)))]
                [heading word])
         books-using (update-vals (group-by second uses) #(count (distinct (map (comp :book first) %))))
         terms (set (keep (fn [[word book-count]] (when (>= book-count minimum-books) word)) books-using))]
     {:books (mapv #(select-keys % [:id :title :shelf :why]) shelf-books)
      :headings headings
      :nesting nesting
      :terms (vec (sort terms))
      :about (vec (for [[heading word] uses :when (terms word)] [(:id heading) word]))})))

(defn load!
  "MERGEs books and headings into the database and replaces its terms.
  Running it twice leaves the same graph. Returns the counts it then holds."
  [driver {:keys [books headings nesting about]}]
  (doseq [[constraint-name label property] [["book_id" "Book" "id"] ["heading_id" "Heading" "id"]
                                            ["term_name" "Term" "name"] ["shelf_name" "Shelf" "name"]]]
    (neo4j/query driver (str "CREATE CONSTRAINT " constraint-name " IF NOT EXISTS FOR (n:" label
                             ") REQUIRE n." property " IS UNIQUE")))
  (neo4j/query driver
               (str "UNWIND $books AS row MERGE (b:Book {id: row.id}) SET b.title = row.title, b.why = row.why "
                    "MERGE (s:Shelf {name: row.shelf}) MERGE (b)-[:ON]->(s)")
               {:books (mapv #(update-keys % name) books)})
  (doseq [batch (partition-all 1000 headings)]
    (neo4j/query driver
                 (str "UNWIND $rows AS row MERGE (h:Heading {id: row.id}) "
                      "SET h.title = row.title, h.level = row.level, h.page = row.page, h.position = row.position "
                      "WITH h, row WHERE row.level = 0 MATCH (b:Book {id: row.book}) MERGE (b)-[:HAS]->(h)")
                 {:rows (mapv #(update-keys % name) batch)}))
  (doseq [batch (partition-all 1000 nesting)]
    (neo4j/query driver
                 "UNWIND $rows AS row MATCH (p:Heading {id: row[0]}), (c:Heading {id: row[1]}) MERGE (p)-[:HAS]->(c)"
                 {:rows (vec batch)}))
  ;; Terms depend on the whole shelf, so they are replaced, not merged: a
  ;; word that was a term under an older rule would otherwise stay one.
  (neo4j/query driver "MATCH (t:Term) DETACH DELETE t")
  (doseq [batch (partition-all 2000 about)]
    (neo4j/query driver
                 "UNWIND $rows AS row MATCH (h:Heading {id: row[0]}) MERGE (t:Term {name: row[1]}) MERGE (h)-[:ABOUT]->(t)"
                 {:rows (vec batch)}))
  (neo4j/counts driver))

(comment
 ;; the term rule; no cache, no server
 (words "Deploying Agents to Kubernetes Clusters")
 (phrases "Retrieval Augmented Generation with Knowledge Graphs")
 (map singular ["anonymous" "approaches" "macros" "kubernetes"])
 ;; needs the cache: bb books fetch, bb books outline
 (cache-dir)
 (map (juxt :id (comp count :entries)) (outlines))
 (update-vals (graph) count)
:review)
