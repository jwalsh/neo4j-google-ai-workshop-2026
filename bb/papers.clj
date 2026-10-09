(ns papers
  "Papers named in a BibTeX file, fetched once into a cache outside the
  repository. Runs in babashka, no JVM: bb papers <command>.

  The cache is $XDG_CACHE_HOME/workshop-papers, or ~/.cache/workshop-papers
  when that is unset, so every worktree and every agent on the machine
  shares one copy, and a container sees it by mounting that one directory.
  PAPERS_CACHE names another place outright.

  Idempotent: a paper already there is not requested again. A download
  lands in a .part file and is renamed when whole, so a failed one never
  leaves a short PDF behind. arXiv asks for no more than one request every
  three seconds; fetch waits that long between papers.

  Only arXiv entries are fetched: an eprint field, or an arxiv.org URL. The
  other entries in a bibliography are web pages, which are not papers and
  whose terms differ by site."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def user-agent "workshop-papers/0.1 (babashka; a personal reading cache)")
(def seconds-between-requests 3)

(defn cache-dir
  "Where papers are kept. PAPERS_CACHE, else the XDG cache directory."
  []
  (or (System/getenv "PAPERS_CACHE")
      (str (fs/path (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (fs/path (fs/home) ".cache"))
                    "workshop-papers"))))

(defn default-bib
  "PAPERS_BIB, else the triage survey's bibliography where ghq puts the gist."
  []
  (or (System/getenv "PAPERS_BIB")
      (str (fs/path (fs/home) "ghq" "gist.github.com" "jwalsh"
                    "5ff341dadc8444f29a10b507869e59f6" "references.bib"))))

(defn- field
  [entry-body field-name]
  (some-> (re-find (re-pattern (str "(?im)^\\s*" field-name "\\s*=\\s*[{\"](.*?)[}\"],?\\s*$")) entry-body)
          second
          (str/replace #"[{}]" "")
          str/trim))

(defn entries
  "The entries of BibTeX text as {:key :kind :title :authors :year :url
  :arxiv}. A parser for the bibliography at hand, not for BibTeX: one field
  per line, braces or quotes."
  [bib-text]
  (for [[_ kind entry-key entry-body] (re-seq #"(?s)@(\w+)\{([^,\s]+),(.*?)\n\}" bib-text)
        :let [url (field entry-body "url")
              arxiv-id (or (when (some->> (field entry-body "eprinttype") (re-find #"(?i)arxiv"))
                             (field entry-body "eprint"))
                           (some->> url (re-find #"arxiv\.org/(?:abs|pdf)/(\d{4}\.\d{4,5})") second))]]
    {:key entry-key
     :kind (str/lower-case kind)
     :title (field entry-body "title")
     :authors (some-> (field entry-body "author") (str/split #"\s+and\s+"))
     :year (or (field entry-body "year") (some-> (field entry-body "date") (subs 0 4)))
     :url url
     :arxiv arxiv-id}))

(defn arxiv-papers
  "The arXiv entries, one per identifier: two keys may cite one paper."
  [bib-path]
  (->> (entries (slurp bib-path))
       (filter :arxiv)
       (group-by :arxiv)
       (map (fn [[_ same-paper]] (assoc (first same-paper) :keys (mapv :key same-paper))))
       (sort-by :arxiv)))

(defn- pdf-path [arxiv-id] (fs/path (cache-dir) "arxiv" (str arxiv-id ".pdf")))
(defn- text-path [arxiv-id] (fs/path (cache-dir) "arxiv" (str arxiv-id ".txt")))

(defn- cached? [file-path] (and (fs/exists? file-path) (pos? (fs/size file-path))))

(defn- download!
  "Fetches one PDF. Returns :fetched, or a reason it was not kept."
  [arxiv-id]
  (let [target (pdf-path arxiv-id)
        partial-file (fs/path (str target ".part"))
        response (http/get (str "https://arxiv.org/pdf/" arxiv-id)
                           {:headers {"User-Agent" user-agent} :as :bytes :throw false :timeout 60000})]
    (cond
      (not= 200 (:status response)) (str "HTTP " (:status response))
      (not= "%PDF" (String. ^bytes (:body response) 0 4)) "not a PDF"
      :else (do (fs/write-bytes partial-file (:body response))
                (fs/move partial-file target {:replace-existing true :atomic-move true})
                :fetched))))

(defn- text!
  "pdftotext beside the PDF, once. Returns the text file's path or nil."
  [arxiv-id]
  (let [target (text-path arxiv-id)]
    (cond (cached? target) target
          (not (fs/which "pdftotext")) nil
          :else (let [partial-file (str target ".part")]
                  (process/shell {:out :string :err :string :continue true}
                                 "pdftotext" "-layout" (str (pdf-path arxiv-id)) partial-file)
                  (when (cached? partial-file)
                    (fs/move partial-file target {:replace-existing true})
                    target)))))

(defn index
  "The cache's index: every paper there, from every collection."
  []
  (let [index-path (fs/path (cache-dir) "index.edn")]
    (if (fs/exists? index-path) (edn/read-string (slurp (str index-path))) [])))

(defn- write-index!
  "Merges papers into the index by arXiv identifier and writes it whole. A
  paper already indexed keeps what it had and gains what is new: a second
  collection, a category, a title. Only papers whose PDF is cached are kept."
  [collection papers]
  (let [index-path (fs/path (cache-dir) "index.edn")
        partial-file (str index-path ".part")
        existing (into {} (map (juxt :arxiv identity)) (index))
        merged (reduce (fn [by-id {:keys [arxiv] :as paper}]
                         (let [before (by-id arxiv)]
                           (assoc by-id arxiv
                                  (-> (merge before (into {} (remove (comp nil? val)) paper))
                                      (assoc :collections (vec (sort (distinct (conj (:collections before []) collection))))
                                             :categories (vec (sort (distinct (concat (:categories before) (:categories paper)))))
                                             :pdf (str (pdf-path arxiv))
                                             :pdf-bytes (when (cached? (pdf-path arxiv)) (fs/size (pdf-path arxiv)))
                                             :text (when (cached? (text-path arxiv)) (str (text-path arxiv))))))))
                       existing
                       (filter #(cached? (pdf-path (:arxiv %))) papers))]
    (spit partial-file (with-out-str (pprint/pprint (vec (sort-by :arxiv (vals merged))))))
    (fs/move partial-file index-path {:replace-existing true})
    (str index-path)))

(defn- collection-name
  "A short name for where papers came from: the directory holding the .bib,
  or the directory of PDFs. A gist's directory is a hash, so PAPERS_COLLECTION
  can say something better."
  [source-path]
  (or (System/getenv "PAPERS_COLLECTION")
      (let [source (fs/absolutize source-path)]
        (str (fs/file-name (if (fs/directory? source) source (fs/parent source)))))))

(defn org-index
  "What an org index in a directory of papers says about each, by arXiv
  identifier: {:title :verdict :why}. It reads a heading, then the first
  following line that names an arXiv identifier, then a `Why read it`
  item. A directory without such a file gives an empty map."
  [directory]
  (let [index-file (fs/path directory "index.org")]
    (if-not (fs/exists? index-file)
      {}
      (->> (str/split (slurp (str index-file)) #"(?m)^\*\* ")
           rest
           (keep (fn [section]
                   (when-let [arxiv-id (second (re-find #"arXiv (\d{4}\.\d{4,5})" section))]
                     [arxiv-id {:title (str/trim (first (str/split-lines section)))
                                :verdict (second (re-find #"verdict \*([^*]+)\*" section))
                                :why (some-> (re-find #"(?m)^- Why read it :: (.*)$" section) second str/trim)}])))
           (into {})))))

(defn add!
  "Takes a directory of PDFs named <arxiv-id>-<anything>.pdf into the cache.
  A subdirectory's name is the paper's category. A PDF already cached is
  not copied again. Returns one row per paper found."
  [directory]
  (fs/create-dirs (fs/path (cache-dir) "arxiv"))
  (let [described (org-index directory)
        found (for [pdf-file (sort (filter #(= "pdf" (fs/extension %)) (file-seq (fs/file directory))))
                    :let [arxiv-id (second (re-find #"^(\d{4}\.\d{4,5})" (str (fs/file-name pdf-file))))]
                    :when arxiv-id]
                (let [parent-name (str (fs/file-name (fs/parent pdf-file)))
                      outcome (if (cached? (pdf-path arxiv-id))
                                :cached
                                (do (fs/copy pdf-file (pdf-path arxiv-id)) :copied))]
                  (text! arxiv-id)
                  (merge {:arxiv arxiv-id
                          :url (str "https://arxiv.org/abs/" arxiv-id)
                          :categories (when-not (fs/same-file? (fs/parent pdf-file) directory) [parent-name])
                          :pdf-outcome outcome}
                         (described arxiv-id))))]
    (write-index! (collection-name directory) (map #(dissoc % :pdf-outcome) found))
    (vec (for [{:keys [arxiv categories pdf-outcome title]} found]
           {:arxiv arxiv :category (first categories) :pdf pdf-outcome
            :text (if (cached? (text-path arxiv)) :yes :no)
            :title (some-> title (subs 0 (min 56 (count title))))}))))

(defn fetch!
  "Fetches every arXiv paper the bibliography names and has not cached, at
  most `limit` requests. Returns one row per paper."
  [bib-path limit]
  (fs/create-dirs (fs/path (cache-dir) "arxiv"))
  (let [papers (arxiv-papers bib-path)
        requests (atom 0)
        rows (vec (for [{:keys [arxiv title]} papers]
                    (let [outcome (cond
                                    (cached? (pdf-path arxiv)) :cached
                                    (>= @requests limit) :over-the-limit
                                    :else (do (when (pos? @requests)
                                                (Thread/sleep (* 1000 seconds-between-requests)))
                                              (swap! requests inc)
                                              (try (download! arxiv)
                                                   (catch Exception failure (ex-message failure)))))]
                      (when (cached? (pdf-path arxiv)) (text! arxiv))
                      {:arxiv arxiv
                       :pdf outcome
                       :text (if (cached? (text-path arxiv)) :yes :no)
                       :title (some-> title (subs 0 (min 60 (count title))))})))]
    (write-index! (collection-name bib-path) (map #(dissoc % :key :kind) papers))
    rows))

(defn -main
  [& [command argument]]
  (let [bib-path (default-bib)]
    (case command
      "where" (do (println "cache:" (cache-dir))
                  (println "bib:  " bib-path (if (fs/exists? bib-path) "" "(missing)")))
      "list" (pprint/print-table
              [:arxiv :cached :year :title]
              (for [{:keys [arxiv year title]} (arxiv-papers bib-path)]
                {:arxiv arxiv :cached (if (cached? (pdf-path arxiv)) "yes" "") :year year
                 :title (some-> title (subs 0 (min 70 (count title))))}))
      "fetch" (let [rows (fetch! bib-path (if argument (parse-long argument) 1000))]
                (pprint/print-table rows)
                (println (count (filter #(#{:cached :fetched} (:pdf %)) rows)) "of" (count rows)
                         "in" (cache-dir)))
      "add" (let [rows (add! (or argument (throw (ex-info "add needs a directory of PDFs." {}))))]
              (pprint/print-table rows)
              (println (count rows) "papers from" argument "; index has" (count (index))))
      "index" (pprint/print-table
               [:arxiv :collections :categories :verdict :title]
               (for [paper (index)]
                 (update paper :title #(some-> % (subs 0 (min 50 (count %)))))))
      (do (println "usage: bb papers where | list | fetch [max-requests] | add DIRECTORY | index")
          (println "  PAPERS_BIB    the .bib to read (default: the triage survey's, under ghq)")
          (println "  PAPERS_CACHE  where to keep papers (default: $XDG_CACHE_HOME/workshop-papers)")
          (println "  PAPERS_COLLECTION  a name for this source in the index (default: its directory's name)")))))
