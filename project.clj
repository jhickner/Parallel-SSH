(defproject parallelSSH "1.0.0-SNAPSHOT"
  :description "Runs a command on multiple servers in parallel"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.clojars.rosejn/clansi "1.2.0-SNAPSHOT" :exclusions [org.clojure/clojure]]]
  :main parallelSSH.core)
