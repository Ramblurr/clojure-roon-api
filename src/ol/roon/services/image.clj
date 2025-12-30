(ns ol.roon.services.image
  "Image service API for fetching images by key.

  This is a consumed service (provided by Roon Core).

  Two methods for fetching images:
  1. MOO protocol via get-image! - returns binary data with content-type
  2. HTTP URL helper - generates URL for direct HTTP fetch"
  (:require [ol.roon.connection :as conn]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def ^:const service "com.roonlabs.image:1")

;;; Request map builder

(defn get-image
  "Returns request map for fetching an image.

  Options:
  | key     | description                                   |
  |---------|-----------------------------------------------|
  | :scale  | :fit, :fill, or :stretch                      |
  | :width  | Desired width in pixels                       |
  | :height | Desired height in pixels                      |
  | :format | :jpeg or :png                                 |"
  ([image-key] (get-image image-key {}))
  ([image-key opts]
   {:uri  (str service "/get_image")
    :body (cond-> {"image_key" image-key}
            (:scale opts)  (assoc "scale" (name (:scale opts)))
            (:width opts)  (assoc "width" (:width opts))
            (:height opts) (assoc "height" (:height opts))
            (:format opts) (assoc "format" (case (:format opts)
                                             :jpeg "image/jpeg"
                                             :png  "image/png"
                                             (name (:format opts)))))}))

;;; Action function

(defn get-image!
  "Fetches image via MOO protocol. Returns promise.

  Promise delivers {:content-type \"image/jpeg\" :data <bytes>}.

  See [[get-image]] for options."
  ([connection image-key] (get-image! connection image-key {}))
  ([connection image-key opts]
   (conn/request! connection (get-image image-key opts))))

;;; HTTP URL helper (pure function)

(defn- url-encode
  "URL-encodes a string value."
  [^String s]
  (URLEncoder/encode s StandardCharsets/UTF_8))

(defn image-url
  "Constructs HTTP URL for direct image fetching.

  Returns URL like: `http://host:port/api/image/key?scale=fit&width=200`

  Options same as [[get-image]]."
  ([host port image-key] (image-url host port image-key {}))
  ([host port image-key opts]
   (let [base   (str "http://" host ":" port "/api/image/" image-key)
         params (cond-> []
                  (:scale opts)  (conj (str "scale=" (name (:scale opts))))
                  (:width opts)  (conj (str "width=" (:width opts)))
                  (:height opts) (conj (str "height=" (:height opts)))
                  (:format opts) (conj (str "format=" (url-encode (case (:format opts)
                                                                    :jpeg "image/jpeg"
                                                                    :png  "image/png"
                                                                    (name (:format opts)))))))]
     (if (seq params)
       (str base "?" (str/join "&" params))
       base))))

(defn image-url-from-connection
  "Constructs HTTP image URL using connection's host/port.

  See [[image-url]] for options."
  ([connection image-key] (image-url-from-connection connection image-key {}))
  ([connection image-key opts]
   (let [{:keys [host port]} (:config connection)]
     (image-url host (or port 9330) image-key opts))))
