(ns ol.roon.connection-test
  "Tests for connection management."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [timeout alt!!]]
            [ol.roon.connection :as conn]
            [ol.roon.schema :as roon]))

;;; Request ID generation

(deftest next-request-id-increments
  (testing "request IDs increment monotonically"
    (let [c (conn/make-connection {:host "test"})]
      (is (= 10 (conn/next-request-id! c)))
      (is (= 11 (conn/next-request-id! c)))
      (is (= 12 (conn/next-request-id! c))))))

(deftest next-subscription-key-increments
  (testing "subscription keys increment monotonically"
    (let [c (conn/make-connection {:host "test"})]
      (is (= 0 (conn/next-subscription-key! c)))
      (is (= 1 (conn/next-subscription-key! c)))
      (is (= 2 (conn/next-subscription-key! c))))))

;;; Backoff calculation

(deftest calculate-backoff-exponential
  (testing "backoff increases exponentially"
    (let [cfg {:backoff-initial-ms 1000 :backoff-max-ms 60000}]
      (is (= 1000 (conn/calculate-backoff cfg 1)))
      (is (= 2000 (conn/calculate-backoff cfg 2)))
      (is (= 4000 (conn/calculate-backoff cfg 3)))
      (is (= 8000 (conn/calculate-backoff cfg 4))))))

(deftest calculate-backoff-caps-at-max
  (testing "backoff caps at max value"
    (let [cfg {:backoff-initial-ms 1000 :backoff-max-ms 5000}]
      (is (= 1000 (conn/calculate-backoff cfg 1)))
      (is (= 2000 (conn/calculate-backoff cfg 2)))
      (is (= 4000 (conn/calculate-backoff cfg 3)))
      (is (= 5000 (conn/calculate-backoff cfg 4)))  ;; capped
      (is (= 5000 (conn/calculate-backoff cfg 5)))  ;; still capped
      (is (= 5000 (conn/calculate-backoff cfg 10)))))) ;; still capped

;;; Pending request management

(deftest add-pending-request
  (testing "adds pending request with promise"
    (let [c      (conn/make-connection {:host "test"})
          p      (promise)
          req-id 42]
      (conn/add-pending! c req-id p)
      (is (some? (conn/get-pending c req-id))))))

(deftest remove-pending-request
  (testing "removes pending request after completion"
    (let [c      (conn/make-connection {:host "test"})
          p      (promise)
          req-id 42]
      (conn/add-pending! c req-id p)
      (conn/remove-pending! c req-id)
      (is (nil? (conn/get-pending c req-id))))))

;;; Subscription management

(deftest add-subscription
  (testing "adds subscription with metadata"
    (let [c       (conn/make-connection {:host "test"})
          sub-key 99
          req-id  42]
      (conn/add-subscription! c sub-key {:subscription "zones" :req-id req-id})
      (is (some? (conn/get-subscription c sub-key))))))

(deftest remove-subscription
  (testing "removes subscription"
    (let [c       (conn/make-connection {:host "test"})
          sub-key 99]
      (conn/add-subscription! c sub-key {:subscription "zones" :req-id 42})
      (conn/remove-subscription! c sub-key)
      (is (nil? (conn/get-subscription c sub-key))))))

;;; State transitions

(deftest initial-state-is-disconnected
  (testing "new connection starts disconnected"
    (let [c (conn/make-connection {:host "test"})]
      (is (= :disconnected (conn/status c))))))

(deftest set-status-updates-state
  (testing "status updates correctly"
    (let [c (conn/make-connection {:host "test"})]
      (conn/set-status! c :connecting)
      (is (= :connecting (conn/status c)))
      (conn/set-status! c :connected)
      (is (= :connected (conn/status c))))))

;;; Message routing

(deftest complete-pending-delivers-to-promise
  (testing "COMPLETE message delivers response body to pending promise"
    (let [c      (conn/make-connection {:host "test"})
          p      (promise)
          req-id 42]
      (conn/add-pending! c req-id p)
      (conn/complete-pending! c req-id "Success" {:data "test"})
      ;; Promise should be delivered with body
      (is (realized? p))
      (is (= {:data "test"} @p)))))

(deftest complete-pending-removes-from-pending
  (testing "COMPLETE removes request from pending"
    (let [c      (conn/make-connection {:host "test"})
          p      (promise)
          req-id 42]
      (conn/add-pending! c req-id p)
      (conn/complete-pending! c req-id "Success" nil)
      (is (nil? (conn/get-pending c req-id))))))

(deftest dispatch-subscription-delivers-to-events-channel
  (testing "CONTINUE delivers event to unified events channel"
    (let [c         (conn/make-connection {:host "test"})
          events-ch (:events-ch c)
          sub-key   99
          req-id    42]
      (conn/add-subscription! c sub-key {:subscription "zones" :req-id req-id})
      (conn/dispatch-subscription! c req-id "Changed" {"zones_changed" []})
      ;; Event should be on the unified events channel
      (let [[result ch] (alt!!
                          events-ch ([v] [v :event])
                          (timeout 100) ([_] [nil :timeout]))]
        (is (= :event ch) "event arrives on events-ch")
        (is (= ::roon/zones-changed (::roon/event result)))
        (is (= {"zones_changed" []} (::roon/data result)))))))

;;; Connection config defaults

(deftest config-has-defaults
  (testing "connection config has sensible defaults"
    (let [c (conn/make-connection {:host "10.0.0.1"})]
      (is (= 9330 (get-in c [:config :port])))
      (is (= 30000 (get-in c [:config :timeout-ms])))
      (is (true? (get-in c [:config :auto-reconnect])))
      (is (= 1000 (get-in c [:config :backoff-initial-ms])))
      (is (= 60000 (get-in c [:config :backoff-max-ms]))))))

(deftest config-allows-overrides
  (testing "config values can be overridden"
    (let [c (conn/make-connection {:host           "10.0.0.1"
                                   :port           1234
                                   :timeout-ms     5000
                                   :auto-reconnect false})]
      (is (= 1234 (get-in c [:config :port])))
      (is (= 5000 (get-in c [:config :timeout-ms])))
      (is (false? (get-in c [:config :auto-reconnect]))))))

;;; Integration tests (require real server)

(deftest ^:integration connect-to-roon-core
  (testing "can connect to a real Roon Core"
    ;; This test requires ROON_TEST_HOST env var
    (if-let [host (System/getenv "ROON_TEST_HOST")]
      (let [c (conn/make-connection {:host            host
                                     :extension-id    "test.connection"
                                     :display-name    "Connection Test"
                                     :display-version "1.0.0"})]
        (conn/start! c)
        (is (= :connected (conn/status c)))
        (conn/disconnect! c)
        (is (= :disconnected (conn/status c))))
      ;; Skip with passing assertion when env var not set
      (is true "Skipped - ROON_TEST_HOST not set"))))

;;; Auto-reconnect behavior

(deftest fail-pending-delivers-disconnect-error
  (testing "pending requests fail with ::disconnected error on disconnect"
    (let [c      (conn/make-connection {:host "test"})
          p      (promise)
          req-id 42]
      (conn/add-pending! c req-id p)
      (conn/fail-pending! c)
      ;; Promise should be delivered with exception
      (is (realized? p))
      (let [result @p]
        (is (instance? clojure.lang.ExceptionInfo result))
        (is (= ::roon/disconnected (::roon/event (ex-data result))))))))

(deftest fail-pending-clears-all-pending
  (testing "fail-pending! clears all pending requests"
    (let [c  (conn/make-connection {:host "test"})
          p1 (promise)
          p2 (promise)
          p3 (promise)]
      (conn/add-pending! c 1 p1)
      (conn/add-pending! c 2 p2)
      (conn/add-pending! c 3 p3)
      (conn/fail-pending! c)
      ;; All promises should be realized
      (is (realized? p1))
      (is (realized? p2))
      (is (realized? p3))
      ;; Pending map should be empty
      (is (nil? (conn/get-pending c 1)))
      (is (nil? (conn/get-pending c 2)))
      (is (nil? (conn/get-pending c 3))))))

(deftest reconnecting-atom-exists
  (testing "connection has reconnecting-atom for guard"
    (let [c (conn/make-connection {:host "test"})]
      (is (some? (:reconnecting-atom c)))
      (is (false? @(:reconnecting-atom c))))))

(deftest reconnecting-event-schema-exists
  (testing "::reconnecting event type is in EventDataRegistry"
    (is (contains? roon/EventDataRegistry ::roon/reconnecting))))

(deftest reconnected-event-schema-exists
  (testing "::reconnected event type is in EventDataRegistry"
    (is (contains? roon/EventDataRegistry ::roon/reconnected))))
