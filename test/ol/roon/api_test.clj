(ns ol.roon.api-test
  "Tests for the public API design.

  Tests the data-driven request API:
  - request! takes {:uri ... :body ...} and returns a promise
  - subscribe! sends request but events flow to unified events channel
  - transport functions have data builders and promise-returning !-suffixed versions"
  (:require
   [clojure.core.async :as a :refer [<!! close! timeout alt!!]]
   [clojure.test :refer [deftest is testing]]
   [ol.roon.connection :as conn]
   [ol.roon.schema :as roon]
   [ol.roon.services.transport :as transport]))

;;; Request map structure

(deftest request-map-structure
  (testing "request! accepts a request map with :uri and optional :body"
    ;; We can't test the full flow without a server, but we can test
    ;; the request map is correctly structured by the data builders
    (let [req (transport/get-zones)]
      (is (string? (:uri req)) "request map has :uri")
      (is (contains? req :body) "request map has :body key"))))

(deftest change-volume-returns-request-map
  (testing "change-volume returns a request map"
    (let [req (transport/change-volume "output-1" :absolute 50)]
      (is (= "com.roonlabs.transport:2/change_volume" (:uri req)))
      (is (= {"output_id" "output-1"
              "how"       "absolute"
              "value"     50}
             (:body req))))))

(deftest control-returns-request-map
  (testing "control returns a request map"
    (let [req (transport/control "zone-1" :play)]
      (is (= "com.roonlabs.transport:2/control" (:uri req)))
      (is (= {"zone_or_output_id" "zone-1"
              "control"           "play"}
             (:body req))))))

(deftest mute-returns-request-map
  (testing "mute returns a request map"
    (let [req (transport/mute "output-1" :mute)]
      (is (= "com.roonlabs.transport:2/mute" (:uri req)))
      (is (= {"output_id" "output-1"
              "how"       "mute"}
             (:body req))))))

(deftest seek-returns-request-map
  (testing "seek returns a request map"
    (let [req (transport/seek "zone-1" :absolute 30)]
      (is (= "com.roonlabs.transport:2/seek" (:uri req)))
      (is (= {"zone_or_output_id" "zone-1"
              "how"               "absolute"
              "seconds"           30}
             (:body req))))))

(deftest get-zones-returns-request-map
  (testing "get-zones returns a request map"
    (let [req (transport/get-zones)]
      (is (= "com.roonlabs.transport:2/get_zones" (:uri req)))
      (is (nil? (:body req))))))

(deftest get-outputs-returns-request-map
  (testing "get-outputs returns a request map"
    (let [req (transport/get-outputs)]
      (is (= "com.roonlabs.transport:2/get_outputs" (:uri req)))
      (is (nil? (:body req))))))

;;; Promise-based request!

(deftest request-returns-promise
  (testing "request! returns a promise"
    ;; Create a mock connection that we can inject responses into
    (let [c              (conn/make-connection {:host "test"})
          req-map        {:uri "com.roonlabs.transport:2/get_zones" :body nil}
          ;; Manually simulate what would happen
          send-ch        (:send-ch c)
          result-promise (conn/request! c req-map)]
      ;; The return value should be a promise
      (is (instance? clojure.lang.IDeref result-promise)
          "request! returns something deref-able (promise)")
      ;; Clean up
      (close! send-ch))))

(deftest request-promise-delivers-on-complete
  (testing "request! promise delivers value when response arrives"
    (let [c       (conn/make-connection {:host "test"})
          req-map {:uri "test/endpoint" :body nil}
          ;; Get next request ID before calling request!
          next-id (.get ^java.util.concurrent.atomic.AtomicLong (:req-counter c))
          result  (conn/request! c req-map)]
      ;; Simulate server response arriving
      (conn/complete-pending! c next-id "Success" {"data" "test-value"} nil)
      ;; Promise should now be realized
      (is (realized? result))
      (is (= {"data" "test-value"} @result)))))

;;; Unified event channel for subscriptions

(deftest subscribe-sends-request-no-channel-returned
  (testing "subscribe! sends subscription request but returns nil"
    (let [c      (conn/make-connection {:host "test"})
          result (conn/subscribe! c "com.roonlabs.transport:2" "zones")]
      ;; subscribe! should not return a channel anymore
      (is (nil? result) "subscribe! returns nil, not a channel"))))

(deftest subscribe-events-flow-to-events-channel
  (testing "subscription events flow to the unified events channel"
    (let [c         (conn/make-connection {:host "test"})
          events-ch (:events-ch c)
          ;; Get the request ID that will be used
          next-id   (.get ^java.util.concurrent.atomic.AtomicLong (:req-counter c))]
      ;; Subscribe
      (conn/subscribe! c "com.roonlabs.transport:2" "zones")
      ;; Simulate subscription event arriving (e.g., zones changed)
      (conn/dispatch-subscription! c next-id "Changed" {"zones_changed" [{"zone_id" "z1"}]})
      ;; Event should arrive on the unified events channel
      (let [[result ch] (alt!!
                          events-ch ([v] [v :event])
                          (timeout 100) ([_] [nil :timeout]))]
        (is (= :event ch) "event arrives on events-ch")
        (is (= ::roon/zones-changed (::roon/event result)) "event type is zones-changed")
        (is (= {"zones_changed" [{"zone_id" "z1"}]} (::roon/data result)) "event has data")))))

(deftest multiple-subscriptions-share-events-channel
  (testing "multiple subscriptions all deliver to the same events channel"
    (let [c         (conn/make-connection {:host "test"})
          events-ch (:events-ch c)
          zones-id  (.get ^java.util.concurrent.atomic.AtomicLong (:req-counter c))]
      ;; Subscribe to zones
      (conn/subscribe! c "com.roonlabs.transport:2" "zones")
      (let [outputs-id (.get ^java.util.concurrent.atomic.AtomicLong (:req-counter c))]
        ;; Subscribe to outputs
        (conn/subscribe! c "com.roonlabs.transport:2" "outputs")
        ;; Simulate zone event
        (conn/dispatch-subscription! c zones-id "Changed" {"zones_changed" []})
        ;; Simulate output event
        (conn/dispatch-subscription! c outputs-id "Changed" {"outputs_changed" []})
        ;; Both should arrive on same channel
        (let [event1 (<!! events-ch)
              event2 (<!! events-ch)]
          (is (= ::roon/zones-changed (::roon/event event1)))
          (is (= ::roon/outputs-changed (::roon/event event2))))))))

;;; Transport service bang functions return promises

(deftest transport-get-zones-bang-returns-promise
  (testing "get-zones! returns a promise"
    (let [c      (conn/make-connection {:host "test"})
          result (transport/get-zones! c)]
      (is (instance? clojure.lang.IDeref result)
          "get-zones! returns a promise"))))

(deftest transport-control-bang-returns-promise
  (testing "control! returns a promise"
    (let [c      (conn/make-connection {:host "test"})
          result (transport/control! c "zone-1" :play)]
      (is (instance? clojure.lang.IDeref result)
          "control! returns a promise"))))

(deftest transport-change-volume-bang-returns-promise
  (testing "change-volume! returns a promise"
    (let [c      (conn/make-connection {:host "test"})
          result (transport/change-volume! c "output-1" :absolute 50)]
      (is (instance? clojure.lang.IDeref result)
          "change-volume! returns a promise"))))

;;; Subscribe functions don't return channels

(deftest transport-subscribe-zones-bang-returns-nil
  (testing "subscribe-zones! returns nil, not a channel"
    (let [c      (conn/make-connection {:host "test"})
          result (transport/subscribe-zones! c)]
      (is (nil? result)
          "subscribe-zones! returns nil"))))

(deftest transport-subscribe-outputs-bang-returns-nil
  (testing "subscribe-outputs! returns nil, not a channel"
    (let [c      (conn/make-connection {:host "test"})
          result (transport/subscribe-outputs! c)]
      (is (nil? result)
          "subscribe-outputs! returns nil"))))
