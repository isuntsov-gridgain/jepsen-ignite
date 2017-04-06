(ns jepsen.ignite-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepsen.ignite :refer :all]))

(deftest simple-test-test
  (is (:valid? (:results (run! (simple-test "1.9.0"))))))
