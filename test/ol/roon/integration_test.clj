(ns ol.roon.integration-test
  "Integration tests against a local RoonServer.

  Uses a pre-seeded server with an already-approved extension.
  The seed (seed.tgz) contains the server identity and auth data,
  so token re-authentication works without manual approval."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.roon.api :as api]
   [ol.roon.services.browse :as browse]
   [ol.roon.services.image :as image]
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

(deftest ^:integration can-browse-root
  (testing "can browse root hierarchy"
    (let [result @(browse/browse! (:conn @harness/conn-state) {:pop-all true})
          action (get result "action")
          list   (get result "list")]
      (is (= "list" action))
      (is (map? list))
      (is (= "Explore" (get list "title"))))))

(deftest ^:integration can-browse-to-albums
  (testing "can navigate to Library > Albums"
    (let [conn        (:conn @harness/conn-state)
          ;; Start at root
          _           @(browse/browse! conn {:pop-all true})
          root-items  (get @(browse/load! conn {:offset 0 :count 10}) "items")
          ;; Find Library
          library-key (get (first (filter #(= "Library" (get % "title")) root-items)) "item_key")
          _           @(browse/browse! conn {:item-key library-key})
          lib-items   (get @(browse/load! conn {:offset 0 :count 10}) "items")
          ;; Find Albums
          albums-key  (get (first (filter #(= "Albums" (get % "title")) lib-items)) "item_key")
          albums      @(browse/browse! conn {:item-key albums-key})
          album-list  (get @(browse/load! conn {:offset 0 :count 10}) "items")]
      (is (string? library-key))
      (is (string? albums-key))
      (is (= "Albums" (get-in albums ["list" "title"])))
      (is (pos? (count album-list)))
      ;; Albums should have image_keys
      (is (some #(get % "image_key") album-list)))))

(deftest ^:integration can-fetch-album-image
  (testing "can fetch image via MOO protocol"
    (let [conn        (:conn @harness/conn-state)
          ;; Navigate to albums
          _           @(browse/browse! conn {:pop-all true})
          root-items  (get @(browse/load! conn {:offset 0 :count 10}) "items")
          library-key (get (first (filter #(= "Library" (get % "title")) root-items)) "item_key")
          _           @(browse/browse! conn {:item-key library-key})
          lib-items   (get @(browse/load! conn {:offset 0 :count 10}) "items")
          albums-key  (get (first (filter #(= "Albums" (get % "title")) lib-items)) "item_key")
          _           @(browse/browse! conn {:item-key albums-key})
          albums      (get @(browse/load! conn {:offset 0 :count 10}) "items")
          ;; Get first album with image
          image-key   (get (first (filter #(get % "image_key") albums)) "image_key")
          ;; Fetch image
          result      @(image/get-image! conn image-key {:scale :fit :width 100 :height 100 :format :jpeg})]
      (is (string? image-key))
      (is (map? result))
      (is (= "image/jpeg" (:content-type result)))
      (is (bytes? (:data result)))
      (is (pos? (count (:data result))))
      ;; Verify JPEG magic bytes (0xFF 0xD8)
      (is (= 0xFF (Byte/toUnsignedInt (aget (:data result) 0))))
      (is (= 0xD8 (Byte/toUnsignedInt (aget (:data result) 1)))))))
