(ns ol.roon.connection
  "Connection lifecycle and message routing.

  The Connection record holds all state for a single Roon Core connection.
  Uses core.async channels for async communication and an atom for mutable state.

  Request/Response Pattern:
  - request! takes a request map {:uri \"...\" :body {...}} and returns a promise
  - The promise delivers the response body on success
  - Deref with timeout: (deref (request! conn req) 5000 :timeout)

  Subscription Pattern:
  - subscribe! sends the subscription request but returns nil
  - All subscription events flow to the unified :events-ch channel
  - Events have shape {::roon/event ::roon/zones-changed ::roon/data {...}}"
  (:require [clojure.core.async :as a :refer [<!! chan put!]]
            [ol.roon.moo :as moo]
            [ol.roon.schema :as roon]
            [ol.roon.services.pairing :as pairing]
            [ol.roon.websocket :as ws])
  (:import [java.util.concurrent.atomic AtomicLong]))

(set! *warn-on-reflection* true)

;;; Default configuration

(def ^:private default-config
  {:port               9330
   :timeout-ms         30000
   :auto-reconnect     true
   :backoff-initial-ms 1000
   :backoff-max-ms     60000})

;;; Connection record

(defrecord Connection
           [config            ;; immutable config map
            state             ;; atom for mutable state
            req-counter       ;; AtomicLong for request IDs
            sub-counter       ;; AtomicLong for subscription keys
            events-ch         ;; channel for all events (user-facing)
            send-ch           ;; channel for outgoing messages
            recv-ch           ;; channel for incoming messages
            reconnecting-atom]) ;; atom to guard against multiple reconnect loops

(defn make-connection
  "Creates a new Connection (not yet connected).

  Config map:
  | key                 | required | description                              |
  |---------------------|----------|------------------------------------------|
  | :host               | yes      | Roon Core IP or hostname                 |
  | :extension-id       | no       | Unique extension identifier              |
  | :display-name       | no       | Human-readable name                      |
  | :display-version    | no       | Version string (default 1.0.0)           |
  | :publisher          | no       | Publisher name                           |
  | :email              | no       | Contact email                            |
  | :token              | no       | Saved auth token for reconnection        |
  | :port               | no       | WebSocket port (default 9330)            |
  | :timeout-ms         | no       | Request timeout (default 30000)          |
  | :auto-reconnect     | no       | Auto-reconnect (default true)            |
  | :backoff-initial-ms | no       | Initial backoff (default 1000)           |
  | :backoff-max-ms     | no       | Max backoff (default 60000)              |
  | :on-core-lost       | no       | Callback fn(core-id) when pairing changes|"
  [config]
  (->Connection
   (merge default-config config)
   (atom {:status                  :disconnected
          :websocket               nil
          :pending                 {}
          :subscriptions           {}
          :provided-services       {}  ;; name -> service spec
          :provided-subscriptions  {}  ;; subscription-key -> {:subscription "name" :req-id n}
          :core-info               nil
          :token                   nil
          :explicitly-disconnected false})
   (AtomicLong. 10)   ;; Start at 10 to avoid confusion with server IDs
   (AtomicLong. 0)
   (chan (a/sliding-buffer 32))   ;; events-ch
   (chan 64)                       ;; send-ch
   (chan 64)                       ;; recv-ch
   (atom false)))                  ;; reconnecting-atom

;;; ID generation

(defn next-request-id!
  "Returns next request ID and increments counter."
  [{:keys [req-counter]}]
  (.getAndIncrement ^AtomicLong req-counter))

(defn next-subscription-key!
  "Returns next subscription key and increments counter."
  [{:keys [sub-counter]}]
  (.getAndIncrement ^AtomicLong sub-counter))

;;; State accessors

(defn status
  "Returns current connection status."
  [{:keys [state]}]
  (:status @state))

(defn set-status!
  "Updates connection status."
  [{:keys [state]} new-status]
  (swap! state assoc :status new-status))

;;; Pending request management

(defn add-pending!
  "Adds a pending request with its promise."
  [{:keys [state]} req-id p]
  (swap! state assoc-in [:pending req-id] p))

(defn get-pending
  "Gets the promise for a pending request."
  [{:keys [state]} req-id]
  (get-in @state [:pending req-id]))

(defn remove-pending!
  "Removes a pending request."
  [{:keys [state]} req-id]
  (swap! state update :pending dissoc req-id))

(defn fail-pending!
  "Fails all pending requests with disconnect error.

  Delivers an ExceptionInfo with ::roon/disconnected to each pending promise
  and clears the pending map."
  [{:keys [state]}]
  (let [pending (:pending @state)]
    (doseq [[_req-id p] pending]
      (deliver p (ex-info "Connection lost" {::roon/event ::roon/disconnected})))
    (swap! state assoc :pending {})))

;;; Subscription management

(defn add-subscription!
  "Adds a subscription with its metadata."
  [{:keys [state]} sub-key sub-info]
  (swap! state assoc-in [:subscriptions sub-key] sub-info))

(defn get-subscription
  "Gets subscription info by key."
  [{:keys [state]} sub-key]
  (get-in @state [:subscriptions sub-key]))

(defn get-subscriptions-by-req-id
  "Gets all subscriptions for a given request ID."
  [{:keys [state]} req-id]
  (filter (fn [[_ v]] (= req-id (:req-id v)))
          (:subscriptions @state)))

(defn remove-subscription!
  "Removes a subscription."
  [{:keys [state]} sub-key]
  (swap! state update :subscriptions dissoc sub-key))

;;; Provided service management

(defn register-provided-service!
  "Registers a provided service spec.

  service - map with :name, :methods, and optionally :subscriptions"
  [{:keys [state]} service]
  (swap! state assoc-in [:provided-services (:name service)] service))

(defn get-provided-service
  "Gets a registered provided service by name."
  [{:keys [state]} service-name]
  (get-in @state [:provided-services service-name]))

(defn- add-provided-subscription!
  "Adds a provided service subscription for tracking."
  [{:keys [state]} subscription-key sub-name req-id]
  (swap! state assoc-in [:provided-subscriptions subscription-key]
         {:subscription sub-name :req-id req-id :subscription-key subscription-key}))

(defn get-provided-subscriptions
  "Gets all provided service subscriptions for a subscription name."
  [{:keys [state]} sub-name]
  (filter (fn [[_ v]] (= sub-name (:subscription v)))
          (:provided-subscriptions @state)))

(defn- remove-provided-subscription!
  "Removes a provided service subscription by subscription key."
  [{:keys [state]} subscription-key]
  (swap! state update :provided-subscriptions dissoc subscription-key))

(defn- parse-service-uri
  "Parses a service URI into [service-name method-name].

  Example: \"com.roonlabs.pairing:1/get_pairing\" -> [\"com.roonlabs.pairing:1\" \"get_pairing\"]"
  [uri]
  (let [idx (.lastIndexOf ^String uri "/")]
    (when (pos? idx)
      [(subs uri 0 idx) (subs uri (inc idx))])))

(defn- normalize-core-info
  "Converts core-info string keys to keyword keys for internal use.

  Input:  {\"core_id\" \"abc\" \"display_name\" \"My Core\"}
  Output: {:id \"abc\" :name \"My Core\"}"
  [core-info]
  (when core-info
    {:id   (get core-info "core_id")
     :name (get core-info "display_name")}))

(defn- find-subscription-for-unsubscribe
  "Finds the subscription spec for an unsubscribe method.

  E.g., for 'unsubscribe_pairing', finds 'subscribe_pairing' spec."
  [service method-name]
  (when (.startsWith ^String method-name "unsubscribe_")
    (let [sub-name (.replace ^String method-name "unsubscribe_" "subscribe_")]
      (get-in service [:subscriptions sub-name]))))

(defn handle-provided-service!
  "Handles an incoming request for a provided service.

  Dispatches to the appropriate method or subscription handler.
  Returns the response map or nil if service not found."
  [{:keys [state] :as conn} uri body req-id]
  (when-let [[service-name method-name] (parse-service-uri uri)]
    (when-let [service (get-provided-service conn service-name)]
      (let [core (normalize-core-info (:core-info @state))]
        (cond
          ;; Check methods first
          (get-in service [:methods method-name])
          (let [method-fn (get-in service [:methods method-name])]
            (method-fn core body))

          ;; Check if it's a subscription start (subscribe_*)
          (get-in service [:subscriptions method-name])
          (let [sub-spec (get-in service [:subscriptions method-name])
                start-fn (:start sub-spec)]
            ;; Track the subscription with request ID for broadcasts
            (when-let [sub-key (get body "subscription_key")]
              (add-provided-subscription! conn sub-key method-name req-id))
            (when start-fn
              (start-fn core body)))

          ;; Check if it's an unsubscribe (unsubscribe_*)
          (find-subscription-for-unsubscribe service method-name)
          (let [sub-spec (find-subscription-for-unsubscribe service method-name)
                end-fn   (:end sub-spec)]
            ;; Remove subscription from tracking
            (when-let [sub-key (get body "subscription_key")]
              (remove-provided-subscription! conn sub-key))
            ;; Call end handler if provided
            (if end-fn
              (end-fn core body)
              {:verb :complete :name "Success" :body nil})))))))

;;; Backoff calculation

(defn calculate-backoff
  "Calculates backoff time for reconnect attempt."
  [{:keys [backoff-initial-ms backoff-max-ms]} attempt]
  (min backoff-max-ms
       (* backoff-initial-ms (long (Math/pow 2 (dec attempt))))))

;;; Message routing

(defn- subscription-event-type
  "Maps subscription name and response body to specific event type keyword."
  [subscription response-name body]
  (case subscription
    "zones"
    (if (= response-name "Subscribed")
      ::roon/zones-subscribed
      (cond
        (contains? body "zones_changed")      ::roon/zones-changed
        (contains? body "zones_added")        ::roon/zones-added
        (contains? body "zones_removed")      ::roon/zones-removed
        (contains? body "zones_seek_changed") ::roon/zones-seek-changed
        :else                                 ::roon/zones-changed))

    "outputs"
    (if (= response-name "Subscribed")
      ::roon/outputs-subscribed
      (cond
        (contains? body "outputs_changed") ::roon/outputs-changed
        (contains? body "outputs_added")   ::roon/outputs-added
        (contains? body "outputs_removed") ::roon/outputs-removed
        :else                              ::roon/outputs-changed))

    "queue"
    (if (= response-name "Subscribed")
      ::roon/queue-subscribed
      ::roon/queue-changed)

    ;; Unknown subscription - use nil to skip
    nil))

(defn complete-pending!
  "Delivers response to pending request promise and removes it."
  [conn req-id name body]
  (when-let [p (get-pending conn req-id)]
    (if (#{"Success" "Registered"} name)
      (deliver p body)
      (deliver p (ex-info "Request failed" {:name name :body body})))
    (remove-pending! conn req-id)))

(defn dispatch-subscription!
  "Delivers event to the unified events channel for all subscriptions matching request ID."
  [{:keys [events-ch] :as conn} req-id event-name body]
  (doseq [[_ {:keys [subscription]}] (get-subscriptions-by-req-id conn req-id)]
    (when-let [event-type (subscription-event-type subscription event-name body)]
      (put! events-ch {::roon/event event-type
                       ::roon/data  body}))))

;;; Send/receive loops (virtual threads)

;; Forward declaration for mutual reference
(declare reconnect-loop!)

(defn- start-send-loop!
  "Starts virtual thread that sends messages from send-ch to WebSocket."
  [{:keys [state send-ch]}]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-let [msg (<!! send-ch)]
         (when-let [ws (:websocket @state)]
           (try
             (ws/send! ws msg)
             (catch Exception e
               (println "[WARN] Send error:" (.getMessage e)))))
         (recur))))))

(defn- send-broadcast!
  "Sends a broadcast message to all subscribers of a subscription name."
  [{:keys [send-ch] :as conn} sub-name response-name body]
  (doseq [[_ {:keys [req-id]}] (get-provided-subscriptions conn sub-name)]
    (let [msg (moo/encode-response :continue response-name req-id body)]
      (put! send-ch msg))))

(defn- handle-incoming-request!
  "Handles incoming REQUEST from Roon (e.g., ping, provided services)."
  [{:keys [send-ch] :as conn} req-id uri body]
  (cond
    ;; Built-in ping handler
    (= uri "com.roonlabs.ping:1/ping")
    (let [response (moo/encode-response :complete "Success" req-id nil)]
      (put! send-ch response))

    ;; Try provided services
    :else
    (if-let [result (handle-provided-service! conn uri body req-id)]
      (do
        ;; Send response to requester
        (let [response (moo/encode-response (:verb result) (:name result) req-id (:body result))]
          (put! send-ch response))
        ;; Send broadcast to all subscribers if specified
        (when-let [broadcast-sub (:broadcast result)]
          (send-broadcast! conn broadcast-sub (:name result) (:body result))))
      ;; Unknown request
      (println "[WARN] Unhandled incoming request:" uri))))

(defn- start-recv-loop!
  "Starts virtual thread that routes messages from recv-ch."
  [{:keys [recv-ch events-ch] :as conn}]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-let [msg (<!! recv-ch)]
         (cond
           ;; WebSocket closed
           (= (:type msg) :closed)
           (do
             (set-status! conn :disconnected)
             (fail-pending! conn)
             (put! events-ch {::roon/event ::roon/disconnected
                              ::roon/data  {:reason (:reason msg)
                                            :code   (:code msg)}})
             (when-not (:explicitly-disconnected @(:state conn))
               (reconnect-loop! conn)))

           ;; WebSocket error
           (= (:type msg) :error)
           (do
             (println "[ERROR] WebSocket error:" (:error msg))
             (set-status! conn :disconnected)
             (fail-pending! conn)
             (put! events-ch {::roon/event ::roon/disconnected
                              ::roon/data  {:reason (str (:error msg))}})
             (when-not (:explicitly-disconnected @(:state conn))
               (reconnect-loop! conn)))

           ;; MOO message
           :else
           (when-let [{:keys [verb request-id name body]} (moo/parse-message msg)]
             (case verb
               :complete (complete-pending! conn request-id name body)
               :continue (do
                           ;; "Registered" completes the registration request
                           (when (= name "Registered")
                             (complete-pending! conn request-id name body))
                           ;; Also dispatch to any subscriptions
                           (dispatch-subscription! conn request-id name body))
               :request  (handle-incoming-request! conn request-id name body)
               nil)))
         (recur))))))

;;; Request/subscription API

(defn request!
  "Sends a request and returns a promise that will deliver the response body.

  request-map - {:uri \"service/method\" :body {...} or nil}

  The promise delivers:
  - Response body on success
  - ExceptionInfo on failure (deref will throw)

  Example:
    (let [result (request! conn {:uri \"transport:2/get_zones\" :body nil})]
      @result)  ;; blocks until response

    ;; With timeout
    (deref (request! conn req) 5000 :timeout)"
  [{:keys [send-ch] :as conn} {:keys [uri body]}]
  (let [req-id (next-request-id! conn)
        p      (promise)
        msg    (moo/encode-request req-id uri body)]
    (add-pending! conn req-id p)
    (put! send-ch msg)
    p))

(defn subscribe!
  "Subscribes to events. Returns nil.

  Events flow to the unified :events-ch channel with shape:
    {:type :subscription :subscription \"zones\" :event \"Changed\" :data {...}}

  service - e.g. \"com.roonlabs.transport:2\"
  event   - e.g. \"zones\""
  ([conn service event] (subscribe! conn service event nil))
  ([{:keys [send-ch] :as conn} service event opts]
   (let [req-id  (next-request-id! conn)
         sub-key (next-subscription-key! conn)
         body    (merge {:subscription_key sub-key} opts)
         msg     (moo/encode-request req-id (str service "/subscribe_" event) body)]
     (add-subscription! conn sub-key {:subscription event :req-id req-id})
     (put! send-ch msg)
     nil)))

;;; Connection lifecycle

(defn- register!
  "Registers extension with Roon Core. Returns response body."
  [{:keys [config state] :as conn}]
  (let [{:keys [extension-id display-name display-version publisher email]} config
        body                                                                {:extension_id      (or extension-id "com.clojure.roon-api")
                                                                             :display_name      (or display-name "Clojure Roon API")
                                                                             :display_version   (or display-version "1.0.0")
                                                                             :publisher         (or publisher "")
                                                                             :email             (or email "dev@example.com")
                                                                             :required_services ["com.roonlabs.transport:2"]
                                                                             :optional_services []
                                                                             :provided_services ["com.roonlabs.ping:1"
                                                                                                 pairing/service-name]}
        ;; Include token if we have one (from state or config)
        body                                                                (if-let [token (or (:token @state) (:token config))]
                                                                              (assoc body :token token)
                                                                              body)
        p                                                                   (request! conn {:uri "com.roonlabs.registry:1/register" :body body})
        ;; Block waiting for registration
        result                                                              (deref p (:timeout-ms config) ::timeout)]
    (if (= result ::timeout)
      (throw (ex-info "Registration timeout" {:timeout-ms (:timeout-ms config)}))
      (if (instance? Exception result)
        (throw result)
        result))))

(defn- do-connect!
  "Connects WebSocket and registers extension. Blocking.

  Used by start! for initial connection and by reconnect-loop! for reconnection.
  Does not emit events - caller is responsible for that."
  [{:keys [config state recv-ch] :as conn}]
  (let [{:keys [host port on-core-lost]} config
        url                              (str "ws://" host ":" port "/api")
        ws                               (ws/connect! url
                                                      {:on-message (fn [_ws data]
                                                                     (put! recv-ch data))
                                                       :on-close   (fn [_ws code reason]
                                                                     (put! recv-ch {:type :closed :code code :reason reason}))
                                                       :on-error   (fn [_ws err]
                                                                     (put! recv-ch {:type :error :error err}))})]
    (swap! state assoc :websocket ws)
    ;; Register provided services (pairing)
    (register-provided-service! conn (pairing/make-service-spec on-core-lost))
    ;; Start message loops
    (start-send-loop! conn)
    (start-recv-loop! conn)
    ;; Register with server (uses saved token if available)
    (let [result (register! conn)]
      (swap! state assoc
             :core-info (select-keys result ["core_id" "display_name" "display_version"])
             :token (get result "token"))
      (set-status! conn :connected)
      result)))

(defn- reconnect-loop!
  "Attempts reconnection with exponential backoff. Runs on virtual thread.

  Guards against multiple concurrent reconnect loops using reconnecting-atom.
  Emits ::reconnecting before each attempt and ::reconnected on success."
  [{:keys [config state events-ch reconnecting-atom] :as conn}]
  ;; Guard: only one reconnect loop at a time
  (when (compare-and-set! reconnecting-atom false true)
    (Thread/startVirtualThread
     (fn []
       (try
         (loop [attempt 1]
           (when (and (not (:explicitly-disconnected @state))
                      (:auto-reconnect config true))
             (let [backoff (calculate-backoff config attempt)]
               (put! events-ch {::roon/event ::roon/reconnecting
                                ::roon/data  {:attempt attempt :backoff-ms backoff}})
               (Thread/sleep ^long backoff)
               (when-not (:explicitly-disconnected @state)
                 (let [success? (try
                                  (do-connect! conn)
                                  true
                                  (catch Exception _e
                                    false))]
                   (if success?
                     (put! events-ch {::roon/event ::roon/reconnected
                                      ::roon/data  (:core-info @state)})
                     (recur (inc attempt))))))))
         (finally
           (reset! reconnecting-atom false)))))))

(defn start!
  "Connects to Roon Core and registers extension. Blocking.

  Returns the connection on success, throws on failure."
  [{:keys [events-ch] :as conn}]
  (set-status! conn :connecting)
  (do-connect! conn)
  (put! events-ch {::roon/event ::roon/registered
                   ::roon/data  (:core-info @(:state conn))})
  conn)

(defn disconnect!
  "Disconnects from Roon Core."
  [{:keys [state events-ch] :as conn}]
  (swap! state assoc :explicitly-disconnected true)
  (set-status! conn :disconnecting)

  (when-let [ws (:websocket @state)]
    (try
      (ws/close! ws)
      (catch Exception _)))

  (swap! state assoc :websocket nil)
  (set-status! conn :disconnected)
  (put! events-ch {::roon/event ::roon/disconnected
                   ::roon/data  {:reason "Explicitly disconnected"}})
  conn)

(defn connected?
  "Returns true if connection is established."
  [conn]
  (= :connected (status conn)))
