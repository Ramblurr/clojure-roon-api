(ns ol.roon.remote-script-test
  (:require
   [clojure.test :refer [deftest is testing]]))

(load-file "scripts/roon-remote.bb")

(def guid->dotnet-bytes (resolve 'ol.roon.remote-script/guid->dotnet-bytes))
(def flexint-encode (resolve 'ol.roon.remote-script/flexint-encode))
(def flexint-decode (resolve 'ol.roon.remote-script/flexint-decode))
(def parse-sood-response (resolve 'ol.roon.remote-script/parse-sood-response))
(def sood-query-service-id (resolve 'ol.roon.remote-script/sood-query-service-id))
(def select-core (resolve 'ol.roon.remote-script/select-core))

(defn bytes->u8s [^bytes bs]
  (mapv #(bit-and 0xFF %) bs))

(defn sood-response-bytes [props]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (.write buf (.getBytes "SOOD" "UTF-8"))
    (.write buf (byte 2))
    (.write buf (byte (int \R)))
    (doseq [[k v] props]
      (let [kbytes (.getBytes ^String k "UTF-8")
            vbytes (.getBytes ^String v "UTF-8")]
        (.write buf (byte (count kbytes)))
        (.write buf kbytes)
        (.write buf (byte (bit-and 0xFF (bit-shift-right (count vbytes) 8))))
        (.write buf (byte (bit-and 0xFF (count vbytes))))
        (.write buf vbytes)))
    (.toByteArray buf)))

(deftest guid->dotnet-bytes-test
  (testing "guid byte order matches .NET Guid.ToByteArray"
    (let [guid     "00112233-4455-6677-8899-aabbccddeeff"
          expected [0x33 0x22 0x11 0x00 0x55 0x44 0x77 0x66 0x88 0x99 0xAA 0xBB 0xCC 0xDD 0xEE 0xFF]]
      (is (some? guid->dotnet-bytes))
      (when guid->dotnet-bytes
        (is (= expected (bytes->u8s ((deref guid->dotnet-bytes) guid))))))))

(deftest flexint-roundtrip-test
  (testing "flexint roundtrip for key boundary values"
    (is (some? flexint-encode))
    (is (some? flexint-decode))
    (when (and flexint-encode flexint-decode)
      (doseq [n [0 1 2 10 127 128 129 16383 16384 2097151 2097152 268435455 -1]]
        (let [encoded       ((deref flexint-encode) n)
              [decoded pos] ((deref flexint-decode) encoded 0)]
          (is (= n decoded))
          (is (= (alength ^bytes encoded) pos)))))))

(deftest parse-sood-response-test
  (testing "parses basic discovery properties"
    (let [props {"name"        "Test Core"
                 "service_id"  (when sood-query-service-id (deref sood-query-service-id))
                 "unique_id"   "11111111-2222-3333-4444-555555555555"
                 "tcp_port_v2" "9200"
                 "tcp_port"    "9100"}
          bytes (sood-response-bytes props)]
      (is (some? parse-sood-response))
      (when parse-sood-response
        (let [parsed ((deref parse-sood-response) bytes (alength ^bytes bytes))]
          (is (= "Test Core" (get parsed "name")))
          (is (= "9200" (get parsed "tcp_port_v2")))
          (is (= "9100" (get parsed "tcp_port")))
          (is (= "11111111-2222-3333-4444-555555555555" (get parsed "unique_id"))))))))

(deftest select-core-test
  (testing "selects a single core or errors on ambiguous results"
    (let [core-a {:ip "10.0.0.1" :unique-id "a"}
          core-b {:ip "10.0.0.2" :unique-id "b"}]
      (is (some? select-core))
      (when select-core
        (is (= core-a ((deref select-core) [core-a] {})))
        (is (= core-b ((deref select-core) [core-a core-b] {:host "10.0.0.2"})))
        (is (= core-a ((deref select-core) [core-a] {:host "10.0.0.99"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No cores discovered"
                              ((deref select-core) [] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple cores discovered"
                              ((deref select-core) [core-a core-b] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No cores match host"
                              ((deref select-core) [core-a core-b] {:host "10.0.0.3"})))))))
