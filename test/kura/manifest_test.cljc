(ns kura.manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [erasure.lrc :as lrc]
            [kura.manifest :as m]))

(def layout (lrc/layout {:k 16 :r 4 :g 6}))

(def obj
  (m/plan {:object-id "obj-1" :size (* 64 1024 1024) :stripe-bytes (* 64 1024 1024)}
          layout))

(deftest geometry
  (is (= (* 4 1024 1024) (:shard-bytes obj)) "64 MiB stripe / k=16 = 4 MiB shards")
  (is (= 1 (:stripes obj)))
  (is (= 26 (m/total-shards obj)))
  (is (= 1.625 (m/multiplier obj)) "matches the code's n/k"))

(deftest stripes-are-independent-units
  (let [big (m/plan {:object-id "obj-2" :size (* 200 1024 1024)
                     :stripe-bytes (* 64 1024 1024)}
                    layout)]
    (is (= 4 (:stripes big)) "200 MiB over 64 MiB stripes rounds up")
    (is (= 104 (m/total-shards big)))
    (is (= 26 (count (m/stripe-shards big 0))))
    (is (apply distinct? (mapcat #(m/stripe-shards big %) (range 4)))
        "shard ids are unique across stripes")))

(deftest small-range-reads-one-shard
  (testing "ADR-2607299200 section 7: a systematic code with directly readable
            data shards reads the range, not the stripe"
    (let [reads (m/range-reads obj (* 5 1024 1024) (* 1 1024 1024))]
      (is (= 1 (count reads)) "1 MiB inside one 4 MiB shard is one read")
      (is (= 1 (:index (first reads))) "offset 5 MiB lands in shard 1")
      (is (= (* 1 1024 1024) (:shard-offset (first reads))))
      (is (= (* 1 1024 1024) (:length (first reads)))))))

(deftest amplification-is-one
  (testing "the claim, as a number"
    (doseq [[off len] [[0 1024]
                       [(* 5 1024 1024) (* 1 1024 1024)]
                       [(* 7 1024 1024) (* 3 1024 1024)]
                       [12345 67890]
                       [0 (* 64 1024 1024)]]]
      (is (= 1.0 (m/amplification obj off len))
          (str "range " off "+" len " must fetch exactly what was asked")))))

(deftest range-spanning-shards-splits-not-widens
  (let [reads (m/range-reads obj (- (* 4 1024 1024) 512) 1024)]
    (is (= 2 (count reads)) "a range straddling a shard boundary is two reads")
    (is (= [0 1] (mapv :index reads)))
    (is (= 1024 (reduce + (map :length reads))) "and still fetches only 1024 bytes")))

(deftest reads-name-a-hedge-group
  (testing "a direct shard read depends on one node, so the caller is told
            which local group can rebuild it if that node is slow — the
            mitigation section 7 owes for the tail-latency cost"
    (doseq [r (m/whole-object-reads obj)]
      (is (= (lrc/group-of layout (:index r)) (:hedge-group r)))
      (is (< (:hedge-group r) (:l layout))))))

(deftest whole-object-read-touches-only-data-shards
  (let [reads (m/whole-object-reads obj)]
    (is (= 16 (count reads)) "k reads, not n")
    (is (every? #(< (:index %) 16) reads) "no parity shard is read on a clean path")
    (is (= (:size obj) (reduce + (map :length reads))))))

(deftest ranges-outside-the-object-are-empty-not-erroneous
  (is (= [] (m/range-reads obj (* 100 1024 1024) 1024)))
  (is (= [] (m/range-reads obj 0 0)))
  (is (= [] (m/range-reads obj -100 50)))
  (testing "a range clipped by the end of the object returns what exists"
    (let [reads (m/range-reads obj (- (:size obj) 100) 5000)]
      (is (= 100 (reduce + (map :length reads)))))))

(deftest ragged-stripe-is-rejected-at-construction
  (is (thrown? #?(:clj Throwable :cljs js/Error)
               (m/plan {:object-id "x" :size 100 :stripe-bytes 1000} layout))
      "1000 does not divide by k=16"))
