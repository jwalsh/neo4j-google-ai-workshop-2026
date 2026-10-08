(ns workshop.gcp
  "Google Cloud, through Google's Java client for Cloud Storage.

  The workshop's datasets are public objects, so the default client here is
  unauthenticated: it needs no Google account and cannot touch the lab
  project. `project-storage` is the authenticated one, for when application
  default credentials for the lab account exist."
  (:require [clojure.java.io :as io]
            [workshop.env :as env])
  (:import (com.google.cloud.storage Blob BlobId Storage Storage$BlobListOption
                                     Storage$BlobSourceOption StorageOptions)
           (java.nio.channels Channels)))

(set! *warn-on-reflection* true)

(def dataset-bucket "neo4j-datasets")
(def dataset-prefix "hands-on-lab/")

(defn public-storage
  "A Storage client with no credentials. Reads public objects only."
  ^Storage []
  (.getService (StorageOptions/getUnauthenticatedInstance)))

(defn project-storage
  "A Storage client on GOOGLE_CLOUD_PROJECT with application default
  credentials. Throws if there are none."
  (^Storage [] (project-storage (env/load-env)))
  (^Storage [env-map]
   (let [{:strs [GOOGLE_CLOUD_PROJECT]} (env/require-vars env-map ["GOOGLE_CLOUD_PROJECT"])
         options-builder (doto (StorageOptions/newBuilder)
                           (.setProjectId GOOGLE_CLOUD_PROJECT))]
     (.getService (.build options-builder)))))

(defn- blob->map
  [^Blob blob]
  {:name (.getName blob)
   :size (.getSize blob)
   :content-type (.getContentType blob)
   :updated (some-> (.getUpdateTimeOffsetDateTime blob) str)})

(defn list-objects
  "The objects directly under a prefix, as maps; a directory below it is one
  entry with a trailing slash. Defaults to the workshop's datasets."
  ([storage] (list-objects storage dataset-bucket dataset-prefix))
  ([^Storage storage ^String bucket ^String prefix]
   (let [list-options (into-array Storage$BlobListOption
                                  [(Storage$BlobListOption/prefix prefix)
                                   (Storage$BlobListOption/currentDirectory)])]
     (mapv blob->map (.iterateAll (.list storage bucket list-options))))))

(defn- object-stream
  ^java.io.InputStream [^Storage storage ^String bucket ^String object-name]
  (Channels/newInputStream
   (.reader storage (BlobId/of bucket object-name)
            (into-array Storage$BlobSourceOption []))))

(defn peek-object
  "The first line-count lines of a text object, without fetching the rest."
  ([storage object-name] (peek-object storage dataset-bucket object-name 5))
  ([storage bucket object-name line-count]
   (with-open [reader (io/reader (object-stream storage bucket object-name))]
     (vec (take line-count (line-seq reader))))))

(defn download-object
  "Copies an object to a local path and returns the path. /data/ is ignored
  by git and is where datasets go."
  [storage bucket object-name destination-path]
  (io/make-parents destination-path)
  (with-open [object-input (object-stream storage bucket object-name)]
    (io/copy object-input (io/file destination-path)))
  destination-path)

(defn public-url
  "The https URL of a public object: what LOAD CSV in the labs is given."
  ([object-name] (public-url dataset-bucket object-name))
  ([bucket object-name]
   (str "https://storage.googleapis.com/" bucket "/" object-name)))
