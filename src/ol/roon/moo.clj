(ns ol.roon.moo
  "MOO protocol encoding and decoding.

  MOO is the message protocol used by Roon over WebSocket.
  Messages have a header line, optional headers, and an optional body.

  Format:
    MOO/1 VERB name
    Header-Name: value
    Content-Type: application/json
    Content-Length: 123
    Request-Id: 42

    {\"json\": \"body\"}

  Verbs: REQUEST, CONTINUE, COMPLETE"
  (:require [clojure.string :as str]
            [charred.api :as charred])
  (:import [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(defn- find-header-end
  "Find the position of the header/body separator (double newline).
  Returns -1 if not found."
  ^long [^String text]
  (let [idx (.indexOf text "\n\n")]
    (if (>= idx 0)
      idx
      ;; Also check for \r\n\r\n (Windows-style)
      (let [crlf-idx (.indexOf text "\r\n\r\n")]
        (if (>= crlf-idx 0)
          crlf-idx
          -1)))))

(defn- parse-first-line
  "Parse the MOO header line.
  Returns {:verb :request|:continue|:complete :name \"...\"}
  or nil if invalid."
  [^String line]
  (when-let [[_ verb name] (re-matches #"MOO/1 ([A-Z]+) (.*)" line)]
    {:verb (keyword (str/lower-case verb))
     :name name}))

(defn- parse-headers
  "Parse header lines into a map."
  [lines]
  (into {}
        (for [line  lines
              :let  [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
              :when k]
          [k v])))

(defn- parse-body
  "Parse the body based on content-type.
  Returns parsed JSON map, raw bytes, or nil."
  [^bytes full-bytes body-start content-type content-length]
  (when (and content-length (pos? content-length))
    (let [body-bytes (byte-array content-length)]
      (System/arraycopy full-bytes body-start body-bytes 0
                        (min content-length (- (alength full-bytes) body-start)))
      (if (= content-type "application/json")
        (charred/read-json (String. body-bytes StandardCharsets/UTF_8))
        body-bytes))))

(defn parse-message
  "Parses a MOO message from bytes.

  Returns a map:
    {:verb         :request | :continue | :complete
     :name         \"service/method\" or \"Success\" etc
     :request-id   42
     :headers      {\"Header\" \"value\"}
     :body         <parsed JSON> | <bytes> | nil
     :content-type \"application/json\" | nil}"
  [^bytes data]
  (let [text       (String. data StandardCharsets/UTF_8)
        header-end (find-header-end text)]
    (when (>= header-end 0)
      (let [header-text (subs text 0 header-end)
            lines       (str/split-lines header-text)
            first-line  (first lines)]
        (when-let [{:keys [verb name]} (parse-first-line first-line)]
          (let [headers        (parse-headers (rest lines))
                request-id     (some-> (get headers "Request-Id") parse-long)
                content-type   (get headers "Content-Type")
                content-length (some-> (get headers "Content-Length") parse-long)
                ;; Calculate body start position (header + separator)
                body-start     (+ header-end
                                  (if (str/includes? (subs text header-end (min (+ header-end 4) (count text))) "\r\n")
                                    4  ;; \r\n\r\n
                                    2)) ;; \n\n
                body           (parse-body data body-start content-type content-length)]
            {:verb         verb
             :name         name
             :request-id   request-id
             :headers      headers
             :body         body
             :content-type content-type}))))))

(defn encode-request
  "Encodes a MOO REQUEST message.

  request-id   - integer request ID
  service-path - e.g. \"com.roonlabs.transport:2/control\"
  body         - map to encode as JSON, or nil"
  [request-id service-path body]
  (let [body-str   (when body (charred/write-json-str body))
        body-bytes (when body-str (.getBytes ^String body-str StandardCharsets/UTF_8))
        header     (str "MOO/1 REQUEST " service-path "\n"
                        "Request-Id: " request-id "\n"
                        (when body-bytes
                          (str "Content-Length: " (alength body-bytes) "\n"
                               "Content-Type: application/json\n"))
                        "\n")]
    (if body-bytes
      (let [header-bytes (.getBytes header StandardCharsets/UTF_8)
            result       (byte-array (+ (alength header-bytes) (alength body-bytes)))]
        (System/arraycopy header-bytes 0 result 0 (alength header-bytes))
        (System/arraycopy body-bytes 0 result (alength header-bytes) (alength body-bytes))
        result)
      (.getBytes header StandardCharsets/UTF_8))))

(defn encode-response
  "Encodes a MOO response (CONTINUE or COMPLETE).

  verb       - :continue or :complete
  name       - e.g. \"Success\" or \"Changed\"
  request-id - integer request ID
  body       - map to encode as JSON, or nil"
  [verb name request-id body]
  (let [verb-str   (str/upper-case (clojure.core/name verb))
        body-str   (when body (charred/write-json-str body))
        body-bytes (when body-str (.getBytes ^String body-str StandardCharsets/UTF_8))
        header     (str "MOO/1 " verb-str " " name "\n"
                        "Request-Id: " request-id "\n"
                        (when body-bytes
                          (str "Content-Length: " (alength body-bytes) "\n"
                               "Content-Type: application/json\n"))
                        "\n")]
    (if body-bytes
      (let [header-bytes (.getBytes header StandardCharsets/UTF_8)
            result       (byte-array (+ (alength header-bytes) (alength body-bytes)))]
        (System/arraycopy header-bytes 0 result 0 (alength header-bytes))
        (System/arraycopy body-bytes 0 result (alength header-bytes) (alength body-bytes))
        result)
      (.getBytes header StandardCharsets/UTF_8))))
