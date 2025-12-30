(ns ol.roon.persistence
  "Persistence helpers for Roon connection state.

  The caller is responsible for actual storage (file, database, etc.).
  These helpers only serialize and deserialize the state structure.

  RoonState structure:
  {::roon/tokens {\"core-id-1\" \"token-1\" \"core-id-2\" \"token-2\"}
   ::roon/paired-core-id \"core-id-1\"}"
  (:require [clojure.edn :as edn]
            [ol.roon.schema :as roon]))

(set! *warn-on-reflection* true)

(defn extract-state
  "Extracts persistable state from connection.

  Returns map with ::roon/tokens and ::roon/paired-core-id.
  If there's a current token and core_id, adds them to the tokens map."
  [{:keys [state]}]
  (let [{:keys [token core-info]} @state
        core-id                   (get core-info "core_id")]
    (if (and token core-id)
      {::roon/tokens         {core-id token}
       ::roon/paired-core-id core-id}
      {::roon/tokens         {}
       ::roon/paired-core-id nil})))

(defn apply-state
  "Merges persisted state into connection config.

  Arguments:
  - config: Connection config map
  - saved-state: Persisted RoonState map
  - core-id: The core_id to get token for

  Returns updated config with :token for the specified core."
  [config saved-state core-id]
  (let [token (get-in saved-state [::roon/tokens core-id])]
    (if token
      (assoc config :token token)
      config)))

(defn state->edn
  "Serializes state to EDN string."
  [state]
  (pr-str state))

(defn edn->state
  "Deserializes state from EDN string."
  [s]
  (edn/read-string s))
