(ns ol.roon.websocket-test
  "Tests for WebSocket wrapper.

  Note: Most WebSocket tests require a real server and are marked ^:integration.
  Unit tests here focus on fragment handling and buffer utilities."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.websocket :as ws])
  (:import [java.nio ByteBuffer]))

;;; Fragment accumulator tests

(deftest fragment-accumulator-creation
  (testing "creates empty accumulator"
    (let [acc (ws/make-fragment-accumulator)]
      (is (some? acc))
      (is (ws/accumulator-empty? acc)))))

(deftest fragment-accumulator-append
  (testing "appends data to accumulator"
    (let [acc   (ws/make-fragment-accumulator)
          data1 (ByteBuffer/wrap (.getBytes "Hello" "UTF-8"))
          data2 (ByteBuffer/wrap (.getBytes " World" "UTF-8"))]
      (ws/accumulator-append! acc data1)
      (is (not (ws/accumulator-empty? acc)))
      (ws/accumulator-append! acc data2)
      (let [result (ws/accumulator-complete! acc)]
        (is (bytes? result))
        (is (= "Hello World" (String. ^bytes result "UTF-8")))))))

(deftest fragment-accumulator-reset
  (testing "completing resets accumulator"
    (let [acc  (ws/make-fragment-accumulator)
          data (ByteBuffer/wrap (.getBytes "test" "UTF-8"))]
      (ws/accumulator-append! acc data)
      (ws/accumulator-complete! acc)
      (is (ws/accumulator-empty? acc)))))

;;; ByteBuffer utilities

(deftest byte-buffer-to-bytes
  (testing "converts ByteBuffer to byte array"
    (let [data "Hello"
          buf  (ByteBuffer/wrap (.getBytes data "UTF-8"))
          arr  (ws/byte-buffer->bytes buf)]
      (is (bytes? arr))
      (is (= data (String. ^bytes arr "UTF-8"))))))

(deftest byte-buffer-to-bytes-preserves-position
  (testing "handles ByteBuffer with non-zero position"
    (let [buf (ByteBuffer/allocate 10)]
      (.put buf (.getBytes "0123456789" "UTF-8"))
      (.position buf 5)
      (let [arr (ws/byte-buffer->bytes buf)]
        (is (= 5 (alength arr)))
        (is (= "56789" (String. ^bytes arr "UTF-8")))))))

;;; Integration tests (require real server)

(deftest connect-to-invalid-host-throws
  (testing "connect! throws on invalid host"
    (is (thrown? Exception
                 (ws/connect! "ws://invalid.local.host:9999" {})))))
