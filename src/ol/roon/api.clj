(ns ol.roon.api
  "High-level API entrypoints for Roon integration.

  Example usage:

  ```clojure
  (require '[ol.roon.api :as roon]
           '[ol.roon.schema :as schema]
           '[ol.roon.services.transport :as transport]
           '[clojure.core.async :refer [<! go-loop]])

  ;; Connect (returns promise, deref to block)
  (def conn @(roon/connect!
               {:host \"10.9.4.17\"
                :extension-id \"com.example.myapp\"
                :display-name \"My Roon App\"
                :display-version \"1.0.0\"
                :publisher \"Example Publisher\"
                :email \"contact@example.com\"}))

  ;; Unified event loop - receives all events
  (go-loop []
    (when-let [event (<! (:events conn))]
      (case (::schema/event event)
        ::schema/registered
        (println \"Connected to\" (::schema/data event))

        ::schema/disconnected
        (println \"Disconnected:\" (:reason (::schema/data event)))

        ::schema/zones-subscribed
        (println \"Initial zones:\" (get (::schema/data event) \"zones\"))

        ::schema/zones-changed
        (println \"Zones changed:\" (::schema/data event))

        ;; Handle other events...
        nil)
      (recur)))

  ;; Subscribe to zone changes (events go to :events channel)
  (transport/subscribe-zones! (:conn conn))

  ;; Get zones (returns promise)
  (let [result @(transport/get-zones! (:conn conn))]
    (println \"Zones:\" (get result \"zones\")))

  ;; Control playback (returns promise)
  @(transport/control! (:conn conn) zone-id :play)

  ;; Data-driven API - build request, then send
  (let [req (transport/change-volume \"output-1\" :absolute 50)]
    @(ol.roon.connection/request! (:conn conn) req))

  ;; Disconnect
  (roon/disconnect! conn)
  ```"
  (:require [ol.roon.connection :as conn]
            [ol.roon.persistence :as persist]
            [ol.roon.sood :as sood]))

(set! *warn-on-reflection* true)

(defn connect!
  "Connects to Roon Core and registers extension. Returns promise.

  Promise delivers {:conn Connection :events <channel>} on success,
  or an exception on failure.

  Events channel receives all events with shape:
    {::roon/event ::roon/registered ::roon/data {...}}
    {::roon/event ::roon/disconnected ::roon/data {:reason \"...\"}}
    {::roon/event ::roon/zones-changed ::roon/data {...}}

  See `ol.roon.schema/EventDataRegistry` for all event types and payloads.

  Usage:
    (let [p (connect! {:host \"10.0.0.1\" ...})]
      ;; do other work while connecting...
      (let [{:keys [conn events]} @p]
        ;; connected, use conn and events
        ...))

    ;; With timeout
    (let [result (deref (connect! config) 5000 :timeout)]
      (when-not (= result :timeout)
        ...))

  Config map:
  | key               | required | description                    |
  |-------------------|----------|--------------------------------|
  | :host             | yes      | Roon Core IP or hostname       |
  | :extension-id     | yes      | Unique extension identifier    |
  | :display-name     | yes      | Human-readable name            |
  | :display-version  | yes      | Version string                 |
  | :publisher        | yes      | Publisher name                 |
  | :email            | yes      | Contact email                  |
  | :token            | no       | Saved token for re-auth        |
  | :port             | no       | WebSocket port (default 9330)  |
  | :timeout-ms       | no       | Request timeout (default 30000)|
  | :auto-reconnect   | no       | Auto-reconnect (default true)  |"
  [config]
  (let [connection     (conn/make-connection config)
        result-promise (promise)]
    (Thread/startVirtualThread
     (fn []
       (let [result @(conn/start! connection)]
         (if (instance? Exception result)
           (deliver result-promise result)
           (deliver result-promise {:conn   connection
                                    :events (:events-ch connection)})))))
    result-promise))

(defn disconnect!
  "Disconnects from Roon Core."
  [{:keys [conn]}]
  (conn/disconnect! conn))

(defn connected?
  "Returns true if connected to Roon Core."
  [{:keys [conn]}]
  (conn/connected? conn))

;;; Discovery

(defn discover!
  "Discovers Roon Cores on the network via SOOD.

  Queries all IPv4 network interfaces via multicast and broadcast.
  Deduplicates responses by unique-id.

  Options:
  | key         | default | description                    |
  |-------------|---------|--------------------------------|
  | :timeout-ms | 3000    | Discovery timeout              |

  Returns seq of Core records with :unique-id, :host, :port, :name, :version."
  ([] (sood/discover!))
  ([opts] (sood/discover! opts)))

(defn discover-one!
  "Discovers first available Roon Core.

  Convenience for single-core setups. Returns Core or nil."
  ([] (sood/discover-one!))
  ([opts] (sood/discover-one! opts)))

;;; Persistence

(defn extract-state
  "Extracts persistable state from connection.

  Returns map with :tokens and :paired-core-id suitable for
  serialization and later use with apply-state."
  [{:keys [conn]}]
  (persist/extract-state conn))

(defn apply-state
  "Merges persisted state into connection config.

  Arguments:
  - config: Connection config map
  - saved-state: Persisted state from extract-state
  - core-id: The core_id to get token for

  Returns updated config with :token for the specified core."
  [config saved-state core-id]
  (persist/apply-state config saved-state core-id))

(defn state->edn
  "Serializes state to EDN string for storage."
  [state]
  (persist/state->edn state))

(defn edn->state
  "Deserializes state from EDN string."
  [s]
  (persist/edn->state s))
