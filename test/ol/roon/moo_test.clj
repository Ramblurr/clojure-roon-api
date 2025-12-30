(ns ol.roon.moo-test
  "Tests for MOO protocol parsing and encoding."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.moo :as moo]
            [ol.roon.test-util :as util]))

;;; Parsing tests

(deftest parse-request-without-body
  (testing "parses ping request with no body"
    (let [data   (util/load-fixture-bytes "raw/ping_request.txt")
          result (moo/parse-message data)]
      (is (= :request (:verb result)))
      (is (= "com.roonlabs.ping:1/ping" (:name result)))
      (is (= 1 (:request-id result)))
      (is (= "quiet" (get-in result [:headers "Logging"])))
      (is (nil? (:body result))))))

(deftest parse-continue-with-json-body
  (testing "parses registered response with JSON body"
    (let [data   (util/load-fixture-bytes "raw/registered_response.txt")
          result (moo/parse-message data)]
      (is (= :continue (:verb result)))
      (is (= "Registered" (:name result)))
      (is (= 10 (:request-id result)))
      (is (= "application/json" (:content-type result)))
      (is (map? (:body result)))
      (is (= "a7cc7331-c854-4fe3-be9f-239833392d3a" (get-in result [:body "core_id"])))
      (is (string? (get-in result [:body "token"]))))))

(deftest parse-complete-with-json-body
  (testing "parses invalid request error response"
    (let [data   (util/load-fixture-bytes "raw/invalid_request_response.txt")
          result (moo/parse-message data)]
      (is (= :complete (:verb result)))
      (is (= "InvalidRequest" (:name result)))
      (is (= 15 (:request-id result)))
      (is (map? (:body result)))
      (is (string? (get-in result [:body "message"]))))))

(deftest parse-success-response
  (testing "parses playback action success response"
    (let [data   (util/load-fixture-bytes "raw/playback_action_response.txt")
          result (moo/parse-message data)]
      (is (= :complete (:verb result)))
      (is (= "Success" (:name result))))))

(deftest parse-zones-response
  (testing "parses get_zones response with large JSON body"
    (let [data   (util/load-fixture-bytes "raw/get_zones_response.txt")
          result (moo/parse-message data)]
      (is (= :complete (:verb result)))
      (is (= "Success" (:name result)))
      (is (map? (:body result)))
      (is (vector? (get-in result [:body "zones"]))))))

;;; Encoding tests

(deftest encode-request-without-body
  (testing "encodes request without body"
    (let [result (moo/encode-request 42 "com.roonlabs.ping:1/ping" nil)
          parsed (moo/parse-message result)]
      (is (bytes? result))
      (is (= :request (:verb parsed)))
      (is (= "com.roonlabs.ping:1/ping" (:name parsed)))
      (is (= 42 (:request-id parsed)))
      (is (nil? (:body parsed))))))

(deftest encode-request-with-json-body
  (testing "encodes request with JSON body"
    (let [body   {:zone_or_output_id "1234" :control "play"}
          result (moo/encode-request 99 "com.roonlabs.transport:2/control" body)
          parsed (moo/parse-message result)]
      (is (bytes? result))
      (is (= :request (:verb parsed)))
      (is (= "com.roonlabs.transport:2/control" (:name parsed)))
      (is (= 99 (:request-id parsed)))
      (is (= "application/json" (:content-type parsed)))
      (is (= "1234" (get-in parsed [:body "zone_or_output_id"])))
      (is (= "play" (get-in parsed [:body "control"]))))))

(deftest encode-response-success
  (testing "encodes COMPLETE Success response"
    (let [result (moo/encode-response :complete "Success" 55 nil)
          parsed (moo/parse-message result)]
      (is (= :complete (:verb parsed)))
      (is (= "Success" (:name parsed)))
      (is (= 55 (:request-id parsed))))))

(deftest encode-response-with-body
  (testing "encodes CONTINUE with JSON body"
    (let [body   {:status "ok" :value 123}
          result (moo/encode-response :continue "Changed" 77 body)
          parsed (moo/parse-message result)]
      (is (= :continue (:verb parsed)))
      (is (= "Changed" (:name parsed)))
      (is (= 77 (:request-id parsed)))
      (is (= "ok" (get-in parsed [:body "status"]))))))

;;; Edge case tests

(deftest parse-handles-empty-body-gracefully
  (testing "handles Content-Length: 0"
    (let [raw    (.getBytes "MOO/1 COMPLETE Success\r\nRequest-Id: 1\r\nContent-Length: 0\r\n\r\n" "UTF-8")
          result (moo/parse-message raw)]
      (is (= :complete (:verb result)))
      (is (nil? (:body result))))))

(deftest parse-handles-missing-content-type
  (testing "body without Content-Type is treated as raw bytes"
    (let [raw    (.getBytes "MOO/1 COMPLETE Success\r\nRequest-Id: 1\r\nContent-Length: 5\r\n\r\nhello" "UTF-8")
          result (moo/parse-message raw)]
      (is (= :complete (:verb result)))
      (is (bytes? (:body result))))))

(deftest request-id-can-be-large
  (testing "handles large request IDs"
    (let [raw    (.getBytes "MOO/1 REQUEST test/method\r\nRequest-Id: 9999999999\r\n\r\n" "UTF-8")
          result (moo/parse-message raw)]
      (is (= 9999999999 (:request-id result))))))
