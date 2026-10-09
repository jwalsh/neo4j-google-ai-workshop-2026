(ns workshop.redact
  "Paints over what should not be in a committed screenshot. Tesseract reads
  the image into words with boxes; a word that looks like an email address,
  or that holds a value from .env, gets a grey box from ImageMagick. A value
  that tesseract split across words takes its whole line.

  This catches what OCR can read. Look at the result before committing it."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [workshop.env :as env]))

;; Values from .env that are never to appear in an image. The instance id
;; and the project id are names, not credentials, and are covered all the
;; same: workshop.labs scrubs them from text, and one rule is easier to keep
;; than two. A console also prints ids that are in no file (app, agent,
;; project number); those are found by eye.
(def secret-variables
  ["LAB_EMAIL" "LAB_PASSWORD" "LAB_EMPLOYEE_ID" "NEO4J_PASSWORD"
   "AURA_INSTANCEID" "GOOGLE_CLOUD_PROJECT"])

(def ^:private box-padding 6)

(defn secret-fragments
  "What to look for: each secret value, and the part of an email before the
  @, lower-cased. Short values are left out; they would match by accident."
  [env-map]
  (->> secret-variables
       (keep env-map)
       (mapcat (fn [secret-value] [secret-value (first (str/split secret-value #"@"))]))
       (filter #(>= (count %) 6))
       (map str/lower-case)
       distinct
       vec))

(defn parse-words
  "Tesseract's TSV as word maps with a box and a [block paragraph line] key."
  [tsv-text]
  (->> (rest (str/split-lines tsv-text))
       (map #(str/split % #"\t" 12))
       (filter #(and (= 12 (count %)) (= "5" (first %)) (not (str/blank? (nth % 11)))))
       (mapv (fn [[_ _ block paragraph line _ left top width height _ text]]
               {:line [block paragraph line]
                :left (parse-long left) :top (parse-long top)
                :width (parse-long width) :height (parse-long height)
                :text text}))))

(defn- sensitive-text?
  [fragments text]
  (let [lowered (str/lower-case text)]
    (or (boolean (re-find #"[\w.+-]+@[\w-]+\.[\w.-]+" text))
        (boolean (some #(str/includes? lowered %) fragments)))))

(defn words-to-cover
  "The words to paint over: any that are sensitive alone, and every word of
  a line that is sensitive only when its words are joined."
  [fragments words]
  (->> (group-by :line words)
       (mapcat (fn [[_ line-words]]
                 (let [alone (filter #(sensitive-text? fragments (:text %)) line-words)]
                   (cond
                     (seq alone) alone
                     (sensitive-text? fragments (str/join (map :text line-words))) line-words
                     :else []))))
       vec))

(defn- run!!
  [& command]
  (let [{:keys [exit out err]} (apply shell/sh command)]
    (when-not (zero? exit)
      (throw (ex-info (str (first command) " failed: " (str/trim err)) {:command command})))
    out))

(defn read-words
  [image-path]
  (parse-words (run!! "tesseract" image-path "stdout" "--psm" "11" "tsv")))

(defn redact!
  "Writes output-path as image-path with the sensitive words covered.
  Returns how many boxes were painted and whether a second reading of the
  output still finds anything."
  ([image-path output-path] (redact! image-path output-path (env/load-env)))
  ([image-path output-path env-map]
   (let [fragments (secret-fragments env-map)
         covered (words-to-cover fragments (read-words image-path))
         draw-arguments (mapcat (fn [{:keys [left top width height]}]
                                  ["-draw" (format "rectangle %d,%d %d,%d"
                                                   (- left box-padding) (- top box-padding)
                                                   (+ left width box-padding)
                                                   (+ top height box-padding))])
                                covered)]
     (apply run!! (concat ["magick" image-path "-fill" "#d0d4d8"] draw-arguments [output-path]))
     {:output output-path
      :covered (count covered)
      :still-readable (count (words-to-cover fragments (read-words output-path)))})))
