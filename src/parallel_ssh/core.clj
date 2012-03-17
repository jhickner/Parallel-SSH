(ns ^{:doc "Runs any arbitary bash command on all servers in parallel"
      :author "Chris McBride"} 
  parallel-ssh.core
  (:use [clojure.string :only [replace-first split upper-case trim-newline join]]
        [clojure.java.shell :only [sh]]
        [clojure.tools.cli :only (cli)]
        [clansi.core :only [style]])
  (:gen-class))

(defrecord CommandResult [out ;out is the stdout the command returned
                          err ;err is stderr
                          server ;server, is the string name of the server who ran this command
                          username ;username who ran this command
                          exit]) ;exit is the exit code

(defn- ^CommandResult run-command
  "Runs the given command on the given server. Returns a CommandResult"
  [^CommandResult empty-cmdresult, cmd-name]
    (let [shell-result-map (sh "ssh" "-T" (str (:username empty-cmdresult) \@ (:server empty-cmdresult)) cmd-name)] 
      (conj empty-cmdresult shell-result-map)))

(defn set-break-handler! ;stole from clojure.repl
  "Register INT signal handler.  After calling this, Ctrl-C will cause
  the given function f to be called."
  ([f]
   (sun.misc.Signal/handle
     (sun.misc.Signal. "INT")
     (proxy [sun.misc.SignalHandler] []
       (handle [signal]
         (f))))))

(defn- ^String format-output 
  [^CommandResult result]
  "format the output text for a given CommandResult" 
    (let [{:keys [err out exit server]} result
          header (str (style (upper-case server) :yellow :bright) "\n")]
      (str header
        (cond
          (nil? exit) (str (style "SERVER TIMED OUT!" :bg-red :bright) "\n") ;if the exit code is not defined, that means that the ssh command never returned
          (not (zero? exit)) (str (style (str "ERROR: " err) :bg-red :bright) "\n")) ;if the exit code is non-zero, that means the ssh command did return but there was an error. So print the stderr returned
        (when (not (nil? out))  
          out)))) ;print the result of the  command.

(defn format-outputs [commandResults]
  (join (map #(format-output %) commandResults)))

(defn run-commands
  "Calls run-command for each server in parallel using agents"
  [cmd-name servers username & [optional-timeout-ms _]]
    (let [agents (doall (map #(agent (map->CommandResult {:server %, :username username})) servers))
          deref-agents (fn []
                       (doall (map #(deref %) agents)))]
      (do
        (doseq [x agents] (send-off x run-command cmd-name))
        ;Sometimes a server might be hanging on ssh, if we kill the process we should see what we got from the other servers
        (set-break-handler! (comp (fn [& _] (System/exit 1)) (partial println "\nCAUGHT SIGINT!\n") format-outputs deref-agents))
        (if (integer? optional-timeout-ms)
          (apply await-for optional-timeout-ms agents)
          (apply await agents))
        (deref-agents))))

(defn valid-server-response? [^CommandResult {:keys [exit]}]
  (and (not (nil? exit)) (zero? exit)))

(defn- parse-cli-args
  [args]
    (cli args
      ["-h" "--help" "Show help" :default false :flag true]
      ["-p" "--[no-]prompt" "Show a confirmation prompt before running the command" :default true :flag true]
      ["-s" "--servers" "csv list of servers" :parse-fn #(split % #",")]
      ["-t" "--timeout" "timeout in seconds" :parse-fn #(* 1000 (Integer. %))]
      ["-u" "--username" "username to use on ssh"]))

(defn- print-usage-and-die 
  [usage-str]
    (do
      (print (replace-first usage-str "Usage:" "Usage: [switches] command-string"))
      (newline)
      (flush)
      (System/exit 1)))

(defn- confirm-prompt
  [cmd-str]
    (do 
      (println (style cmd-str :bright))
      (print "This command will run on all servers, please type 'yes' to confirm: ")
      (flush)
      (= (trim-newline (read-line)) "yes")))

(defn -main [& args]
  (let [[{:keys [servers prompt help timeout username]} [cmd-str & _] usage] (parse-cli-args args)]
    (if (or (= true help) (nil? servers) (nil? username) (nil? cmd-str))
      (print-usage-and-die usage)
      (do 
        (when (and prompt (not (confirm-prompt cmd-str))) 
          (System/exit 1))
        (println (format-outputs (run-commands cmd-str servers username timeout)))
        (System/exit 0)))))
