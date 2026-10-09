;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts.
;;
;; This directory has its own deps.edn inside a repository that has one too.
;; As in experiments/003-form13-datalog: left alone, clojure-mode picks the
;; candidate with a .git directory, the repository root, and cider-jack-in
;; starts there with the wrong classpath. clojure-preferred-build-tool makes
;; it take the nearest deps.edn, this directory. The :dev alias then puts
;; dev/query_ui/explore.clj on the classpath, and the relative paths the
;; code uses (scratch/, spec/, ../../.env) resolve.
;;
;; cider-jack-in-clj only: the front end is compiled ahead of time by
;; `clojure -M:cljs`, and there is no ClojureScript REPL set up here.
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
