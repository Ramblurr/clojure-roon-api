(ns ol.roon.services.settings-test
  "Tests for settings provided service."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ol.roon.services.settings :as settings]))

;;; Test fixtures and helpers

(def test-settings (atom {:volume 50 :enabled true :name "Test"}))

(defn make-test-layout-fn
  "Creates a layout-fn that uses test-settings atom and tracks saves."
  [saved-atom]
  (fn [{:keys [values dry-run?]}]
    (let [v      (or values @test-settings)
          errors (cond-> {}
                   (or (< (:volume v) 0) (> (:volume v) 100))
                   (assoc :volume "Volume must be 0-100"))
          valid? (empty? errors)]
      ;; Save when valid and not dry-run
      (when (and values (not dry-run?) valid?)
        (reset! test-settings v)
        (when saved-atom (reset! saved-atom v)))
      ;; Return layout
      {"values"    v
       "layout"    [{"type"  "integer"        "title" "Volume"
                     "min"   0                "max"   100      "setting" "volume"
                     "error" (:volume errors)}
                    {"type"    "dropdown"                          "title" "Enabled"
                     "values"  [{"title" "Disabled" "value" false}
                                {"title" "Enabled" "value" true}]
                     "setting" "enabled"}
                    {"type"    "string" "title" "Name"
                     "setting" "name"}]
       "has_error" (not valid?)})))

(use-fixtures :each
  (fn [f]
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (f)))

;;; Tests

(deftest settings-service-test
  (testing "creates valid service instance"
    (let [svc (settings/make-service (make-test-layout-fn nil))]
      (is (= "com.roonlabs.settings:1" (:name svc)))
      (is (contains? (get-in svc [:spec :methods]) "get_settings"))
      (is (contains? (get-in svc [:spec :methods]) "save_settings"))
      (is (contains? (get-in svc [:spec :subscriptions]) "subscribe_settings"))))

  (testing "get_settings returns current layout"
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (let [svc    (settings/make-service (make-test-layout-fn nil))
          get-fn (get-in svc [:spec :methods "get_settings"])
          result (get-fn nil nil)]
      (is (= :complete (:verb result)))
      (is (= "Success" (:name result)))
      (is (= 50 (get-in result [:body "settings" "values" :volume])))))

  (testing "save_settings dry-run validates without saving"
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (let [saved   (atom nil)
          svc     (settings/make-service (make-test-layout-fn saved))
          save-fn (get-in svc [:spec :methods "save_settings"])
          result  (save-fn nil {"is_dry_run" true
                                "settings"   {"values" {:volume 75 :enabled true :name "X"}}})]
      (is (= "Success" (:name result)))
      (is (nil? @saved))                        ;; not saved because dry-run
      (is (= 50 (:volume @test-settings)))))    ;; original value unchanged

  (testing "save_settings actual save updates state"
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (let [saved   (atom nil)
          svc     (settings/make-service (make-test-layout-fn saved))
          save-fn (get-in svc [:spec :methods "save_settings"])
          result  (save-fn nil {"is_dry_run" false
                                "settings"   {"values" {:volume 75 :enabled true :name "X"}}})]
      (is (= "Success" (:name result)))
      (is (= {:volume 75 :enabled true :name "X"} @saved))
      (is (= 75 (:volume @test-settings)))))

  (testing "save_settings returns NotValid on validation error"
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (let [saved   (atom nil)
          svc     (settings/make-service (make-test-layout-fn saved))
          save-fn (get-in svc [:spec :methods "save_settings"])
          result  (save-fn nil {"is_dry_run" false
                                "settings"   {"values" {:volume 150 :enabled true :name "X"}}})]
      (is (= "NotValid" (:name result)))
      (is (true? (get-in result [:body "settings" "has_error"])))
      (is (nil? @saved))                        ;; not saved because invalid
      (is (= 50 (:volume @test-settings)))))    ;; original value unchanged

  (testing "subscribe_settings returns Subscribed with layout"
    (reset! test-settings {:volume 50 :enabled true :name "Test"})
    (let [svc      (settings/make-service (make-test-layout-fn nil))
          start-fn (get-in svc [:spec :subscriptions "subscribe_settings" :start])
          result   (start-fn nil nil)]
      (is (= :continue (:verb result)))
      (is (= "Subscribed" (:name result)))
      (is (contains? (get-in result [:body "settings"]) "layout")))))
