(ns ol.roon.persistence-test
  "Tests for persistence helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.persistence :as persist]
            [ol.roon.schema :as roon]))

;;; extract-state tests

(deftest extract-state-returns-tokens-and-paired-core-id
  (testing "extract-state returns map with ::roon/tokens and ::roon/paired-core-id"
    (let [conn   {:state (atom {:token     "token-abc"
                                :core-info {"core_id" "core-123"}})}
          result (persist/extract-state conn)]
      (is (map? result))
      (is (contains? result ::roon/tokens))
      (is (contains? result ::roon/paired-core-id))
      (is (= {"core-123" "token-abc"} (::roon/tokens result)))
      (is (= "core-123" (::roon/paired-core-id result))))))

(deftest extract-state-handles-nil-token
  (testing "extract-state handles nil token gracefully"
    (let [conn   {:state (atom {:token     nil
                                :core-info {"core_id" "core-123"}})}
          result (persist/extract-state conn)]
      (is (= {} (::roon/tokens result)))
      (is (nil? (::roon/paired-core-id result))))))

(deftest extract-state-handles-nil-core-info
  (testing "extract-state handles nil core-info gracefully"
    (let [conn   {:state (atom {:token     nil
                                :core-info nil})}
          result (persist/extract-state conn)]
      (is (= {} (::roon/tokens result)))
      (is (nil? (::roon/paired-core-id result))))))

;;; apply-state tests

(deftest apply-state-merges-token-for-paired-core
  (testing "apply-state merges token into config for paired core"
    (let [saved  {::roon/tokens         {"core-123" "token-abc" "core-456" "token-def"}
                  ::roon/paired-core-id "core-123"}
          config {:host "10.0.0.1" :port 9330}
          result (persist/apply-state config saved "core-123")]
      (is (= "token-abc" (:token result)))
      (is (= "10.0.0.1" (:host result)))
      (is (= 9330 (:port result))))))

(deftest apply-state-uses-different-core-token
  (testing "apply-state uses token for specified core"
    (let [saved  {::roon/tokens         {"core-123" "token-abc" "core-456" "token-def"}
                  ::roon/paired-core-id "core-123"}
          config {:host "10.0.0.2" :port 9330}
          result (persist/apply-state config saved "core-456")]
      (is (= "token-def" (:token result))))))

(deftest apply-state-returns-config-unchanged-when-no-token
  (testing "apply-state returns config unchanged when no matching token"
    (let [saved  {::roon/tokens         {"core-123" "token-abc"}
                  ::roon/paired-core-id "core-123"}
          config {:host "10.0.0.1" :port 9330}
          result (persist/apply-state config saved "unknown-core")]
      (is (nil? (:token result)))
      (is (= "10.0.0.1" (:host result))))))

(deftest apply-state-handles-empty-saved-state
  (testing "apply-state handles empty saved state"
    (let [saved  {::roon/tokens {} ::roon/paired-core-id nil}
          config {:host "10.0.0.1"}
          result (persist/apply-state config saved "core-123")]
      (is (nil? (:token result)))
      (is (= "10.0.0.1" (:host result))))))

;;; Serialization tests

(deftest state-to-edn-produces-string
  (testing "state->edn produces EDN string"
    (let [state {::roon/tokens {"core-123" "token-abc"} ::roon/paired-core-id "core-123"}
          edn   (persist/state->edn state)]
      (is (string? edn))
      (is (> (count edn) 0)))))

(deftest edn-to-state-parses-string
  (testing "edn->state parses EDN string back to map"
    (let [edn    "#:ol.roon.schema{:tokens {\"core-123\" \"token-abc\"} :paired-core-id \"core-123\"}"
          result (persist/edn->state edn)]
      (is (map? result))
      (is (= {"core-123" "token-abc"} (::roon/tokens result)))
      (is (= "core-123" (::roon/paired-core-id result))))))

(deftest round-trip-serialization-preserves-data
  (testing "round-trip serialization preserves data"
    (let [state    {::roon/tokens         {"core-123" "token-abc"
                                           "core-456" "token-def"}
                    ::roon/paired-core-id "core-456"}
          edn      (persist/state->edn state)
          restored (persist/edn->state edn)]
      (is (= state restored)))))
