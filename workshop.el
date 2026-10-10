;;; workshop.el --- A REPL view of this workshop through CIDER -*- lexical-binding: t; -*-

;;; Commentary:

;; One command, `workshop-repl', gets a Clojure REPL for this repository and
;; lays the frame out: a page of forms to evaluate on the left, the REPL on
;; the right.  It reuses a REPL CIDER already has, else connects to the one
;; .nrepl-port names (bb repl), else jacks in.  .dir-locals.el supplies the
;; :dev alias for the last case.
;;
;;   M-x workshop-repl       the REPL and the layout
;;   M-x workshop-graph      point the REPL at another Neo4j and open its page
;;   M-x workshop-layout     the layout again, after wandering off
;;   M-x workshop-dashboard  start the status page from the REPL and browse it
;;   M-x workshop-annotate   keep a note on a book, with the region as its quote
;;   M-x workshop-annotations  a book's notes, as an org buffer
;;
;; It also makes Cypher a language org-babel can run.  C-c C-c on
;;
;;   #+begin_src cypher :graph books :var shelf="graphs"
;;   MATCH (b:Book) WHERE b.shelf = $shelf RETURN b.title LIMIT 5
;;   #+end_src
;;
;; sends the statement through the workshop REPL and puts the rows under the
;; block as a table.  :graph is a name from `workshop-graphs' and asks that
;; graph for this one block; without it the block asks whichever graph the
;; REPL is on.  Each :var is a query parameter.  The cypher-mode package, if
;; installed, colours the block; nothing here needs it.
;;
;; Notes on books are kept outside the repository, one file each under
;; $XDG_DATA_HOME/workshop-books/annotations, so a git worktree reaches the
;; same notes and an agent given its own BOOKS_ANNOTATIONS reaches none of
;; them.  In a buffer showing a cached PDF, `workshop-annotate' offers that
;; book and the page in view.  bb books-load puts the notes in the graph.
;;
;; `workshop-graph' does not start a second REPL.  It evaluates
;; (user/use-env! "books-graph.env"), which closes the session's driver and
;; opens the next one from that file, so `q' in the REPL asks the new graph.
;; The embedded servers are started outside Emacs: bb local-mbta, bb local-books,
;; bb local-papers, bb local.  The transit graph is the one shown first.
;;
;; Load it once per Emacs session with M-x load-file, or from an init file:
;;
;;   (load "~/ghq/github.com/jwalsh/neo4j-google-ai-workshop-2026/workshop.el")
;;
;; .dir-locals.el does not load it: that would take an `eval' entry, and
;; Emacs asks about those every time a file is opened.

;;; Code:

(require 'cider)
(require 'cl-lib)

(defconst workshop-root
  (file-name-directory (or load-file-name buffer-file-name))
  "The repository this file is at the root of.")

(defvar workshop-graphs
  '(("transit" "mbta-graph.env"   "dev/mbta_explore.clj")
    ("books"   "books-graph.env"  "dev/books_explore.clj")
    ("papers"  "papers-graph.env" "dev/explore.clj")
    ("filings" "local-graph.env"  "dev/explore.clj")
    ("lab"     nil                "dev/explore.clj"))
  "The graphs on offer, each as (NAME ENV-FILE PAGE).
ENV-FILE names the Neo4j, nil meaning .env, the lab's own instance.  PAGE is
the file of forms opened beside the REPL.  Another workshop's data is one
more entry here and one more env file.")

(defvar workshop-page "dev/mbta_explore.clj"
  "The page the layout shows, relative to `workshop-root'.
It starts as the transit graph's: that one needs no account and no lab, only
bb local-mbta, and its page names its own graph whatever the REPL is on.")

(defun workshop--path (relative-path)
  "RELATIVE-PATH under `workshop-root'."
  (expand-file-name relative-path workshop-root))

(defun workshop--repl ()
  "The Clojure REPL CIDER has for this repository, or nil."
  (let ((default-directory workshop-root))
    (cl-find-if (lambda (repl-buffer)
                  (file-equal-p workshop-root
                                (or (buffer-local-value 'nrepl-project-dir repl-buffer)
                                    (buffer-local-value 'default-directory repl-buffer))))
                (ignore-errors (cider-repls 'clj)))))

(defvar workshop-dashboard-port 8377
  "The port the status page is served on.")

(defun workshop--listening-p (port)
  "Whether something on this machine accepts connections on PORT."
  (ignore-errors
    (delete-process (open-network-stream "workshop-probe" nil "127.0.0.1" port))
    t))

(defun workshop--port ()
  "The port in .nrepl-port if something is listening on it, or nil."
  (let ((port-file (workshop--path ".nrepl-port")))
    (when (file-readable-p port-file)
      (let ((port (string-to-number (with-temp-buffer
                                      (insert-file-contents port-file)
                                      (buffer-string)))))
        ;; A REPL that was killed leaves its port file behind.
        (when (and (> port 0) (workshop--listening-p port))
          port)))))

(defun workshop--eval (form)
  "Evaluate FORM, a string, in the workshop REPL's `user' namespace.
Returns the printed value, or signals an error with what the REPL said."
  (let* ((repl (or (workshop--repl) (user-error "No workshop REPL: M-x workshop-repl")))
         (response (cider-nrepl-sync-request:eval form repl "user")))
    (or (nrepl-dict-get response "value")
        (error "%s" (string-trim (or (nrepl-dict-get response "err") "no value came back"))))))

;;;###autoload
(defun workshop-layout ()
  "Show the page on the left and the REPL on the right, alone in the frame."
  (interactive)
  ;; A connection can arrive while the minibuffer is selected, and the
  ;; minibuffer cannot be made the only window.
  (when (window-minibuffer-p)
    (select-window (or (minibuffer-selected-window) (frame-first-window))))
  (delete-other-windows)
  (find-file (workshop--path workshop-page))
  (let ((repl (workshop--repl)))
    (when repl
      (set-window-buffer (split-window-right) repl))))

(defun workshop--layout-once ()
  "Lay the frame out when a connection made by `workshop-repl' arrives."
  (remove-hook 'cider-connected-hook #'workshop--layout-once)
  (run-at-time 0 nil #'workshop-layout))

;;;###autoload
(defun workshop-repl ()
  "Get a Clojure REPL for the workshop, then show it beside a page of forms.
An existing REPL is reused; else .nrepl-port is connected to; else CIDER
jacks in with the alias .dir-locals.el names."
  (interactive)
  (if (workshop--repl)
      (workshop-layout)
    (let ((default-directory workshop-root)
          (port (workshop--port)))
      (add-hook 'cider-connected-hook #'workshop--layout-once)
      ;; Visiting a file of the project first gives CIDER its directory locals.
      (find-file (workshop--path workshop-page))
      (if port
          (cider-connect-clj (list :host "localhost" :port port :project-dir workshop-root))
        (cider-jack-in-clj (list :project-dir workshop-root))))))

;;;###autoload
(defun workshop-graph (graph-name)
  "Point the REPL's `q' at the graph named GRAPH-NAME and open its page."
  (interactive (list (completing-read "Graph: " (mapcar #'car workshop-graphs) nil t)))
  (pcase-let ((`(,_ ,env-file ,page) (or (assoc graph-name workshop-graphs)
                                         (user-error "No graph named %s" graph-name))))
    (let ((answer (workshop--eval (format "(use-env! %s)" (if env-file (prin1-to-string env-file) "nil")))))
      (setq workshop-page page)
      (workshop-layout)
      (message "%s: %s" graph-name answer))))

;;;###autoload
(defun workshop-dashboard ()
  "Open the status page in a browser, starting it from the REPL if need be.
A page already being served, by this REPL or by bb dashboard in a terminal,
is opened as it is: a second one could not take the same port."
  (interactive)
  (let ((url (format "http://127.0.0.1:%d/" workshop-dashboard-port)))
    (unless (workshop--listening-p workshop-dashboard-port)
      (workshop--eval (format "(dashboard %d)" workshop-dashboard-port)))
    (browse-url url)))

;;; Cypher in org-babel

(declare-function org-babel--get-vars "ob-core" (params))

(defvar org-babel-default-header-args:cypher '((:results . "table"))
  "A Cypher block's rows are a table unless the block says otherwise.")

(defun workshop--clojure-literal (value)
  "VALUE, a string or number from an org header, as Clojure source."
  (let ((print-escape-newlines nil))
    (prin1-to-string (if (symbolp value) (symbol-name value) value))))

(defun workshop--table-form (graph-name statement variables)
  "The Clojure form that runs STATEMENT on the graph named GRAPH-NAME.
VARIABLES is an alist of query parameters.  A nil GRAPH-NAME is the graph the
REPL is on."
  (let ((env-file (when graph-name
                    (pcase (assoc graph-name workshop-graphs)
                      (`(,_ ,graph-env-file ,_) (or graph-env-file ".env"))
                      (_ (user-error "No graph named %s" graph-name))))))
    (format "(table %s %s {%s})"
            (if env-file (workshop--clojure-literal env-file) "nil")
            (workshop--clojure-literal statement)
            (mapconcat (lambda (variable)
                         (format "%s %s"
                                 (workshop--clojure-literal (symbol-name (car variable)))
                                 (workshop--clojure-literal (cdr variable))))
                       variables " "))))

(defun org-babel-execute:cypher (body params)
  "Run BODY, one Cypher statement, through the workshop REPL.
PARAMS are the block's header arguments: :graph names a graph in
`workshop-graphs', each :var is a query parameter.  Returns the column names,
a rule, and the rows."
  (let* ((graph-name (cdr (assq :graph params)))
         (variables (org-babel--get-vars params))
         (printed-rows (workshop--eval
                        (workshop--table-form (and graph-name (format "%s" graph-name))
                                              (string-trim body)
                                              variables)))
         (rows (mapcar (lambda (row) (append row nil)) (car (read-from-string printed-rows)))))
    ;; No rows means no column names either: an empty result, not a table.
    (when (cdr rows)
      (cons (car rows) (cons 'hline (cdr rows))))))

;;; Notes on books

(declare-function doc-view-current-page "doc-view" ())
(declare-function pdf-view-current-page "ext:pdf-view" (&optional window))

(defun workshop--shelf-ids ()
  "The ids of the books on the shelf, asked of the REPL."
  (append (car (read-from-string (workshop--eval "(mapv :id (shelf-books))"))) nil))

(defun workshop--page-in-view ()
  "The page the buffer shows, when it is a PDF in doc-view or pdf-tools."
  (cond ((and (derived-mode-p 'pdf-view-mode) (fboundp 'pdf-view-current-page))
         (pdf-view-current-page))
        ((and (derived-mode-p 'doc-view-mode) (fboundp 'doc-view-current-page))
         (doc-view-current-page))))

(defun workshop--read-book ()
  "A book id from the shelf, offering the one the buffer's file is named for."
  (let* ((shelf-ids (workshop--shelf-ids))
         (visited (and buffer-file-name (file-name-base buffer-file-name))))
    (completing-read "Book: " shelf-ids nil t nil nil (car (member visited shelf-ids)))))

;;;###autoload
(defun workshop-annotate (book-id page note-text &optional quoted-text)
  "Keep NOTE-TEXT as a note on page PAGE of the book BOOK-ID.
QUOTED-TEXT is the passage it is about; interactively, the region.  PAGE is
the PDF's own page count, or nil.  The note is a file of its own in the XDG
data directory, never in the repository."
  (interactive
   (let ((quoted-text (when (use-region-p)
                        (string-trim (buffer-substring-no-properties (region-beginning) (region-end))))))
     (list (workshop--read-book)
           (let ((answer (read-string "Page (empty for none): "
                                      (let ((page-in-view (workshop--page-in-view)))
                                        (and page-in-view (number-to-string page-in-view))))))
             (unless (string-empty-p answer) (string-to-number answer)))
           (read-string "Note: ")
           quoted-text)))
  (let ((kept-id (workshop--eval
                  (format "(:id (annotate! {:book %s :page %s :text %s :quote %s}))"
                          (workshop--clojure-literal book-id)
                          (if page (number-to-string page) "nil")
                          (workshop--clojure-literal note-text)
                          (if quoted-text (workshop--clojure-literal quoted-text) "nil")))))
    (message "Kept %s on %s" (car (read-from-string kept-id)) book-id)))

;;;###autoload
(defun workshop-annotations (book-id)
  "Show the notes kept on the book BOOK-ID in an org buffer.
The buffer is a view: the notes are the files, and editing it changes none."
  (interactive (list (workshop--read-book)))
  (let ((org-text (car (read-from-string
                        (workshop--eval (format "(annotations-org %s)" (workshop--clojure-literal book-id)))))))
    (with-current-buffer (get-buffer-create (format "*notes: %s*" book-id))
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert org-text)
        (org-mode)
        (goto-char (point-min)))
      (view-mode 1)
      (pop-to-buffer (current-buffer)))))

(provide 'workshop)

;;; workshop.el ends here
