;; Copyright (c) 2013 The ClojureWerkz team and contributors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;       http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns clojurewerkz.meltdown.streams
  (:import [reactor.core.composable.spec Streams]
           [reactor.core.composable Stream Deferred]
           [reactor.core Environment]
           [reactor.function Function Predicate]
           [reactor.tuple Tuple2]
           clojure.lang.IFn)
  (:require [clojurewerkz.meltdown.consumers :as mc]))

(def dispatcher-types
  {:event-loop "eventLoop"
   :thread-pool "threadPoolExecutor"
   :ring-buffer "ringBuffer"})

(defn ^Function fn->function
  "Instantiates a reactor consumer from a Clojure
   function"
  [^IFn f]
  (reify Function
    (apply [this a]
      (f a))))

(defn ^Predicate fn->predicate
  "Instantiates a reactor consumer from a Clojure
   function"
  [^IFn f]
  (proxy [Predicate] []
    (test [a]
      (f a))))

(defn environment
  []
  (Environment.))

(defn ^Deferred create
  "Creates a processing channel"
  [& {:keys [dispatcher-type dispatcher values batch-size env]}]
  (let [spec (Streams/defer)]
    (if env
      (.env spec env)
      (.env spec (environment)))
    (if dispatcher-type
      (.dispatcher spec (dispatcher-type dispatcher-types))
      (.synchronousDispatcher spec))
    (when dispatcher
      (.dispatcher spec dispatcher))
    (when values
      (.each spec values))
    (when batch-size
      (.batchSize spec batch-size))
    (.get spec)))

(defn accept
  [^Stream stream value]
  (.accept stream value))

(defn- maybe-compose
  [deferred-or-stream]
  (if (instance? Deferred deferred-or-stream)
    (.compose deferred-or-stream)
    deferred-or-stream))

(defn map*
  "Defines a map function, that will apply `f` to all events going through it."
  [f deferred-or-stream]
  (.map (maybe-compose deferred-or-stream) (fn->function f)))

(defn filter*
  "Defines a filter function, that will apply predicate `f` to all events going through it
   and will stream only those for which predicate returned truthy value."
  [f deferred-or-stream]
  (.filter (maybe-compose deferred-or-stream) (fn->predicate f)))

(defn batch*
  "Defines a batch function that will accumulate values until it's reaches count of `i`, and
   streams the resulting array of values further down."
  [i deferred-or-stream]
  (map* #(into [] %)
        (.collect (.batch (maybe-compose deferred-or-stream) i))))

(defn reduce*
  "Defines an aggregator funciton that will apply previous aggregator value and new incoming event
   to function `f`, receives `default-value` with which aggregator is initialized."
  ([f default-value deferred-or-stream]
     (.reduce (maybe-compose deferred-or-stream) (fn->function
                                                   (fn [^Tuple2 tuple]
                                                     (let [value (.getT1 tuple)
                                                           acc (or (.getT2 tuple) default-value)]
                                                       (f acc value))))))
  ([f deferred-or-stream]
     (reduce* f nil deferred-or-stream)))

(defn consume
  "Defines a consumer for stream."
  [^Stream stream f]
  (.consume stream
            (mc/from-fn-raw f)))

(defn custom-stream
  [f deferred-or-stream]
  (let [downstream (create)]
    (consume (maybe-compose deferred-or-stream)
             #(f % downstream))
    (.compose downstream)))
