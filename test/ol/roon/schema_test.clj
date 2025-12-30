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
