(ns ol.roon.test-harness
  (:require
   [babashka.fs :as fs]
   [babashka.process :as proc]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [compose-fixtures]]
   [ol.roon.api :as api]))

(def default-timeout-ms 60000)

(def ready-line-re #"\bRunning\b")

(def ansi-re #"\x1b\[[0-9;]*m")

(defonce state (atom nil))

(def ^:private default-seed-archive (fs/path "test" "fixtures" "seed.tgz"))

(def ^:private lock-file (fs/path "/tmp" "roon-test-harness.pid"))

(defonce ^:private shutdown-hook-registered? (atom false))

(defn- process-alive?
  "Check if a process with the given PID is still running."
  [pid]
  (try
    (let [result (proc/shell {:out :string :err :string :continue true}
                             "kill" "-0" (str pid))]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- read-lock-file
  "Read the PID from the lock file, returns nil if missing or unreadable."
  []
  (when (fs/exists? lock-file)
    (try
      (let [content (str/trim (slurp (fs/file lock-file)))]
        (parse-long content))
      (catch Exception _ nil))))

(defn- write-lock-file!
  "Write our PID to the lock file."
  [pid]
  (spit (fs/file lock-file) (str pid)))

(defn- delete-lock-file!
  "Delete the lock file if it exists."
  []
  (when (fs/exists? lock-file)
    (fs/delete lock-file)))

(defn- current-pid
  "Get the PID of the current JVM process."
  []
  (.pid (java.lang.ProcessHandle/current)))

(defn- acquire-lock!
  "Acquire the test harness lock. Throws if another process holds it."
  []
  (when-let [existing-pid (read-lock-file)]
    (when (process-alive? existing-pid)
      (throw (ex-info "Another test harness is already running"
                      {:lock-file  (str lock-file)
                       :holder-pid existing-pid
                       :message    "Wait for the other test run to complete or manually delete the lock file if stale."}))))
  (write-lock-file! (current-pid)))

(defn- release-lock!
  "Release the test harness lock if we hold it."
  []
  (when-let [lock-pid (read-lock-file)]
    (when (= lock-pid (current-pid))
      (delete-lock-file!))))

(defn running?
  "Returns true if a RoonServer is currently running."
  []
  (let [p (:proc @state)]
    (and p (proc/alive? p))))

(defn- register-shutdown-hook!
  "Register a JVM shutdown hook to clean up on exit."
  []
  (when (compare-and-set! shutdown-hook-registered? false true)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (try
                  (when (running?)
                    (when-let [{:keys [proc]} @state]
                      (proc/destroy-tree proc)))
                  (release-lock!)
                  (catch Exception _)))))))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- strip-ansi [s]
  (str/replace s ansi-re ""))

(defn- sanitize-id [s]
  (let [clean (str/replace s #"[^A-Za-z0-9_.-]+" "_")]
    (if (str/blank? clean)
      "roon"
      clean)))

(defn- absolute-path [p]
  (-> p fs/path fs/absolutize str))

(defn- log-line! [^java.io.Writer writer line]
  (locking writer
    (.write writer (str line "\n"))
    (.flush writer)))

(defn- start-stream-reader [stream writer ready-promise]
  (future
    (with-open [rdr (io/reader stream)]
      (doseq [line (line-seq rdr)]
        (let [clean (strip-ansi line)]
          (log-line! writer clean)
          (when (re-find ready-line-re clean)
            (deliver ready-promise clean)))))))

(defn- wait-ready! [p ready-promise timeout-ms]
  (let [deadline (+ (now-ms) timeout-ms)]
    (loop []
      (let [result (deref ready-promise 200 ::timeout)]
        (if (= result ::timeout)
          (do
            (when-not (proc/alive? p)
              (throw (ex-info "RoonServer exited before readiness"
                              {:exit (:exit @p)})))
            (when (>= (now-ms) deadline)
              (throw (ex-info "Timed out waiting for RoonServer readiness"
                              {:timeout-ms timeout-ms})))
            (recur))
          result)))))

(defn- build-dataroot [base-dir id]
  (let [run-id (str (sanitize-id (str id)) "-" (random-uuid))]
    (absolute-path (fs/path base-dir run-id))))

(defn- maybe-extract-seed! [dataroot seed-archive]
  (when (and seed-archive (fs/exists? seed-archive))
    ;; Use system tar for extraction - handles long paths and GNU extensions
    ;; --strip-components=1 removes the "RoonServer" prefix from paths
    (let [result (proc/shell {:dir (str dataroot)
                              :out :string
                              :err :string}
                             "tar" "-xzf" (str (fs/absolutize seed-archive))
                             "--strip-components=1")]
      (when-not (zero? (:exit result))
        (throw (ex-info "Failed to extract seed archive"
                        {:exit (:exit result)
                         :err  (:err result)}))))))

(defn- roon-remote-command
  [{:keys [host port core-id authorize authorize-all deauthorize timeout-ms discover-timeout-ms]}]
  (cond-> ["./scripts/roon-remote.bb"]
    host (into ["--host" host])
    port (into ["--port" (str port)])
    core-id (into ["--core-id" core-id])
    authorize (into ["--authorize" authorize])
    deauthorize (into ["--deauthorize" deauthorize])
    authorize-all (conj "--authorize-all")
    timeout-ms (into ["--timeout-ms" (str timeout-ms)])
    discover-timeout-ms (into ["--discover-timeout-ms" (str discover-timeout-ms)])))

(defn authorize-all!
  ([] (authorize-all! {}))
  ([opts]
   (let [cmd    (roon-remote-command (assoc opts :authorize-all true))
         result (apply proc/shell {:out :string
                                   :err :string
                                   :dir (str (fs/absolutize "."))}
                       cmd)]
     (when-not (zero? (:exit result))
       (throw (ex-info "Failed to authorize extensions via roon-remote.bb"
                       {:exit (:exit result)
                        :err  (:err result)})))
     result)))

(defn start!
  "Starts a local RoonServer process and waits for readiness.

  Returns the harness state map with `:dataroot` and `:log-file`.
  Throws if `:id` is missing, the process exits early, or readiness
  times out.

  Options:

  | key             | description
  |-----------------|-------------
  | `:id`           | Required dataroot name prefix for this run
  | `:base-dir`     | Parent directory for dataroot creation
  | `:timeout-ms`   | Readiness timeout in milliseconds
  | `:seed-archive` | Path to a seed archive to extract before start

  Example:

  ```clojure
  (start! {:id \"my.ns-test\"})
  ```"
  ([] (start! {}))
  ([{:keys [id base-dir timeout-ms seed-archive]
     :or   {base-dir     "target/roon-test"
            timeout-ms   default-timeout-ms
            seed-archive default-seed-archive}}]
   (when-not id
     (throw (ex-info "Missing required :id for RoonServer dataroot" {})))
   (when (running?)
     (throw (ex-info "RoonServer already running" {:dataroot (:dataroot @state)})))
   (acquire-lock!)
   (register-shutdown-hook!)
   (let [dataroot      (build-dataroot base-dir id)
         _             (fs/create-dirs dataroot)
         _             (maybe-extract-seed! dataroot seed-archive)
         log-file      (str (fs/path dataroot "roonserver.log"))
         writer        (io/writer (io/file log-file) :append true)
         ready-promise (promise)
         p             (proc/process {:out       :pipe
                                      :err       :pipe
                                      :extra-env {"ROON_DATAROOT" dataroot
                                                  "ROON_ID_DIR"   dataroot}}
                                     "RoonServer")
         readers       [(start-stream-reader (:out p) writer ready-promise)
                        (start-stream-reader (:err p) writer ready-promise)]]
     (try
       (wait-ready! p ready-promise timeout-ms)
       (reset! state {:proc       p
                      :dataroot   dataroot
                      :log-file   log-file
                      :log-writer writer
                      :readers    readers
                      :ready-at   (now-ms)})
       @state
       (catch Exception e
         (proc/destroy-tree p)
         (deref p 2000 nil)
         (.close writer)
         (release-lock!)
         (throw e))))))

(defn stop!
  "Stops the running RoonServer process and deletes its dataroot.

  This is safe to call even when nothing is running.
  See [[start!]] and [[cleanup!]]."
  []
  (when-let [{:keys [proc dataroot log-writer]} @state]
    (try
      (when (proc/alive? proc)
        (proc/destroy-tree proc)
        (deref proc 5000 nil))
      (finally
        (when log-writer
          (.close log-writer))
        (when (and dataroot (fs/exists? dataroot))
          (fs/delete-tree dataroot {:force true}))
        (reset! state nil)
        (release-lock!)))))

(defn cleanup!
  "Removes all test dataroots under `:base-dir` after stopping the server.

  Options:

  | key         | description
  |-------------|-------------
  | `:base-dir` | Parent directory for dataroot deletion

  Example:

  ```clojure
  (cleanup!)
  ```"
  ([] (cleanup! {}))
  ([{:keys [base-dir]
     :or   {base-dir "target/roon-test"}}]
   (when (running?)
     (stop!))
   (let [root (fs/path base-dir)]
     (when (fs/exists? root)
       (fs/delete-tree root {:force true})))))

;;; Fixtures

(def ^:private seed-info-path "test/fixtures/seed-info.edn")

(defonce conn-state (atom nil))

(defn server-fixture
  "Fixture that starts/stops the RoonServer process.

  Use this when you only need the server running but will manage
  the connection yourself."
  ([] (server-fixture {}))
  ([opts]
   (fn [f]
     (start! opts)
     (try
       (f)
       (finally
         (stop!))))))

(defn connection-fixture
  "Fixture that connects to a running RoonServer.

  Requires the server to already be running (use with server-fixture).
  Stores the connection in `conn-state` atom.

  Options:
  | key           | description
  |---------------|-------------
  | `:host`       | Server host (default 127.0.0.1)
  | `:port`       | Server port (default 9330)
  | `:timeout-ms` | Connection timeout (default 15000)"
  ([] (connection-fixture {}))
  ([opts]
   (fn [f]
     (let [seed-info  (edn/read-string (slurp seed-info-path))
           timeout-ms (get opts :timeout-ms 15000)
           conn       @(api/connect!
                        {:host              (get opts :host "127.0.0.1")
                         :port              (get opts :port 9330)
                         :extension-id      (:extension-id seed-info)
                         :display-name      (:display-name seed-info)
                         :display-version   (:display-version seed-info)
                         :publisher         (:publisher seed-info)
                         :email             (:email seed-info)
                         :token             (:token seed-info)
                         :required-services (:required-services seed-info)
                         :timeout-ms        timeout-ms})]
       (reset! conn-state conn)
       (try
         (f)
         (finally
           (api/disconnect! conn)
           (reset! conn-state nil)))))))

(defn roon-fixture
  "Fixture that starts RoonServer and connects to it.

  Composition of server-fixture and connection-fixture.
  Stores the connection in `conn-state` atom.

  Options are passed to both fixtures:
  | key             | description
  |-----------------|-------------
  | `:id`           | Required dataroot name prefix
  | `:base-dir`     | Parent directory for dataroot
  | `:timeout-ms`   | Server readiness timeout
  | `:seed-archive` | Path to seed archive
  | `:host`         | Server host (default 127.0.0.1)
  | `:port`         | Server port (default 9330)"
  ([] (roon-fixture {}))
  ([opts]
   (compose-fixtures
    (server-fixture opts)
    (connection-fixture opts))))
