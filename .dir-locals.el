;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts. cider-jack-in starts `clojure` with the :dev alias, which puts
;; dev/user.clj on the classpath: the REPL opens in `user` with (q "...").
((nil
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev"))
 (clojure-mode
  (indent-tabs-mode . nil)
  (fill-column . 80)))
