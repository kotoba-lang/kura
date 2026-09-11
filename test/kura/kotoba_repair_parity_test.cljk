(ns kura.kotoba-repair-parity-test
  "Equality gate between `kura.repair`'s queue-order decisions and their
  `.kotoba` port (`kotoba/repair_core.kotoba`).

  These three numbers decide which group gets rebuilt first when bandwidth is
  scarce. They fail quietly: a margin off by one does not scatter shards or
  throw, it just puts a group that is one loss from unreadable behind one that
  is comfortable, and nothing says so until the next loss lands.

  The oracle is `r/urgency` itself — the real sort key, driven from a real
  `r/assess` over a real layout — rather than a restatement of the rule here.
  A gate that compares a port against a copy of itself proves nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [erasure.lrc :as lrc]
            [kura.kotoba-harness :as harness]
            [kura.repair :as r]))

(def ^:private port "kotoba/repair_core.kotoba")

(defn- cases [c] (harness/run-cases port "repair-core" c))

(def ^:private layout (lrc/layout {:k 16 :r 4 :g 6}))
(def ^:private tolerated (lrc/max-tolerated-erasures layout))

(deftest margin-matches-the-cljc
  (let [actual (cases {"m_none" (str "(margin " tolerated " 0)")
                       "m_one" (str "(margin " tolerated " 1)")
                       "m_some" (str "(margin " tolerated " 3)")
                       "m_exact" (str "(margin " tolerated " " tolerated ")")
                       "m_over" (str "(margin " tolerated " " (+ tolerated 5) ")")})]
    (testing "against r/margin over the same layout"
      (is (= (r/margin layout #{}) (get actual "m_none")))
      (is (= (r/margin layout #{3}) (get actual "m_one")))
      (is (= (r/margin layout #{3 7 11}) (get actual "m_some"))))
    (testing "the floor, which is what keeps a doomed group from sorting last"
      (is (= 0 (get actual "m_exact")))
      (is (= 0 (get actual "m_over")))
      (is (= (r/margin layout (set (range (+ tolerated 5)))) (get actual "m_over"))))
    (testing "and the bound is the guaranteed one, not the optimistic one"
      (is (= 7 tolerated))
      (is (= 7 (get actual "m_none"))))))

(deftest urgency-components-match-the-real-sort-key
  ;; Drive the actual queue-order function and check the port reproduces each
  ;; element of the vector it returns.
  (let [pol (r/policy {:grace-seconds 900 :repair-threshold 3})
        ;; the same shard-state shape kura.repair-test builds
        shard-states (fn [lost]
                       (into {} (map (fn [i] [i {:state (if (lost i) :lost :live)}]))
                             (range (:n layout))))
        assessment (r/assess layout pol 10000 (shard-states #{3 7}))
        [readable-rank margin-rank local-rank] (r/urgency layout assessment)
        local-steps (count (filter #(= :local (:op %)) (:steps (:plan assessment))))
        actual (cases {"rank_readable" (str "(readable-rank "
                                            (if (:readable? assessment) "true" "false") ")")
                       "rank_margin" (str "(margin " tolerated " "
                                          (count (:erased assessment)) ")")
                       "rank_local" (str "(local-rank " local-steps ")")})]
    (testing "each component of the vector r/urgency actually returned"
      (is (= readable-rank (get actual "rank_readable")))
      (is (= margin-rank (get actual "rank_margin")))
      (is (= local-rank (get actual "rank_local"))))
    (testing "unreadable sorts first — 0 before 1"
      (is (= 1 (get (cases {"y" "(readable-rank true)"}) "y")))
      (is (= 0 (get (cases {"n" "(readable-rank false)"}) "n"))))
    (testing "more local steps sorts earlier, so the rank is negated"
      (is (= -3 (get (cases {"l" "(local-rank 3)"}) "l")))
      (is (= 0 (get (cases {"l0" "(local-rank 0)"}) "l0"))))))

(deftest native-admission-is-recorded-not-claimed
  ;; MEASURED, and the two gates disagree. `kotoba -M compile --target
  ;; aarch64-macos` accepts this module; `only-native-word-typed-features?`
  ;; rejects it. The difference is the bare `:bool` parameter on
  ;; `readable-rank` — compiler ADR 0219 calls that a gap in the INTERPRETER
  ;; rather than in either backend, which is consistent with the AOT target
  ;; taking it and this predicate refusing.
  ;;
  ;; Pinned as false so the disagreement is visible: if a later compiler makes
  ;; the predicate accept it, this test fails and someone reads this comment
  ;; instead of finding a stale claim in a docstring.
  (is (false? (harness/native-qualifiable? port "repair-core"))))
