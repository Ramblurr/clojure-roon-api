(ns ol.roon.services.pairing-test
  "Tests for pairing service."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ol.roon.services.pairing :as pairing]))

;;; Fixtures

(defn reset-pairing-fixture
  "Resets pairing state before each test."
  [f]
  (pairing/reset-pairing!)
  (f)
  (pairing/reset-pairing!))

(use-fixtures :each reset-pairing-fixture)

;;; get-pairing tests

(deftest get-pairing-returns-nil-initially
  (testing "get-pairing returns nil when not paired"
    (is (nil? (pairing/get-pairing)))))

;;; pair! tests

(deftest pair-sets-paired-core
  (testing "pair! sets the paired core"
    (pairing/pair! {:id "core-1" :name "Test Core"} nil)
    (is (= "core-1" (pairing/get-pairing)))))

(deftest pair-returns-new-core-id
  (testing "pair! returns the new paired core id"
    (let [result (pairing/pair! {:id "core-2" :name "Another Core"} nil)]
      (is (= "core-2" result)))))

(deftest pair-calls-on-core-lost-when-changing-cores
  (testing "pair! calls on-core-lost when switching to a different core"
    (pairing/pair! {:id "core-1" :name "First Core"} nil)
    (let [lost-cores (atom [])]
      (pairing/pair! {:id "core-2" :name "Second Core"}
                     (fn [old-id] (swap! lost-cores conj old-id)))
      (is (= ["core-1"] @lost-cores)))))

(deftest pair-does-not-call-on-core-lost-when-pairing-same-core
  (testing "pair! does not call on-core-lost when pairing same core"
    (pairing/pair! {:id "core-1" :name "First Core"} nil)
    (let [lost-cores (atom [])]
      (pairing/pair! {:id "core-1" :name "First Core"}
                     (fn [old-id] (swap! lost-cores conj old-id)))
      (is (empty? @lost-cores)))))

(deftest pair-does-not-call-on-core-lost-when-no-previous-core
  (testing "pair! does not call on-core-lost when no core was paired"
    (let [lost-cores (atom [])]
      (pairing/pair! {:id "core-1" :name "First Core"}
                     (fn [old-id] (swap! lost-cores conj old-id)))
      (is (empty? @lost-cores)))))

;;; make-service-spec tests

(deftest make-service-spec-returns-map-with-required-keys
  (testing "make-service-spec returns map with :name :methods :subscriptions"
    (let [spec (pairing/make-service-spec nil)]
      (is (map? spec))
      (is (= pairing/service-name (:name spec)))
      (is (contains? spec :methods))
      (is (contains? spec :subscriptions)))))

(deftest make-service-spec-has-get-pairing-method
  (testing "make-service-spec includes get_pairing method"
    (let [spec    (pairing/make-service-spec nil)
          methods (:methods spec)]
      (is (contains? methods "get_pairing"))
      (is (fn? (get methods "get_pairing"))))))

(deftest make-service-spec-has-pair-method
  (testing "make-service-spec includes pair method"
    (let [spec    (pairing/make-service-spec nil)
          methods (:methods spec)]
      (is (contains? methods "pair"))
      (is (fn? (get methods "pair"))))))

(deftest make-service-spec-has-subscribe-pairing
  (testing "make-service-spec includes subscribe_pairing subscription"
    (let [spec (pairing/make-service-spec nil)
          subs (:subscriptions spec)]
      (is (contains? subs "subscribe_pairing")))))

;;; Service method behavior tests

(deftest get-pairing-method-returns-paired-core-id
  (testing "get_pairing method returns paired_core_id in body"
    (pairing/pair! {:id "core-1" :name "Test"} nil)
    (let [spec     (pairing/make-service-spec nil)
          method   (get-in spec [:methods "get_pairing"])
          response (method nil nil)]
      (is (= :complete (:verb response)))
      (is (= "Success" (:name response)))
      (is (= {"paired_core_id" "core-1"} (:body response))))))

(deftest get-pairing-method-returns-nil-body-when-not-paired
  (testing "get_pairing method returns nil body when not paired"
    (let [spec     (pairing/make-service-spec nil)
          method   (get-in spec [:methods "get_pairing"])
          response (method nil nil)]
      (is (= :complete (:verb response)))
      (is (= "Success" (:name response)))
      (is (nil? (:body response))))))

(deftest subscribe-pairing-returns-subscribed-with-paired-core
  (testing "subscribe_pairing start returns Subscribed with paired_core_id"
    (pairing/pair! {:id "core-1" :name "Test"} nil)
    (let [spec     (pairing/make-service-spec nil)
          sub-spec (get-in spec [:subscriptions "subscribe_pairing"])
          start-fn (:start sub-spec)
          response (start-fn nil nil)]
      (is (= :continue (:verb response)))
      (is (= "Subscribed" (:name response)))
      (is (= {"paired_core_id" "core-1"} (:body response))))))

(deftest subscribe-pairing-returns-undefined-when-not-paired
  (testing "subscribe_pairing start returns 'undefined' when not paired"
    (let [spec     (pairing/make-service-spec nil)
          sub-spec (get-in spec [:subscriptions "subscribe_pairing"])
          start-fn (:start sub-spec)
          response (start-fn nil nil)]
      (is (= :continue (:verb response)))
      (is (= "Subscribed" (:name response)))
      (is (= {"paired_core_id" "undefined"} (:body response))))))
