(ns ol.roon.integration-test
  "Integration tests against a local RoonServer.

  Uses a pre-seeded server with an already-approved extension.
  The seed (seed.tgz) contains the server identity and auth data,
  so token re-authentication works without manual approval."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.roon.api :as api]
   [ol.roon.services.transport :as transport]
   [ol.roon.test-harness :as harness]))

(use-fixtures :once (harness/roon-fixture {:id "integration-test"}))

(deftest ^:integration can-connect-and-list-zones
  (testing "connection is established"
    (is (api/connected? @harness/conn-state)))
  (testing "can list zones"
    (let [result @(transport/get-zones! (:conn @harness/conn-state))
          zones  (get result "zones")]
      (is (vector? zones)))))

(deftest ^:integration can-list-outputs
  (testing "can list outputs"
    (let [result  @(transport/get-outputs! (:conn @harness/conn-state))
          outputs (get result "outputs")]
      (is (vector? outputs)))))
