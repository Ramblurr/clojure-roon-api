(ns ol.roon.services.browse
  "Browse service API for navigating Roon's content hierarchy.

  This is a consumed service (provided by Roon Core).

  API Pattern:
  - Data builders (no ! suffix) return request maps: {:uri ... :body ...}
  - Action functions (! suffix) send request and return a promise"
  (:refer-clojure :exclude [load])
  (:require [ol.roon.connection :as conn]))

(set! *warn-on-reflection* true)

(def ^:const service "com.roonlabs.browse:1")
(def ^:const default-hierarchy "browse")

;;; Request map builders

(defn browse
  "Returns request map for browse navigation.

  Options:
  | key                 | description                                    |
  |---------------------|------------------------------------------------|
  | :multi-session-key  | Session key for multi-session browsing         |
  | :item-key           | Navigate into this item                        |
  | :input              | User input for search/input prompts            |
  | :zone-or-output-id  | Context zone for playback actions              |
  | :pop-all            | Return to root level                           |
  | :pop-levels         | Pop N levels from current position             |
  | :refresh-list       | Refresh current list                           |
  | :set-display-offset | Set display offset for pagination              |"
  ([] (browse {}))
  ([opts]
   {:uri  (str service "/browse")
    :body (cond-> {"hierarchy" default-hierarchy}
            (:multi-session-key opts)  (assoc "multi_session_key" (:multi-session-key opts))
            (:item-key opts)           (assoc "item_key" (:item-key opts))
            (:input opts)              (assoc "input" (:input opts))
            (:zone-or-output-id opts)  (assoc "zone_or_output_id" (:zone-or-output-id opts))
            (:pop-all opts)            (assoc "pop_all" true)
            (:pop-levels opts)         (assoc "pop_levels" (:pop-levels opts))
            (:refresh-list opts)       (assoc "refresh_list" true)
            (:set-display-offset opts) (assoc "set_display_offset" (:set-display-offset opts)))}))

(defn load
  "Returns request map for loading items at current level.

  Options:
  | key                 | description                                    |
  |---------------------|------------------------------------------------|
  | :multi-session-key  | Session key for multi-session browsing         |
  | :level              | Level to load from (optional)                  |
  | :offset             | Starting offset (default 0)                    |
  | :count              | Number of items to load (default 100)          |
  | :set-display-offset | Set display offset for pagination              |"
  ([] (load {}))
  ([opts]
   {:uri  (str service "/load")
    :body (cond-> {"hierarchy" default-hierarchy
                   "offset"    (or (:offset opts) 0)
                   "count"     (or (:count opts) 100)}
            (:multi-session-key opts)  (assoc "multi_session_key" (:multi-session-key opts))
            (:level opts)              (assoc "level" (:level opts))
            (:set-display-offset opts) (assoc "set_display_offset" (:set-display-offset opts)))}))

;;; Action functions

(defn browse!
  "Navigates browse hierarchy. Returns promise with BrowseResponse.

  See [[browse]] for options."
  ([connection] (browse! connection {}))
  ([connection opts] (conn/request! connection (browse opts))))

(defn load!
  "Loads items at current level. Returns promise with LoadResponse.

  See [[load]] for options."
  ([connection] (load! connection {}))
  ([connection opts] (conn/request! connection (load opts))))
