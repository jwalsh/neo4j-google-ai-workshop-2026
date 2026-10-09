(ns books
  "A catalog of a directory of PDF books, from the PDFs' own metadata. Runs
  in babashka: bb books <command>.

  BOOKS_DIR is the directory. BOOKS_HOST, when set, is an ssh destination
  the directory is on: the catalog is then made there, by pdfinfo over ssh,
  and only its text comes back. No book is copied anywhere, and the catalog
  is kept in the XDG cache beside the papers, not in the repository: a
  personal library is not this repository's to publish.

  The catalog is title, author, pages and producer as each PDF states them,
  which is uneven: a title can be missing and an author can be the name of
  the typesetting program. `catalog` records what is there and says what is
  missing; it does not guess."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn cache-dir
  []
  (or (System/getenv "BOOKS_CACHE")
      (str (fs/path (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                        (fs/path (fs/home) ".cache"))
                    "workshop-books"))))

(defn- catalog-path [] (fs/path (cache-dir) "catalog.edn"))

(def ^:private listing-script
  "Prints, for each PDF in the directory, a line `== name` and then pdfinfo's
  lines for it. One process for the whole directory. Homebrew's bin is added
  to the path because a non-interactive ssh shell does not have it."
  (str "PATH=\"$PATH:/opt/homebrew/bin:/usr/local/bin\"\n"
       "cd \"$1\" || exit 2\n"
       "command -v pdfinfo >/dev/null || { echo 'pdfinfo not found' >&2; exit 3; }\n"
       "for book in *.pdf; do\n"
       "  echo \"== $book\"\n"
       "  pdfinfo \"$book\" 2>/dev/null | grep -E '^(Title|Author|Pages|Producer|Creator|CreationDate|File size):'\n"
       "done\n"
       "true\n"))

(defn- raw-listing
  "Runs the script, here or over ssh, with the script on standard input so
  that nothing in it meets a second shell's quoting."
  [directory host]
  (let [command (if host
                  ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=8" host "sh" "-s" "--" (pr-str directory)]
                  ["sh" "-s" "--" directory])
        {:keys [out exit err]} (apply process/shell
                                      {:in listing-script :out :string :err :string :continue true}
                                      command)]
    (when-not (zero? exit)
      (throw (ex-info (str "Listing failed: " (str/trim (str err))) {:exit exit})))
    out))

(def ^:private tool-names
  "An Author or Title that is really the program that made the file."
  #"(?i)framemaker|formatter|latex|indesign|acrobat|pdftex|word|calibre|quark|prince|untitled|\.indd|\.docx?|\.fm$")

(defn parse-listing
  "pdfinfo's output for many files as book maps."
  [listing]
  (for [section (rest (str/split listing #"(?m)^== "))
        :let [[file-name & info-lines] (str/split-lines section)
              info (into {} (keep #(when-let [[_ field value] (re-matches #"([A-Za-z ]+):\s*(.*)" %)]
                                     [field (str/trim value)]))
                         info-lines)
              stated (fn [field] (let [value (info field)]
                                   (when (and (not (str/blank? value)) (not (re-find tool-names value)))
                                     value)))]]
    {:file file-name
     :title (stated "Title")
     :author (stated "Author")
     :pages (some-> (info "Pages") parse-long)
     :year (some->> (info "CreationDate") (re-find #"\b(19|20)\d\d\b") first)
     :producer (or (info "Creator") (info "Producer"))
     :megabytes (some->> (info "File size") (re-find #"\d+") parse-long (* 1e-6) (format "%.1f"))}))

(defn catalog
  "The catalog in the cache: every book seen, from every place looked."
  []
  (if (fs/exists? (catalog-path)) (edn/read-string (slurp (str (catalog-path)))) []))

(defn- places-path [] (fs/path (cache-dir) "places.edn"))

(defn places
  "Where each place is, as the last catalog of it said: {place {:dir :host}}."
  []
  (if (fs/exists? (places-path)) (edn/read-string (slurp (str (places-path)))) {}))

(defn- place-name
  "A short name for where a directory is: BOOKS_PLACE, else the host's last
  part, else \"local\"."
  [host]
  (or (System/getenv "BOOKS_PLACE")
      (some-> host (str/split #"@") last)
      "local"))

(defn catalog!
  "Catalogs one directory and merges it into the cache by file name: a book
  seen in two places is one entry with both under :places. Returns the
  whole catalog."
  []
  (let [directory (or (System/getenv "BOOKS_DIR")
                      (throw (ex-info "BOOKS_DIR is not set: the directory of PDFs." {})))
        host (System/getenv "BOOKS_HOST")
        place (place-name host)
        seen (parse-listing (raw-listing directory host))
        before (into {} (map (juxt :file identity)) (catalog))
        ;; A book no longer in this place loses the place, and goes when it has none.
        cleared (into {} (keep (fn [[file-name book]]
                                 (let [places (vec (remove #{place} (:places book)))]
                                   (when (seq places) [file-name (assoc book :places places)]))))
                      before)
        merged (reduce (fn [by-file {:keys [file] :as book}]
                         (assoc by-file file
                                (assoc (merge (by-file file) (into {} (remove (comp nil? val)) book))
                                       :places (vec (sort (distinct (conj (:places (by-file file) []) place)))))))
                       cleared
                       seen)
        books (vec (sort-by :file (vals merged)))]
    (fs/create-dirs (cache-dir))
    (spit (str (catalog-path) ".part") (with-out-str (pprint/pprint books)))
    (fs/move (str (catalog-path) ".part") (catalog-path) {:replace-existing true})
    (spit (str (places-path)) (with-out-str (pprint/pprint (assoc (places) place {:dir directory :host host}))))
    books))

(defn- copy-path [book-id] (fs/path (cache-dir) "pdf" (str book-id ".pdf")))

(defn shelf
  "A shelf file: [{:file :shelf :why}]."
  [shelf-path]
  (edn/read-string (slurp shelf-path)))

(defn fetch!
  "Copies a shelf's books into the cache, each once, as <id>.pdf: from a
  local place by cp, from a remote one by scp, to a .part file renamed when
  whole. The cache's names are the shelf's ids, not the library's file
  names, so two people with differently named copies share one layout.
  Returns one row per book."
  [shelf-path]
  (fs/create-dirs (fs/path (cache-dir) "pdf"))
  (let [by-file (into {} (map (juxt :file identity)) (catalog))
        known-places (places)]
    (vec (for [{:keys [file id]} (shelf shelf-path)]
           (let [target (copy-path id)
                 ;; a copy made under the library's own name, before ids
                 earlier-copy (fs/path (cache-dir) "pdf" file)
                 _ (when (and (fs/exists? earlier-copy) (not (fs/exists? target)))
                     (fs/move earlier-copy target))
                 ;; a local place first: it costs nothing
                 from (->> (:places (by-file file))
                           (map (fn [place] (assoc (known-places place) :place place)))
                           (sort-by #(if (:host %) 1 0))
                           first)
                 outcome (cond
                           (and (fs/exists? target) (pos? (fs/size target))) :cached
                           (nil? from) :not-in-the-catalog
                           :else (let [partial-file (str target ".part")
                                       source (str (:dir from) "/" file)
                                       {:keys [exit]} (if (:host from)
                                                        (process/shell {:continue true :out :string :err :string}
                                                                       "scp" "-q" "-o" "BatchMode=yes"
                                                                       (str (:host from) ":" source) partial-file)
                                                        (process/shell {:continue true :out :string :err :string}
                                                                       "cp" source partial-file))]
                                   (if (and (zero? exit) (fs/exists? partial-file))
                                     (do (fs/move partial-file target {:replace-existing true})
                                         (keyword (str "from-" (:place from))))
                                     :failed)))]
             {:id id
              :copy outcome
              :megabytes (when (fs/exists? target) (format "%.1f" (/ (fs/size target) 1e6)))})))))

(defn- unescape
  [xml-text]
  (-> xml-text
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'") (str/replace "&#39;" "'") (str/replace "&amp;" "&")))

(defn parse-outline
  "The bookmark outline in pdftohtml's XML as [{:level :page :title}]: an
  <outline> element opens a level, an <item> is a heading at the level it
  is in."
  [xml-text]
  (first
   (reduce (fn [[entries level] line]
             (cond
               (str/starts-with? line "<outline>") [entries (inc level)]
               (str/starts-with? line "</outline>") [entries (dec level)]
               :else (if-let [[_ page title] (re-matches #"<item page=\"(\d+)\">(.*)</item>" line)]
                       [(conj entries {:level level :page (parse-long page) :title (str/trim (unescape title))}) level]
                       [entries level])))
           [[] 0]
           (map str/trim (str/split-lines xml-text)))))

(defn outline!
  "Reads the bookmark outline of every cached book with pdftohtml and
  writes outlines.edn: [{:id :entries [...]}]. A book with no bookmarks
  has no entries and is listed as such."
  []
  (let [outlines (vec (for [pdf-file (sort (fs/glob (fs/path (cache-dir) "pdf") "*.pdf"))]
                        (let [{:keys [out]} (process/shell {:out :string :err :string :continue true}
                                                           "pdftohtml" "-xml" "-i" "-stdout" "-f" "1" "-l" "1"
                                                           (str pdf-file))]
                          {:id (fs/strip-ext (str (fs/file-name pdf-file)))
                           :entries (parse-outline (or out ""))})))
        outlines-path (fs/path (cache-dir) "outlines.edn")]
    (spit (str outlines-path ".part") (with-out-str (pprint/pprint outlines)))
    (fs/move (str outlines-path ".part") outlines-path {:replace-existing true})
    outlines))

;; A book's own index: the concepts its author chose, read from the pages
;; the outline says the index is on.

(defn- index-pages
  "Where a book's index is, from its outline, as [first-page last-page]:
  from the heading titled Index to the page before the next heading at its
  level or above. last-page is nil when the index runs to the end. nil when
  the outline has no such heading."
  [entries]
  (let [[position index-heading] (first (filter #(re-matches #"(?i)\s*index\s*" (:title (second %)))
                                                (map-indexed vector entries)))]
    (when index-heading
      (let [following (first (filter #(and (<= (:level %) (:level index-heading))
                                           (> (:page %) (:page index-heading)))
                                     (drop (inc position) entries)))]
        [(:page index-heading) (some-> following :page dec)]))))

(defn parse-positions
  "pdftotext's -bbox-layout output as lines with where each starts:
  [{:page :x :text}], page counted from 1 within the output."
  [bbox-text]
  (for [[page-number page-text] (map-indexed vector (rest (str/split bbox-text #"<page ")))
        [_ line-x line-body] (re-seq #"(?s)<line xMin=\"([\d.]+)\"[^>]*>(.*?)</line>" page-text)]
    {:page (inc page-number)
     :x (parse-double line-x)
     :text (str/join " " (map (comp unescape second) (re-seq #"<word[^>]*>(.*?)</word>" line-body)))}))

(defn- column-edges
  "The left edge of each column, from where lines start. Lines of one
  column start within a few points of its edge or indented from it;
  another column starts much further right. An edge is the leftmost
  position several lines share, so one stray line does not move it."
  [line-positions]
  (let [columns (reduce (fn [columns position]
                          (if (and (seq columns) (< (- position (first (peek columns))) 60))
                            (conj (pop columns) (conj (peek columns) position))
                            (conj columns [position])))
                        []
                        (sort line-positions))]
    (for [column columns
          :let [needed (max 2 (quot (count column) 30))
                edge (first (keep (fn [[position line-count]] (when (>= line-count needed) position))
                                  (sort (frequencies (map #(Math/round (double %)) column)))))]
          :when edge]
      edge)))

(def ^:private page-references
  "Page numbers at the end of an index line, with ranges and roman numerals."
  #"(?:[,\s]+(?:\d+|[ivxlc]+)(?:\s*[–‐-]\s*\d+)?(?:n\d*)?(?:st|nd|rd|th)?)+[,\s]*$")

(defn index-entries
  "The main entries of an index from its positioned lines:
  [{:name :pages}]. A main entry starts at its column's left edge; a
  sub-entry or a wrapped line is indented and is left out, since \"defined\"
  or \"overview\" under a concept is not a concept. Left and right pages
  have different margins, so edges are found for each separately."
  [lines]
  (let [edges-by-side (update-vals (group-by (comp odd? :page) lines)
                                   #(column-edges (map :x %)))
        main? (fn [{:keys [page x]}]
                (some #(< (Math/abs (- x %)) 3) (edges-by-side (odd? page))))]
    (->> (partition-all 2 1 lines)
         (keep (fn [[line following]]
                 (when (main? line)
                   (let [wrapped? (re-find #"[‐-]$" (:text line))
                         whole (if (and wrapped? following)
                                 (str (str/replace (:text line) #"[‐-]$" "") (:text following))
                                 (:text line))
                         without-see (str/replace whole #"(?i)\s*\(?\bsee\b.*$" "")
                         entry-name (-> without-see
                                        (str/replace page-references "")
                                        (str/split #",") first str str/trim)]
                     (when (and (re-find #"[A-Za-z]{3}" entry-name)
                                (<= (count (str/split entry-name #"\s+")) 5)
                                (not (re-matches #"(?i)index|symbols?|numerics?" entry-name))
                                ;; a buyer's watermark, and the publisher's pages after an index
                                (not (re-find #"(?i)licensed to|@|what.s inside|thanks for|perks don" whole)))
                       {:name entry-name
                        :pages (some->> (re-find page-references without-see)
                                        (re-seq #"\d+(?:\s*[–‐-]\s*\d+)?")
                                        (map #(str/replace % #"\s*[–‐]\s*" "-"))
                                        (str/join ", ") not-empty)})))))
         (reduce (fn [[kept seen] entry]
                   (if (seen (:name entry)) [kept seen] [(conj kept entry) (conj seen (:name entry))]))
                 [[] #{}])
         first)))

(defn index!
  "Reads the index of every cached book whose outline has one and writes
  index.edn: [{:id :pages [first last] :entries [{:name :pages}]}]. Only
  the index pages are read, and what is kept is the entries' names and
  page numbers. A book with no Index heading is listed with no entries."
  []
  (let [outlines (edn/read-string (slurp (str (fs/path (cache-dir) "outlines.edn"))))
        indexes (vec (for [{:keys [id entries]} outlines]
                       (if-let [[first-page last-page] (index-pages entries)]
                         (let [{:keys [out]} (apply process/shell {:out :string :err :string :continue true}
                                                    (concat ["pdftotext" "-bbox-layout" "-f" (str first-page)]
                                                            (when last-page ["-l" (str last-page)])
                                                            [(str (copy-path id)) "-"]))]
                           {:id id :pages [first-page last-page]
                            :entries (index-entries (parse-positions (or out "")))})
                         {:id id :pages nil :entries []})))
        index-path (fs/path (cache-dir) "index.edn")]
    (spit (str index-path ".part") (with-out-str (pprint/pprint indexes)))
    (fs/move (str index-path ".part") index-path {:replace-existing true})
    indexes))

(defn -main
  [& [command]]
  (case command
    "catalog" (let [books (catalog!)]
                (println (count books) "books," (reduce + (keep :pages books)) "pages, in" (str (catalog-path)))
                (doseq [[places place-books] (sort-by (comp str key) (group-by :places books))]
                  (println (format "  %-28s %d" (str/join " + " places) (count place-books))))
                (println "  with a title: " (count (filter :title books)))
                (println "  with an author:" (count (filter :author books)))
                (println "  with a year:  " (count (filter :year books))))
    "fetch" (let [rows (fetch! (or (second *command-line-args*) "shelves/homage.edn"))]
              (pprint/print-table rows)
              (println (count (remove #(#{:failed :not-in-the-catalog} (:copy %)) rows)) "of" (count rows)
                       "in" (str (fs/path (cache-dir) "pdf"))))
    "outline" (let [outlines (outline!)]
                (pprint/print-table (for [{:keys [id entries]} outlines]
                                      {:headings (count entries) :id id}))
                (println (count outlines) "books," (reduce + (map (comp count :entries) outlines)) "headings"))
    "index" (let [indexes (index!)]
              (pprint/print-table (for [{:keys [id pages entries]} indexes]
                                    {:entries (count entries) :from (first pages) :id id}))
              (println (count (filter (comp seq :entries) indexes)) "of" (count indexes) "books have an index,"
                       (reduce + (map (comp count :entries) indexes)) "entries"))
    "only" (doseq [book (catalog)
                   :when (= [(second *command-line-args*)] (:places book))]
             (println (:file book)))
    "list" (pprint/print-table [:pages :year :title :author :file]
                               (for [book (catalog)]
                                 (-> book
                                     (update :title #(some-> % (subs 0 (min 48 (count %)))))
                                     (update :author #(some-> % (subs 0 (min 28 (count %)))))
                                     (update :file #(subs % 0 (min 36 (count %)))))))
    (do (println "usage: bb books catalog | list | only PLACE | fetch [SHELF.edn] | outline | index")
        (println "  BOOKS_DIR   the directory of PDFs (required)")
        (println "  BOOKS_HOST  an ssh destination the directory is on (optional)")
        (println "  BOOKS_PLACE a name for this place in the catalog (default: the host, or local)")
        (println "  BOOKS_CACHE where the catalog is kept (default: $XDG_CACHE_HOME/workshop-books)"))))
