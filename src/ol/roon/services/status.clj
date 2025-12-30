(ns ol.roon.services.status
  "Status service (com.roonlabs.status:1).

  Displays extension status in Roon UI.
  This is a provided service - the extension provides it to Roon Core.

  Usage:
  ```clojure
  ;; Create service instance
  (def status-svc (status/make-service))

  ;; Pass to connect - service is stored inside connection
  (def conn (roon/connect! {:host \"...\"
                            :provided-services [status-svc]}))

  ;; Update status via connection
  (status/set-status! conn \"Ready\" false)
  (status/set-status! conn \"Error occurred\" true)
  ```"
  (:require [ol.roon.connection :as conn]))

(set! *warn-on-reflection* true)

(def ^:const service-name
  "The Roon service name for status."
  "com.roonlabs.status:1")

(defn make-service
  "Creates a status service instance.

  Returns a service instance map containing:
  - :name - service name
  - :spec - service spec to register with connection
  - :set-status! - internal fn to update status (use set-status! helper instead)

  State is managed internally. User calls set-status! to update."
  []
  (let [state (atom {:message "" :is_error false})]
    {:name                                                                                          service-name
     :spec
     {:name                                                                            service-name
      :methods
      {"get_status"
       (fn [_core _body]
         (let [{:keys [message is_error]} @state]
           {:verb :complete
            :name "Success"
            :body {"message" (or message "") "is_error" (boolean is_error)}}))}
      :subscriptions
      {"subscribe_status"
       {:start (fn [_core _body]
                 (let [{:keys [message is_error]} @state]
                   {:verb :continue
                    :name "Subscribed"
                    :body {"message" (or message "") "is_error" (boolean is_error)}}))
        :end   nil}}}
     :set-status!
     (fn [broadcast-fn message is-error]
       (let [msg (or message "")
             err (boolean is-error)]
         (reset! state {:message msg :is_error err})
         (broadcast-fn "subscribe_status" {"message" msg "is_error" err})))}))

(defn set-status!
  "Updates status and broadcasts to all subscribers.

  Arguments:
  - conn: Connection from connect!
  - message: Status message string
  - is-error: Boolean indicating error state

  Looks up status service from connection and updates it."
  [conn message is-error]
  (when-let [svc (conn/get-service-instance conn service-name)]
    ((:set-status! svc) #(conn/broadcast! conn %1 %2) message is-error)))
