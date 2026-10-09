;;; ob-cypher-cider.el --- Org Babel cypher blocks through a CIDER REPL -*- lexical-binding: t; -*-

;;; Commentary:

;; Runs a `#+begin_src cypher' block by sending its body to the connected
;; CIDER REPL as (local-graph.session/org-table "..."), which asks the local
;; database through the repository's driver wrapper and prints the rows as a
;; list this file reads back into an Org table.
;;
;; No cypher-shell, no HTTP endpoint, no ob-cypher.  The REPL has to be the
;; one jacked in from experiments/008-local-graph-dx, with
;; (local-graph.session/up!) evaluated.
;;
;; `:var name="value"' becomes the Cypher parameter $name.
;;
;; Load it with M-x load-file, or the elisp block at the top of explore.org.
;; It defines `org-babel-execute:cypher', the name MELPA's ob-cypher also
;; defines: whichever is loaded last wins.

;;; Code:

(require 'ob)
(require 'cider)

(defvar org-babel-default-header-args:cypher '((:results . "table replace"))
  "Default header arguments for cypher blocks.")

(defun ob-cypher-cider--parameters (params)
  "The :var entries of PARAMS as a Clojure map literal with string keys."
  (concat "{"
          (mapconcat (lambda (variable)
                       (format "%S %S" (symbol-name (car variable)) (cdr variable)))
                     (org-babel--get-vars params)
                     " ")
          "}"))

(defun org-babel-execute:cypher (body params)
  "Run the Cypher in BODY through the CIDER REPL, with PARAMS, as a table."
  (let* ((form (format "(local-graph.session/org-table %S %s)"
                       (string-trim body)
                       (ob-cypher-cider--parameters params)))
         (response (cider-nrepl-sync-request:eval form (cider-current-repl) "user"))
         (printed-value (nrepl-dict-get response "value"))
         (error-output (nrepl-dict-get response "err")))
    (if printed-value
        ;; The value is a Clojure string holding a printed list: read twice.
        (read (read printed-value))
      (error "Cypher block failed: %s" (or error-output "no value came back")))))

(provide 'ob-cypher-cider)

;;; ob-cypher-cider.el ends here
