(ns ol.roon.test-util
  "Test utilities for loading fixtures and common test helpers."
  (:require [clojure.java.io :as io]))

(def ^:private fixtures-dir "test/fixtures")

(defn load-fixture-bytes
  "Loads a fixture file as a byte array.

  path - relative path within test/fixtures/, e.g. \"raw/ping_request.txt\""
  [path]
  (let [file (io/file fixtures-dir path)]
    (when-not (.exists file)
      (throw (ex-info "Fixture not found" {:path path :full-path (.getAbsolutePath file)})))
    (with-open [in (io/input-stream file)]
      (let [buf (byte-array (.available in))]
        (.read in buf)
        buf))))

(defn load-fixture-string
  "Loads a fixture file as a string."
  [path]
  (slurp (io/file fixtures-dir path)))
