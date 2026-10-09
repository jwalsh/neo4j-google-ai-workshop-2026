;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts.
;;
;; This directory has its own deps.edn, nested inside a repository that has
;; one too. Left alone, clojure-mode (20260709) picks the candidate with a
;; .git directory, the repository root, and cider-jack-in would start there
;; without Neo4j on the classpath. clojure-preferred-build-tool makes it take
;; the nearest deps.edn instead: this directory. Then the :dev alias puts
;; dev/local_graph/explore.clj on the classpath.
;;
;; Emacs reads the nearest .dir-locals.el only, so the clojure-mode settings
;; are repeated from the root.
((nil
  (clojure-preferred-build-tool . "deps.edn")
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev"))
 (clojure-mode
  (indent-tabs-mode . nil)
  (fill-column . 80)))
