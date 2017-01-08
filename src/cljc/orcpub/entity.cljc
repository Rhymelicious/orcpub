(ns orcpub.entity
  (:require [clojure.spec :as spec]
            [orcpub.modifiers :as modifiers]
            [orcpub.entity-spec :as es]
            [orcpub.dnd.e5.modifiers :as dnd5-mods]
            [orcpub.dnd.e5.character :as dnd5-char]
            [orcpub.template :as t]))

(spec/def ::key keyword?)
(spec/def ::option (spec/keys :req [::key]
                              :opt [::options]))
(spec/def ::option-vec (spec/+ ::option))
(spec/def ::options (spec/map-of keyword? (spec/or :single ::option
                                                   :multiple ::option-vec)))
(spec/def ::raw-entity (spec/keys :opt [::options]))

(spec/def ::flat-option (spec/keys :req [::t/path]
                                   :opt [::value]))
(spec/def ::flat-options (spec/+ ::flat-option))

(declare build-options-paths)

(defn build-option-paths [path option]
  (let [new-path (conj path (::key option))
        child-options (::options option)
        option-value (::value option)
        result (cond-> {::t/path new-path}
                 option-value (assoc ::value option-value))]
    (if (seq child-options)
      (conj (build-options-paths new-path child-options)
            result)
      result)))

(defn build-options-entry-value-paths [path value]
  (if (sequential? value)
    (map (partial build-option-paths path) value)
    [(build-option-paths path value)]))

(defn build-options-entry-paths [path [option-key value]]
  (let [new-path (conj path option-key)]
    (build-options-entry-value-paths new-path value)))

(defn build-options-paths [path options]
  #_{:pre [(spec/valid? ::options options)]}
  (map (partial build-options-entry-paths path) options))

(defn flatten-options [options]
  #_{:pre [(spec/valid? ::options options)]
   :post [(spec/valid? ::flat-options %)]}
  (flatten (build-options-paths [] options)))

(defn collect-modifiers [flat-options modifier-map]
  #_{:pre [(spec/valid? ::flat-options flat-options)
         (spec/valid? ::t/modifier-map modifier-map)]
   :post [(spec/valid? ::modifiers/modifiers %)]}
  (mapcat
   (fn [{path ::t/path
         option-value ::value
         :as option}]
     (let [modifiers (::t/modifiers (get-in modifier-map path))]
       (if option-value
         (map
          (fn [mod]
            (mod option-value))
          modifiers)
         modifiers)))
   flat-options))

(defn index-of-option [selection option-key]
  (first
   (keep-indexed
    (fn [i v]
      (if (= option-key (::key v))
        i))
    selection)))

(defn template-item-with-key [items item-key]
  (first
   (keep-indexed
    (fn [i s]
      (if (= (::t/key s) item-key)
        [i s]))
    items)))

(defn get-entity-path
  ([template option-path]
   (get-entity-path template [] option-path))
  ([template current-path [selection-k option-k & ks]]
   (if selection-k
     (let [[selection-i selection]
           (template-item-with-key (::t/selections template) selection-k)
           {:keys [::t/min ::t/max ::t/options]} selection
           [option-i option]
           (template-item-with-key options option-k)]
       (get-entity-path
        option
        (concat current-path
                [::options selection-k]
                (if (and option-k
                         (or (nil? max) (> max 1)))
                  (if (nat-int? option-k)
                    [option-k]
                    [option-i])))
        ks))
     (vec current-path))))

(defn apply-options [raw-entity template]
  (let [modifier-map (t/make-modifier-map template)
        options (flatten-options (::options raw-entity))
        modifiers (collect-modifiers options modifier-map)]
    (es/apply-modifiers (::t/base template) modifiers)
    #_(reduce
     (fn [current-entity modifier]
       (update-in current-entity
                  (let [path (::modifiers/path modifier)]
                    (if (keyword? path)
                      [path]
                      path))
                  (partial modifiers/modify modifier)))
     raw-entity
     modifiers)))

(defn apply-derived-values [raw-entity template]
  (reduce
   (fn [current-entity derived-value]
     (assoc-in
      current-entity
      (::t/path derived-value)
      ((::t/value-fn derived-value) current-entity)))
   raw-entity
   (::t/derived-values template)))

(defn build [raw-entity template]
  (-> (apply-options raw-entity template)
      #_(apply-derived-values template)))

(spec/fdef
 build
 :args (spec/cat :raw-entity ::raw-entity :modifier-map ::t/template)
 :ret any?)

(defn name-to-kw [name]
  (-> name
      clojure.string/lower-case
      (clojure.string/replace #"\W" "-")))

(defn selection [name options]
  {::t/name name
   ::t/key (name-to-kw name)
   ::t/options options})

(defn option [name & [selections modifiers]]
  (cond-> {::t/name name
           ::t/key (name-to-kw name)}
    selections (assoc ::t/selections selections)
    modifiers (assoc ::t/modifiers modifiers)))
