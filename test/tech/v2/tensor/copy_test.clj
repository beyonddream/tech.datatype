(ns tech.v2.tensor.copy-test
  (:require [tech.v2.datatype :as dtype]
            [tech.v2.datatype.functional :as dfn]
            [tech.v2.tensor :as dtt]
            [clojure.test :refer :all]))


(deftest tensor-copy-test
  (let [new-tens (repeat 2 (dtt/->tensor [[2] [3]]))
        dest-tens (dtt/new-tensor [2 2])]
    (dtype/copy-raw->item! (->> new-tens
                                (map #(dtt/select % 0 :all)))
                           (dtt/select dest-tens 0 :all))
    (is (dfn/equals dest-tens (dtt/->tensor [[2 2] [0 0]])))
    (dtype/copy-raw->item! (->> new-tens
                                (map #(dtt/select % 1 :all)))
                           (dtt/select dest-tens 1 :all))
    (is (dfn/equals dest-tens (dtt/->tensor [[2 2] [3 3]])))))
