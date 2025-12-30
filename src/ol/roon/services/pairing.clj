(ns ol.roon.services.pairing
  "Pairing service for Roon Core selection.

  Implements com.roonlabs.pairing:1 as a provided service.
  Only one core can be paired at a time.

  The pairing service allows the Roon Core to query which core is paired
  and to request pairing with a new core. When pairing changes, subscribers
  are notified via the subscribe_pairing subscription.")

(set! *warn-on-reflection* true)

(def ^:const service-name
  "The Roon service name for pairing."
  "com.roonlabs.pairing:1")

;;; State

;; Atom holding current paired core: {:id "..." :name "..."} or nil.
(defonce ^:private paired-core (atom nil))

;;; Service methods

(defn get-pairing
  "Returns current paired core id, or nil if not paired."
  []
  (:id @paired-core))

(defn pair!
  "Pairs with the given core.

  If the new core is different from the current paired core, calls on-core-lost
  with the old core id before setting the new core.

  Does not call on-core-lost when:
  - No core was previously paired
  - The new core is the same as the current core

  Arguments:
  - core: Map with :id and :name keys
  - on-core-lost: Callback fn called with old core id when pairing changes

  Returns the new paired core id."
  [core on-core-lost]
  (let [old-core @paired-core
        new-id   (:id core)]
    ;; Only call on-core-lost if there was a previous core AND it's different
    (when (and old-core
               on-core-lost
               (not= (:id old-core) new-id))
      (on-core-lost (:id old-core)))
    (reset! paired-core core)
    new-id))

(defn reset-pairing!
  "Resets pairing state. For testing only."
  []
  (reset! paired-core nil))

;;; Provided service spec

(defn make-service-spec
  "Creates the pairing service spec for registration.

  Returns map with :name, :methods and :subscriptions for the service framework.

  Arguments:
  - on-core-lost: Callback fn called with old core id when pairing changes"
  [on-core-lost]
  {:name                                                                 service-name
   :methods
   {"get_pairing"
    (fn [_core _body]
      {:verb :complete
       :name "Success"
       :body (when-let [id (get-pairing)]
               {"paired_core_id" id})})

    "pair"
    (fn [core _body]
      (pair! core on-core-lost)
      {:verb      :continue
       :name      "Changed"
       :body      {"paired_core_id" (:id core)}
       :broadcast "subscribe_pairing"})}

   :subscriptions
   {"subscribe_pairing"
    {:start (fn [_core _body]
              {:verb :continue
               :name "Subscribed"
               :body {"paired_core_id" (or (get-pairing) "undefined")}})
     :end   nil}}})
