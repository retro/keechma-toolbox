(ns keechma.toolbox.forms.controller
  (:require [keechma.toolbox.pipeline.controller :as pp-controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline->]]
            [keechma.toolbox.forms.core :as forms-core]
            [keechma.toolbox.forms.helpers :as forms-helpers]
            [forms.dirty :refer [calculate-dirty-fields]]
            [forms.core :as keechma-forms-core]
            [promesa.core :as p]))

(defn get-form-record [forms-config [form-type _]]
  (get forms-config form-type))

(defn get-form-state [app-db form-props]
  (get-in app-db [:kv forms-core/id-key :states form-props]))

(defn get-forms [app-db]
  (or (get-in app-db [:kv forms-core/id-key]) {:order [] :states {}}))

(defn add-form-to-app-db [app-db form-props form-state]
  (let [forms (get-forms app-db)
        forms-order (vec (filter #(not= form-props %1) (:order forms)))
        forms-states (:states forms)]
    (assoc-in app-db [:kv forms-core/id-key]
              (assoc forms
                     :states (assoc forms-states form-props form-state)
                     :order (conj forms-order form-props)))))

(defn get-initial-state [app-db forms-config value]
  (let [form-props (:form-props value)
        form-record (get-form-record forms-config form-props)]
    (->> (p/promise (forms-core/get-data form-record app-db form-props))
         (p/map #(assoc value :initial-data %1)))))

(defn premount-form [app-db {:keys [form-props]}]
  (add-form-to-app-db app-db form-props
                      {:submitted? false
                       :dirty-paths (set {})
                       :cached-dirty-paths (set {})
                       :data {}
                       :initial-data {}
                       :errors {}
                       :status {:type :mounting}}))

(defn mount-form [app-db forms-config {:keys [form-props initial-data]}]
  (let [form-record (get-form-record forms-config form-props)
        processed-data (forms-core/process-in form-record app-db form-props initial-data)
        form-state (get-form-state app-db form-props)]
    (assoc-in app-db [:kv forms-core/id-key :states form-props]
              (assoc form-state
                     :initial-data processed-data
                     :data processed-data
                     :status {:type :mounted}))))

(defn mount-failed [app-db forms-config error {:keys [form-props]}]
  (let [form-state (get-form-state app-db form-props)]
    (assoc-in app-db [:kv forms-core/id-key :states form-props :status]
              {:type :mount-failed
               :cause (:payload error)})))

(defn mark-dirty-and-validate
  ([form-record form-state] (mark-dirty-and-validate form-record form-state true))
  ([form-record form-state dirty-only?]
   (if dirty-only?
     (let [errors (forms-core/validate form-record (:data form-state))
           dirty-paths (calculate-dirty-fields (:initial-data form-state) (:data form-state))]
       (assoc form-state
              :errors errors
              :dirty-paths (set dirty-paths)))
     (let [errors (forms-core/validate form-record (:data form-state))
           cached-dirty-paths (:cached-dirty-paths form-state)
           dirty-paths (keechma-forms-core/errors-keypaths errors)]
       (assoc form-state
              :errors errors
              :dirty-paths dirty-paths
              :cached-dirty-paths (set (concat cached-dirty-paths dirty-paths)))))))

(defn handle-on-change [app-db forms-config [form-props path element value]]
  (let [form-state (get-form-state app-db form-props)
        form-record (get-form-record forms-config form-props)
        old-value (forms-helpers/attr-get-in form-state path)
        formatter (or (forms-core/format-attr-with form-record app-db form-props form-state path value)
                      identity)
        formatted-value (formatter value old-value)
        processor (forms-core/process-attr-with form-record app-db form-props form-state path formatted-value)
        new-state (if processor
                    (processor app-db form-props form-state path formatted-value)
                    (forms-helpers/attr-assoc-in form-state path formatted-value))
        attr-valid? (forms-helpers/attr-valid? form-state path)]
    (assoc-in app-db [:kv forms-core/id-key :states form-props]
              (if attr-valid?
                new-state
                (mark-dirty-and-validate form-record new-state)))))

(defn handle-on-blur [app-db forms-config [form-props path]]
  (let [form-state (get-form-state app-db form-props)
        form-record (get-form-record forms-config form-props)]
    (assoc-in app-db [:kv forms-core/id-key :states form-props]
              (mark-dirty-and-validate form-record form-state))))

(defn actions [forms-config]
  {:mount-form (pipeline->
                (begin [_ value app-db]
                       {:form-props value}
                       (pp/commit! (premount-form app-db value))
                       (get-initial-state app-db forms-config value)
                       (pp/commit! (mount-form app-db forms-config value)))
                (rescue [_ error value app-db]
                        (pp/commit! (mount-failed app-db forms-config error value))))
   :on-change (pipeline->
               (begin [_ value app-db]
                      (pp/commit! (handle-on-change app-db forms-config value))))
   :on-blur (pipeline->
             (begin [_ value app-db]
                    (pp/commit! (handle-on-blur app-db forms-config value))))})


(defn make-controller [forms-config]
  (pp-controller/constructor (fn [] true) (actions forms-config)))

(defn register
  ([forms-config] (register {} forms-config))
  ([controllers forms-config]
   (assoc controllers forms-core/id-key (make-controller forms-config))))
