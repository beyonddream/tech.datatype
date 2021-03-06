(ns tech.v2.datatype.functional.impl
  (:require [tech.v2.datatype.unary-op
             :refer [datatype->unary-op]
             :as unary]
            [tech.v2.datatype.binary-op
             :refer [datatype->binary-op]
             :as binary]
            [tech.v2.datatype.reduce-op
             :as reduce-op]
            [tech.v2.datatype.boolean-op
             :refer [datatype->boolean-unary
                     datatype->boolean-binary
                     boolean-unary-iterable-map
                     boolean-binary-iterable-map
                     boolean-unary-reader-map
                     boolean-binary-reader-map]
             :as boolean-op]
            [tech.v2.datatype.argtypes :as argtypes]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.base :as dtype-base]
            [tech.v2.datatype.readers.range :as reader-range]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.casting :as casting]
            [tech.v2.datatype.array]
            [tech.v2.datatype.list]
            [tech.v2.datatype.primitive]
            [tech.v2.datatype.sparse.reader :as sparse-reader]
            [tech.v2.datatype.comparator :as comparator]
            [tech.parallel.utils :as parallel-utils]
            [tech.v2.datatype.operation-provider :as op-provider])
  (:import [java.util List RandomAccess]))


(defn default-options
  [item options]
  (assoc options :datatype
         (or (:datatype options)
             (dtype-base/get-datatype item))))


(defmacro export-symbols
  [src-ns & symbol-list]
  `(parallel-utils/export-symbols ~src-ns ~@symbol-list))


(defn apply-reduce-op
  "Reduce an iterable into one thing.  This is not currently parallelized."
  [options reduce-op values]
  (op-provider/reduce-op reduce-op values options))


(defn apply-unary-op
    "Perform operation returning a scalar, reader, or an iterator.  Note that the
  results of this could be a reader, iterable or a scalar depending on what was passed
  in.  Also note that the results are lazyily calculated so no computation is done in
  this method aside from building the next thing *unless* the inputs are scalar in which
  case the operation is evaluated immediately."
  [options un-op arg]
  (op-provider/unary-op un-op arg options))


(defn apply-unary-boolean-op
    "Perform operation returning a scalar, reader, or an iterator.  Note that the
  results of this could be a reader, iterable or a scalar depending on what was passed
  in.  Also note that the results are lazyily calculated so no computation is done in
  this method aside from building the next thing *unless* the inputs are scalar in which
  case the operation is evaluated immediately."
  [options un-op arg]
  (op-provider/boolean-unary-op un-op arg options))


(defn- do-apply-binary-op
  [options lhs rhs bin-op]
  (op-provider/binary-op bin-op lhs rhs options))


(defn apply-binary-op
  "We perform a left-to-right reduction making scalars/readers/etc.  This matches
  clojure semantics.  Note that the results of this could be a reader, iterable or a
  scalar depending on what was passed in.  Also note that the results are lazily
  calculated so no computation is done in this method aside from building the next
  thing *unless* the inputs are scalar in which case the operation is evaluated
  immediately."
  [options bin-op arg1 arg2 & args]
  (let [all-args (concat [arg1 arg2] args)]
    (->> all-args
         (reduce #(do-apply-binary-op options %1 %2 bin-op)))))


(defn- do-apply-boolean-binary-op
  [options lhs rhs bin-op]
  (op-provider/boolean-binary-op bin-op lhs rhs options))


(defn apply-binary-boolean-op
  "We perform a left-to-right reduction making scalars/readers/etc.  This matches
  clojure semantics.  Note that the results of this could be a reader, iterable or a
  scalar depending on what was passed in.  Also note that the results are lazily
  calculated so no computation is done in this method aside from building the next thing
  *unless* the inputs are scalar in which case the operation is evaluated immediately."
  [options
   bin-op arg1 arg2 & args]
  (let [options (assoc options :op-type :boolean)
        all-args (concat [arg1 arg2] args)]
    (->> all-args
         (reduce #(do-apply-boolean-binary-op options %1 %2 bin-op)))))


(def all-builtins (->> (concat (->> unary/builtin-unary-ops
                                    (map (fn [[k v]]
                                           {:name k
                                            :type :unary
                                            :operator v})))
                               (->> binary/builtin-binary-ops
                                    (map (fn [[k v]]
                                           {:name k
                                            :type :binary
                                            :operator v})))
                               (->> boolean-op/builtin-boolean-unary-ops
                                    (map (fn [[k v]]
                                           {:name k
                                            :type :boolean-unary
                                            :operator v})))
                               (->> boolean-op/builtin-boolean-binary-ops
                                    (map (fn [[k v]]
                                           {:name k
                                            :type :boolean-binary
                                            :operator v}))))
                       (group-by :name)))


(defn- safe-name
  [item]
  (let [item-name (name item)]
    (if (= item-name "/")
      "div"
      item-name)))


(defmacro def-builtin-operator
  [op-name op-seq]
  (let [op-types (->> (map :type op-seq)
                      set)
        op-name-symbol (symbol (name op-name))
        argnum-types (->> op-types
                          (map {:unary :unary
                                :boolean-unary :unary
                                :binary :binary
                                :boolean-binary :binary})
                          set)]
    `(do
       (defn ~op-name-symbol
         ~(str "Operator " (name op-name) ":" (vec op-types) "." )
         [& ~'args]
         (let [~'n-args (count ~'args)]
           ~(cond
              (= argnum-types #{:unary :binary})
              `(when-not (> ~'n-args 0)
                 (throw (ex-info (format "Operator called with too few (%s) arguments."
                                         ~'n-args)
                                 {})))
              (= argnum-types #{:unary})
              `(when-not (= ~'n-args 1)
                 (throw (ex-info (format "Operator takes 1 argument, (%s) given."
                                         ~'n-args)
                                 {})))
              (= argnum-types #{:binary})
              `(when-not (> ~'n-args 0)
                 (throw (ex-info (format "Operator called with too few (%s) arguments"
                                         ~'n-args)
                                 {})))
              :else
              (throw (ex-info "Incorrect op types" {:types argnum-types
                                                    :op-types op-types})))
           (let [~'options {}]
             (if (= ~'n-args 1)
               ~(if
                  (contains? op-types :boolean-unary)
                  `(apply-unary-boolean-op ~'options ~op-name (first ~'args))
                  `(apply-unary-op ~'options ~op-name (first ~'args)))
               ~(if (contains? op-types :boolean-binary)
                  `(apply apply-binary-boolean-op
                          ~'options
                          ~op-name
                          ~'args)
                  `(apply apply-binary-op
                          ~'options
                          ~op-name
                          ~'args))))))
       ~(when (contains? argnum-types :binary)
          (let [op-name-symbol (symbol (str "reduce-" (safe-name op-name)))]
            `(defn ~op-name-symbol
               ~(str "Operator reduce-" (name op-name)"." )
               [& ~'args]
               (let [~'n-args (count ~'args)]
                 (when-not (> ~'n-args 0)
                   (throw (ex-info
                           (format "Operator called with too few (%s) arguments."
                                   ~'n-args)
                           {})))
                 (let [~'options {}]
                   (if (= ~'n-args 1)
                     (apply-reduce-op ~'options ~op-name (first ~'args))
                     (~op-name-symbol
                      (mapv ~op-name-symbol ~'args)))))))))))


(defmacro define-all-builtins
  []
  `(do
     ~@(->> all-builtins
            (map (fn [[op-name op-seq]]
                   `(def-builtin-operator ~op-name ~op-seq))))))


(defn- ->list
  ^List [item]
  (when-not (instance? List item)
    (throw (ex-info "Item is not a list." {})))
  item)


(defmacro impl-arg-op
  [datatype op]
  (if (= datatype :object)
    `(fn [values#]
       (if (instance? RandomAccess values#)
         (let [values# (->list values#)
               n-elems# (.size values#)]
           (reduce (fn [lhs# rhs#]
                     (if (~op
                          (.get values# lhs#)
                          (.get values# rhs#))
                       lhs#
                       rhs#))
                   (range n-elems#)))
         (let [value-reader# (typecast/datatype->reader ~datatype values#)
               n-elems# (.size value-reader#)]
           (reduce-op/iterable-reduce
            :int32
            (if (~op
                 (.read value-reader# ~'accum)
                 (.read value-reader# ~'next))
              ~'accum
              ~'next)
            (reader-range/reader-range :int32 0 n-elems#)))))
    `(fn [values#]
       (let [value-reader# (typecast/datatype->reader ~datatype values#)
             n-elems# (.size value-reader#)]
         (reduce-op/iterable-reduce
          :int32
          (if (~op
               (.read value-reader# ~'accum)
               (.read value-reader# ~'next))
            ~'accum
            ~'next)
          (reader-range/reader-range :int32 0 n-elems#))))))


(defmacro make-no-boolean-macro-table
  [sub-macro]
  `(->> [~@(for [dtype (concat casting/host-numeric-types
                               [:object])]
             [dtype `(~sub-macro ~dtype)])]
        (into {})))

(defmacro make-maxarg
  [datatype]
  `(impl-arg-op ~datatype >=))

(def maxarg-table (make-no-boolean-macro-table make-maxarg))

(defn argmax
  "Return index of first maximal value"
  ([{:keys [datatype]} values]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         maxarg-fn (get maxarg-table (casting/safe-flatten datatype))]
     (maxarg-fn values)))
  ([values]
   (argmax {} values)))

(defmacro make-minarg
  [datatype]
  `(impl-arg-op ~datatype <=))

(def minarg-table (make-no-boolean-macro-table make-minarg))

(defn argmin
  "Return index of first minimal value"
  ([{:keys [datatype]} values]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         minarg-fn (get minarg-table (casting/safe-flatten datatype))]
     (minarg-fn values)))
  ([values]
   (argmin {} values)))

(defmacro make-last-maxarg
  [datatype]
  `(impl-arg-op ~datatype >))

(def last-maxarg-table (make-no-boolean-macro-table make-last-maxarg))

(defn argmax-last
  "Return index of last maximal value"
  ([{:keys [datatype]} values]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         maxarg-fn (get last-maxarg-table (casting/safe-flatten datatype))]
     (maxarg-fn values)))
  ([values]
   (argmax-last {}  values)))

(defmacro make-last-minarg
  [datatype]
  `(impl-arg-op ~datatype <))

(def last-minarg-table (make-no-boolean-macro-table make-last-minarg))


(defn argmin-last
  "Return index of first minimal value"
  ([{:keys [datatype]} values]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         minarg-fn (get last-minarg-table (casting/safe-flatten datatype))]
     (minarg-fn values)))
  ([values]
   (argmin-last {} values)))


(defmacro impl-compare-arg-op
  [datatype]
  `(fn [values# comparator#]
     (let [value-reader# (typecast/datatype->reader ~datatype values#)
           n-elems# (.size value-reader#)
           comparator# (comparator/datatype->comparator ~datatype comparator#)]
       (reduce-op/iterable-reduce
        :int32
        (if (<= (.compare
                 comparator#
                 (.read value-reader# ~'accum)
                 (.read value-reader# ~'next))
                0)
          ~'accum
          ~'next)
        (reader-range/reader-range :int32 0 n-elems#)))))

(def compare-arg-ops (make-no-boolean-macro-table impl-compare-arg-op))

(defn argcompare
  "Given a reader of values and a comparator, return the first item where
  the comparator's value is less than zero."
  ([{:keys [datatype]} values comp-item]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         compare-fn (get compare-arg-ops (casting/safe-flatten datatype))]
     (compare-fn values comp-item)))
  ([values comp-item]
   (argcompare {} values comp-item)))


(defmacro impl-compare-last-arg-op
  [datatype]
  `(fn [values# comparator#]
     (let [value-reader# (typecast/datatype->reader ~datatype values#)
           n-elems# (.size value-reader#)
           comparator# (comparator/datatype->comparator ~datatype comparator#)]
       (reduce-op/iterable-reduce
        :int32
        (if (>= (.compare
                 comparator#
                 (.read value-reader# ~'accum)
                 (.read value-reader# ~'next))
                0)
          ~'accum
          ~'next)
        (reader-range/reader-range :int32 0 n-elems#)))))

(def compare-last-arg-ops (make-no-boolean-macro-table impl-compare-last-arg-op))

(defn argcompare-last
  "Given a reader of values and a comparator, return the last item where
  the comparator's value is less than zero."
  ([{:keys [datatype]} values comp-item]
   (let [datatype (or datatype (dtype-base/get-datatype values))
         compare-fn (get compare-last-arg-ops (casting/safe-flatten datatype))]
     (compare-fn values comp-item)))
  ([values comp-item]
   (argcompare-last {} values comp-item)))
