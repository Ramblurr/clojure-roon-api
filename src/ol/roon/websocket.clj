(ns ol.roon.websocket
  "WebSocket wrapper using JDK HttpClient.
  Adapted from hato (MIT License): https://github.com/gnarroway/hato"
  (:import
   [java.io ByteArrayOutputStream]
   [java.net URI]
   [java.net.http HttpClient WebSocket WebSocket$Builder WebSocket$Listener]
   [java.nio ByteBuffer]
   [java.time Duration]
   [java.util.concurrent CompletableFuture TimeUnit]
   [java.util.function Consumer Function]))

(set! *warn-on-reflection* true)

;;; ByteBuffer utilities

(defn byte-buffer->bytes
  "Convert a ByteBuffer to a byte array.
  Reads from current position to limit."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

;;; Fragment accumulator for reassembling split messages

(defn make-fragment-accumulator
  "Creates a mutable accumulator for reassembling fragmented messages.
  WebSocket messages may arrive in multiple frames (last?=false until final frame)."
  []
  (atom (ByteArrayOutputStream.)))

(defn accumulator-empty?
  "Returns true if accumulator has no data."
  [acc]
  (zero? (.size ^ByteArrayOutputStream @acc)))

(defn accumulator-append!
  "Appends ByteBuffer data to accumulator."
  [acc ^ByteBuffer buf]
  (let [^bytes arr (byte-buffer->bytes buf)]
    (.write ^ByteArrayOutputStream @acc arr 0 (alength arr))))

(defn accumulator-complete!
  "Returns accumulated bytes and resets accumulator."
  [acc]
  (let [baos ^ByteArrayOutputStream @acc
        arr  (.toByteArray baos)]
    (.reset baos)
    arr))

;;; WebSocket listener

(defn- make-listener
  "Creates a WebSocket$Listener that handles fragmented messages.

  opts map:
  - :on-open    (fn [ws])
  - :on-message (fn [ws bytes]) - called with complete message bytes
  - :on-close   (fn [ws status reason])
  - :on-error   (fn [ws throwable])"
  [{:keys [on-open on-message on-close on-error]}]
  (let [accumulator (make-fragment-accumulator)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (.request ws 1)
        (when on-open
          (on-open ws)))

      (onText [_ ws data last?]
        ;; Convert text to bytes - MOO protocol uses binary
        (.request ws 1)
        (accumulator-append! accumulator (ByteBuffer/wrap (.getBytes (str data) "UTF-8")))
        (when last?
          (when on-message
            (let [complete-data (accumulator-complete! accumulator)]
              (.thenApply (CompletableFuture/completedFuture nil)
                          (reify Function
                            (apply [_ _] (on-message ws complete-data))))))))

      (onBinary [_ ws data last?]
        (.request ws 1)
        (accumulator-append! accumulator data)
        (when last?
          (when on-message
            (let [complete-data (accumulator-complete! accumulator)]
              (.thenApply (CompletableFuture/completedFuture nil)
                          (reify Function
                            (apply [_ _] (on-message ws complete-data))))))))

      (onPing [_ ws _data]
        (.request ws 1)
        nil)

      (onPong [_ ws _data]
        (.request ws 1)
        nil)

      (onClose [_ ws status reason]
        (when on-close
          (.thenApply (CompletableFuture/completedFuture nil)
                      (reify Function
                        (apply [_ _] (on-close ws status reason))))))

      (onError [_ ws err]
        (when on-error
          (on-error ws err))))))

;;; Public API

(defn connect!
  "Opens a WebSocket connection. Returns promise.

  url  - WebSocket URL, e.g. \"ws://10.9.4.17:9330/api\"
  opts - map of:
    :on-open     (fn [ws])
    :on-message  (fn [ws bytes]) - complete message bytes
    :on-close    (fn [ws status reason])
    :on-error    (fn [ws throwable])
    :timeout-ms  Connection timeout (default 10000)
    :headers     Map of header name -> value

  Returns a promise that delivers the WebSocket on success,
  or an exception on failure/timeout."
  [url opts]
  (let [timeout-ms                                                    (get opts :timeout-ms 10000)
        headers                                                       (get opts :headers {})
        listener                                                      (make-listener opts)
        http-client                                                   (HttpClient/newHttpClient)
        result                                                        (promise)
        ^WebSocket$Builder builder
        (cond-> (.newWebSocketBuilder http-client)
          timeout-ms (.connectTimeout (Duration/ofMillis timeout-ms))
          true       identity)]
    ;; Add headers
    (doseq [[k v] headers]
      (.header builder (name k) (str v)))
    ;; Connect async, deliver to promise
    (let [^CompletableFuture future (.buildAsync builder (URI/create url) listener)]
      (-> future
          (.orTimeout timeout-ms TimeUnit/MILLISECONDS)
          (.thenAccept (reify Consumer
                         (accept [_ ws]
                           (deliver result ws))))
          (.exceptionally (reify Function
                            (apply [_ ex]
                              (deliver result ex)
                              nil)))))
    result))

(defn send!
  "Sends binary data through WebSocket.

  ws   - WebSocket instance
  data - byte array to send"
  [^WebSocket ws ^bytes data]
  (.sendBinary ws (ByteBuffer/wrap data) true))

(defn close!
  "Closes WebSocket gracefully."
  ([^WebSocket ws]
   (close! ws WebSocket/NORMAL_CLOSURE ""))
  ([^WebSocket ws status-code reason]
   (.sendClose ws status-code reason)))

(defn abort!
  "Aborts WebSocket connection immediately."
  [^WebSocket ws]
  (.abort ws))
