(ns ol.roon.schema-test
  "Tests for Malli schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [charred.api :as charred]
            [ol.roon.schema :as schema]
            [ol.roon.test-util :as util]))

;;; Helper

(defn- valid? [schema data]
  (m/validate schema data))

(defn- load-json-fixture [path]
  (charred/read-json (util/load-fixture-string path)))

;;; Zone Schema Tests

(deftest zone-schema-validates-fixture
  (testing "Zone schema validates real fixture data"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")]
      (is (seq zones) "Fixture should have zones")
      (doseq [zone zones]
        (is (valid? schema/Zone zone)
            (str "Zone should validate: " (get zone "zone_id")))))))

(deftest zone-schema-requires-zone-id
  (testing "Zone schema rejects missing zone_id"
    (is (not (valid? schema/Zone {:display_name "Test"})))))

(deftest zone-schema-requires-display-name
  (testing "Zone schema rejects missing display_name"
    (is (not (valid? schema/Zone {:zone_id "abc123"})))))

;;; Output Schema Tests

(deftest output-schema-validates-fixture
  (testing "Output schema validates real fixture data"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")]
      (is (seq outputs) "Fixture should have outputs")
      (doseq [output outputs]
        (is (valid? schema/Output output)
            (str "Output should validate: " (get output "output_id")))))))

(deftest output-schema-allows-missing-volume
  (testing "Output schema allows missing volume (not all outputs have volume)"
    (let [output {"output_id"                 "abc123"
                  "zone_id"                   "def456"
                  "display_name"              "Test Output"
                  "can_group_with_output_ids" []}]
      (is (valid? schema/Output output)))))

(deftest output-schema-requires-output-id
  (testing "Output schema rejects missing output_id"
    (is (not (valid? schema/Output {"zone_id" "abc" "display_name" "test"})))))

;;; Volume Schema Tests

(deftest volume-schema-validates-fixture
  (testing "Volume schema validates volume from fixture"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          outputs-with-vol (filter #(get % "volume") outputs)]
      (is (seq outputs-with-vol) "Fixture should have outputs with volume")
      (doseq [output outputs-with-vol]
        (is (valid? schema/Volume (get output "volume"))
            (str "Volume should validate for: " (get output "output_id")))))))

(deftest volume-schema-requires-type
  (testing "Volume schema requires type field"
    (is (not (valid? schema/Volume {"min" 0 "max" 100 "value" 50})))))

;;; NowPlaying Schema Tests

(deftest now-playing-schema-validates-fixture
  (testing "NowPlaying schema validates now_playing from fixture"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          zones-with-np  (filter #(get % "now_playing") zones)]
      (is (seq zones-with-np) "Fixture should have zones with now_playing")
      (doseq [zone zones-with-np]
        (is (valid? schema/NowPlaying (get zone "now_playing"))
            (str "NowPlaying should validate for: " (get zone "zone_id")))))))

;;; Settings Schema Tests

(deftest settings-schema-validates-fixture
  (testing "Settings schema validates settings from fixture"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          zones-with-set (filter #(get % "settings") zones)]
      (is (seq zones-with-set) "Fixture should have zones with settings")
      (doseq [zone zones-with-set]
        (is (valid? schema/Settings (get zone "settings"))
            (str "Settings should validate for: " (get zone "zone_id")))))))

;;; SourceControl Schema Tests

(deftest source-control-schema-validates-fixture
  (testing "SourceControl schema validates source_controls from fixture"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          outputs-with-sc  (filter #(seq (get % "source_controls")) outputs)]
      (is (seq outputs-with-sc) "Fixture should have outputs with source_controls")
      (doseq [output outputs-with-sc
              sc     (get output "source_controls")]
        (is (valid? schema/SourceControl sc)
            (str "SourceControl should validate for: " (get sc "control_key")))))))

;;; Enum Schema Tests

(deftest state-enum-validates
  (testing "State enum validates valid states"
    (is (valid? schema/State "playing"))
    (is (valid? schema/State "paused"))
    (is (valid? schema/State "loading"))
    (is (valid? schema/State "stopped"))))

(deftest state-enum-allows-unknown
  (testing "State enum allows unknown values (open enum)"
    ;; Roon may add new states, we shouldn't crash
    (is (valid? schema/State "some_future_state"))))

(deftest loop-enum-validates
  (testing "Loop enum validates valid values"
    (is (valid? schema/Loop "disabled"))
    (is (valid? schema/Loop "loop"))
    (is (valid? schema/Loop "loop_one"))))

;;; Zone Value Assertions (from Java ZoneMapperTest)

(deftest zone-foo-values
  (testing "Zone 'Foo' has expected values"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          foo            (first (filter #(= "Foo" (get % "display_name")) zones))]
      (is (some? foo) "Foo zone should exist")
      (is (= "160163df90e742c33b0b2404ae5d08cb8908" (get foo "zone_id")))
      (is (= "Foo" (get foo "display_name")))
      (is (= "stopped" (get foo "state")))
      (is (= false (get foo "is_next_allowed")))
      (is (= false (get foo "is_previous_allowed")))
      (is (= false (get foo "is_pause_allowed")))
      (is (= true (get foo "is_play_allowed")))
      (is (= false (get foo "is_seek_allowed")))
      (is (= 0 (get foo "queue_items_remaining")))
      (is (= 0 (get foo "queue_time_remaining")))
      ;; Settings
      (let [settings (get foo "settings")]
        (is (= "disabled" (get settings "loop")))
        (is (= false (get settings "shuffle")))
        (is (= true (get settings "auto_radio"))))
      ;; Now playing
      (let [np (get foo "now_playing")]
        (is (some? np))
        (is (nil? (get np "seek_position")))
        (is (= "Holiday Jazz - JAZZRADIO.com" (get-in np ["one_line" "line1"])))))))

(deftest zone-baz-values
  (testing "Zone 'Baz' (paused with track) has expected values"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          baz            (first (filter #(= "Baz" (get % "display_name")) zones))]
      (is (some? baz) "Baz zone should exist")
      (is (= "16010e07332ef639cc7e2560953d8e108390" (get baz "zone_id")))
      (is (= "paused" (get baz "state")))
      (is (= true (get baz "is_next_allowed")))
      (is (= true (get baz "is_previous_allowed")))
      (is (= true (get baz "is_seek_allowed")))
      (is (= 7 (get baz "queue_items_remaining")))
      (is (= 2362 (get baz "queue_time_remaining")))
      ;; Now playing with track info
      (let [np (get baz "now_playing")]
        (is (some? np))
        (is (= 138 (get np "seek_position")))
        (is (= 760 (get np "length")))
        (is (= "9e9d4f41b5f00ffaf42a875d60e60bce" (get np "image_key")))
        (is (= "Gil Mellé" (get-in np ["two_line" "line2"])))
        (is (= "The Complete Blue Note Fifties Sessions" (get-in np ["three_line" "line3"])))))))

(deftest zone-scooby-playing-values
  (testing "Zone 'Scooby' (playing) has expected values"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          scooby         (first (filter #(= "Scooby" (get % "display_name")) zones))]
      (is (some? scooby) "Scooby zone should exist")
      (is (= "16013065cbe8e3ecaf4c8c47d851e7641867" (get scooby "zone_id")))
      (is (= "playing" (get scooby "state")))
      (is (= true (get scooby "is_pause_allowed")))
      (is (= false (get scooby "is_play_allowed")))
      (is (= 4964 (get scooby "queue_items_remaining")))
      (is (= 1678953 (get scooby "queue_time_remaining")))
      ;; Has multiple outputs (grouped zone)
      (is (= 3 (count (get scooby "outputs"))))
      ;; Now playing
      (let [np (get scooby "now_playing")]
        (is (= 93 (get np "seek_position")))
        (is (= 372 (get np "length")))
        (is (= "Popsy" (get-in np ["two_line" "line1"])))
        (is (= "Bobby Timmons Trio / Bobby Timmons" (get-in np ["two_line" "line2"])))))))

(deftest zone-bar-no-now-playing
  (testing "Zone 'Bar' (stopped, no now_playing) has expected values"
    (let [zones-response (load-json-fixture "bodies/get_zones_response.json")
          zones          (get zones-response "zones")
          bar            (first (filter #(= "Bar" (get % "display_name")) zones))]
      (is (some? bar) "Bar zone should exist")
      (is (= "1601367641070e02f66dee7898248238f265" (get bar "zone_id")))
      (is (= "stopped" (get bar "state")))
      (is (nil? (get bar "now_playing")) "Bar should have no now_playing"))))

;;; Output Value Assertions (from Java OutputMapperTest)

(deftest output-bar-with-volume
  (testing "Output 'Bar' has volume settings"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          bar              (first (filter #(= "Bar" (get % "display_name")) outputs))]
      (is (some? bar) "Bar output should exist")
      (is (= "1701367641070e02f66dee7898248238f265" (get bar "output_id")))
      (is (= "1601367641070e02f66dee7898248238f265" (get bar "zone_id")))
      ;; Volume
      (let [vol (get bar "volume")]
        (is (some? vol) "Bar should have volume")
        (is (= "number" (get vol "type")))
        (is (= 0 (get vol "min")))
        (is (= 100 (get vol "max")))
        (is (= 0 (get vol "value")))
        (is (= 1 (get vol "step")))
        (is (= false (get vol "is_muted")))))))

(deftest output-baz-volume-value
  (testing "Output 'Baz' volume value is 7"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          baz              (first (filter #(= "Baz" (get % "display_name")) outputs))]
      (is (some? baz))
      (is (= 7 (get-in baz ["volume" "value"]))))))

(deftest output-foo-no-volume
  (testing "Output 'Foo' has no volume (no volume control)"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          foo              (first (filter #(= "Foo" (get % "display_name")) outputs))]
      (is (some? foo) "Foo output should exist")
      (is (= "170163df90e742c33b0b2404ae5d08cb8908" (get foo "output_id")))
      (is (nil? (get foo "volume")) "Foo should not have volume"))))

(deftest output-fred-source-control-standby
  (testing "Output 'Fred' has source control with standby support"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          fred             (first (filter #(= "Fred" (get % "display_name")) outputs))]
      (is (some? fred))
      (let [sc (first (get fred "source_controls"))]
        (is (some? sc))
        (is (= true (get sc "supports_standby")))
        (is (= "selected" (get sc "status")))))))

(deftest output-can-group-with-ids
  (testing "Output can_group_with_output_ids contains expected IDs"
    (let [outputs-response (load-json-fixture "bodies/get_outputs_response.json")
          outputs          (get outputs-response "outputs")
          bar              (first (filter #(= "Bar" (get % "display_name")) outputs))
          group-ids        (get bar "can_group_with_output_ids")]
      (is (= 5 (count group-ids)))
      (is (some #{"1701367641070e02f66dee7898248238f265"} group-ids))
      (is (some #{"170163df90e742c33b0b2404ae5d08cb8908"} group-ids)))))

;;; Queue Change Operation Tests (from Java QueueChangeMapperTest)

(deftest queue-change-remove-operation
  (testing "Queue change REMOVE operation has correct structure"
    (let [response (load-json-fixture "bodies/queue_change_response.json")
          changes  (get response "changes")
          remove   (first (filter #(= "remove" (get % "operation")) changes))]
      (is (some? remove) "Should have remove operation")
      (is (valid? schema/QueueChange remove))
      (is (= "remove" (get remove "operation")))
      (is (= 0 (get remove "index")))
      (is (= 10 (get remove "count"))))))

(deftest queue-change-insert-operation
  (testing "Queue change INSERT operation has correct structure"
    (let [response (load-json-fixture "bodies/queue_change_response.json")
          changes  (get response "changes")
          insert   (first (filter #(= "insert" (get % "operation")) changes))]
      (is (some? insert) "Should have insert operation")
      (is (valid? schema/QueueChange insert))
      (is (= "insert" (get insert "operation")))
      (is (= 0 (get insert "index")))
      (let [items (get insert "items")]
        (is (= 10 (count items)))
        ;; First item
        (let [first-item (first items)]
          (is (= 1057136 (get first-item "queue_item_id")))
          (is (= 245 (get first-item "length")))
          (is (= "Mrs. Butterworth" (get-in first-item ["two_line" "line1"])))
          (is (= "Nirvana" (get-in first-item ["two_line" "line2"]))))
        ;; Last item
        (let [last-item (last items)]
          (is (= 1057145 (get last-item "queue_item_id")))
          (is (= 150 (get last-item "length")))
          (is (= "Polly" (get-in last-item ["two_line" "line1"]))))))))

(deftest queue-changed-data-validates
  (testing "QueueChangedData schema validates fixture"
    (let [response (load-json-fixture "bodies/queue_change_response.json")]
      (is (valid? schema/QueueChangedData response)))))

;;; Widget Schema Tests (from plan 004)

(deftest widget-dropdown-schema-test
  (testing "validates dropdown widget"
    (is (valid? schema/WidgetDropdown
                {"type"    "dropdown"
                 "title"   "Choice"
                 "values"  [{"title" "A" "value" "a"}]
                 "setting" "choice"})))
  (testing "validates dropdown with optional fields"
    (is (valid? schema/WidgetDropdown
                {"type"     "dropdown"
                 "title"    "Choice"
                 "subtitle" "Pick one"
                 "values"   [{"title" "A" "value" "a"} {"title" "B" "value" "b"}]
                 "setting"  "choice"
                 "error"    "Invalid selection"}))))

(deftest widget-integer-schema-test
  (testing "validates integer widget"
    (is (valid? schema/WidgetInteger
                {"type"    "integer"
                 "title"   "Volume"
                 "min"     0
                 "max"     100
                 "setting" "volume"})))
  (testing "validates integer widget with error"
    (is (valid? schema/WidgetInteger
                {"type"    "integer"
                 "title"   "Volume"
                 "min"     0
                 "max"     100
                 "setting" "volume"
                 "error"   "Out of range"})))
  (testing "validates integer with string min/max"
    (is (valid? schema/WidgetInteger
                {"type"    "integer"
                 "title"   "Volume"
                 "min"     "0"
                 "max"     "100"
                 "setting" "volume"}))))

(deftest widget-string-schema-test
  (testing "validates string widget"
    (is (valid? schema/WidgetString
                {"type"    "string"
                 "title"   "Name"
                 "setting" "name"})))
  (testing "validates string widget with maxlength"
    (is (valid? schema/WidgetString
                {"type"      "string"
                 "title"     "Name"
                 "maxlength" 50
                 "setting"   "name"}))))

(deftest widget-label-schema-test
  (testing "validates label widget"
    (is (valid? schema/WidgetLabel
                {"type"  "label"
                 "title" "Info text here"}))))

(deftest widget-zone-schema-test
  (testing "validates zone widget"
    (is (valid? schema/WidgetZone
                {"type"    "zone"
                 "title"   "Select Zone"
                 "setting" "zone"}))))

(deftest widget-group-schema-test
  (testing "validates group widget"
    (is (valid? schema/WidgetGroup
                {"type"  "group"
                 "title" "Advanced Settings"
                 "items" [{"type" "string" "title" "Name" "setting" "name"}]}))))

(deftest settings-layout-schema-test
  (testing "validates settings layout"
    (is (valid? schema/SettingsLayout
                {"values"    {"volume" 50}
                 "layout"    [{"type"    "integer"
                               "title"   "Volume"
                               "min"     0
                               "max"     100
                               "setting" "volume"}]
                 "has_error" false})))
  (testing "validates settings layout with error"
    (is (valid? schema/SettingsLayout
                {"values"    {"volume" 150}
                 "layout"    [{"type"    "integer"
                               "title"   "Volume"
                               "min"     0
                               "max"     100
                               "setting" "volume"
                               "error"   "Volume must be 0-100"}]
                 "has_error" true}))))

(deftest status-message-schema-test
  (testing "validates status message"
    (is (valid? schema/StatusMessage
                {"message"  "Ready"
                 "is_error" false})))
  (testing "validates error status"
    (is (valid? schema/StatusMessage
                {"message"  "Connection failed"
                 "is_error" true}))))
