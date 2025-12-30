(ns ol.roon.services.image-test
  "Tests for image service API."
  (:require [clojure.test :refer [deftest is testing]]
            [ol.roon.services.image :as image]))

(deftest get-image-request-test
  (testing "get-image with only image-key"
    (let [req (image/get-image "img-12345")]
      (is (= "com.roonlabs.image:1/get_image" (:uri req)))
      (is (= {"image_key" "img-12345"} (:body req)))))

  (testing "get-image with scale option"
    (let [req (image/get-image "img-123" {:scale :fit})]
      (is (= {"image_key" "img-123" "scale" "fit"} (:body req))))
    (let [req (image/get-image "img-123" {:scale :fill})]
      (is (= {"image_key" "img-123" "scale" "fill"} (:body req))))
    (let [req (image/get-image "img-123" {:scale :stretch})]
      (is (= {"image_key" "img-123" "scale" "stretch"} (:body req)))))

  (testing "get-image with dimensions"
    (let [req (image/get-image "img-123" {:width 300 :height 300})]
      (is (= {"image_key" "img-123" "width" 300 "height" 300} (:body req)))))

  (testing "get-image with format jpeg"
    (let [req (image/get-image "img-123" {:format :jpeg})]
      (is (= {"image_key" "img-123" "format" "image/jpeg"} (:body req)))))

  (testing "get-image with format png"
    (let [req (image/get-image "img-123" {:format :png})]
      (is (= {"image_key" "img-123" "format" "image/png"} (:body req)))))

  (testing "get-image with all options"
    (let [req (image/get-image "img-123" {:scale  :fit
                                          :width  200
                                          :height 200
                                          :format :jpeg})]
      (is (= {"image_key" "img-123"
              "scale"     "fit"
              "width"     200
              "height"    200
              "format"    "image/jpeg"}
             (:body req))))))

(deftest image-url-test
  (testing "image-url with just key"
    (let [url (image/image-url "10.9.4.17" 9330 "img-abc")]
      (is (= "http://10.9.4.17:9330/api/image/img-abc" url))))

  (testing "image-url with scale"
    (let [url (image/image-url "10.9.4.17" 9330 "img-abc" {:scale :fit})]
      (is (= "http://10.9.4.17:9330/api/image/img-abc?scale=fit" url))))

  (testing "image-url with dimensions"
    (let [url (image/image-url "10.9.4.17" 9330 "img-abc" {:width 300 :height 300})]
      (is (= "http://10.9.4.17:9330/api/image/img-abc?width=300&height=300" url))))

  (testing "image-url with format (URL-encoded)"
    (let [url (image/image-url "10.9.4.17" 9330 "img-abc" {:format :jpeg})]
      (is (= "http://10.9.4.17:9330/api/image/img-abc?format=image%2Fjpeg" url))))

  (testing "image-url with all options"
    (let [url (image/image-url "10.9.4.17" 9330 "img-abc"
                               {:scale :fit :width 200 :height 200 :format :png})]
      (is (= "http://10.9.4.17:9330/api/image/img-abc?scale=fit&width=200&height=200&format=image%2Fpng"
             url)))))

(deftest image-url-from-connection-test
  (testing "uses connection config for host and port"
    (let [mock-conn {:config {:host "192.168.1.100" :port 9330}}
          url       (image/image-url-from-connection mock-conn "img-xyz")]
      (is (= "http://192.168.1.100:9330/api/image/img-xyz" url))))

  (testing "uses default port 9330 when not specified"
    (let [mock-conn {:config {:host "192.168.1.100"}}
          url       (image/image-url-from-connection mock-conn "img-xyz")]
      (is (= "http://192.168.1.100:9330/api/image/img-xyz" url))))

  (testing "passes options through"
    (let [mock-conn {:config {:host "10.0.0.1" :port 9330}}
          url       (image/image-url-from-connection mock-conn "img-abc"
                                                     {:scale :fit :width 300})]
      (is (= "http://10.0.0.1:9330/api/image/img-abc?scale=fit&width=300" url)))))
