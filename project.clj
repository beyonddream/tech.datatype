(defproject thinktopic/think.datatype "0.3.2"
  :description "Library for efficient manipulation of contiguous mutable containers of primitive datatypes."
  :url "http://github.com/thinktopic/think.datatype"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [net.mikera/vectorz-clj "0.45.0"]
                 [net.mikera/core.matrix "0.56.0"]]

  :java-source-paths ["java"]

  :think/meta {:type :library :tags [:low-level]})