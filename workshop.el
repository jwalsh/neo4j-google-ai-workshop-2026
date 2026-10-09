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
;;
;; `workshop-graph' does not start a second REPL.  It evaluates
;; (user/use-env! "books-graph.env"), which closes the session's driver and
;; opens the next one from that file, so `q' in the REPL asks the new graph.
;; The embedded servers are started outside Emacs: bb local, bb local-papers,
;; bb local-books.
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
  '(("lab"     nil                "dev/explore.clj")
    ("filings" "local-graph.env"  "dev/explore.clj")
    ("papers"  "papers-graph.env" "dev/explore.clj")
    ("books"   "books-graph.env"  "dev/books_explore.clj"))
  "The graphs on offer, each as (NAME ENV-FILE PAGE).
ENV-FILE names the Neo4j, nil meaning .env, the lab's own instance.  PAGE is
the file of forms opened beside the REPL.  Another workshop's data is one
more entry here and one more env file.")

(defvar workshop-page "dev/explore.clj"
  "The page the layout shows, relative to `workshop-root'.")

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

(defun workshop--port ()
  "The port in .nrepl-port if something is listening on it, or nil."
  (let ((port-file (workshop--path ".nrepl-port")))
    (when (file-readable-p port-file)
      (let ((port (string-to-number (with-temp-buffer
                                      (insert-file-contents port-file)
                                      (buffer-string)))))
        ;; A REPL that was killed leaves its port file behind.
        (when (and (> port 0)
                   (ignore-errors
                     (delete-process (open-network-stream "workshop-probe" nil "localhost" port))
                     t))
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
  "Start, or restart, the status page from the REPL and open it in a browser."
  (interactive)
  (browse-url (read (workshop--eval "(dashboard)"))))

(provide 'workshop)

;;; workshop.el ends here
