(ns jepsen.ignite
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [clojure.pprint        :refer [pprint]]
            [jepsen [client    :as client]
                    [core      :as jepsen]
                    [db        :as db]
                    [tests     :as tests]
                    [control   :as c :refer [|]]
                    [checker   :as checker]
                    [model     :as model]
                    [nemesis   :as nemesis]
                    [generator :as gen]
                    [util      :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.ubuntu :as ubuntu])
  (:import (clojure.lang ExceptionInfo)
           (org.apache.ignite Ignition IgniteCache)))

(defn localNodeLogFileName
  [node]
  (str/replace (str "/tmp/node" node ".log") #":" "_"))

(defn awaitStartedGrid
  "Waiting for started grid"
  [node test]
  (info node "waiting for topology snapshot")
  (io/delete-file (localNodeLogFileName node) true)
  (c/download "/tmp/apache-ignite-fabric/node.log" (localNodeLogFileName node))
  (while
    (not (str/includes? (slurp (localNodeLogFileName node)) "Topology snapshot [ver=5, servers=5,"))
      (Thread/sleep 5000)
      (c/download "/tmp/apache-ignite-fabric/node.log" (localNodeLogFileName node))))

(defn killStalledNodes
  "Kill stalled Apache Ignite."
  [node test]
  (info node "killing stalled nodes")
  (c/sudo "ubuntu"
    (meh (c/exec :pkill :-9 :-f "org.apache.ignite.startup.cmdline.CommandLineStartup"))))

(defn fixNodeId
  "Fix node id"
  [node]
  (str/replace node ":" ""))

(defn setCfgParameters
  "Set configuration parameters"
  [tmpl_file output_file]
  (c/exec :echo (-> (io/resource tmpl_file)
                    slurp
                    (str/replace #"n1" (cn/ip "n1"))
                    (str/replace #"n2" (cn/ip "n2"))
                    (str/replace #"n3" (cn/ip "n3"))
                    (str/replace #"n4" (cn/ip "n4"))
                    (str/replace #"n5" (cn/ip "n5")))
          :> output_file))

(defn install!
  "Installs Apache Ignite on the given node."
  [node version]
  (c/sudo "ubuntu"
    (c/cd "/tmp"
      (c/exec :wget :-c (str "http://apache-mirror.rbc.ru/pub/apache//ignite/" version "/apache-ignite-fabric-" version "-bin.zip"))
      (c/exec :rm :-rf (str "apache-ignite-fabric-" version "-bin"))
      (c/exec :rm :-rf str "apache-ignite-fabric")
      (c/exec :unzip :-q (str "apache-ignite-fabric-" version "-bin.zip"))
      (c/exec :mv :-f (str "apache-ignite-fabric-" version "-bin") "apache-ignite-fabric")
      (c/exec :mkdir "/tmp/apache-ignite-fabric/jepsen"))))

(defn configure!
  "Uploads configuration files to the given node."
  [node test]
  (info node "configuring")
  (setCfgParameters "default.xml" "/tmp/jepsen_config.xml")
  (c/upload "/tmp/jepsen_config.xml" "/tmp/apache-ignite-fabric/jepsen/config.xml")
  (io/copy (io/file (.getCanonicalPath (io/file "./resources/default.client.xml"))) (io/file (str "/tmp/jepsen_config.client." (fixNodeId node) ".xml")))
  (spit (str "/tmp/jepsen_config.client." (fixNodeId node) ".xml") (str/replace (slurp (str "/tmp/jepsen_config.client." (fixNodeId node) ".xml")) "gridname" (fixNodeId node))))

(defn start!
  "Starts Apache Ignite."
  [node test]
  (info node "starting ignite")
  (c/sudo "ubuntu"
    (c/cd "/tmp/apache-ignite-fabric"
      (c/exec "bin/ignite.sh" "jepsen/config.xml" :-v (c/lit ">node.log") (c/lit "2>&1 &"))
      (Thread/sleep 5000))))

(defn stop!
  "Stops Apache Ignite."
  [node test]
  (info node "stopping ignite")
  (c/sudo "ubuntu"
    (meh (c/exec :killall :-9qw "ignite.sh"))))

(defn wipe!
  "Shuts down the server and wipes data."
  [node test]
  (stop! node test)
  (info node "deleting data files")
  (c/sudo "ubuntu"
    (c/exec :rm :-rf "/tmp/apache-ignite-fabric/work/db")))


(defn db
  "Apache Ignite for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (killStalledNodes node test)
      (install! node version)
      (configure! node test)
      (jepsen/synchronize test)
      (start! node test)
      (awaitStartedGrid node test)
      (jepsen/synchronize test))

    (teardown! [_ test node]
      (wipe! node test))

    db/LogFiles
    (log-files [_ test node]
      ["/tmp/apache-ignite-fabric/node.log"])))

; Generators

(defn r   [_ _] {:type :invoke, :f :read})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 100)})


(defn ignite-get
  [cache op]
  (.get cache op))

(defn ignite-put
  [cache op]
  (.put cache op op))

(defrecord IgniteСlient [client node cache]
  client/Client

  (setup! [this test node]
    (let [client (Ignition/start (str "/tmp/jepsen_config.client." (fixNodeId node) ".xml"))
         cache (.getOrCreateCache client "jepsen_cache")]
         (dotimes [i 100] (ignite-put cache i))
         (assoc this :client client :cache cache)))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read (assoc op :type :ok :value (ignite-get cache op))
        :write (assoc op :type :ok :value (ignite-put cache op)))))

  (teardown! [_ test]))


(defn client
  "Ignite client"
  [node]
  (IgniteСlient. nil nil nil))

(defn std-gen
  "Generator"
  [gen]
  (gen/phases
    (->> gen
         (gen/nemesis
           (gen/seq (cycle[(gen/sleep 10)
                           {:type :info :f :start}
                           (gen/sleep 10)
                           {:type :info :f :stop}])))
         (gen/time-limit 100))
   ; Recover
   (gen/nemesis (gen/once {:type :info :f :stop}))
   ; Wait for resumption of normal ops
   (gen/clients (gen/time-limit 10 gen))))

(defn simple-test
  [version]
  (merge tests/noop-test
         {:name      "ignite-simple-test"
          :client    (client nil)
          :generator (->> (gen/mix [r w])
                          (gen/stagger 0.1)
                          (gen/clients)
                           std-gen)
          :model     (model/cas-register)
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker/compose {:linear checker/linearizable})
          :os        ubuntu/os
          :db        (db version)}))
