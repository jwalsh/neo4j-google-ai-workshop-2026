(ns workshop.msa
  "experiments/001-msa-similarity, ported: the core of the Dataproc
  similarity notebooks with no database. Natural log of four census
  figures for the metro areas (micro areas dropped), min-max scaling, each area's 15 nearest neighbours by Euclidean
  distance, similarity 1 / (1 + distance), cutoff at the first percentile.
  The Python in the experiment is the reference; `edges` must print the same
  lines it does."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def features ["population" "medianHouseholdIncome" "medianHomePrice" "percentOver25WithBachelors"])
(def area-filter "Metro")
(def top-k 15)
(def cutoff-percentile 0.01)

(defn read-areas
  [csv-path]
  (with-open [reader (io/reader csv-path)]
    (let [[header & lines] (csv/read-csv reader)]
      (into []
            (comp (map #(zipmap header %))
                  (filter #(str/includes? (% "name") area-filter))
                  (map (fn [row]
                         {:name (row "name")
                          :logs (mapv #(Math/log (parse-double (row %))) features)})))
            lines))))

(defn min-max-scale
  [areas]
  (let [columns (apply map vector (map :logs areas))
        lows (mapv #(apply min %) columns)
        highs (mapv #(apply max %) columns)]
    (mapv (fn [area]
            (assoc area :scaled (mapv (fn [value low high] (/ (- value low) (- high low)))
                                      (:logs area) lows highs)))
          areas)))

(defn- round-9
  [number]
  (/ (Math/rint (* number 1e9)) 1e9))

(defn similarity
  [scaled-a scaled-b]
  (let [distance (Math/sqrt (reduce + (map (fn [a b] (let [gap (- a b)] (* gap gap)))
                                           scaled-a scaled-b)))]
    (round-9 (/ 1.0 (+ 1.0 distance)))))

(defn nearest-neighbours
  "For every area, its top-k most similar others as [source target similarity].
  Ties go to the name that sorts first, as in the Python."
  [areas]
  (vec (mapcat (fn [source]
                 (->> areas
                      (remove #(identical? % source))
                      (map (fn [target]
                             [(:name source) (:name target)
                              (similarity (:scaled source) (:scaled target))]))
                      (sort-by (fn [[_ target-name edge-similarity]]
                                 [(- edge-similarity) target-name]))
                      (take top-k)))
               areas)))

(defn apply-cutoff
  [edges]
  (let [similarities (vec (sort (map peek edges)))
        cutoff (nth similarities (int (* (count similarities) cutoff-percentile)))]
    (filterv #(>= (peek %) cutoff) edges)))

(defn edges
  "The similarity graph for a census file, sorted."
  [csv-path]
  (->> (read-areas csv-path) min-max-scale nearest-neighbours apply-cutoff sort vec))

(defn most-similar
  "The neighbours kept for one area, nearest first."
  [similarity-edges area-name]
  (->> similarity-edges
       (filter #(= area-name (first %)))
       (sort-by peek >)
       (mapv (fn [[_ target-name edge-similarity]] {:area target-name :similarity edge-similarity}))))
