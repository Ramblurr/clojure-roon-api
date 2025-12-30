(ns ol.roon.services.settings
  "Settings service (com.roonlabs.settings:1).

  Provides extension configuration UI in Roon.
  This is a provided service - the extension provides it to Roon Core.

  Usage:
  ```clojure
  (def my-settings (atom {:volume 50}))

  (def settings-svc
    (settings/make-service
      (fn [{:keys [values dry-run?]}]
        (let [v (or values @my-settings)
              errors (cond-> {}
                       (> (:volume v) 100) (assoc :volume \"Too high\"))
              valid? (empty? errors)]
          ;; Save when valid and not dry-run
          (when (and values (not dry-run?) valid?)
            (reset! my-settings v))
          ;; Return layout
          {\"values\"    v
           \"layout\"    [{\"type\" \"integer\"
                         \"title\" \"Volume\"
                         \"min\" 0 \"max\" 100
                         \"setting\" \"volume\"
                         \"error\" (:volume errors)}]
           \"has_error\" (not valid?)}))))

  (roon/connect! {:host \"...\" :provided-services [settings-svc]})
  ```")

(set! *warn-on-reflection* true)

(def ^:const service-name
  "The Roon service name for settings."
  "com.roonlabs.settings:1")

(defn make-service
  "Creates a settings service instance.

  Arguments:
  - layout-fn: fn({:keys [values dry-run?]}) -> {\"values\" map \"layout\" vec \"has_error\" bool}
    Called with {:values nil :dry-run? false} to get current layout.
    Called with {:values map :dry-run? true} to validate without saving.
    Called with {:values map :dry-run? false} to validate and save.
    User is responsible for saving when values are valid and dry-run? is false.

  Returns a service instance to pass to :provided-services."
  [layout-fn]
  {:name                                                                                    service-name
   :spec
   {:name                                                                      service-name
    :methods
    {"get_settings"
     (fn [_core _body]
       (let [layout (layout-fn {:values nil :dry-run? false})]
         {:verb :complete
          :name "Success"
          :body {"settings" layout}}))

     "save_settings"
     (fn [_core body]
       (let [dry-run? (get body "is_dry_run" false)
             values   (get-in body ["settings" "values"])
             layout   (layout-fn {:values values :dry-run? dry-run?})]
         {:verb      :complete
          :name      (if (get layout "has_error") "NotValid" "Success")
          :body      {"settings" layout}
          :broadcast (when (and (not dry-run?) (not (get layout "has_error")))
                       "subscribe_settings")}))}

    :subscriptions
    {"subscribe_settings"
     {:start (fn [_core _body]
               (let [layout (layout-fn {:values nil :dry-run? false})]
                 {:verb :continue
                  :name "Subscribed"
                  :body {"settings" layout}}))
      :end   nil}}}})
