(ns ol.roon.services.ping
  "Ping service (com.roonlabs.ping:1).

  Always enabled. Responds to ping requests from Roon Core.")

(set! *warn-on-reflection* true)

(def ^:const service-name
  "The Roon service name for ping."
  "com.roonlabs.ping:1")

(defn make-service-spec
  "Creates ping service spec."
  []
  {:name    service-name
   :methods {"ping" (fn [_core _body]
                      {:verb :complete :name "Success" :body nil})}})
