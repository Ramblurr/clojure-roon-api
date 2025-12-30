(ns ol.roon.sood
  "SOOD (Service-Oriented Opensourced Discovery) for Roon Cores.

  Discovers Roon Cores on the local network using UDP multicast.
  Queries all IPv4 network interfaces for comprehensive discovery.

  SOOD Protocol:
  - UDP port 9003
  - Multicast group 239.255.90.90
  - Binary TLV format with header `SOOD\\x02` + type byte (Q=query, R=response)
  - Properties encoded as: name_len(1 byte), name, value_len(2 bytes BE), value"
  (:import [java.net DatagramSocket DatagramPacket InetAddress NetworkInterface]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;;; Constants

(def ^:const sood-port
  "UDP port for SOOD discovery."
  9003)

(def ^:const sood-multicast-ip
  "Multicast group address for SOOD."
  "239.255.90.90")

(def ^:const roon-service-id
  "Roon's service UUID for SOOD discovery."
  "00720724-5143-4a9b-abac-0e50cba674bb")

;;; Core record

(defrecord Core [unique-id host port name version])

;;; Internal functions (stubs for TDD)

(defn build-query
  "Builds SOOD query packet bytes.

  Returns byte array containing:
  - Header: SOOD (4 bytes) + version 2 (1 byte) + type Q (1 byte)
  - Properties: _tid (transaction UUID), query_service_id (Roon UUID)"
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

(defn parse-response
  "Parses SOOD response bytes into property map.

  Returns map of property name to value, or nil if:
  - Header is invalid (not SOOD version 2)
  - Message type is not R (response)
  - Data is malformed or truncated"
  [^bytes data len]
  (try
    (when (and (>= len 6)
               (= "SOOD" (String. data 0 4 StandardCharsets/UTF_8))
               (= 2 (aget data 4)))
      (let [msg-type (char (aget data 5))]
        (when (= \R msg-type)
          (loop [pos   6
                 props {}]
            (if (>= pos len)
              props
              (let [name-len (bit-and (aget data pos) 0xff)]
                (when (pos? name-len)
                  (let [name-start (inc pos)
                        name-end   (+ name-start name-len)]
                    ;; Bounds check: ensure name and value length bytes are within buffer
                    (when (< (inc name-end) len)
                      (let [prop-name  (String. data name-start name-len StandardCharsets/UTF_8)
                            val-len-hi (bit-and (aget data name-end) 0xff)
                            val-len-lo (bit-and (aget data (inc name-end)) 0xff)
                            val-len    (bit-or (bit-shift-left val-len-hi 8) val-len-lo)
                            val-start  (+ name-end 2)]
                        (if (= val-len 65535)
                          (recur val-start (assoc props prop-name nil))
                          ;; Bounds check: ensure value is within buffer
                          (when (<= (+ val-start val-len) len)
                            (let [val (String. data val-start val-len StandardCharsets/UTF_8)]
                              (recur (+ val-start val-len) (assoc props prop-name val)))))))))))))))
    (catch Exception _
      nil)))

(defn- get-ipv4-interfaces
  "Returns seq of {:iface NetworkInterface :addr InetAddress :broadcast InetAddress}
   for all IPv4 interfaces that are up and not loopback."
  []
  (for [^NetworkInterface iface         (enumeration-seq (NetworkInterface/getNetworkInterfaces))
        :when                           (.isUp iface)
        :when                           (not (.isLoopback iface))
        ^java.net.InterfaceAddress addr (.getInterfaceAddresses iface)
        :let                            [inet-addr (.getAddress addr)
                                         broadcast  (.getBroadcast addr)]
        :when                           (instance? java.net.Inet4Address inet-addr)]
    {:iface     iface
     :addr      inet-addr
     :broadcast broadcast}))

(defn- send-query!
  "Sends SOOD query to multicast and broadcast addresses on all interfaces.

  Uses the provided socket to send queries. Sends multicast to the multicast
  group and broadcast to each interface's broadcast address."
  [^DatagramSocket socket ^bytes query]
  (let [multicast-addr (InetAddress/getByName sood-multicast-ip)
        multicast-pkt  (DatagramPacket. query (count query) multicast-addr sood-port)]
    ;; Send to multicast group
    (try
      (.send socket multicast-pkt)
      (catch Exception _))
    ;; Send to broadcast address on each interface
    (doseq [{:keys [^InetAddress broadcast]} (get-ipv4-interfaces)
            :when                            broadcast]
      (try
        (let [pkt (DatagramPacket. query (count query) broadcast (int sood-port))]
          (.send socket pkt))
        (catch Exception _)))))

(defn- receive-responses!
  "Receives SOOD responses until timeout, returns map of unique-id to Core."
  [^DatagramSocket socket timeout-ms]
  (let [recv-buf (byte-array 1024)
        end-time (+ (System/currentTimeMillis) timeout-ms)
        cores    (atom {})]
    (loop []
      (when (< (System/currentTimeMillis) end-time)
        (try
          (let [remaining (- end-time (System/currentTimeMillis))]
            (when (pos? remaining)
              (.setSoTimeout socket (int (min remaining 500)))
              (let [packet (DatagramPacket. recv-buf (count recv-buf))]
                (.receive socket packet)
                (when-let [props (parse-response recv-buf (.getLength packet))]
                  (when (and (= (get props "service_id") roon-service-id)
                             (get props "http_port")
                             (get props "unique_id"))
                    (let [unique-id (get props "unique_id")
                          host      (or (get props "_replyaddr")
                                        (.getHostAddress (.getAddress packet)))
                          port      (parse-long (get props "http_port"))
                          core-name (get props "name")
                          version   (get props "display_version")]
                      (swap! cores assoc unique-id
                             (->Core unique-id host port core-name version))))))))
          (catch java.net.SocketTimeoutException _))
        (recur)))
    @cores))

(defn discover!
  "Discovers Roon Cores on the network.

  Queries all IPv4 network interfaces via multicast and broadcast.
  Deduplicates responses by unique-id.

  Options:
  | key         | default | description                    |
  |-------------|---------|--------------------------------|
  | :timeout-ms | 3000    | Discovery timeout              |

  Returns seq of Core records."
  ([] (discover! {}))
  ([opts]
   (let [timeout-ms (get opts :timeout-ms 3000)
         ;; Use a single socket for send and receive
         socket     (doto (DatagramSocket.)
                      (.setBroadcast true)
                      (.setSoTimeout 500))
         query      (build-query)]
     (try
       ;; Send query to multicast and broadcast on all interfaces
       (send-query! socket query)
       ;; Receive responses - ensure we return seq, not nil
       (or (vals (receive-responses! socket timeout-ms)) ())
       (finally
         (.close socket))))))

(defn discover-one!
  "Discovers first available Roon Core.

  Convenience for single-core setups. Returns Core or nil."
  ([] (discover-one! {}))
  ([opts] (first (discover! opts))))
