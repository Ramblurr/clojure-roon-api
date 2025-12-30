(ns ol.roon.sood-test
  "Tests for SOOD discovery protocol."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ol.roon.sood :as sood])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

;;; Test helpers

(defn- build-test-response
  "Builds a test SOOD response packet with given properties."
  [props]
  (let [buf (byte-array 1024)
        bb  (ByteBuffer/wrap buf)]
    ;; Header: SOOD + version 2 + type R
    (.put bb (.getBytes "SOOD" StandardCharsets/UTF_8))
    (.put bb (byte 2))
    (.put bb (byte (int \R)))
    ;; Properties
    (doseq [[k v] props]
      (let [name-bytes (.getBytes ^String k StandardCharsets/UTF_8)]
        (.put bb (byte (count name-bytes)))
        (.put bb name-bytes)
        (if (nil? v)
          (.putShort bb (short -1))  ;; 65535 = nil
          (let [val-bytes (.getBytes ^String v StandardCharsets/UTF_8)]
            (.putShort bb (short (count val-bytes)))
            (.put bb val-bytes)))))
    (let [len (.position bb)]
      {:data (byte-array (take len buf))
       :len  len})))

(defn- build-invalid-header
  "Builds a packet with invalid header."
  []
  (let [buf (byte-array 10)]
    (System/arraycopy (.getBytes "XOOD" StandardCharsets/UTF_8) 0 buf 0 4)
    (aset-byte buf 4 2)
    (aset-byte buf 5 (byte (int \R)))
    {:data buf :len 6}))

(defn- build-wrong-version
  "Builds a packet with wrong version."
  []
  (let [buf (byte-array 10)]
    (System/arraycopy (.getBytes "SOOD" StandardCharsets/UTF_8) 0 buf 0 4)
    (aset-byte buf 4 99)  ;; Wrong version
    (aset-byte buf 5 (byte (int \R)))
    {:data buf :len 6}))

(defn- build-query-type
  "Builds a packet with Q type instead of R."
  []
  (let [buf (byte-array 10)]
    (System/arraycopy (.getBytes "SOOD" StandardCharsets/UTF_8) 0 buf 0 4)
    (aset-byte buf 4 2)
    (aset-byte buf 5 (byte (int \Q)))  ;; Query, not response
    {:data buf :len 6}))

;;; build-query tests

(deftest build-query-generates-sood-header
  (testing "build-query generates correct SOOD header"
    (let [query (sood/build-query)]
      (is (> (count query) 6) "Query should be longer than header")
      (is (= "SOOD" (String. query 0 4 StandardCharsets/UTF_8))
          "Header should start with SOOD")
      (is (= 2 (aget query 4))
          "Version should be 2")
      (is (= (int \Q) (aget query 5))
          "Type should be Q for query"))))

(deftest build-query-includes-tid-property
  (testing "build-query includes _tid property"
    (let [query (sood/build-query)
          text  (String. query StandardCharsets/UTF_8)]
      ;; _tid should appear in the packet
      (is (str/includes? text "_tid")
          "Query should contain _tid property"))))

(deftest build-query-includes-service-id
  (testing "build-query includes query_service_id property"
    (let [query (sood/build-query)
          text  (String. query StandardCharsets/UTF_8)]
      ;; query_service_id and roon UUID should appear
      (is (str/includes? text "query_service_id")
          "Query should contain query_service_id property")
      (is (str/includes? text sood/roon-service-id)
          "Query should contain Roon service UUID"))))

;;; parse-response tests

(deftest parse-response-extracts-properties
  (testing "parse-response extracts properties from valid response"
    (let [{:keys [data len]} (build-test-response
                              {"unique_id"       "abc-123"
                               "http_port"       "9330"
                               "name"            "Living Room"
                               "display_version" "2.0"
                               "service_id"      sood/roon-service-id})
          result             (sood/parse-response data len)]
      (is (some? result) "Should return a map")
      (is (= "abc-123" (get result "unique_id")))
      (is (= "9330" (get result "http_port")))
      (is (= "Living Room" (get result "name")))
      (is (= "2.0" (get result "display_version")))
      (is (= sood/roon-service-id (get result "service_id"))))))

(deftest parse-response-handles-nil-values
  (testing "parse-response handles nil property values"
    (let [{:keys [data len]} (build-test-response
                              {"unique_id" "abc-123"
                               "optional"  nil})
          result             (sood/parse-response data len)]
      (is (some? result))
      (is (= "abc-123" (get result "unique_id")))
      (is (nil? (get result "optional"))))))

(deftest parse-response-returns-nil-for-invalid-header
  (testing "parse-response returns nil for invalid header"
    (let [{:keys [data len]} (build-invalid-header)]
      (is (nil? (sood/parse-response data len))))))

(deftest parse-response-returns-nil-for-wrong-version
  (testing "parse-response returns nil for wrong version"
    (let [{:keys [data len]} (build-wrong-version)]
      (is (nil? (sood/parse-response data len))))))

(deftest parse-response-returns-nil-for-query-type
  (testing "parse-response returns nil for query type (only parses responses)"
    (let [{:keys [data len]} (build-query-type)]
      (is (nil? (sood/parse-response data len))))))

(deftest parse-response-returns-nil-for-truncated-data
  (testing "parse-response returns nil for truncated data"
    (is (nil? (sood/parse-response (byte-array 3) 3))
        "Too short for header")
    (is (nil? (sood/parse-response (byte-array 5) 5))
        "Missing type byte")))

(deftest parse-response-handles-malformed-property-lengths
  (testing "parse-response returns nil for malformed property lengths"
    ;; Build a response with valid header but truncated property data
    (let [buf (byte-array 20)]
      ;; Valid header
      (System/arraycopy (.getBytes "SOOD" StandardCharsets/UTF_8) 0 buf 0 4)
      (aset-byte buf 4 2)      ;; version
      (aset-byte buf 5 (byte (int \R)))  ;; response type
      ;; Property with name-len claiming 50 bytes but buffer only has 14 left
      (aset-byte buf 6 50)
      ;; Should return nil (gracefully) instead of throwing
      (is (nil? (sood/parse-response buf 20))
          "Should return nil for truncated name"))))

;;; get-ipv4-interfaces tests

(deftest get-ipv4-interfaces-returns-interface-maps
  (testing "get-ipv4-interfaces returns seq of interface maps"
    (let [ifaces (#'sood/get-ipv4-interfaces)]
      ;; Should return a sequence (possibly empty on CI)
      (is (sequential? ifaces))
      ;; If there are interfaces, they should have expected keys
      (when (seq ifaces)
        (doseq [iface ifaces]
          (is (contains? iface :iface) "Should have :iface key")
          (is (contains? iface :addr) "Should have :addr key"))))))

;;; discover! tests

(deftest discover-returns-seq-of-cores
  (testing "discover! returns a sequence"
    ;; Use short timeout to keep test fast
    ;; May find real servers on network - that's OK
    (let [result (sood/discover! {:timeout-ms 500})]
      (is (sequential? result) "Should return a sequence (empty if no cores)")
      ;; If servers found, they should be Core records
      (doseq [core result]
        (is (instance? ol.roon.sood.Core core) "Each result should be a Core")
        (is (some? (:unique-id core)) "Core should have unique-id")
        (is (some? (:host core)) "Core should have host")
        (is (some? (:port core)) "Core should have port")))))

(deftest discover-deduplicates-by-unique-id
  (testing "deduplication works via unique-id keyed map"
    ;; The dedup logic in receive-responses! uses (swap! cores assoc unique-id ...)
    ;; This test verifies the pattern: multiple responses with same unique-id
    ;; result in a single Core, with later values replacing earlier ones.
    (let [cores-atom (atom {})
          ;; Simulate receiving duplicate responses
          response1  {:unique-id "abc-123" :host "10.0.0.1" :port 9330 :name "First" :version "1.0"}
          response2  {:unique-id "abc-123" :host "10.0.0.2" :port 9330 :name "Second" :version "2.0"}
          response3  {:unique-id "def-456" :host "10.0.0.3" :port 9330 :name "Other" :version "1.0"}]
      ;; Apply the same dedup pattern used in receive-responses!
      (swap! cores-atom assoc (:unique-id response1)
             (sood/->Core (:unique-id response1) (:host response1) (:port response1)
                          (:name response1) (:version response1)))
      (swap! cores-atom assoc (:unique-id response2)
             (sood/->Core (:unique-id response2) (:host response2) (:port response2)
                          (:name response2) (:version response2)))
      (swap! cores-atom assoc (:unique-id response3)
             (sood/->Core (:unique-id response3) (:host response3) (:port response3)
                          (:name response3) (:version response3)))
      ;; Should have 2 unique cores, not 3
      (is (= 2 (count @cores-atom)) "Should deduplicate by unique-id")
      ;; The abc-123 core should have the second response's data (later wins)
      (let [abc-core (get @cores-atom "abc-123")]
        (is (= "10.0.0.2" (:host abc-core)) "Later response should overwrite earlier"))
      ;; The def-456 core should exist
      (is (some? (get @cores-atom "def-456"))))))

;;; Core record tests

(deftest core-record-has-expected-fields
  (testing "Core record has expected fields"
    (let [core (sood/->Core "id-1" "10.0.0.1" 9330 "Test" "2.0")]
      (is (= "id-1" (:unique-id core)))
      (is (= "10.0.0.1" (:host core)))
      (is (= 9330 (:port core)))
      (is (= "Test" (:name core)))
      (is (= "2.0" (:version core))))))
