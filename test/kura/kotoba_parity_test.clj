(ns kura.kotoba-parity-test
  "Equality gate between `kura.hash`/`kura.placement` and their `.kotoba` port
  (`kotoba/kura_core.kotoba`), per ADR-2607299200 section 5.

  Placement is the one thing in this library that cannot take its arithmetic
  by injection: a client, a coordinator and an auditor must independently
  compute the same node set for a group. That makes it exactly the thing worth
  having two implementations of and pinning them together — a port that merely
  looks equivalent is a network partition waiting for the first disagreement.

  Same harness as `erasure.kotoba-parity-test`: compile the port with a
  generated zero-arg probe per case, run it on the KIR interpreter in this
  JVM, compare integers. No marshalling, no runtime boundary. The harness
  itself now lives in `kura.kotoba-harness`, shared with
  `kura.kotoba-decision-parity-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [kura.audit :as audit]
            [kura.hash :as h]
            [kura.kotoba-harness :as harness]
            [kura.placement :as p]))

(def ^:private port-path "kotoba/kura_core.kotoba")

(defn- run-cases [cases]
  (harness/run-cases port-path "kura-core" cases))

(def ^:private mix-corpus
  (concat [0 1 2 255 256 65535 65536 2147483647 2147483648 4294967295]
          (map #(* % 2654435761) (range 1 20))
          (map #(mod (* % 97) 4294967296) (range 1 30))))

(deftest mix32-matches
  (let [xs (map #(h/u32 %) mix-corpus)
        cases (into {} (map-indexed (fn [i x] [(str "mix_" i) (str "(mix32 " x ")")]) xs))
        actual (run-cases cases)]
    (doseq [[i x] (map-indexed vector xs)]
      (is (= (h/mix32 x) (get actual (str "mix_" i))) (str "mix32 " x)))))

(deftest fnv-and-key-match
  (testing "three-byte identifiers, exhaustive over a printable-ASCII slice —
            the step function and its composition are what must agree"
    (let [triples (for [a [97 65 48 122] b [98 66 49 33] c [99 67 50 126]] [a b c])
          cases (into {}
                      (mapcat (fn [[i [a b c]]]
                                [[(str "fnv_" i) (str "(fnv1a-of-3 " a " " b " " c ")")]
                                 [(str "key_" i) (str "(key-of-3 " a " " b " " c ")")]])
                              (map-indexed vector triples)))
          actual (run-cases cases)]
      (doseq [[i [a b c]] (map-indexed vector triples)]
        (let [s (str (char a) (char b) (char c))]
          (is (= (h/fnv1a s) (get actual (str "fnv_" i))) (str "fnv1a " s))
          (is (= (h/key32 s) (get actual (str "key_" i))) (str "key32 " s)))))))

(deftest rendezvous-scoring-matches
  (testing "the score a node gets for a group, and the best over its virtual
            ids — disagreement here means two hosts place the same object on
            different nodes"
    (let [pgs (map p/group-name (range 6))
          nodes (map #(str "n-" %) (range 8))
          pairs (for [pg pgs n nodes] [pg n])
          cases (into {}
                      (mapcat (fn [[i [pg n]]]
                                (let [pgk (h/key32 pg) nk (h/key32 n)]
                                  [[(str "s0_" i) (str "(rendezvous-score " pgk " " nk " 0)")]
                                   [(str "b4_" i) (str "(best-score " pgk " " nk " 4 0)")]]))
                              (map-indexed vector pairs)))
          actual (run-cases cases)]
      (doseq [[i [pg n]] (map-indexed vector pairs)]
        (is (= (p/score pg (p/node {:id n :weight 1 :availability :always-on}))
               (get actual (str "s0_" i)))
            (str "weight-1 score for " pg "/" n))
        (is (= (p/score pg (p/node {:id n :weight 4 :availability :always-on}))
               (get actual (str "b4_" i)))
            (str "weight-4 score for " pg "/" n))))))

(deftest group-assignment-matches
  (let [objs (map #(str "obj-" %) (range 40))
        gc 1024
        cases (into {} (map-indexed
                        (fn [i o] [(str "g_" i)
                                   (str "(group-of " (h/key32 o) " " gc ")")])
                        objs))
        actual (run-cases cases)]
    (doseq [[i o] (map-indexed vector objs)]
      (is (= (p/group-of o gc) (get actual (str "g_" i))) (str "group-of " o)))))

(deftest audit-sample-bound-is-conservative
  (testing "the guest's integer stand-in must never ask for FEWER challenges
            than the exact formula — under-auditing is the failure that
            matters, over-auditing just costs a little bandwidth"
    (let [configs (for [pct [1 2 5 10] nines [2 3 4]] [pct nines])
          cases (into {} (map (fn [[pct nines]]
                                [(str "sb_" pct "_" nines)
                                 (str "(samples-lower-bound " pct " " nines ")")]))
                      configs)
          actual (run-cases cases)]
      (doseq [[pct nines] configs]
        (let [exact (audit/samples-needed (/ pct 100.0) (Math/pow 10 (- nines)))
              guest (get actual (str "sb_" pct "_" nines))]
          (is (>= guest exact)
              (str "f=" pct "% delta=1e-" nines ": guest " guest " exact " exact))
          (is (< guest (* 2 exact))
              (str "and not wastefully loose: guest " guest " exact " exact)))))))
