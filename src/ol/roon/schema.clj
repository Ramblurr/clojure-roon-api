(ns ol.roon.schema
  "Malli schemas for Roon API types and events.

  These schemas are designed to be permissive - they validate the structure
  but allow unknown fields and enum values since Roon may add new fields
  in future versions.

  Events are emitted to the events channel with shape:
    {::event ::zones-changed
     ::data  {...payload...}}

  Use `[ol.roon.schema :as roon]` then `::roon/event` and `::roon/zones-changed`.")

(set! *warn-on-reflection* true)

;;; Enums (open - allow unknown values to future-proof)

(def State
  "Zone playback state. Open enum to allow future values."
  :string)

(def Loop
  "Loop mode. Open enum to allow future values."
  :string)

(def ControlStatus
  "Source control status. Open enum."
  :string)

;;; Nested Types

(def Volume
  "Output volume settings."
  [:map
   ["type" :string]
   ["min" {:optional true} number?]
   ["max" {:optional true} number?]
   ["value" {:optional true} [:maybe number?]]
   ["step" {:optional true} number?]
   ["is_muted" {:optional true} boolean?]
   ["hard_limit_min" {:optional true} number?]
   ["hard_limit_max" {:optional true} number?]
   ["soft_limit" {:optional true} number?]])

(def DisplayLine
  "Single-line display info."
  [:map
   ["line1" {:optional true} [:maybe :string]]])

(def TwoLine
  "Two-line display info."
  [:map
   ["line1" {:optional true} [:maybe :string]]
   ["line2" {:optional true} [:maybe :string]]])

(def ThreeLine
  "Three-line display info."
  [:map
   ["line1" {:optional true} [:maybe :string]]
   ["line2" {:optional true} [:maybe :string]]
   ["line3" {:optional true} [:maybe :string]]])

(def NowPlaying
  "Current track info for a zone."
  [:map
   ["seek_position" {:optional true} [:maybe number?]]
   ["length" {:optional true} [:maybe number?]]
   ["image_key" {:optional true} [:maybe :string]]
   ["one_line" {:optional true} DisplayLine]
   ["two_line" {:optional true} TwoLine]
   ["three_line" {:optional true} ThreeLine]])

(def Settings
  "Zone playback settings."
  [:map
   ["loop" {:optional true} Loop]
   ["shuffle" {:optional true} boolean?]
   ["auto_radio" {:optional true} boolean?]])

(def SourceControl
  "Source control for an output."
  [:map
   ["control_key" :string]
   ["display_name" {:optional true} :string]
   ["supports_standby" {:optional true} boolean?]
   ["status" {:optional true} ControlStatus]])

;;; Main Types

(def Output
  "Audio output device."
  [:map
   ["output_id" :string]
   ["zone_id" {:optional true} :string]
   ["display_name" {:optional true} :string]
   ["can_group_with_output_ids" {:optional true} [:vector :string]]
   ["volume" {:optional true} Volume]
   ["source_controls" {:optional true} [:vector SourceControl]]])

(def Zone
  "Playback zone (one or more grouped outputs)."
  [:map
   ["zone_id" :string]
   ["display_name" :string]
   ["outputs" {:optional true} [:vector Output]]
   ["state" {:optional true} State]
   ["is_next_allowed" {:optional true} boolean?]
   ["is_previous_allowed" {:optional true} boolean?]
   ["is_pause_allowed" {:optional true} boolean?]
   ["is_play_allowed" {:optional true} boolean?]
   ["is_seek_allowed" {:optional true} boolean?]
   ["queue_items_remaining" {:optional true} number?]
   ["queue_time_remaining" {:optional true} number?]
   ["settings" {:optional true} Settings]
   ["now_playing" {:optional true} NowPlaying]])

;;; Response Schemas

(def ZonesResponse
  "Response from get_zones."
  [:map
   ["zones" [:vector Zone]]])

(def OutputsResponse
  "Response from get_outputs."
  [:map
   ["outputs" [:vector Output]]])

;;; Discovery Types

(def Core
  "Discovered Roon Core from SOOD."
  [:map
   [:unique-id :string]
   [:host :string]
   [:port :int]
   [:name {:optional true} [:maybe :string]]
   [:version {:optional true} [:maybe :string]]])

;;; Persistence Types

(def RoonState
  "Persisted connection state."
  [:map
   [::tokens [:map-of :string :string]]
   [::paired-core-id {:optional true} [:maybe :string]]])

;;; Queue Types

(def QueueItem
  "Item in the playback queue."
  [:map
   ["queue_item_id" number?]
   ["length" {:optional true} number?]
   ["image_key" {:optional true} [:maybe :string]]
   ["one_line" {:optional true} DisplayLine]
   ["two_line" {:optional true} TwoLine]
   ["three_line" {:optional true} ThreeLine]])

(def QueueOperation
  "Queue change operation type. Open enum."
  :string)

(def QueueChange
  "A change to the playback queue."
  [:map
   ["operation" QueueOperation]
   ["index" number?]
   ["items" {:optional true} [:vector QueueItem]]
   ["count" {:optional true} number?]])

;;; Zone Seek

(def ZoneSeek
  "Seek position update for a zone."
  [:map
   ["zone_id" :string]
   ["queue_time_remaining" {:optional true} number?]
   ["seek_position" {:optional true} number?]])

;;;; Event Types and Schemas

;;; Event Payload Schemas

(def RegisteredData
  "Payload for ::registered event."
  [:map
   ["core_id" :string]
   ["display_name" :string]
   ["display_version" {:optional true} :string]])

(def DisconnectedData
  "Payload for ::disconnected event."
  [:map
   [:reason :string]
   [:code {:optional true} [:maybe :int]]])

(def ZonesSubscribedData
  "Payload for ::zones-subscribed event."
  [:map ["zones" [:vector Zone]]])

(def ZonesChangedData
  "Payload for ::zones-changed event."
  [:map ["zones_changed" [:vector Zone]]])

(def ZonesAddedData
  "Payload for ::zones-added event."
  [:map ["zones_added" [:vector Zone]]])

(def ZonesRemovedData
  "Payload for ::zones-removed event."
  [:map ["zones_removed" [:vector :string]]])

(def ZonesSeekChangedData
  "Payload for ::zones-seek-changed event."
  [:map ["zones_seek_changed" [:vector ZoneSeek]]])

(def OutputsSubscribedData
  "Payload for ::outputs-subscribed event."
  [:map ["outputs" [:vector Output]]])

(def OutputsChangedData
  "Payload for ::outputs-changed event."
  [:map ["outputs_changed" [:vector Output]]])

(def OutputsAddedData
  "Payload for ::outputs-added event."
  [:map ["outputs_added" [:vector Output]]])

(def OutputsRemovedData
  "Payload for ::outputs-removed event."
  [:map ["outputs_removed" [:vector :string]]])

(def QueueSubscribedData
  "Payload for ::queue-subscribed event."
  [:map ["items" [:vector QueueItem]]])

(def QueueChangedData
  "Payload for ::queue-changed event."
  [:map ["changes" [:vector QueueChange]]])

(def ReconnectingData
  "Payload for ::reconnecting event."
  [:map
   [:attempt :int]
   [:backoff-ms :int]])

(def ReconnectedData
  "Payload for ::reconnected event. Same shape as RegisteredData."
  RegisteredData)

;;; Pairing Event Payloads

(def CoreFoundData
  "Payload for ::core-found event."
  Core)

(def CoreLostData
  "Payload for ::core-lost event."
  [:map
   [:core-id :string]])

(def CorePairedData
  "Payload for ::core-paired event."
  [:map
   [:core-id :string]
   [:core-name {:optional true} [:maybe :string]]])

(def PairingChangedData
  "Payload for ::pairing-changed event (sent to pairing subscribers)."
  [:map
   ["paired_core_id" [:maybe :string]]])

;;; Event Registry

(def EventDataRegistry
  "Maps event type keywords to their payload Malli schemas.

  Use for validation or documentation generation."
  {::registered         RegisteredData
   ::disconnected       DisconnectedData
   ::reconnecting       ReconnectingData
   ::reconnected        ReconnectedData
   ::zones-subscribed   ZonesSubscribedData
   ::zones-changed      ZonesChangedData
   ::zones-added        ZonesAddedData
   ::zones-removed      ZonesRemovedData
   ::zones-seek-changed ZonesSeekChangedData
   ::outputs-subscribed OutputsSubscribedData
   ::outputs-changed    OutputsChangedData
   ::outputs-added      OutputsAddedData
   ::outputs-removed    OutputsRemovedData
   ::queue-subscribed   QueueSubscribedData
   ::queue-changed      QueueChangedData
   ;; Pairing events
   ::core-found         CoreFoundData
   ::core-lost          CoreLostData
   ::core-paired        CorePairedData
   ::pairing-changed    PairingChangedData})

(def EventType
  "Enum of all valid event types. Derived from EventDataRegistry."
  (into [:enum] (keys EventDataRegistry)))

(def Event
  "Schema for events emitted to the events channel."
  [:map
   [::event EventType]
   [::data :any]])
