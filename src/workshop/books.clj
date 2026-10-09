(ns workshop.books
  "A shelf of books as a graph, from their tables of contents. `bb books
  fetch` and `bb books outline` keep the PDFs and their bookmark outlines
  in a cache outside the repository; this reads a shelf file and that
  cache and projects them:

    (:Shelf)<-[:ON]-(:Book)-[:HAS]->(:Heading)-[:HAS]->(:Heading)
    (:Heading)-[:ABOUT]->(:Term)
    (:Book)-[:INDEXES {pages}]->(:Concept)

  A Term is a word, or a run of two or three words, from a heading; its
  `books` property says how many books' headings use it, and a term with
  `books` of 2 or more is a shared one. A Concept is a main entry of a
  book's own index, as `bb books index` read it: the author's list of what
  the book is about, where a Term is only what its headings happen to say.
  Apart from those index pages nothing
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

(defn indexes
  "Each cached book's index entries by id, as `bb books index` wrote them:
  {id [{:name :pages}]}. Empty when no index has been read: the index is a
  second layer, and the outline projection stands without it."
  []
  (let [index-file (io/file (cache-dir) "index.edn")]
    (if (.exists index-file)
      (into {} (map (juxt :id :entries)) (edn/read-string (slurp index-file)))
      {})))

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

(defn concept-name
  "An index entry's name as the key books are joined on: lower case, the
  last word's plural folded, and an acronym with its expansion reduced to
  the expansion, whichever way round the index wrote them. \"RAG
  (retrieval-augmented generation)\" and \"Retrieval-augmented generation
  (RAG)\" are one concept."
  [entry-name]
  (let [lowered (str/lower-case (str/trim entry-name))
        [_ before inside] (re-matches #"(.+?)\s*\((.+)\)" lowered)
        chosen (cond (nil? before) (str/trim (str/replace lowered #"\s*\(.*$" ""))
                     (> (count inside) (count before)) inside
                     :else before)
        parts (str/split (str/replace chosen #"[.:;]+$" "") #"\s+")]
    (str/join " " (conj (vec (butlast parts)) (singular (last parts))))))

(defn graph
  "The projection as data:
  {:books [...] :headings [...] :nesting [[parent-id child-id]] :terms [...]
   :about [[heading-id term]]}. minimum-books is how many books must use a
  word in a heading for it to be a term."
  ([] (graph (edn/read-string (slurp default-shelf)) (outlines) 2 (indexes)))
  ([shelf-books outlines-by-id minimum-books] (graph shelf-books outlines-by-id minimum-books {}))
  ([shelf-books outlines-by-id minimum-books indexes-by-id]
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
         headings-using (update-vals (group-by second uses) count)
         shared (set (keep (fn [[word book-count]] (when (>= book-count minimum-books) word)) books-using))
         ;; A word one book alone uses is that book's own vocabulary and is
         ;; kept. A run of words one book uses is kept only if the book
         ;; uses it twice: once is two words that happened to be adjacent.
         kept (into shared (keep (fn [[word heading-count]]
                                   (when (or (not (str/includes? word " ")) (>= heading-count 2)) word))
                                 headings-using))
         index-uses (distinct (for [{:keys [id]} shelf-books
                                    {:keys [pages] entry-name :name} (indexes-by-id id)
                                    :let [concept (concept-name entry-name)]
                                    :when (>= (count concept) 3)]
                                [id concept pages]))
         concept-books (update-vals (group-by second index-uses) #(count (distinct (map first %))))]
     {:books (mapv #(select-keys % [:id :title :shelf :why]) shelf-books)
      :headings headings
      :nesting nesting
      :terms (vec (sort shared))
      :term-books (select-keys books-using kept)
      :about (vec (for [[heading word] uses :when (kept word)] [(:id heading) word]))
      :concept-books concept-books
      ;; one row per book and concept: the first pages given win
      :indexes (vec (vals (reduce (fn [by-pair [id concept pages]]
                                    (if (by-pair [id concept]) by-pair (assoc by-pair [id concept] [id concept pages])))
                                  {} index-uses)))})))

(defn load!
  "MERGEs books and headings into the database and replaces its terms.
  Running it twice leaves the same graph. Returns the counts it then holds."
  [driver {:keys [books headings nesting about term-books indexes concept-books]}]
  (doseq [[constraint-name label property] [["book_id" "Book" "id"] ["heading_id" "Heading" "id"]
                                            ["term_name" "Term" "name"] ["shelf_name" "Shelf" "name"]
                                            ["concept_name" "Concept" "name"]]]
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
  (doseq [batch (partition-all 2000 term-books)]
    (neo4j/query driver "UNWIND $rows AS row MATCH (t:Term {name: row[0]}) SET t.books = row[1]"
                 {:rows (mapv vec batch)}))
  ;; Concepts are replaced for the same reason terms are.
  (neo4j/query driver "MATCH (c:Concept) DETACH DELETE c")
  (doseq [batch (partition-all 2000 indexes)]
    (neo4j/query driver
                 (str "UNWIND $rows AS row MATCH (b:Book {id: row[0]}) MERGE (c:Concept {name: row[1]}) "
                      "MERGE (b)-[i:INDEXES]->(c) SET i.pages = row[2]")
                 {:rows (vec batch)}))
  (doseq [batch (partition-all 2000 concept-books)]
    (neo4j/query driver "UNWIND $rows AS row MATCH (c:Concept {name: row[0]}) SET c.books = row[1]"
                 {:rows (mapv vec batch)}))
  (neo4j/counts driver))

(comment
 ;; the term rule; no cache, no server
 (words "Deploying Agents to Kubernetes Clusters")
 (phrases "Retrieval Augmented Generation with Knowledge Graphs")
 (map singular ["anonymous" "approaches" "macros" "kubernetes"])
 ;; needs the cache: bb books fetch, bb books outline
 (cache-dir)
 (update-vals (outlines) count)
 (update-vals (indexes) count)
 (map concept-name ["RAG (retrieval-augmented generation)" "Retrieval-augmented generation (RAG)" "Vector databases"])
 (update-vals (graph) count)
:review)
