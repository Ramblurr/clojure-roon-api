(ns roon
  "Roon API MVP - Connect to Roon Core via websocket using MOO protocol"
  (:require
   [charred.api :as charred]
   [clojure.string :as str]
   [hato.websocket :as ws])
  (:import
   [java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress
    MulticastSocket NetworkInterface StandardSocketOptions]
   [java.nio ByteBuffer]
   [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;;; ============================================================================
;;; Configuration
;;; ============================================================================

(def extension-info
  {:extension_id      "com.ramblurr.roon-clj"
   :display_name      "Roon Clojure MVP"
   :display_version   "0.1.0"
   :publisher         "ramblurr"
   :email             "dev@example.com"
   :required_services ["com.roonlabs.transport:2"]
   :optional_services []
   :provided_services ["com.roonlabs.ping:1"]})

(def services
  {:registry  "com.roonlabs.registry:1"
   :transport "com.roonlabs.transport:2"})

;;; ============================================================================
;;; SOOD Discovery
;;; ============================================================================

(def ^:const sood-port 9003)
(def ^:const sood-multicast-ip "239.255.90.90")
(def ^:const roon-service-id "00720724-5143-4a9b-abac-0e50cba674bb")

(defn- build-sood-query
  "Build a SOOD query packet for Roon discovery."
  []
  (let [tid   (str (java.util.UUID/randomUUID))
        props {"_tid"             tid
               "query_service_id" roon-service-id}
        buf   (byte-array 1024)
        bb    (ByteBuffer/wrap buf)]
    ;; Header: SOOD + version(2) + type(Q)
    (.put bb (.getBytes "SOOD" StandardCharsets/UTF_8))
    (.put bb (byte 2))
    (.put bb (byte (int \Q)))
    ;; Properties: name_len(1), name, value_len(2 BE), value
    (doseq [[k v] props]
      (let [name-bytes (.getBytes ^String k StandardCharsets/UTF_8)
            val-bytes  (.getBytes ^String v StandardCharsets/UTF_8)]
        (.put bb (byte (count name-bytes)))
        (.put bb name-bytes)
        (.putShort bb (short (count val-bytes)))
        (.put bb val-bytes)))
    (let [len (.position bb)]
      (byte-array (take len buf)))))

(defn- parse-sood-response
  "Parse a SOOD response packet into a map of properties."
  [^bytes data len]
  (when (and (>= len 6)
             (= "SOOD" (String. data 0 4 StandardCharsets/UTF_8))
             (= 2 (aget data 4)))
    (let [msg-type (char (aget data 5))]
      (when (= \R msg-type)
        (loop [pos   6
               props {}]
          (if (>= pos len)
            props
            (let [name-len   (bit-and (aget data pos) 0xff)
                  _          (when (zero? name-len) (throw (ex-info "Invalid name len" {})))
                  name-start (inc pos)
                  name-end   (+ name-start name-len)
                  name       (String. data name-start name-len StandardCharsets/UTF_8)
                  val-len-hi (bit-and (aget data name-end) 0xff)
                  val-len-lo (bit-and (aget data (inc name-end)) 0xff)
                  val-len    (bit-or (bit-shift-left val-len-hi 8) val-len-lo)
                  val-start  (+ name-end 2)]
              (if (= val-len 65535)
                (recur val-start (assoc props name nil))
                (let [val (String. data val-start val-len StandardCharsets/UTF_8)]
                  (recur (+ val-start val-len) (assoc props name val)))))))))))

(defn discover!
  "Discover Roon Cores on the network via SOOD.
   Returns a seq of maps with :ip and :port keys.
   timeout-ms defaults to 3000."
  ([] (discover! 3000))
  ([timeout-ms]
   (let [multicast-addr (InetAddress/getByName sood-multicast-ip)
         query-packet   (build-sood-query)
         results        (atom [])
         socket         (doto (DatagramSocket.)
                          (.setSoTimeout 500))]
     (try
       ;; Send query to multicast address
       (.send socket (DatagramPacket. ^bytes query-packet
                                      (int (count query-packet))
                                      ^InetAddress multicast-addr
                                      (int sood-port)))
       (println "Sent SOOD discovery query...")
       ;; Listen for responses
       (let [recv-buf (byte-array 1024)
             end-time (+ (System/currentTimeMillis) timeout-ms)]
         (loop []
           (when (< (System/currentTimeMillis) end-time)
             (try
               (let [packet (DatagramPacket. recv-buf (count recv-buf))]
                 (.receive socket packet)
                 (when-let [props (parse-sood-response recv-buf (.getLength packet))]
                   (when (and (= (get props "service_id") roon-service-id)
                              (get props "http_port"))
                     (let [ip   (or (get props "_replyaddr")
                                    (.getHostAddress (.getAddress packet)))
                           port (parse-long (get props "http_port"))]
                       (println "Found Roon Core:" ip ":" port)
                       (swap! results conj {:ip ip :port port :props props})))))
               (catch java.net.SocketTimeoutException _))
             (recur))))
       @results
       (finally
         (.close socket))))))

;;; ============================================================================
;;; State
;;; ============================================================================

(defonce state
  (atom {:ws        nil      ; websocket connection
         :req-id    0        ; next request ID
         :pending   {}       ; request-id -> {:promise ...}
         :core-info nil      ; core info after registration
         :token     nil      ; auth token for reconnection
         :zones     {}       ; zone-id -> zone-data
         :outputs   {}}))    ; output-id -> output-data

;; Buffer for fragmented websocket messages
;; Note: Unlike Rust's tokio-tungstenite or Python's websocket-client,
;; Java's HttpClient WebSocket (used by hato) delivers message fragments
;; as they arrive, requiring manual reassembly. We buffer chunks until
;; the final fragment (last?=true) is received.
(defonce msg-buffer (atom nil))

;;; ============================================================================
;;; MOO Message Building
;;; ============================================================================

(defn build-moo-request
  "Build a MOO REQUEST message as bytes.
   service+method is like 'com.roonlabs.transport:2/get_zones'"
  [service+method body req-id]
  (let [body-str   (when body (charred/write-json-str body))
        body-bytes (when body-str (.getBytes ^String body-str StandardCharsets/UTF_8))
        header     (str "MOO/1 REQUEST " service+method "\n"
                        "Request-Id: " req-id "\n"
                        (when body-bytes
                          (str "Content-Length: " (count body-bytes) "\n"
                               "Content-Type: application/json\n"))
                        "\n")]
    (if body-bytes
      (let [header-bytes (.getBytes header StandardCharsets/UTF_8)
            result       (byte-array (+ (count header-bytes) (count body-bytes)))]
        (System/arraycopy header-bytes 0 result 0 (count header-bytes))
        (System/arraycopy body-bytes 0 result (count header-bytes) (count body-bytes))
        result)
      (.getBytes header StandardCharsets/UTF_8))))

(defn build-moo-response
  "Build a MOO COMPLETE/CONTINUE response as bytes."
  [verb status body req-id]
  (let [body-str   (when body (charred/write-json-str body))
        body-bytes (when body-str (.getBytes ^String body-str StandardCharsets/UTF_8))
        header     (str "MOO/1 " verb " " status "\n"
                        "Request-Id: " req-id "\n"
                        (when body-bytes
                          (str "Content-Length: " (count body-bytes) "\n"
                               "Content-Type: application/json\n"))
                        "\n")]
    (if body-bytes
      (let [header-bytes (.getBytes header StandardCharsets/UTF_8)
            result       (byte-array (+ (count header-bytes) (count body-bytes)))]
        (System/arraycopy header-bytes 0 result 0 (count header-bytes))
        (System/arraycopy body-bytes 0 result (count header-bytes) (count body-bytes))
        result)
      (.getBytes header StandardCharsets/UTF_8))))

;;; ============================================================================
;;; MOO Message Parsing
;;; ============================================================================

(defn byte-buffer->bytes
  "Convert a ByteBuffer to a byte array."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn parse-moo-message
  "Parse incoming MOO message from ByteBuffer into a map:
   {:verb :request/:complete/:continue
    :name \"Success\" or \"service/method\"
    :request-id 42
    :body {...} or nil}"
  [^ByteBuffer buf]
  (let [^bytes bytes (byte-buffer->bytes buf)
        text         (String. bytes StandardCharsets/UTF_8)
        header-end   (.indexOf text "\n\n")]
    (when (pos? header-end)
      (let [header-text (subs text 0 header-end)
            body-text   (when (< (+ header-end 2) (count text))
                          (subs text (+ header-end 2)))
            lines       (str/split header-text #"\n")
            first-line  (first lines)]
        ;; Parse first line: MOO/1 VERB name
        (when-let [[_ verb name] (re-matches #"MOO/1 ([A-Z]+) (.*)" first-line)]
          (let [headers      (into {}
                                   (for [line  (rest lines)
                                         :let  [[_ k v] (re-matches #"([^:]+): *(.*)" line)]
                                         :when k]
                                     [k v]))
                request-id   (some-> (get headers "Request-Id") parse-long)
                content-type (get headers "Content-Type")
                body         (when (and (= content-type "application/json")
                                        (not (str/blank? body-text)))
                               (charred/read-json body-text :key-fn keyword))]
            {:verb       (keyword (str/lower-case verb))
             :name       name
             :request-id request-id
             :body       body
             :headers    headers}))))))

;;; ============================================================================
;;; Request/Response Handling
;;; ============================================================================

(defn next-req-id!
  "Get and increment the next request ID."
  []
  (let [id (:req-id @state)]
    (swap! state update :req-id inc)
    id))

(defn handle-incoming-request!
  "Handle requests from Roon (e.g., ping service)."
  [msg]
  (let [{:keys [name request-id]} msg]
    (println "<- REQUEST" request-id name)
    (case name
      "com.roonlabs.ping:1/ping"
      (let [response (build-moo-response "COMPLETE" "Success" nil request-id)]
        (ws/send! (:ws @state) (ByteBuffer/wrap response))
        (println "-> COMPLETE" request-id "Success (ping)"))
      ;; Default: log unknown request
      (println "   Unhandled incoming request:" name))))

(defn handle-response!
  "Handle response messages (COMPLETE/CONTINUE)."
  [msg]
  (let [{:keys [request-id verb name body]} msg]
    (println "<-" (str/upper-case (clojure.core/name verb)) request-id name
             (when body (pr-str body)))
    (when-let [pending (get-in @state [:pending request-id])]
      (deliver (:promise pending) msg)
      (when (= verb :complete)
        (swap! state update :pending dissoc request-id)))))

(defn handle-message
  "Route incoming MOO messages to appropriate handlers.
   Handles fragmented websocket messages by buffering until last?=true."
  [_ws ^ByteBuffer buf last?]
  (let [chunk (byte-buffer->bytes buf)]
    ;; Accumulate chunks
    (if @msg-buffer
      (let [existing @msg-buffer
            combined (byte-array (+ (count existing) (count chunk)))]
        (System/arraycopy existing 0 combined 0 (count existing))
        (System/arraycopy chunk 0 combined (count existing) (count chunk))
        (reset! msg-buffer combined))
      (reset! msg-buffer chunk))
    ;; Process when we have the complete message
    (when last?
      (let [complete-buf (ByteBuffer/wrap @msg-buffer)]
        (reset! msg-buffer nil) ;; Clear buffer
        (if-let [msg (parse-moo-message complete-buf)]
          (case (:verb msg)
            :request              (handle-incoming-request! msg)
            (:complete :continue) (handle-response! msg)
            (println "Unknown verb:" (:verb msg)))
          (println "[WARN] Failed to parse complete message"))))))

(defn send-request!
  "Send a request and return a promise for the response."
  [service method body]
  (let [req-id         (next-req-id!)
        service+method (str service "/" method)
        msg-bytes      (build-moo-request service+method body req-id)
        p              (promise)]
    (swap! state assoc-in [:pending req-id] {:promise p})
    (ws/send! (:ws @state) (ByteBuffer/wrap msg-bytes))
    (println "->" "REQUEST" req-id service+method (when body (pr-str body)))
    p))

;;; ============================================================================
;;; Connection Management
;;; ============================================================================

(defn connect!
  "Connect to Roon Core at given host:port.
   Returns the websocket connection."
  [host port]
  (let [url (str "ws://" host ":" port "/api")
        ws  @(ws/websocket url
                           {:on-message (fn [ws buf last?]
                                          (handle-message ws buf last?))
                            :on-close   (fn [_ws status reason]
                                          (println "WebSocket closed:" status reason)
                                          (swap! state assoc :ws nil))
                            :on-error   (fn [_ws err]
                                          (println "WebSocket error:" err))})]
    (swap! state assoc :ws ws)
    (println "Connected to" url)
    ws))

(defn disconnect!
  "Close the websocket connection."
  []
  (when-let [ws (:ws @state)]
    (ws/close! ws)
    (swap! state assoc :ws nil)
    (println "Disconnected")))

;;; ============================================================================
;;; Registration Flow
;;; ============================================================================

(defn get-core-info!
  "Request core info from registry service."
  []
  @(send-request! (:registry services) "info" nil))

(defn register!
  "Register extension with Roon Core.
   Pass token if reconnecting with saved credentials.
   User must approve extension in Roon Settings > Extensions."
  ([] (register! nil))
  ([token]
   (let [reg-info (cond-> extension-info
                    token (assoc :token token))
         response @(send-request! (:registry services) "register" reg-info)]
     (when (= (:name response) "Registered")
       (swap! state assoc
              :core-info (:body response)
              :token     (get-in response [:body :token]))
       (println "Registered with core:" (get-in response [:body :display_name]))
       response))))

;;; ============================================================================
;;; Transport API
;;; ============================================================================

(defn get-zones!
  "Request list of all zones."
  []
  (let [response @(send-request! (:transport services) "get_zones" nil)]
    (when (= (:name response) "Success")
      (let [zones (get-in response [:body :zones])]
        (swap! state assoc :zones
               (into {} (map (juxt :zone_id identity) zones)))
        zones))))

(defn get-outputs!
  "Request list of all outputs."
  []
  (let [response @(send-request! (:transport services) "get_outputs" nil)]
    (when (= (:name response) "Success")
      (let [outputs (get-in response [:body :outputs])]
        (swap! state assoc :outputs
               (into {} (map (juxt :output_id identity) outputs)))
        outputs))))

(defn change-volume!
  "Change volume on an output.
   how: :absolute, :relative, or :relative_step
   value: volume value or delta"
  [output-id how value]
  @(send-request! (:transport services) "change_volume"
                  {:output_id output-id
                   :how       (name how)
                   :value     value}))

(defn volume-up!
  "Increase volume by one step."
  [output-id]
  (change-volume! output-id :relative_step 1))

(defn volume-down!
  "Decrease volume by one step."
  [output-id]
  (change-volume! output-id :relative_step -1))

(defn control!
  "Send playback control command.
   control: :play, :pause, :stop, :next, :previous, :playpause"
  [zone-id control]
  @(send-request! (:transport services) "control"
                  {:zone_or_output_id zone-id
                   :control           (name control)}))

(defn mute!
  "Mute or unmute an output."
  [output-id mute?]
  @(send-request! (:transport services) "mute"
                  {:output_id output-id
                   :how       (if mute? "mute" "unmute")}))

;;; ============================================================================
;;; Helper Functions
;;; ============================================================================

(defn find-zone-by-name
  "Find a zone by its display name."
  [name]
  (->> (vals (:zones @state))
       (filter #(= (:display_name %) name))
       first))

(defn find-output-by-name
  "Find an output by its display name."
  [name]
  (->> (vals (:outputs @state))
       (filter #(= (:display_name %) name))
       first))

(defn list-zone-names
  "List all zone display names."
  []
  (map :display_name (vals (:zones @state))))

(defn list-output-names
  "List all output display names."
  []
  (map :display_name (vals (:outputs @state))))

;;; ============================================================================
;;; REPL Convenience
;;; ============================================================================

(comment
  ;; Example REPL session:

  ;; 1. Discover Roon Cores on the network
  (discover!)
  ;; => [{:ip "10.9.4.17" :port 9330 :props {...}}]

  ;; 2. Connect to Roon Core
  (connect! "10.9.4.17" 9330)

  ;; 3. Get core info
  (get-core-info!)

  ;; 4. Register extension - user must approve in Roon Settings > Extensions
  (register!)
  ;; After approval, save the token for future connections:
  (spit "roon-config.edn" (pr-str {:token   (:token @state)
                                   :core-id (:core_id (:core-info @state))}))

  ;; For subsequent connections, load and use the saved token:
  (def config (read-string (slurp "roon-config.edn")))
  (register! (:token config))

  ;; 5. List zones and outputs
  (get-zones!)
  (get-outputs!)
  (list-zone-names)
  (list-output-names)

  ;; 5. Find specific zone/output
  (def living-room (find-zone-by-name "Living Room"))
  (def dac (find-output-by-name "DAC"))

  ;; 6. Volume control
  (volume-up! (:output_id dac))
  (volume-down! (:output_id dac))
  (change-volume! (:output_id dac) :absolute 50)

  ;; 7. Playback control
  (control! (:zone_id living-room) :play)
  (control! (:zone_id living-room) :pause)
  (control! (:zone_id living-room) :next)

  ;; 8. Mute
  (mute! (:output_id dac) true)
  (mute! (:output_id dac) false)

  ;; 9. Disconnect
  (disconnect!)

  ;; Check current state
  @state
  (:zones @state)
  (:outputs @state))
