(defproject jepsen.ignite "0.0.1-SNAPSHOT"
  :description "Ignite tests"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.4"]
                 [javax.cache/cache-api "1.0.0"]
                 [org.apache.ignite/ignite-core "1.9.0"]
                 [org.apache.ignite/ignite-spring "1.9.0"]
                 [org.apache.ignite/ignite-log4j "1.9.0"]
                 [org.apache.ignite/ignite-indexing "1.9.0"]])
