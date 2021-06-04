(ns bluejdbc.instance
  (:require [bluejdbc.connectable.current :as conn.current]
            [bluejdbc.instance :as instance]
            [bluejdbc.row :as row]
            [bluejdbc.util :as u]
            [camel-snake-kebab.core :as csk]
            [clojure.data :as data]
            [methodical.core :as m]
            [methodical.impl.combo.threaded :as m.combo.threaded]
            [potemkin :as p]
            [pretty.core :as pretty]))

(defn normalize-key [k]
  (when k
    (if (and (keyword? k) (namespace k))
      (keyword (csk/->kebab-case (namespace k)) (csk/->kebab-case (name k)))
      (keyword (csk/->kebab-case k)))))

(defn normalize-map [key-xform m]
  {:pre [(fn? key-xform) (map? m)]}
  (into (empty m)
        (map (fn [[k v]]
               [(key-xform k) v]))
        m))

(m/defmulti key-transform-fn*
  {:arglists '([connectable tableable])}
  u/dispatch-on-first-two-args)

(m/defmethod key-transform-fn* :default
  [_ _]
  ;; TODO -- should this value come from a dynamic variable? You could always implement that behavior yourself if you
  ;; wanted to.
  normalize-key)

(p/defprotocol+ IInstance
  :extend-via-metadata true
  (original [instance]
    "Get the original version of `instance` as it appeared when it first came out of the DB.")

  (with-original [instance new-original]
    "Return a copy of `instance` with its `original` map set to `new-original`.")

  (current [instance]
    "Return the underlying map representing the current state of an `instance`.")

  (with-current [instance new-current]
    "Return a copy of `instance` with its underlying `current` map set to `new-current`.")

  (changes [instance]
    "Get a map with any changes made to `instance` since it came out of the DB.")

  ;; TODO -- these should probably be renamed to `tableable` and `with-tableable` respectively.
  (tableable [instance]
    ("Get the tableable associated with `instance`."))

  (with-tableable [instance new-tableable]
    "Return a copy of `instance` with its tableable set to `new-tableable.`")

  (connectable [instance]
    "Get the connectable associated with `instance`.")

  (with-connectable [instance new-connectable]
    "Return a copy of `instance` with its connectable set to `new-connectable.`"))

(defn bluejdbc-instance?
  "True if `x` is a BlueJDBC instance, i.e. a `bluejdbc.instance.Instance` or some other class that satisfies
  `bluejdbc.instance.IInstance`."
  [x]
  (clojure.core/instance? bluejdbc.instance.IInstance x))

(declare ->TransientInstance)

(p/def-map-type Instance [conn tbl ^clojure.lang.IPersistentMap orig ^clojure.lang.IPersistentMap m key-xform mta]
  (get [_ k default-value]
    (get m (key-xform k) default-value))

  (assoc [this k v]
    (let [new-m (assoc m (key-xform k) v)]
      (if (identical? m new-m)
        this
        (Instance. conn tbl orig new-m key-xform mta))))

  (dissoc [this k]
    (let [new-m (dissoc m (key-xform k))]
      (if (identical? m new-m)
        this
        (Instance. conn tbl orig new-m key-xform mta))))

  (keys [_]
    (keys m))

  (meta [_]
    mta)

  (with-meta [this new-meta]
    (if (identical? mta new-meta)
      this
      (Instance. conn tbl orig m key-xform new-meta)))

  clojure.lang.IPersistentCollection
  (equiv [_ x]
    (if (bluejdbc-instance? x)
      ;; TODO -- not sure if two instances with different connectables should be considered different. I guess not
      ;; because it makes them inconvenient to use in tests and stuff
      ;;
      ;; TODO -- should a instance be considered equal to a plain map with non-normalized keys? e.g.
      ;;    (= (instance {:updated-at 100}) {:updated_at 100})
      ;;
      ;; I was leaning towards yes but I'm not sure how to make it work in both directions.
      (and #_(= conn (connectable x))
           (= tbl (tableable x))
           (= m   x))
      (and (map? x)
           (= m (normalize-map key-xform x)))))

  java.util.Map
  (containsKey [_ k]
    (.containsKey m (key-xform k)))

  clojure.lang.IEditableCollection
  (asTransient [_]
    (->TransientInstance conn tbl (transient m) key-xform mta))

  IInstance
  (original [_]
    orig)

  (with-original [this new-original]
    (if (identical? orig new-original)
      this
      (Instance. conn tbl new-original m key-xform mta)))

  (current [_]
    m)

  (with-current [this new-current]
    (if (identical? m new-current)
      this
      (Instance. conn tbl orig new-current key-xform mta)))

  (changes [_]
    (second (data/diff orig m)))

  (tableable [_]
    tbl)

  (with-tableable [this new-tableable]
    (if (identical? tbl new-tableable)
      this
      (Instance. conn new-tableable orig m key-xform mta)))

  (connectable [_]
    conn)

  (with-connectable [this new-connectable]
    (if (identical? conn new-connectable)
      this
      (Instance. new-connectable tbl orig m key-xform mta)))

  row/RealizeRow
  (realize-row [_]
    (if (identical? orig m)
      (let [m (row/realize-row m)]
        (Instance. conn tbl m m key-xform mta))
      (Instance. conn tbl (row/realize-row orig) (row/realize-row m) key-xform mta)))

  pretty/PrettyPrintable
  (pretty [_]
    ;; TODO -- only print connectable if enabled?
    (list (u/qualify-symbol-for-*ns* `instance) #_conn tbl m)))

(deftype ^:private TransientInstance [conn tbl ^clojure.lang.ITransientMap m key-xform mta]
  clojure.lang.ITransientMap
  (conj [this v]
    (.conj m v)
    this)
  (persistent [_]
    (let [m (persistent! m)]
      (Instance. conn tbl m m key-xform mta)))
  (assoc [this k v]
    (.assoc m (key-xform k) v)
    this)
  (without [this k]
    (.without m (key-xform k))
    this))

(m/defmulti instance*
  {:arglists '([connectable tableable original-map current-map key-xform metta])}
  u/dispatch-on-first-two-args
  :combo (m.combo.threaded/threading-method-combination :fourth))

(m/defmethod instance* :default
  [connectable tableable original current key-xform metta]
  ;; TODO -- not sure if unwrapping this stuff makes sense or not... it makes sense for (instance (->LegacyModel ...))
  ;; tho
  (->Instance (u/dispatch-value connectable) (u/dispatch-value tableable) original current key-xform metta))

(defn instance
  (^bluejdbc.instance.Instance [tableable]
   (instance (conn.current/current-connectable tableable) tableable {}))

  (^bluejdbc.instance.Instance [tableable m]
   (instance (conn.current/current-connectable tableable) tableable m))

  (^bluejdbc.instance.Instance [connectable tableable m]
   (let [key-xform     (key-transform-fn* connectable tableable)
         m             (normalize-map key-xform m)
         [connectable] (conn.current/ensure-connectable connectable tableable nil)]
     (instance* connectable tableable m m key-xform (meta m))))

  (^bluejdbc.instance.Instance [connectable tableable k v & more]
   (let [m (into {} (partition-all 2) (list* k v more))]
     (instance connectable tableable m))))

(extend-protocol IInstance
  nil
  (tableable [_]
    nil)
  (original [_]
    nil)
  (current [_]
    nil)
  (changes [_]
    nil)
  (with-tableable [_ new-table]
    (instance new-table))
  (connectable [_]
    nil)
  (with-connectable [this _]
    (instance connectable nil nil))

  ;; TODO -- not sure what the correct behavior for objects should be... if I call (original 1) I think that should
  ;; throw an Exception rather than returning nil, because that makes no sense.
  ;;
  Object
  (tableable [_]
    nil)
  ;; (original [_]
  ;;   nil)
  ;; (current [_]
  ;;   nil)
  ;; (changes [_]
  ;;   nil)
  (connectable [_]
    nil)

  ;; generally just treat a plain map like an instance with nil tableable/connectable and original = nil,
  ;; and no-op for anything that would require "upgrading" the map to an actual instance in such a way that if
  ;;
  ;;    (= plain-map instance)
  ;;
  ;; then
  ;;
  ;;    (= (f plain-map) (f instance))
  clojure.lang.IPersistentMap
  (tableable [_]
    nil)
  (with-tableable [m new-tableable]
    (instance tableable m))
  (original [_]
    nil)
  (current [m]
    m)
  (with-current [_ new-current]
    new-current)
  (with-original [m _]
    m)
  (changes [_]
    nil)
  (connectable [_]
    nil)
  (with-connectable [_ m]
    (instance connectable nil m)))

(defn reset-original
  "Return a copy of `instance` with its `original` value set to its current value, discarding the previous original
  value. No-ops if `instance` is not a Blue JDBC instance."
  [instance]
  (if (bluejdbc-instance? instance)
    (with-original instance (current instance))
    instance))

;; TODO -- should we have a revert-changes helper function as well?

(defn update-original
  "Applies `f` directly to the underlying `original` map of an `instance`. No-ops if `instance` is not an `Instance`."
  [instance f & args]
  (if (bluejdbc-instance? instance)
    (with-original instance (apply f (original instance) args))
    instance))

(defn update-current
  "Applies `f` directly to the underlying `current` map of an `instance`; useful if you need to operate on it directly.
  Acts like regular `(apply f instance args)` if `instance` is not an `Instance`."
  [instance f & args]
  (with-current instance (apply f (current instance) args)))

(defn update-original-and-current
  "Like `(apply f instance args)`, but affects both the `original` map and `current` map of `instance` rather than just
  the current map. Acts like regular `(apply f instance args)` if `instance` is not an `Instance`.

  `f` is applied directly to the underlying `original` and `current` maps of `instance` itself. `f` is only applied
  once if `original` and `current` are currently the same object (i.e., the new `original` and `current` will also be
  the same object). If `current` and `original` are not the same object, `f` is applied twice."
  [instance f & args]
  (if (identical? (original instance) (current instance))
    (reset-original (apply update-current instance f args))
    (as-> instance instance
      (apply update-original instance f args)
      (apply update-current  instance f args))))
