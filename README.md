# clojure-roon-api

A pure Clojure port of the RoonLabs [node-roon-api](https://github.com/RoonLabs/node-roon-api) for interfacing with Roon (www.roonlabs.com).

## Current Status

This library is in active development and implements the core functionality needed for most Roon extensions.

The API may change as the library matures.

The following services are implemented:

| Node.js Module               | Status          | Feature                              |
|------------------------------|-----------------|--------------------------------------|
| node-roon-api                | Implemented     | Core selection in multi-core setups  |
| node-roon-api                | Implemented     | Keep-alive ping service              |
| node-roon-api-status         | Implemented     | Extension status display in Roon UI  |
| node-roon-api-settings       | Implemented     | Extension settings UI in Roon        |
| node-roon-api-transport      | Implemented     | Playback, zones, volume, queue       |
| node-roon-api-browse         | Implemented     | Library navigation and search        |
| node-roon-api-image          | Implemented     | Album art and image fetching         |
| node-roon-api-volume-control | Not Implemented | External device volume control       |
| node-roon-api-source-control | Not Implemented | External device input and standby    |

Requires Java 21+ for virtual threads.

## Installation

This library is not yet on Clojars.

Add it to your `deps.edn` as a git dependency:

```clojure
{:deps {com.github.ramblurr/clojure-roon-api
        {:git/url "https://github.com/Ramblurr/clojure-roon-api"
         :git/sha "REPLACE_WITH_CURRENT_SHA"}}}
```


## Usage

```clojure
(require '[ol.roon.api :as roon]
         '[ol.roon.services.transport :as transport]
         '[ol.roon.services.browse :as browse])

;; Optional: discover Roon Cores on the network
(doseq [core (roon/discover!)]
  (println "Found:" (:name core) "at" (:host core) ":" (:port core)))

;; Or get just the first one
(def core (roon/discover-one!))

;; Connect to a Roon Core (use discovered host or specify directly)
(def result @(roon/connect! {:host            (:host core) ; or "10.0.0.100"
                             :port            (:port core) ; optional, default 9330
                             :extension-id    "com.example.myapp"
                             :display-name    "My Roon App"
                             :display-version "1.0.0"
                             :publisher       "Example Publisher"
                             :email           "contact@example.com"}))

;; On first run, approve the extension in Roon Settings > Extensions
(def conn (:conn result))

;; List zones
(doseq [zone (get @(transport/get-zones! conn) "zones")]
  (println "Zone:" (get zone "display_name")))

;; Control playback
(let [zone-id (-> @(transport/get-zones! conn)
                  (get "zones")
                  first
                  (get "zone_id"))]
  @(transport/control! conn zone-id :play))

;; Browse the library
(let [browse-result @(browse/browse! conn)
      load-result   @(browse/load! conn {:count 10})]
  (doseq [item (get load-result "items")]
    (println " -" (get item "title"))))

;; Disconnect when done
(roon/disconnect! result)
```


### Token Persistence

Once authorized, save the token to stay connected across restarts:

```clojure
(require '[ol.roon.api :as roon])

;; After connecting, extract state for persistence
(let [state (roon/extract-state result)]
  (spit "roon-state.edn" (roon/state->edn state)))

;; On next run, load and apply saved state
(let [saved-state (roon/edn->state (slurp "roon-state.edn"))
      core-id     (:paired-core-id saved-state)
      config      (roon/apply-state {:host            "10.0.0.100"
                                     :extension-id    "com.example.myapp"
                                     :display-name    "My Roon App"
                                     :display-version "1.0.0"
                                     :publisher       "Example Publisher"
                                     :email           "contact@example.com"}
                                    saved-state
                                    core-id)]
  @(roon/connect! config))
```


### Subscriptions

Subscribe to zone changes for real-time updates:

```clojure
(require '[ol.roon.api :as roon]
         '[ol.roon.schema :as schema]
         '[ol.roon.services.transport :as transport]
         '[clojure.core.async :refer [go-loop <!]])

(let [result @(roon/connect! config)
      conn   (:conn result)
      events (:events result)]

  ;; Start subscription
  (transport/subscribe-zones! conn)

  ;; Handle events
  (go-loop []
    (when-let [event (<! events)]
      (case (::schema/event event)
        ::schema/zones-subscribed
        (println "Initial zones:" (count (get (::schema/data event) "zones")))

        ::schema/zones-changed
        (println "Zones updated")

        nil)
      (recur))))
```


## Other Roon API Libraries

This library is a port of the official [node-roon-api](https://github.com/RoonLabs/node-roon-api) (JavaScript).

Other community Roon API implementations:

- [pyroon](https://github.com/pavoni/pyroon) (Python)
- [rust-roon-api](https://github.com/TheAppgineer/rust-roon-api) (Rust)
- [roon-api-java](https://gitlab.com/dottydingo/projects/roon/roon-api-java) (Java)


## Legal

This library is not officially commissioned or supported by Roon Labs.
The trademark "ROON" is registered by Roon Labs LLC.
This project is a community endeavor.

Copyright 2025 Casey Link

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
