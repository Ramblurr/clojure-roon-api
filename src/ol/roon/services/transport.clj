(ns ol.roon.services.transport
  "Transport service API for playback control.

  The transport service is consumed from the Roon Core.
  It provides zone/output management, playback control, and subscriptions.

  API Pattern:
  - Data builders (no ! suffix) return request maps: {:uri ... :body ...}
  - Action functions (! suffix) send request and return a promise
  - Subscribe functions (! suffix) send subscription, events flow to :events-ch"
  (:require [ol.roon.connection :as conn]))

(set! *warn-on-reflection* true)

(def ^:const service "com.roonlabs.transport:2")

;;; Request map builders (data-driven API)

(defn get-zones
  "Returns request map for getting all zones."
  []
  {:uri  (str service "/get_zones")
   :body nil})

(defn get-outputs
  "Returns request map for getting all outputs."
  []
  {:uri  (str service "/get_outputs")
   :body nil})

(defn control
  "Returns request map for playback control.

  zone-or-output-id - zone_id or output_id string
  control-action    - :play, :pause, :playpause, :stop, :previous, :next"
  [zone-or-output-id control-action]
  {:uri  (str service "/control")
   :body {"zone_or_output_id" zone-or-output-id
          "control"           (name control-action)}})

(defn change-volume
  "Returns request map for volume change.

  output-id - output_id string
  how       - :absolute or :relative or :relative_step
  value     - volume value (0-100 for absolute, delta for relative)"
  [output-id how value]
  {:uri  (str service "/change_volume")
   :body {"output_id" output-id
          "how"       (name how)
          "value"     value}})

(defn mute
  "Returns request map for mute/unmute.

  output-id - output_id string
  how       - :mute or :unmute"
  [output-id how]
  {:uri  (str service "/mute")
   :body {"output_id" output-id
          "how"       (name how)}})

(defn seek
  "Returns request map for seek.

  zone-or-output-id - zone_id or output_id string
  how               - :absolute or :relative
  seconds           - position in seconds"
  [zone-or-output-id how seconds]
  {:uri  (str service "/seek")
   :body {"zone_or_output_id" zone-or-output-id
          "how"               (name how)
          "seconds"           seconds}})

(defn pause-all
  "Returns request map for pausing all zones."
  []
  {:uri  (str service "/pause_all")
   :body nil})

(defn mute-all
  "Returns request map for muting/unmuting all zones.

  how - :mute or :unmute"
  [how]
  {:uri  (str service "/mute_all")
   :body {"how" (name how)}})

(defn standby
  "Returns request map for standby.

  output-id   - output_id string
  control-key - optional source control key"
  ([output-id]
   (standby output-id nil))
  ([output-id control-key]
   {:uri  (str service "/standby")
    :body (cond-> {"output_id" output-id}
            control-key (assoc "control_key" control-key))}))

(defn toggle-standby
  "Returns request map for toggling standby.

  output-id   - output_id string
  control-key - source control key (required)"
  [output-id control-key]
  {:uri  (str service "/toggle_standby")
   :body {"output_id"   output-id
          "control_key" control-key}})

(defn convenience-switch
  "Returns request map for convenience switch.

  output-id   - output_id string
  control-key - optional source control key"
  ([output-id]
   (convenience-switch output-id nil))
  ([output-id control-key]
   {:uri  (str service "/convenience_switch")
    :body (cond-> {"output_id" output-id}
            control-key (assoc "control_key" control-key))}))

(defn transfer-zone
  "Returns request map for zone transfer.

  from-zone-or-output-id - source zone_id or output_id
  to-zone-or-output-id   - target zone_id or output_id"
  [from-zone-or-output-id to-zone-or-output-id]
  {:uri  (str service "/transfer_zone")
   :body {"from_zone_or_output_id" from-zone-or-output-id
          "to_zone_or_output_id"   to-zone-or-output-id}})

(defn group-outputs
  "Returns request map for grouping outputs.

  output-ids - vector of output_id strings to group"
  [output-ids]
  {:uri  (str service "/group_outputs")
   :body {"output_ids" output-ids}})

(defn ungroup-outputs
  "Returns request map for ungrouping outputs.

  output-ids - vector of output_id strings to ungroup"
  [output-ids]
  {:uri  (str service "/ungroup_outputs")
   :body {"output_ids" output-ids}})

(defn change-settings
  "Returns request map for changing zone settings.

  zone-or-output-id - zone_id or output_id string
  settings          - map of settings (loop, shuffle, auto_radio)"
  [zone-or-output-id settings]
  {:uri  (str service "/change_settings")
   :body (assoc settings "zone_or_output_id" zone-or-output-id)})

(defn play-from-here
  "Returns request map for playing from a queue item.

  zone-or-output-id - zone_id or output_id string
  queue-item-id     - queue item ID (number)"
  [zone-or-output-id queue-item-id]
  {:uri  (str service "/play_from_here")
   :body {"zone_or_output_id" zone-or-output-id
          "queue_item_id"     queue-item-id}})

;;; Action functions (return promises)

(defn get-zones!
  "Gets all zones. Returns a promise.

  Deref the promise to get the zones vector."
  [connection]
  (conn/request! connection (get-zones)))

(defn get-outputs!
  "Gets all outputs. Returns a promise.

  Deref the promise to get the outputs vector."
  [connection]
  (conn/request! connection (get-outputs)))

(defn control!
  "Sends playback control command. Returns a promise.

  zone-or-output-id - zone_id or output_id string
  control-action    - :play, :pause, :playpause, :stop, :previous, :next"
  [connection zone-or-output-id control-action]
  (conn/request! connection (control zone-or-output-id control-action)))

(defn change-volume!
  "Changes output volume. Returns a promise.

  output-id - output_id string
  how       - :absolute or :relative or :relative_step
  value     - volume value (0-100 for absolute, delta for relative)"
  [connection output-id how value]
  (conn/request! connection (change-volume output-id how value)))

(defn mute!
  "Mutes or unmutes output. Returns a promise.

  output-id - output_id string
  how       - :mute or :unmute"
  [connection output-id how]
  (conn/request! connection (mute output-id how)))

(defn seek!
  "Seeks within current track. Returns a promise.

  zone-or-output-id - zone_id or output_id string
  how               - :absolute or :relative
  seconds           - position in seconds"
  [connection zone-or-output-id how seconds]
  (conn/request! connection (seek zone-or-output-id how seconds)))

(defn pause-all!
  "Pauses all zones. Returns a promise."
  [connection]
  (conn/request! connection (pause-all)))

(defn mute-all!
  "Mutes or unmutes all zones. Returns a promise.

  how - :mute or :unmute"
  [connection how]
  (conn/request! connection (mute-all how)))

(defn standby!
  "Puts output into standby. Returns a promise.

  output-id   - output_id string
  control-key - optional source control key"
  ([connection output-id]
   (standby! connection output-id nil))
  ([connection output-id control-key]
   (conn/request! connection (standby output-id control-key))))

(defn toggle-standby!
  "Toggles standby state on output. Returns a promise.

  output-id   - output_id string
  control-key - source control key (required)"
  [connection output-id control-key]
  (conn/request! connection (toggle-standby output-id control-key)))

(defn convenience-switch!
  "Activates convenience switch on output. Returns a promise.

  output-id   - output_id string
  control-key - optional source control key"
  ([connection output-id]
   (convenience-switch! connection output-id nil))
  ([connection output-id control-key]
   (conn/request! connection (convenience-switch output-id control-key))))

(defn transfer-zone!
  "Transfers playback from one zone to another. Returns a promise.

  from-zone-or-output-id - source zone_id or output_id
  to-zone-or-output-id   - target zone_id or output_id"
  [connection from-zone-or-output-id to-zone-or-output-id]
  (conn/request! connection (transfer-zone from-zone-or-output-id to-zone-or-output-id)))

(defn group-outputs!
  "Groups outputs into a zone. Returns a promise.

  output-ids - vector of output_id strings to group"
  [connection output-ids]
  (conn/request! connection (group-outputs output-ids)))

(defn ungroup-outputs!
  "Removes outputs from their group. Returns a promise.

  output-ids - vector of output_id strings to ungroup"
  [connection output-ids]
  (conn/request! connection (ungroup-outputs output-ids)))

(defn change-settings!
  "Changes zone settings. Returns a promise.

  zone-or-output-id - zone_id or output_id string
  settings          - map of settings (loop, shuffle, auto_radio)"
  [connection zone-or-output-id settings]
  (conn/request! connection (change-settings zone-or-output-id settings)))

(defn play-from-here!
  "Starts playback from a queue item. Returns a promise.

  zone-or-output-id - zone_id or output_id string
  queue-item-id     - queue item ID (number)"
  [connection zone-or-output-id queue-item-id]
  (conn/request! connection (play-from-here zone-or-output-id queue-item-id)))

;;; Subscriptions (events flow to unified events-ch)

(defn subscribe-zones!
  "Subscribes to zone events. Returns nil.

  Events flow to connection's :events-ch:
    {::roon/event ::roon/zones-subscribed ::roon/data {\"zones\" [...]}}
    {::roon/event ::roon/zones-changed ::roon/data {\"zones_changed\" [...]}}
    {::roon/event ::roon/zones-added ::roon/data {\"zones_added\" [...]}}
    {::roon/event ::roon/zones-removed ::roon/data {\"zones_removed\" [...]}}
    {::roon/event ::roon/zones-seek-changed ::roon/data {\"zones_seek_changed\" [...]}}"
  [connection]
  (conn/subscribe! connection service "zones"))

(defn subscribe-outputs!
  "Subscribes to output events. Returns nil.

  Events flow to connection's :events-ch:
    {::roon/event ::roon/outputs-subscribed ::roon/data {\"outputs\" [...]}}
    {::roon/event ::roon/outputs-changed ::roon/data {\"outputs_changed\" [...]}}
    {::roon/event ::roon/outputs-added ::roon/data {\"outputs_added\" [...]}}
    {::roon/event ::roon/outputs-removed ::roon/data {\"outputs_removed\" [...]}}"
  [connection]
  (conn/subscribe! connection service "outputs"))

(defn subscribe-queue!
  "Subscribes to queue events for a zone. Returns nil.

  zone-or-output-id - zone_id or output_id string
  max-items         - maximum queue items to receive

  Events flow to connection's :events-ch:
    {::roon/event ::roon/queue-subscribed ::roon/data {\"items\" [...]}}
    {::roon/event ::roon/queue-changed ::roon/data {\"changes\" [...]}}"
  [connection zone-or-output-id max-items]
  (conn/subscribe! connection service "queue"
                   {"zone_or_output_id" zone-or-output-id
                    "max_item_count"    max-items}))
