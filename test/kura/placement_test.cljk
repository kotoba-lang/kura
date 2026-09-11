(ns kura.placement-test
  (:require [clojure.test :refer [deftest is testing]]
            [kura.hash :as h]
            [kura.placement :as p]))

(defn- nodes
  "A synthetic population: `racks` racks of `per-rack` nodes, two operators."
  [racks per-rack]
  (vec (for [r (range racks) i (range per-rack)]
         (p/node {:id (str "n-" r "-" i)
                  :weight 1
                  :availability :always-on
                  :domains {:rack (str "rack-" r)
                            :operator (if (even? r) "acme" "globex")
                            :region (if (< r (quot racks 2)) "apac" "emea")}}))))

(deftest hash-is-stable-and-cross-host-defined
  (testing "pinned vectors — placement is arithmetic, not configuration, so a
            change here is a change to where every object in the network lives"
    (is (= 0 (h/mix32 0)))
    (is (= 2166136261 (h/fnv1a "")))
    (is (= (h/fnv1a "abc") (h/fnv1a "abc")))
    (is (not= (h/fnv1a "abc") (h/fnv1a "abd")))
    (is (every? #(< -1 % 4294967296) (map h/key32 ["a" "pg-0" "n-1-2" ""]))))
  (testing "ascii guard"
    (is (h/ascii? "pg-12"))
    (is (not (h/ascii? "群-1")))
    (is (thrown? #?(:clj Throwable :cljs js/Error) (h/key32 "群")))))

(deftest selection-is-deterministic
  (let [ns (nodes 8 4)
        pol (p/policy {:caps {:rack 4}})]
    (is (= (p/select "pg-1" ns 26 pol) (p/select "pg-1" ns 26 pol)))
    (is (= (p/select "pg-1" (shuffle ns) 26 pol) (p/select "pg-1" ns 26 pol))
        "input order must not matter — two hosts may hold the node set in
         different orders and must still agree on placement")))

(deftest domain-caps-are-respected
  (testing "8 racks at cap 4 admits 32 >= 26, so the group fills"
    (let [ns (nodes 8 8)
          pol (p/policy {:caps {:rack 4 :operator 16}})
          sel (p/select "pg-7" ns 26 pol)]
      (is (:complete? sel))
      (is (= 26 (count (:nodes sel))))
      (is (every? #(<= % 4)
                  (vals (frequencies (map #(get-in % [:domains :rack])
                                          (:nodes sel)))))
          "no rack holds more than its cap")
      (is (= 26 (count (set (map :id (:nodes sel)))))
          "no node is chosen twice")))

  (testing "8 racks at cap 3 admits at most 24 < 26 — the ceiling is the
            product of domains and cap, not the node count, and 64 nodes do
            not help. Reported, not silently filled."
    (let [sel (p/select "pg-7" (nodes 8 8) 26 (p/policy {:caps {:rack 3}}))]
      (is (false? (:complete? sel)))
      (is (= 24 (count (:nodes sel))))
      (is (= 2 (:shortfall sel))))))

(deftest underfill-is-reported-not-hidden
  (testing "plenty of nodes, but a cap that cannot be satisfied"
    (let [ns (nodes 4 20)                       ; 80 nodes, only 4 racks
          pol (p/policy {:caps {:rack 2}})      ; so at most 8 can be chosen
          sel (p/select "pg-3" ns 26 pol)]
      (is (false? (:complete? sel)))
      (is (= 8 (count (:nodes sel))))
      (is (= 18 (:shortfall sel))))))

(deftest spread-is-even-enough-to-be-usable
  (testing "no node should hold a wildly disproportionate share of groups"
    (let [ns (nodes 8 8)                        ; 64 nodes
          pol (p/policy {:caps {:rack 4}})
          counts (frequencies
                  (mapcat #(map :id (:nodes (p/select (p/group-name %) ns 26 pol)))
                          (range 200)))
          expected (/ (* 200 26) 64.0)]
      (is (= 64 (count counts)) "every node wins some groups")
      (is (< (apply max (vals counts)) (* 2.0 expected))
          (str "worst node " (apply max (vals counts)) " vs expected " expected)))))

(deftest weight-buys-proportionally-more-groups
  (let [ns (conj (nodes 4 4) (p/node {:id "big" :weight 8 :availability :always-on
                                      :domains {:rack "rack-9" :operator "acme"
                                                :region "apac"}}))
        pol (p/policy {:caps {}})
        counts (frequencies
                (mapcat #(map :id (:nodes (p/select (p/group-name %) ns 4 pol)))
                        (range 400)))
        light (/ (reduce + (map counts (map #(str "n-" (quot % 4) "-" (mod % 4))
                                            (range 16))))
                 16.0)]
    (is (> (get counts "big" 0) light)
        (str "weight 8 node won " (get counts "big" 0)
             ", average weight-1 node won " light))))

(deftest object-to-group-is-a-function-not-a-table
  (let [gc 1024]
    (is (= (p/group-of "obj-abc" gc) (p/group-of "obj-abc" gc)))
    (is (every? #(< -1 % gc) (map #(p/group-of (str "obj-" %) gc) (range 500))))
    (testing "objects spread across groups"
      (is (> (count (set (map #(p/group-of (str "obj-" %) gc) (range 2000))))
             (* 0.5 gc))))))

(deftest removing-a-node-moves-only-its-own-groups
  (testing "the rendezvous claim, checked rather than repeated"
    (let [before (nodes 6 6)
          victim (:id (first before))
          after (vec (remove #(= victim (:id %)) before))
          cfg {:group-count 300 :n 26 :pol (p/policy {:caps {:rack 5}})}
          held (p/groups-touching victim (assoc cfg :nodes before))
          moved (p/churn cfg before after)]
      (is (seq held))
      (is (= (set (keys moved)) (set held))
          "exactly the groups that held the victim changed, no others")
      (is (every? (fn [[_ {:keys [removed]}]] (= #{victim} removed)) moved)
          "and each of them lost only the victim"))))

(deftest adding-a-node-disturbs-minimally
  (testing "the rendezvous guarantee is about the SIZE of each disturbance,
            not the number of groups disturbed.

            Selecting 26 of 37 nodes, a newcomer beats the 26th-best score in
            roughly 26/37 = 70% of groups, so ~70% of them change and that is
            correct, not a defect. What must hold — and what a consistent-hash
            ring would break — is that each affected group gains only the
            newcomer and loses exactly one node. Nothing reshuffles."
    (let [before (nodes 6 6)
          newcomer (p/node {:id "n-new" :availability :always-on :domains {:rack "rack-2" :operator "acme"
                                                  :region "apac"}})
          after (conj before newcomer)
          cfg {:group-count 300 :n 26 :pol (p/policy {:caps {:rack 5}})}
          moved (p/churn cfg before after)]
      (is (every? (fn [[_ {:keys [added]}]] (= #{"n-new"} added)) moved)
          "every changed group gained exactly the newcomer and nothing else")
      (is (every? (fn [[_ {:keys [removed]}]] (= 1 (count removed))) moved)
          "and displaced exactly one node")
      (let [expected (* 300 (/ 26.0 37))]
        (is (< (* 0.75 expected) (count moved) (* 1.25 expected))
            (str "moved " (count moved) ", theory says ~" (int expected)))))))

(deftest placement-end-to-end
  (let [ns (nodes 8 8)
        cfg {:group-count 512 :nodes ns :n 26 :pol (p/policy {:caps {:rack 4}})}
        pl (p/placement "obj-42" cfg)]
    (is (:complete? pl))
    (is (= 26 (count (:shards pl))))
    (is (= (set (range 26)) (set (keys (:shards pl)))))
    (is (= 26 (count (set (vals (:shards pl))))) "one shard per distinct node")
    (is (= pl (p/placement "obj-42" cfg)))))

;; --- availability ----------------------------------------------------------
;;
;; The class exists because a laptop's disk is fine and the laptop is in a bag.
;; Every test here is about keeping those two facts from being confused.

(defn- laptops
  "A fleet of intermittent nodes, each its own site — which is the honest
  declaration for machines that are genuinely apart, and still does not make
  them a durable network."
  [n]
  (vec (for [i (range n)]
         (p/node {:id (str "lap-" i) :availability :intermittent
                  :domains {:rack (str "home-" i) :operator (str "op-" i)
                            :region "apac"}}))))

(deftest availability-must-be-declared
  (testing "no default is safe, so there is no default"
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (p/node {:id "n-1" :domains {:rack "r1"}})))
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (p/node {:id "n-1" :availability :sometimes})))
    (testing "and the message says why, because the operator has to choose"
      (is (re-find #"sleep"
                   #?(:clj (try (p/node {:id "n-1"}) (catch AssertionError e (.getMessage e)))
                      :cljs (try (p/node {:id "n-1"}) (catch :default e (str e)))))))))

(deftest the-intermittent-cap-is-asymmetric
  (testing "always-on is uncapped — rented buckets are the floor this stands on"
    (let [r (p/select "pg-0" (nodes 6 6) 26 (p/policy {:max-intermittent 2}))]
      (is (:complete? r))
      (is (= 26 (:always-on r)))
      (is (zero? (:intermittent r))
          "a cap of 2 must not mean AT LEAST 2 — there are no laptops here")))
  (testing "intermittent is capped at exactly what was allowed"
    (let [mixed (into (nodes 6 6) (laptops 20))
          r (p/select "pg-0" mixed 26 (p/policy {:max-intermittent 3}))]
      (is (:complete? r))
      (is (= 3 (:intermittent r)) "no more than the policy permits")
      (is (= 23 (:always-on r))))))

(deftest the-default-is-zero-sleeping-nodes
  (testing "a policy written before this cap existed asked for no laptops, and
            must keep getting none — granting some silently would change the
            durability of every object already placed under it"
    (let [mixed (into (nodes 6 6) (laptops 20))
          r (p/select "pg-0" mixed 26 (p/policy {}))]
      (is (zero? (:intermittent r)))
      (is (= 26 (:always-on r))))))

(deftest a-network-of-laptops-cannot-fill-a-group
  (testing "this is the finding that matters for onboarding consumer hardware:
            20 laptops at 20 separate sites pass every domain cap and still
            cannot hold one object, because the code's tolerance for absence is
            a budget and they spend all of it"
    (let [r (p/select "pg-0" (laptops 20) 26 (p/policy {:max-intermittent 7}))]
      (is (not (:complete? r)))
      (is (= 7 (:intermittent r)))
      (is (= 19 (:shortfall r))
          "short by 19 — the fleet is not small, it is asleep"))))

(deftest a-full-group-still-reports-how-much-of-it-sleeps
  (testing ":complete? answers 'did I get n shards', not 'can I read them
            tonight'. A caller reading only that flag never learns the
            difference, so select reports both counts."
    (let [mixed (into (nodes 6 6) (laptops 20))
          r (p/select "pg-0" mixed 26 (p/policy {:max-intermittent 7}))]
      (is (:complete? r))
      (is (= 7 (:intermittent r)))
      (is (= 26 (+ (:intermittent r) (:always-on r)))
          "every chosen node is classified — none is unaccounted for"))))
