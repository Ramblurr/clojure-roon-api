(ns ol.roon.test-harness-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.roon.test-harness :as harness]))

(deftest roon-remote-command-test
  (testing "builds roon-remote command with authorize-all and host"
    (let [cmd (#'harness/roon-remote-command {:host          "127.0.0.1"
                                              :authorize-all true})]
      (is (= ["./scripts/roon-remote.bb" "--host" "127.0.0.1" "--authorize-all"]
             cmd)))))
