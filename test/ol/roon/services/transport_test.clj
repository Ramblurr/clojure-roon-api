(ns ol.roon.services.transport-test
  "Unit tests for transport service request map builders."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.roon.services.transport :as transport]))

(deftest mute-all-test
  (testing "mute-all returns correct request map"
    (is (= {:uri  "com.roonlabs.transport:2/mute_all"
            :body {"how" "mute"}}
           (transport/mute-all :mute)))
    (is (= {:uri  "com.roonlabs.transport:2/mute_all"
            :body {"how" "unmute"}}
           (transport/mute-all :unmute)))))

(deftest toggle-standby-test
  (testing "toggle-standby returns correct request map with required control-key"
    (is (= {:uri  "com.roonlabs.transport:2/toggle_standby"
            :body {"output_id"   "output-123"
                   "control_key" "source-1"}}
           (transport/toggle-standby "output-123" "source-1")))))

(deftest play-from-here-test
  (testing "play-from-here returns correct request map"
    (is (= {:uri  "com.roonlabs.transport:2/play_from_here"
            :body {"zone_or_output_id" "zone-456"
                   "queue_item_id"     1057136}}
           (transport/play-from-here "zone-456" 1057136)))
    (is (= {:uri  "com.roonlabs.transport:2/play_from_here"
            :body {"zone_or_output_id" "output-789"
                   "queue_item_id"     42}}
           (transport/play-from-here "output-789" 42)))))
