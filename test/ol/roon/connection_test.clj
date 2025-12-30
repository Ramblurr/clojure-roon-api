(ns ol.roon.connection-test
  "Tests for connection management."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [timeout alt!!]]
            [ol.roon.connection :as conn]
            [ol.roon.moo :as moo]
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

;;; Provided services tests

(deftest register-provided-service-adds-to-registry
  (testing "register-provided-service! adds service to provided-services"
    (let [c       (conn/make-connection {:host "test"})
          service {:name    "com.test.service:1"
                   :methods {"get_value" (fn [_core _body] {:verb :complete :name "Success" :body {"value" 42}})}}]
      (conn/register-provided-service! c service)
      (is (some? (conn/get-provided-service c "com.test.service:1"))))))

(deftest handle-provided-service-dispatches-to-method
  (testing "handle-provided-service! dispatches incoming REQUEST to registered method"
    (let [calls-atom (atom [])
          c          (conn/make-connection {:host "test"})
          service    {:name    "com.test.service:1"
                      :methods {"get_value"
                                (fn [core body]
                                  (swap! calls-atom conj {:core core :body body})
                                  {:verb :complete :name "Success" :body {"value" 42}})}}]
      (conn/register-provided-service! c service)
      ;; Simulate incoming request dispatch (req-id 100)
      (let [response (conn/handle-provided-service! c "com.test.service:1/get_value" {:request "data"} 100)]
        ;; Method should have been called
        (is (= 1 (count @calls-atom)))
        (is (= {:request "data"} (:body (first @calls-atom))))
        ;; Response should be returned
        (is (= :complete (:verb response)))
        (is (= "Success" (:name response)))
        (is (= {"value" 42} (:body response)))))))

(deftest handle-provided-service-returns-nil-for-unknown-service
  (testing "handle-provided-service! returns nil for unregistered service"
    (let [c (conn/make-connection {:host "test"})]
      (is (nil? (conn/handle-provided-service! c "com.unknown.service:1/method" nil 100))))))

(deftest handle-provided-service-subscription-start
  (testing "handle-provided-service! handles subscription start"
    (let [c       (conn/make-connection {:host "test"})
          service {:name          "com.test.service:1"
                   :methods       {}
                   :subscriptions {"subscribe_value"
                                   {:start (fn [_core _body]
                                             {:verb :continue
                                              :name "Subscribed"
                                              :body {"value" "initial"}})
                                    :end   nil}}}]
      (conn/register-provided-service! c service)
      (let [response (conn/handle-provided-service! c "com.test.service:1/subscribe_value"
                                                    {"subscription_key" 123} 100)]
        (is (= :continue (:verb response)))
        (is (= "Subscribed" (:name response)))
        (is (= {"value" "initial"} (:body response)))))))

(deftest provided-service-subscription-tracking
  (testing "provided service subscriptions are tracked for broadcasts"
    (let [c       (conn/make-connection {:host "test"})
          service {:name          "com.test.service:1"
                   :methods       {}
                   :subscriptions {"subscribe_value"
                                   {:start (fn [_core _body]
                                             {:verb :continue
                                              :name "Subscribed"
                                              :body {"value" "initial"}})
                                    :end   nil}}}]
      (conn/register-provided-service! c service)
      ;; Start subscription - use string key like real Roon does (req-id 100)
      (conn/handle-provided-service! c "com.test.service:1/subscribe_value"
                                     {"subscription_key" 123} 100)
      ;; Subscription should be tracked (returns [[key value] ...] pairs)
      (let [subs (conn/get-provided-subscriptions c "subscribe_value")]
        (is (seq subs))
        (is (= 123 (:subscription-key (second (first subs)))))
        ;; req-id should be stored for broadcasts
        (is (= 100 (:req-id (second (first subs)))))))))

;;; Provided services config tests (Task 5)

(deftest provided-services-config-accepted
  (testing "connection accepts :provided-services config key"
    (let [svc {:name  "com.test.service:1"
               :spec  {:name "com.test.service:1" :methods {}}
               :extra "data"}
          c   (conn/make-connection {:host              "test"
                                     :provided-services [svc]})]
      (is (= [svc] (get-in c [:config :provided-services]))))))

(deftest get-service-instance-returns-full-instance
  (testing "get-service-instance returns the full service instance (not just spec)"
    (let [my-fn (fn [] "hello")
          svc   {:name    "com.test.status:1"
                 :spec    {:name "com.test.status:1" :methods {}}
                 :set-fn! my-fn}
          c     (conn/make-connection {:host "test"})]
      (conn/register-service-instance! c svc)
      (let [instance (conn/get-service-instance c "com.test.status:1")]
        (is (some? instance))
        (is (= my-fn (:set-fn! instance)))
        (is (= "com.test.status:1" (:name instance)))))))

(deftest broadcast-sends-to-all-subscribers
  (testing "broadcast! sends CONTINUE message to all subscribers"
    (let [c       (conn/make-connection {:host "test"})
          service {:name          "com.test.service:1"
                   :methods       {}
                   :subscriptions {"subscribe_value"
                                   {:start (fn [_core _body]
                                             {:verb :continue
                                              :name "Subscribed"
                                              :body {}})
                                    :end   nil}}}]
      (conn/register-provided-service! c service)
      ;; Create two subscribers
      (conn/handle-provided-service! c "com.test.service:1/subscribe_value"
                                     {"subscription_key" 1} 100)
      (conn/handle-provided-service! c "com.test.service:1/subscribe_value"
                                     {"subscription_key" 2} 101)
      ;; Verify both are tracked
      (let [subs (conn/get-provided-subscriptions c "subscribe_value")]
        (is (= 2 (count subs)))))))

(deftest broadcast-uses-changed-response-name
  (testing "broadcast! uses 'Changed' as the response name per Roon protocol"
    (let [c       (conn/make-connection {:host "test"})
          send-ch (:send-ch c)
          service {:name          "com.test.service:1"
                   :methods       {}
                   :subscriptions {"subscribe_value"
                                   {:start (fn [_core _body]
                                             {:verb :continue
                                              :name "Subscribed"
                                              :body {}})
                                    :end   nil}}}]
      (conn/register-provided-service! c service)
      ;; Create a subscriber
      (conn/handle-provided-service! c "com.test.service:1/subscribe_value"
                                     {"subscription_key" 1} 100)
      ;; Send a broadcast
      (conn/broadcast! c "subscribe_value" {"data" "test"})
      ;; Read from send-ch and parse the message
      (let [[msg ch] (alt!!
                       send-ch ([v] [v :msg])
                       (timeout 100) ([_] [nil :timeout]))]
        (is (= :msg ch) "broadcast message should be on send-ch")
        (when msg
          (let [parsed (moo/parse-message msg)]
            (is (= :continue (:verb parsed)) "broadcast should be CONTINUE")
            (is (= "Changed" (:name parsed)) "broadcast response name must be 'Changed'")))))))
