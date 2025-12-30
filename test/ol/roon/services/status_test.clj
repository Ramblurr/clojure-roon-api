(ns ol.roon.services.status-test
  "Tests for status provided service."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.services.status :as status]))

(deftest status-service-test
  (testing "creates valid service instance"
    (let [svc (status/make-service)]
      (is (= "com.roonlabs.status:1" (:name svc)))
      (is (contains? (get-in svc [:spec :methods]) "get_status"))
      (is (contains? (get-in svc [:spec :subscriptions]) "subscribe_status"))
      (is (fn? (:set-status! svc)))))

  (testing "get_status returns current state"
    (let [svc    (status/make-service)
          get-fn (get-in svc [:spec :methods "get_status"])
          result (get-fn nil nil)]
      (is (= :complete (:verb result)))
      (is (= "Success" (:name result)))
      (is (= "" (get-in result [:body "message"])))
      (is (false? (get-in result [:body "is_error"])))))

  (testing "set-status! updates internal state"
    (let [svc          (status/make-service)
          broadcasts   (atom [])
          broadcast-fn (fn [sub body] (swap! broadcasts conj {:sub sub :body body}))]
      ;; Call internal set-status!
      ((:set-status! svc) broadcast-fn "Processing" false)
      ;; Verify broadcast was called
      (is (= 1 (count @broadcasts)))
      (is (= "subscribe_status" (:sub (first @broadcasts))))
      (is (= "Processing" (get-in (first @broadcasts) [:body "message"])))
      ;; Verify get_status returns updated state
      (let [get-fn (get-in svc [:spec :methods "get_status"])
            result (get-fn nil nil)]
        (is (= "Processing" (get-in result [:body "message"]))))))

  (testing "set-status! with error state"
    (let [svc          (status/make-service)
          broadcast-fn (fn [_ _] nil)]
      ((:set-status! svc) broadcast-fn "Connection failed" true)
      (let [get-fn (get-in svc [:spec :methods "get_status"])
            result (get-fn nil nil)]
        (is (true? (get-in result [:body "is_error"]))))))

  (testing "subscribe_status returns Subscribed with current state"
    (let [svc          (status/make-service)
          broadcast-fn (fn [_ _] nil)
          _            ((:set-status! svc) broadcast-fn "Ready" false)
          start-fn     (get-in svc [:spec :subscriptions "subscribe_status" :start])
          result       (start-fn nil nil)]
      (is (= :continue (:verb result)))
      (is (= "Subscribed" (:name result)))
      (is (= "Ready" (get-in result [:body "message"])))))

  (testing "set-status! normalizes nil inputs to match protocol expectations"
    (let [svc          (status/make-service)
          broadcasts   (atom [])
          broadcast-fn (fn [sub body] (swap! broadcasts conj {:sub sub :body body}))]
      ;; Call with nil message and nil is-error
      ((:set-status! svc) broadcast-fn nil nil)
      ;; Broadcast should have normalized values
      (is (= 1 (count @broadcasts)))
      (is (= "" (get-in (first @broadcasts) [:body "message"])) "nil message should become empty string")
      (is (false? (get-in (first @broadcasts) [:body "is_error"])) "nil is_error should become false")
      ;; Internal state should also be normalized
      (let [get-fn (get-in svc [:spec :methods "get_status"])
            result (get-fn nil nil)]
        (is (= "" (get-in result [:body "message"])))
        (is (false? (get-in result [:body "is_error"])))))))
