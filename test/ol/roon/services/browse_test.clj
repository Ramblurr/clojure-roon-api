(ns ol.roon.services.browse-test
  "Tests for browse service API."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.services.browse :as browse]))

(deftest browse-request-test
  (testing "browse with no options returns default hierarchy"
    (let [req (browse/browse)]
      (is (= "com.roonlabs.browse:1/browse" (:uri req)))
      (is (= {"hierarchy" "browse"} (:body req)))))

  (testing "browse with pop-all"
    (let [req (browse/browse {:pop-all true})]
      (is (= "com.roonlabs.browse:1/browse" (:uri req)))
      (is (= {"hierarchy" "browse" "pop_all" true} (:body req)))))

  (testing "browse with item-key navigation"
    (let [req (browse/browse {:item-key "some-item-key"})]
      (is (= {"hierarchy" "browse" "item_key" "some-item-key"} (:body req)))))

  (testing "browse with multi-session-key"
    (let [req (browse/browse {:multi-session-key "session-123"})]
      (is (= {"hierarchy" "browse" "multi_session_key" "session-123"} (:body req)))))

  (testing "browse with input (for user input items)"
    (let [req (browse/browse {:item-key "search-key" :input "search query"})]
      (is (= {"hierarchy" "browse"
              "item_key"  "search-key"
              "input"     "search query"}
             (:body req)))))

  (testing "browse with zone-or-output-id"
    (let [req (browse/browse {:zone-or-output-id "zone-123"})]
      (is (= {"hierarchy" "browse" "zone_or_output_id" "zone-123"} (:body req)))))

  (testing "browse with pop-levels"
    (let [req (browse/browse {:pop-levels 2})]
      (is (= {"hierarchy" "browse" "pop_levels" 2} (:body req)))))

  (testing "browse with refresh-list"
    (let [req (browse/browse {:refresh-list true})]
      (is (= {"hierarchy" "browse" "refresh_list" true} (:body req)))))

  (testing "browse with set-display-offset"
    (let [req (browse/browse {:set-display-offset 10})]
      (is (= {"hierarchy" "browse" "set_display_offset" 10} (:body req)))))

  (testing "browse with all options"
    (let [req (browse/browse {:multi-session-key  "sess"
                              :item-key           "item"
                              :input              "query"
                              :zone-or-output-id  "zone"
                              :pop-levels         1
                              :refresh-list       true
                              :set-display-offset 5})]
      (is (= {"hierarchy"          "browse"
              "multi_session_key"  "sess"
              "item_key"           "item"
              "input"              "query"
              "zone_or_output_id"  "zone"
              "pop_levels"         1
              "refresh_list"       true
              "set_display_offset" 5}
             (:body req))))))

(deftest load-request-test
  (testing "load with no options uses defaults"
    (let [req (browse/load)]
      (is (= "com.roonlabs.browse:1/load" (:uri req)))
      (is (= {"hierarchy" "browse" "offset" 0 "count" 100} (:body req)))))

  (testing "load with custom offset and count"
    (let [req (browse/load {:offset 20 :count 50})]
      (is (= {"hierarchy" "browse" "offset" 20 "count" 50} (:body req)))))

  (testing "load with multi-session-key"
    (let [req (browse/load {:multi-session-key "session-456"})]
      (is (= {"hierarchy"         "browse"
              "offset"            0
              "count"             100
              "multi_session_key" "session-456"}
             (:body req)))))

  (testing "load with specific level"
    (let [req (browse/load {:level 2})]
      (is (= {"hierarchy" "browse" "offset" 0 "count" 100 "level" 2}
             (:body req)))))

  (testing "load with set-display-offset"
    (let [req (browse/load {:set-display-offset 15})]
      (is (= {"hierarchy"          "browse"
              "offset"             0
              "count"              100
              "set_display_offset" 15}
             (:body req)))))

  (testing "load with all options"
    (let [req (browse/load {:multi-session-key  "sess"
                            :level              3
                            :offset             10
                            :count              25
                            :set-display-offset 5})]
      (is (= {"hierarchy"          "browse"
              "multi_session_key"  "sess"
              "level"              3
              "offset"             10
              "count"              25
              "set_display_offset" 5}
             (:body req))))))
