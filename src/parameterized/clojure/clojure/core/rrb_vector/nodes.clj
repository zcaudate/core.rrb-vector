(ns clojure.core.rrb-vector.nodes
  (:require [clojure.core.rrb-vector.parameters :as p])
  (:import (clojure.core VecNode ArrayManager)
           (clojure.lang PersistentVector PersistentVector$Node)
           (java.util.concurrent.atomic AtomicReference)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true) ;; :warn-on-boxed

;;; array managers

(defmacro mk-am [t]
  (#'clojure.core/mk-am &env &form t))

(definline object [x] x)

(def ams
  (assoc @#'clojure.core/ams :object (mk-am object)))

(def object-am
  (ams :object))

;;; empty nodes

;; The checking-* functions for the parameterized version expect an
;; empty node with at most p/max-branches children.  The Clojure
;; versions have 32, unless one also parameterizes the Clojure code,
;; which I have not done.  I will instead try changing the definition
;; of these empty nodes to have the branch factor of the parameterized
;; version, so the checking-* functions will not give errors for them.
(def NOEDIT (java.util.concurrent.atomic.AtomicReference. nil))
(def empty-pv-node (PersistentVector$Node. NOEDIT (object-array p/max-branches)))

(def empty-gvec-node (VecNode. nil (object-array p/max-branches)))

;;; node managers

(definterface NodeManager
  (node [^java.util.concurrent.atomic.AtomicReference edit arr])
  (empty [])
  (array [node])
  (^java.util.concurrent.atomic.AtomicReference edit [node])
  (^boolean regular [node])
  (clone [^clojure.core.ArrayManager am ^int shift node]))

(def object-nm
  (reify NodeManager
    (node [_ edit arr]
      (PersistentVector$Node. edit arr))
    (empty [_]
      empty-pv-node)
    (array [_ node]
      (.-array ^PersistentVector$Node node))
    (edit [_ node]
      (.-edit ^PersistentVector$Node node))
    (regular [_ node]
      (not (== (alength ^objects (.-array ^PersistentVector$Node node)) (int p/non-regular-array-len))))
    (clone [_ am shift node]
      (PersistentVector$Node.
       (.-edit ^PersistentVector$Node node)
       (aclone ^objects (.-array ^PersistentVector$Node node))))))

(def primitive-nm
  (reify NodeManager
    (node [_ edit arr]
      (VecNode. edit arr))
    (empty [_]
      empty-gvec-node)
    (array [_ node]
      (.-arr ^VecNode node))
    (edit [_ node]
      (.-edit ^VecNode node))
    (regular [_ node]
      (not (== (alength ^objects (.-arr ^VecNode node)) (int p/non-regular-array-len))))
    (clone [_ am shift node]
      (if (zero? shift)
        (VecNode. (.-edit ^VecNode node)
                  (.aclone am (.-arr ^VecNode node)))
        (VecNode. (.-edit ^VecNode node)
                  (aclone ^objects (.-arr ^VecNode node)))))))

;;; ranges

(defmacro ranges [nm node]
  `(ints (aget ~(with-meta `(.array ~nm ~node) {:tag 'objects}) p/max-branches)))

(defn last-range [^NodeManager nm node]
  (let [rngs (ranges nm node)
        i    (unchecked-dec-int (aget rngs p/max-branches))]
    (aget rngs i)))

(defn regular-ranges [shift cnt]
  (let [step (bit-shift-left (int 1) (int shift))
        rngs (int-array p/non-regular-array-len)]
    (loop [i (int 0) r step]
      (if (< r cnt)
        (do (aset rngs i r)
            (recur (unchecked-inc-int i) (unchecked-add-int r step)))
        (do (aset rngs i (int cnt))
            (aset rngs p/max-branches (unchecked-inc-int i))
            rngs)))))

;;; root overflow

(defn overflow? [^NodeManager nm root shift cnt]
  (if (.regular nm root)
    (> (bit-shift-right (unchecked-inc-int (int cnt)) (int p/shift-increment))
       (bit-shift-left (int 1) (int shift)))
    (let [rngs (ranges nm root)
          slc  (aget rngs p/max-branches)]
      (and (== slc (int p/max-branches))
           (or (== (int shift) (int p/shift-increment))
               (recur nm
                      (aget ^objects (.array nm root) (unchecked-dec-int slc))
                      (unchecked-subtract-int (int shift) (int p/shift-increment))
                      (unchecked-add-int
                       (unchecked-subtract-int (aget rngs p/max-branches-minus-1) (aget rngs p/max-branches-minus-2))
                       (int p/max-branches))))))))

;;; find nil / 0

(defn index-of-0 ^long [arr]
  (let [arr (ints arr)]
    (loop [l 0 h p/max-branches-minus-1]
      (if (>= l (unchecked-dec h))
        (if (zero? (aget arr l))
          l
          (if (zero? (aget arr h))
            h
            p/max-branches))
        (let [mid (unchecked-add l (bit-shift-right (unchecked-subtract h l) 1))]
          (if (zero? (aget arr mid))
            (recur l mid)
            (recur (unchecked-inc-int mid) h)))))))

(defn index-of-nil ^long [arr]
  (loop [l 0 h p/max-branches-minus-1]
    (if (>= l (unchecked-dec h))
      (if (nil? (aget ^objects arr l))
        l
        (if (nil? (aget ^objects arr h))
          h
          p/max-branches))
      (let [mid (unchecked-add l (bit-shift-right (unchecked-subtract h l) 1))]
        (if (nil? (aget ^objects arr mid))
          (recur l mid)
          (recur (unchecked-inc-int mid) h))))))

;;; children

(defn first-child [^NodeManager nm node]
  (aget ^objects (.array nm node) 0))

(defn last-child [^NodeManager nm node]
  (let [arr (.array nm node)]
    (if (.regular nm node)
      (aget ^objects arr (dec (index-of-nil arr)))
      (aget ^objects arr (unchecked-dec-int (aget (ranges nm node) p/max-branches))))))

(defn remove-leftmost-child [^NodeManager nm shift parent]
  (let [arr (.array nm parent)]
    (if (nil? (aget ^objects arr 1))
      nil
      (let [regular? (.regular nm parent)
            new-arr  (object-array (if regular? p/max-branches p/non-regular-array-len))]
        (System/arraycopy arr 1 new-arr 0 p/max-branches-minus-1)
        (if-not regular?
          (let [rngs     (ranges nm parent)
                rng0     (aget rngs 0)
                new-rngs (int-array p/non-regular-array-len)
                lim      (aget rngs p/max-branches)]
            (System/arraycopy rngs 1 new-rngs 0 (dec lim))
            (loop [i 0]
              (when (< i lim)
                (aset new-rngs i (- (aget new-rngs i) rng0))
                (recur (inc i))))
            (aset new-rngs p/max-branches (dec (aget rngs p/max-branches)))
            (aset new-rngs (dec (aget rngs p/max-branches)) (int 0))
            (aset ^objects new-arr p/max-branches new-rngs)))
        (.node nm (.edit nm parent) new-arr)))))

(defn replace-leftmost-child [^NodeManager nm shift parent pcnt child d]
  (if (.regular nm parent)
    (let [step (bit-shift-left 1 shift)
          rng0 (- step d)
          ncnt (- pcnt d)
          li   (bit-and (bit-shift-right shift (dec pcnt)) p/branch-mask)
          arr      (.array nm parent)
          new-arr  (object-array p/non-regular-array-len)
          new-rngs (int-array p/non-regular-array-len)]
      (aset ^objects new-arr 0 child)
      (System/arraycopy arr 1 new-arr 1 li)
      (aset ^objects new-arr p/max-branches new-rngs)
      (aset new-rngs 0 (int rng0))
      (aset new-rngs li (int ncnt))
      (aset new-rngs p/max-branches (int (inc li)))
      (loop [i 1]
        (when (<= i li)
          (aset new-rngs i (+ (aget new-rngs (dec i)) step))
          (recur (inc i))))
      (.node nm nil new-arr))
    (let [new-arr  (aclone ^objects (.array nm parent))
          rngs     (ranges nm parent)
          new-rngs (int-array p/non-regular-array-len)
          li       (dec (aget rngs p/max-branches))]
      (aset new-rngs p/max-branches (aget rngs p/max-branches))
      (aset ^objects new-arr p/max-branches new-rngs)
      (aset ^objects new-arr 0 child)
      (loop [i 0]
        (when (<= i li)
          (aset new-rngs i (- (aget rngs i) (int d)))
          (recur (inc i))))
      (.node nm nil new-arr))))

(defn replace-rightmost-child [^NodeManager nm shift parent child d]
  (if (.regular nm parent)
    (let [arr (.array nm parent)
          i   (unchecked-dec (index-of-nil arr))]
      (if (.regular nm child)
        (let [new-arr (aclone ^objects arr)]
          (aset ^objects new-arr i child)
          (.node nm nil new-arr))
        (let [arr     (.array nm parent)
              new-arr (object-array p/non-regular-array-len)
              step    (bit-shift-left 1 shift)
              rngs    (int-array p/non-regular-array-len)]
          (aset rngs p/max-branches (inc i))
          (aset ^objects new-arr p/max-branches rngs)
          (System/arraycopy arr 0 new-arr 0 i)
          (aset ^objects new-arr i child)
          (loop [j 0 r step]
            (when (<= j i)
              (aset rngs j r)
              (recur (inc j) (+ r step))))
          (aset rngs i (int (last-range nm child)))
          (.node nm nil new-arr))))
    (let [rngs     (ranges nm parent)
          new-rngs (aclone rngs)
          i        (dec (aget rngs p/max-branches))
          new-arr  (aclone ^objects (.array nm parent))]
      (aset ^objects new-arr i child)
      (aset ^objects new-arr p/max-branches new-rngs)
      (aset new-rngs i (int (+ (aget rngs i) d)))
      (.node nm nil new-arr))))

;;; fold-tail

(defn new-path [^NodeManager nm ^ArrayManager am shift node]
  (let [reg? (== p/max-branches (.alength am (.array nm node)))
        len  (if reg? p/max-branches p/non-regular-array-len)
        arr  (object-array len)
        rngs (if-not reg?
               (doto (int-array p/non-regular-array-len)
                 (aset 0 (.alength am (.array nm node)))
                 (aset p/max-branches 1)))
        ret  (.node nm nil arr)]
    (loop [arr arr shift shift]
      (if (== shift p/shift-increment)
        (do (if-not reg?
              (aset arr p/max-branches rngs))
            (aset arr 0 node))
        (let [a (object-array len)
              e (.node nm nil a)]
          (aset arr 0 e)
          (if-not reg?
            (aset arr p/max-branches rngs))
          (recur a (- shift p/shift-increment)))))
    ret))

(defn fold-tail [^NodeManager nm ^ArrayManager am node shift cnt tail]
  (let [tlen     (.alength am tail)
        reg?     (and (.regular nm node) (== tlen p/max-branches))
        arr      (.array nm node)
        li       (index-of-nil arr)
        new-arr  (object-array (if reg? p/max-branches p/non-regular-array-len))
        rngs     (if-not (.regular nm node) (ranges nm node))
        cret     (if (== shift p/shift-increment)
                   (.node nm nil tail)
                   (fold-tail nm am
                              (aget ^objects arr (dec li))
                              (- shift p/shift-increment)
                              (if (.regular nm node)
                                (mod cnt (bit-shift-left 1 shift))
                                (let [li (unchecked-dec-int (aget rngs p/max-branches))]
                                  (if (pos? li)
                                    (unchecked-subtract-int
                                     (aget rngs li)
                                     (aget rngs (unchecked-dec-int li)))
                                    (aget rngs 0))))
                              tail))
        new-rngs (ints (if-not reg?
                         (if rngs
                           (aclone rngs)
                           (regular-ranges shift cnt))))]
    (when-not (and (or (nil? cret) (== shift p/shift-increment)) (== li p/max-branches))
      (System/arraycopy arr 0 new-arr 0 li)
      (when-not reg?
        (if (or (nil? cret) (== shift p/shift-increment))
          (do (aset new-rngs li
                    (+ (if (pos? li)
                         (aget new-rngs (dec li))
                         (int 0))
                       tlen))
              (aset new-rngs p/max-branches (inc li)))
          (do (when (pos? li)
                (aset new-rngs (dec li)
                      (+ (aget new-rngs (dec li)) tlen)))
              (aset new-rngs p/max-branches li))))
      (if-not reg?
        (aset new-arr p/max-branches new-rngs))
      (if (nil? cret)
        (aset new-arr li
              (new-path nm am
                        (unchecked-subtract-int shift p/shift-increment)
                        (.node nm nil tail)))
        (aset new-arr (if (== shift p/shift-increment) li (dec li)) cret))
      (.node nm nil new-arr))))
