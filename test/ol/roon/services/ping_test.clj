(ns ol.roon.services.ping-test
  "Tests for ping provided service."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.services.ping :as ping]))

(deftest ping-service-spec-test
  (testing "creates valid service spec"
    (let [spec (ping/make-service-spec)]
      (is (= "com.roonlabs.ping:1" (:name spec)))
      (is (contains? (:methods spec) "ping"))))

  (testing "ping method returns Success with nil body"
    (let [spec    (ping/make-service-spec)
          ping-fn (get-in spec [:methods "ping"])
          result  (ping-fn nil nil)]
      (is (= :complete (:verb result)))
      (is (= "Success" (:name result)))
      (is (nil? (:body result))))))
