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

(defn catalog!
  "Makes the catalog and writes it to the cache. Returns the books."
  []
  (let [directory (or (System/getenv "BOOKS_DIR")
                      (throw (ex-info "BOOKS_DIR is not set: the directory of PDFs." {})))
        books (vec (parse-listing (raw-listing directory (System/getenv "BOOKS_HOST"))))]
    (fs/create-dirs (cache-dir))
    (spit (str (catalog-path) ".part") (with-out-str (pprint/pprint books)))
    (fs/move (str (catalog-path) ".part") (catalog-path) {:replace-existing true})
    books))

(defn catalog
  []
  (when (fs/exists? (catalog-path)) (edn/read-string (slurp (str (catalog-path))))))

(defn -main
  [& [command]]
  (case command
    "catalog" (let [books (catalog!)]
                (println (count books) "books," (reduce + (keep :pages books)) "pages, in" (str (catalog-path)))
                (println "  with a title: " (count (filter :title books)))
                (println "  with an author:" (count (filter :author books)))
                (println "  with a year:  " (count (filter :year books))))
    "list" (pprint/print-table [:pages :year :title :author :file]
                               (for [book (catalog)]
                                 (-> book
                                     (update :title #(some-> % (subs 0 (min 48 (count %)))))
                                     (update :author #(some-> % (subs 0 (min 28 (count %)))))
                                     (update :file #(subs % 0 (min 36 (count %)))))))
    (do (println "usage: bb books catalog | list")
        (println "  BOOKS_DIR   the directory of PDFs (required)")
        (println "  BOOKS_HOST  an ssh destination the directory is on (optional)")
        (println "  BOOKS_CACHE where the catalog is kept (default: $XDG_CACHE_HOME/workshop-books)"))))
