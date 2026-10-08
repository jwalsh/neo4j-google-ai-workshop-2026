(ns workshop.diagram
  "Query results drawn as mermaid, so a subgraph can be looked at from the
  REPL and kept in a note without a console and a screenshot.

  The input is rows shaped like labs/lab-06/06-home-edges.cypher returns:
  {:source :sourceLabel :relationship :target :targetLabel}."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- node-id
  [node-name]
  (str "n_" (str/replace (str/lower-case node-name) #"[^a-z0-9]+" "_")))

(defn- quoted
  [text]
  (str/replace text "\"" "'"))

(defn edges->mermaid
  "A mermaid flowchart of edge rows. Nodes are grouped by label into
  classes so each label can be styled; the label is printed under the name."
  ([edge-rows] (edges->mermaid edge-rows {}))
  ([edge-rows {:keys [direction comment] :or {direction "LR"}}]
   (let [nodes (distinct (mapcat (fn [{:keys [source sourceLabel target targetLabel]}]
                                   [[source sourceLabel] [target targetLabel]])
                                 edge-rows))
         labels (sort (distinct (keep second nodes)))]
     (str/join
      "\n"
      (concat
       (when comment (map #(str "%% " %) (str/split-lines comment)))
       [(str "flowchart " direction)]
       (for [[node-name node-label] (sort nodes)]
         (format "  %s[\"%s<br/><small>%s</small>\"]:::%s"
                 (node-id node-name) (quoted node-name) (or node-label "") (str/lower-case (or node-label "node"))))
       (for [{:keys [source relationship target]} edge-rows]
         (format "  %s -- %s --> %s" (node-id source) relationship (node-id target)))
       (map-indexed (fn [label-index label]
                      (format "  classDef %s fill:%s,stroke:#555,color:#111"
                              (str/lower-case label)
                              (nth ["#cfe8d5" "#f6c9c0" "#d6dcf5" "#f3e3b3" "#e3d0ee"] (mod label-index 5))))
                    labels)
       [""])))))

(defn render!
  "Renders a mermaid file to an image with mmdc, if it is installed.
  puppeteer-config-path names a JSON file with the browser mmdc should use;
  without one mmdc uses its own."
  ([mermaid-path image-path] (render! mermaid-path image-path nil))
  ([mermaid-path image-path puppeteer-config-path]
   (let [arguments (cond-> ["mmdc" "-q" "-i" mermaid-path "-o" image-path "-b" "white" "-s" "2"]
                     puppeteer-config-path (into ["-p" puppeteer-config-path]))
         {:keys [exit err]} (apply shell/sh arguments)]
     (when-not (zero? exit)
       (throw (ex-info (str "mmdc failed: " (str/trim err)) {:arguments arguments})))
     image-path)))
