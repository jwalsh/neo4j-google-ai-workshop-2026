;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts. Emacs reads the nearest .dir-locals.el only, so this one stands
;; in for the repository root's inside this directory. cider-jack-in starts
;; `clojure` here with the :dev alias, which puts dev/explore_logic.clj on
;; the classpath beside src/.
((nil
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev"))
 (clojure-mode
  (indent-tabs-mode . nil)
  (fill-column . 80)))
