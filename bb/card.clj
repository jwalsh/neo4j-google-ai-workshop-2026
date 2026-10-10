(ns card
  "The repository's card: a small transit map held in a pair of parentheses.
  Runs in babashka: bb card [OUT.svg].

  The picture is data. `lines` is each line as a colour and the points it
  runs through, `stations` is where the circles go, and the SVG is written
  from those, so a different map is a different value here and not a
  drawing to redo. `gmake card` renders the PNGs from the SVG."
  (:require [clojure.string :as str]))

(def size 1024)
(def paper "#f6f3ec")
(def ink "#1d2733")

(def lines
  "Each line of the map: straight runs and 45 degree turns, as a transit
  map draws them. Later lines are drawn over earlier ones."
  [{:name "orange" :colour "#d9541e" :points [[300 372] [440 372] [512 444] [512 652] [584 724] [724 724]]}
   {:name "blue"   :colour "#1f5fa8" :points [[300 652] [440 652] [512 580] [512 444] [584 372] [724 372]]}
   {:name "green"  :colour "#2e8b57" :points [[340 512] [684 512]]}])

(def stations
  "Where a circle is drawn: :end for the last stop of a line, :stop for one
  along it, :interchange for the one all three lines meet at."
  [{:at [300 372] :kind :end} {:at [724 724] :kind :end}
   {:at [300 652] :kind :end} {:at [724 372] :kind :end}
   {:at [340 512] :kind :end} {:at [684 512] :kind :end}
   {:at [440 372] :kind :stop} {:at [584 724] :kind :stop}
   {:at [440 652] :kind :stop} {:at [584 372] :kind :stop}
   {:at [512 512] :kind :interchange}])

(def circle-of
  {:end {:radius 24 :stroke 12} :stop {:radius 18 :stroke 12} :interchange {:radius 40 :stroke 16}})

(defn- path [points]
  (str "M " (str/join " L " (map (fn [[x y]] (str x " " y)) points))))

(defn svg
  "The card as SVG text, from a map's lines and stations."
  [map-lines map-stations]
  (str/join
   "\n"
   (concat
    [(format "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\">" size size size size)
     (format "  <rect width=\"%d\" height=\"%d\" fill=\"%s\"/>" size size paper)
     (format "  <g fill=\"none\" stroke=\"%s\" stroke-width=\"34\" stroke-linecap=\"round\">" ink)
     "    <path d=\"M 250 150 C 90 330, 90 694, 250 874\"/>"
     "    <path d=\"M 774 150 C 934 330, 934 694, 774 874\"/>"
     "  </g>"
     "  <g fill=\"none\" stroke-width=\"30\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"]
    (for [{:keys [colour points]} map-lines]
      (format "    <path d=\"%s\" stroke=\"%s\"/>" (path points) colour))
    ["  </g>"
     (format "  <g fill=\"%s\" stroke=\"%s\">" paper ink)]
    (for [{[x y] :at :keys [kind]} map-stations
          :let [{:keys [radius stroke]} (circle-of kind)]]
      (format "    <circle cx=\"%d\" cy=\"%d\" r=\"%d\" stroke-width=\"%d\"/>" x y radius stroke))
    ["  </g>" "</svg>" ""])))

(defn -main
  [& [out-path]]
  (let [target (or out-path "notes/diagrams/workshop-card.svg")]
    (spit target (svg lines stations))
    (println target)))
