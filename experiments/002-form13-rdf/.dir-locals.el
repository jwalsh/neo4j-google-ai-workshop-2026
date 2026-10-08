;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts. This directory has its own deps.edn, so cider-jack-in from a file
;; under it starts `clojure` here, not at the repository root, with the :dev
;; alias: dev/rdf_explore.clj is on the classpath beside src/.
((nil
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev"))
 (clojure-mode
  (indent-tabs-mode . nil)
  (fill-column . 80)))
