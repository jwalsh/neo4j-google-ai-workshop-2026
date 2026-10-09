(ns workshop.papers
  "Papers as a dataset. `bb papers fetch` keeps arXiv PDFs and their text in
  a cache outside the repository; this reads that cache's index and makes a
  graph of it in three layers, each cheaper to trust than the next:

  1. from the bibliography alone: Paper, Author, WROTE;
  2. from the text, no model: CITES, where one paper's text contains
     another's title or arXiv identifier;
  3. from the text, with a model: MENTIONS, names a small model lists and
     the text is then checked to contain (workshop.extract/names-of).

  Nothing here is about one field. A different .bib gives a different graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [workshop.extract :as extract]
            [workshop.neo4j :as neo4j]))

(defn cache-dir
  "The same rule as bb/papers.clj: PAPERS_CACHE, else the XDG cache."
  []
  (or (System/getenv "PAPERS_CACHE")
      (str (io/file (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (io/file (System/getProperty "user.home") ".cache"))
                    "workshop-papers"))))

(defn index
  "The papers in the cache, as the last `bb papers fetch` wrote them."
  []
  (let [index-file (io/file (cache-dir) "index.edn")]
    (when-not (.exists index-file)
      (throw (ex-info "No papers cached. bb papers fetch." {:cache (cache-dir)})))
    (edn/read-string (slurp index-file))))

(defn text [paper] (some-> (:text paper) slurp))

(defn- letters
  "Text reduced to lower-case letters and digits: what survives a PDF's
  line breaks, hyphenation and ligatures well enough to compare."
  [phrase]
  (str/replace (str/lower-case (str phrase)) #"[^a-z0-9]" ""))

(defn opening
  "The first characters of a paper's text: title, authors, abstract and the
  start of the introduction, which is where a paper says what it is."
  ([paper] (opening paper 6000))
  ([paper length]
   (let [whole (str/replace (or (text paper) "") #"[ \t]+" " ")]
     (subs whole 0 (min length (count whole))))))

(defn citations
  "Pairs [citing cited] of arXiv identifiers: the citing paper's text holds
  the cited paper's title, or its identifier. A title is compared by its
  first 45 letters, which is enough to be unique here and short enough to
  survive a reference list's line breaks. A paper citing itself is left out."
  [papers]
  (let [needles (for [{:keys [arxiv title]} papers]
                  {:arxiv arxiv
                   :title-start (let [squeezed (letters title)]
                                  (subs squeezed 0 (min 45 (count squeezed))))})]
    (vec (for [citing papers
               :let [haystack (letters (text citing))
                     raw (or (text citing) "")]
               {:keys [arxiv title-start]} needles
               :when (and (not= arxiv (:arxiv citing))
                          (or (str/includes? raw arxiv)
                              (and (>= (count title-start) 20)
                                   (str/includes? haystack title-start))))]
           [(:arxiv citing) arxiv]))))

(defn graph
  "Layers one and two as data:
  {:papers [...] :authors [...] :wrote [[author arxiv]] :cites [[arxiv arxiv]]
   :filed [[arxiv \"Collection\"|\"Category\" name]]}. A collection is where
  a paper came from, a category is the directory it was filed under."
  ([] (graph (index)))
  ([papers]
   {:papers (mapv #(select-keys % [:arxiv :title :year :url :verdict :why]) papers)
    :filed (vec (for [{:keys [arxiv collections categories]} papers
                      [kind names] [["Collection" collections] ["Category" categories]]
                      group-name names]
                  [arxiv kind group-name]))
    :authors (vec (sort (distinct (remove #{"others"} (mapcat :authors papers)))))
    :wrote (vec (for [{:keys [arxiv authors]} papers
                      author authors
                      :when (not= "others" author)]
                  [author arxiv]))
    :cites (citations papers)}))

(defn mentions
  "Layer three for one paper: the names a model lists in answer to
  `question` about the paper's opening, as {:kept :dropped}. Kept names are
  in the text; dropped ones are not and are the model's own."
  ([paper question] (mentions paper question {}))
  ([paper question options]
   (extract/names-of (opening paper) question options)))

(defn load!
  "MERGEs a graph into the database: (:Paper {arxiv}), (:Author {name}),
  WROTE, CITES, (:Collection {name}) and (:Category {name}) with IN, and
  when given, (:Topic {name}) with MENTIONS. Running it
  twice changes nothing. Returns what the database then holds."
  ([driver paper-graph] (load! driver paper-graph nil))
  ([driver {:keys [papers wrote cites filed]} mention-rows]
   (neo4j/query driver "CREATE CONSTRAINT paper_arxiv IF NOT EXISTS FOR (p:Paper) REQUIRE p.arxiv IS UNIQUE")
   (neo4j/query driver "CREATE CONSTRAINT author_name IF NOT EXISTS FOR (a:Author) REQUIRE a.name IS UNIQUE")
   (neo4j/query driver "CREATE CONSTRAINT topic_name IF NOT EXISTS FOR (t:Topic) REQUIRE t.name IS UNIQUE")
   (neo4j/query driver
                "UNWIND $papers AS row MERGE (p:Paper {arxiv: row.arxiv}) SET p += row"
                {:papers (mapv #(update-keys (into {} (remove (comp nil? val)) %) name) papers)})
   (doseq [[kind rows] (group-by second filed)]
     (neo4j/query driver
                  (str "UNWIND $rows AS row MATCH (p:Paper {arxiv: row[0]}) "
                       "MERGE (g:`" kind "` {name: row[2]}) MERGE (p)-[:IN]->(g)")
                  {:rows rows}))
   (neo4j/query driver
                "UNWIND $rows AS row MERGE (a:Author {name: row[0]}) WITH a, row MATCH (p:Paper {arxiv: row[1]}) MERGE (a)-[:WROTE]->(p)"
                {:rows wrote})
   (neo4j/query driver
                "UNWIND $rows AS row MATCH (a:Paper {arxiv: row[0]}), (b:Paper {arxiv: row[1]}) MERGE (a)-[:CITES]->(b)"
                {:rows cites})
   (when (seq mention-rows)
     (neo4j/query driver
                  "UNWIND $rows AS row MATCH (p:Paper {arxiv: row[0]}) MERGE (t:Topic {name: row[1]}) MERGE (p)-[:MENTIONS]->(t)"
                  {:rows mention-rows}))
   (first (neo4j/query driver
                       (str "MATCH (p:Paper) WITH count(p) AS papers "
                            "MATCH (a:Author) WITH papers, count(a) AS authors "
                            "OPTIONAL MATCH (:Author)-[w:WROTE]->() WITH papers, authors, count(w) AS wrote "
                            "OPTIONAL MATCH (:Paper)-[c:CITES]->() WITH papers, authors, wrote, count(c) AS cites "
                            "OPTIONAL MATCH (:Paper)-[m:MENTIONS]->() "
                            "RETURN papers, authors, wrote, cites, count(m) AS mentions")))))
