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

(defn- write-index!
  [papers]
  (let [index-path (fs/path (cache-dir) "index.edn")
        partial-file (str index-path ".part")]
    (spit partial-file
          (with-out-str
            (pprint/pprint
             (vec (for [{:keys [arxiv] :as paper} papers
                        :when (cached? (pdf-path arxiv))]
                    (assoc (select-keys paper [:arxiv :keys :title :authors :year :url])
                           :pdf (str (pdf-path arxiv))
                           :pdf-bytes (fs/size (pdf-path arxiv))
                           :text (when (cached? (text-path arxiv)) (str (text-path arxiv)))))))))
    (fs/move partial-file index-path {:replace-existing true})
    (str index-path)))

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
    (write-index! papers)
    rows))

(defn index
  "The cache's index: what is there, as written by the last fetch."
  []
  (let [index-path (fs/path (cache-dir) "index.edn")]
    (when (fs/exists? index-path) (edn/read-string (slurp (str index-path))))))

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
      (do (println "usage: bb papers where | list | fetch [max-requests]")
          (println "  PAPERS_BIB    the .bib to read (default: the triage survey's, under ghq)")
          (println "  PAPERS_CACHE  where to keep papers (default: $XDG_CACHE_HOME/workshop-papers)")))))
