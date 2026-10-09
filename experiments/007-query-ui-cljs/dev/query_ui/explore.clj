(ns query-ui.explore
  "Rich comments for CIDER. Jack in from any file in this directory
  (.dir-locals.el picks this deps.edn and the :dev alias), load this buffer
  with C-c C-k, then evaluate forms one at a time with C-c C-e or C-M-x.

  Nothing here runs on load. Paths are relative to this directory, which is
  where a REPL started here has its working directory."
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.pprint :refer [pprint]]
            [query-ui.backend :as backend]
            [query-ui.bombadil :as bombadil]
            [query-ui.server :as server]))

;; ------------------------------------------------------------ the server

(comment
  ;; The front end has to be compiled once, and again after app.cljs
  ;; changes. About ten seconds; :advanced, one file in scratch/public/js.
  (shell/sh "clojure" "-M:cljs")

  (server/start!)                 ; fixture, http://127.0.0.1:8378/
  (server/start! :fixture 8379)   ; another port
  (server/stop!)

  ;; Which back end is it on?
  (some-> @server/running :backend-atom deref backend/backend-name)

  ;; Change it under the running server. Reload the page afterwards: the
  ;; page prints the back end it was served with.
  (server/switch-backend! :live)  ; needs NEO4J_* in ../../.env
  (server/switch-backend! :fixture)
  )

;; ---------------------------------- the data a frame is drawn from, no page

(comment
  (def fixture (backend/fixture-backend))

  ;; One statement: {:kind :single :command ... :outcome {...}}
  (backend/frame-data fixture "RETURN 1 AS ok")

  ;; What the status line under it will say.
  (-> (backend/frame-data fixture "MATCH (n) RETURN count(n) AS nodes")
      :outcome :status-line)

  ;; A failure.
  (backend/frame-data fixture "RETURN 1/0 AS impossible")

  ;; Anything the fixture does not know is a syntax error.
  (backend/frame-data fixture "this is not cypher")

  ;; Several statements: a row each; after the first failure the rest are
  ;; :aborted and were never sent.
  (->> (backend/frame-data fixture "RETURN 1 AS ok; RETURN 1/0 AS impossible; RETURN 1 AS ok;thi")
       :statements
       (mapv (juxt :number :statement (comp :status :outcome))))

  ;; How the editor's text is cut up. A semicolon inside quotes is not a
  ;; separator.
  (backend/split-statements "RETURN ';' AS semicolon; RETURN 2;thi")

  ;; Edge-shaped rows get a Graph view: mermaid text from workshop.diagram.
  (-> (backend/frame-data
       fixture
       (str "MATCH (home {id: 'Home'})-[r]->(member) RETURN home.id AS source, "
            "'Segment' AS sourceLabel, type(r) AS relationship, member.id AS target, "
            "'Product' AS targetLabel"))
      :outcome :graph println)

  ;; Every statement the fixture answers.
  (keys backend/fixture-answers)

  ;; Exactly the JSON the page receives from POST /api/run.
  (-> (server/run-response fixture "RETURN 1 AS ok; this is not cypher")
      (json/read-str :key-fn keyword)
      pprint)
  )

;; --------------------------------------------------------- the live side

(comment
  ;; The same calls against the instance in ../../.env.
  (def live (backend/live-backend))
  (backend/run-statement live "RETURN 1 AS ok")
  (backend/frame-data live "MATCH (m:Manager) RETURN m.name AS name LIMIT 3")
  (backend/frame-data live "RETURN 1 AS first; RETURN 1/0 AS impossible; RETURN 3 AS third")
  (backend/close-backend live)
  )

;; --------------------------------------------------------------- Bombadil

(comment
  ;; The server must be up: (server/start!)

  ;; The command, to read or to paste into a shell.
  (bombadil/managed-command {:origin bombadil/replica-origin
                             :specification "spec/replica.ts"
                             :output-path "scratch/bombadil/replica"
                             :seconds 60})

  ;; Run it from here. Blocks for the time limit; headless. Returns the
  ;; exit code and a summary of the trace.
  (bombadil/run-replica! 30)

  ;; The witnesses: every one should come back violated.
  (bombadil/run-witnesses! 30)

  ;; The Aura specification and the test-external path, against the replica.
  (bombadil/run-external-replica! 30)

  ;; Read a finished run again.
  (bombadil/trace-summary "scratch/bombadil/replica")

  ;; Step through it in a browser (this one opens a window, on purpose).
  (shell/sh "bombadil" "browser" "inspect" "scratch/bombadil/replica")

  ;; The real page. Read README.org, "The real page", first: this was not
  ;; run when the experiment was written.
  (bombadil/run-aura! "https://console.neo4j.io/projects/PROJECT-ID/studio/query" 120)
  )
