(ns kura.kotoba-oracle-test
  "What keeps the shipped artifact honest, now that it is what runs.

  `kura.kotoba-decision-parity-test` compiles `kotoba/order_core.kotoba` fresh
  and compares it to `kura.order`. That was the whole check while the host had
  its own copy of the admission rules. It is not the whole check any more,
  because for a well-formed order the host no longer computes them — it reads
  `resources/kura/oracle/order.kir.edn`, and a fresh compile is not that file.
  Two things have to hold that did not have to before:

    1. the shipped artifact IS the current source, compiled
    2. the host actually reads it, rather than having quietly kept a copy

  The second is the one that is easy to lose and impossible to see: a
  delegation that fell back to a host implementation would pass every parity
  test ever written, because a host copy is exactly what those tests compare
  against. So this asks the only question that separates them — swap in a core
  that answers differently and see whether the host follows."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [kotoba.compiler.core :as compiler]
            [kura.kotoba-oracle :as oracle]
            [kura.kotoba-oracle-gen :as gen]
            [kura.order :as o]))

(defn- renumber-gensyms
  "The KIR with compiler-generated symbol suffixes renumbered from zero.

  `and` and `or` lower to `let` over a temporary, and the temporary carries a
  gensym counter that is per-JVM, not per-compile — so two compiles of an
  unchanged source produce KIR that differs in `or-tmp__11099` vs
  `or-tmp__12404` and nothing else. Comparing raw would make the drift gate
  fail always, which is the same as not having one.

  Renumbering rather than erasing, because the suffix is what distinguishes
  two temporaries in the same scope; collapsing them all to one name would
  make two structurally different bodies compare equal. Indices are assigned in
  traversal order, which is identical for two otherwise-equal structures."
  [kir]
  (let [seen (volatile! {})]
    (walk/postwalk
     (fn [x]
       (if (and (symbol? x) (re-find #"__\d+$" (name x)))
         (let [n (or (get @seen x)
                     (let [n (count @seen)] (vswap! seen assoc x n) n))]
           (symbol (str (str/replace (name x) #"__\d+$" "") "__" n)))
         x))
     kir)))

(deftest the-shipped-artifact-is-the-current-source-compiled
  (doseq [[id source] (sort-by key oracle/cores)]
    (testing (str id " <- " source)
      (let [shipped (edn/read-string (slurp (io/resource (oracle/resource-path id))))
            fresh (:kir (compiler/compile-source (slurp (io/file source)) gen/target {}))]
        (is (= (renumber-gensyms fresh) (renumber-gensyms shipped))
            (str "shipped KIR for " id " is stale — run `clojure -M:test:gen`"))))))

(deftest every-declared-core-actually-ships
  (doseq [id (keys oracle/cores)]
    (is (some? (io/resource (oracle/resource-path id)))
        (str "no artifact for " id))
    (is (some? (oracle/kir id)))))

(deftest a-missing-artifact-throws-rather-than-deciding-anything
  ;; The seam's one refusal. If it fell back instead, the first thing anyone
  ;; would notice is that a decision quietly stopped being the shipped one.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shipped decision core is missing"
                        (oracle/kir :not-a-core)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare that export"
                        (oracle/param-types :order 'admit))))

(deftest the-record-abi-is-read-out-of-the-artifact
  ;; A caller learns the record's declared field order from the artifact rather
  ;; than writing it down a second time — that is what `signature` is for, and
  ;; the non-ASCII test below uses it. Pinned here so that a rename or a
  ;; reordering in `order_core.kotoba` shows up as this failing rather than as
  ;; a statement that silently permutes — which is the failure the length
  ;; delimiters exist to prevent and would not catch, because a permutation
  ;; keeps every length.
  (is (= [[:record :order/unsigned
           [[:node-id :string] [:action :string] [:shard-id :string]
            [:max-bytes :i64] [:expires-at :i64] [:issued-at :i64]
            [:nonce :string]]]]
         (oracle/param-types :order 'signing-bytes)))
  (is (= [:i64 :i64 :i64] (oracle/param-types :order 'admit-ok?)))
  (is (= :i64 (:result (oracle/signature :order 'known-action?)))
      "every predicate in this port is 1/0, not :bool — see kura.kotoba-oracle/truth"))

(deftest the-published-action-set-matches-the-core
  ;; `kura.order/actions` is the enumerable form of a closed set that the
  ;; shipped core states as five string comparisons. `admit` asks the core, so
  ;; the set is now documentation — and documentation that disagrees with the
  ;; rule is worse than none. ADR-2608120200 decision 3.
  (doseq [a o/actions]
    (is (oracle/truth (oracle/call :order 'known-action? [(name a)]))
        (str a " is published but the core rejects it")))
  (doseq [a [:read :write :repair :get-repair :put- :PUT "" "put "]]
    (is (not (oracle/truth (oracle/call :order 'known-action? [(name a)])))
        (str (pr-str a) " is not published but the core accepts it"))))

;; --- the swap ------------------------------------------------------------

(def ^:private unsigned-record
  "The 7-field record as `order_core.kotoba` declares it. Spelled out HERE,
  unlike in `kura.order`, because the substitute core below has to declare the
  same type for the swap to be a swap and not a different module."
  (str "[:record :order/unsigned [[:node-id :string] [:action :string]"
       " [:shard-id :string] [:max-bytes :i64] [:expires-at :i64]"
       " [:issued-at :i64] [:nonce :string]]]"))

(def ^:private wrong-order-source
  "Same exports, same signatures, deliberately different answers.

  Each one is chosen so that a host that had kept its own copy would be
  observably unchanged: the statement is a constant, the closed set admits only
  a word that is not in it, every order is expired, nothing is within its
  limit, the crypto runs even on an incoherent order, and the verdict is yes no
  matter what the reasons say."
  (str "(ns order-core (:export [signing-bytes settlement-leaf-id known-action?"
       " expired? not-yet-valid? within-limit? signature-check-needed? admit-ok?]))"
       ;; Declared even though `kura.order` does not call it, so that the test
       ;; below can show `signing-bytes` NOT following — the direction that
       ;; can fail if someone delegates it without meeting the two conditions
       ;; the namespace docstring names.
       "(defn signing-bytes [o " unsigned-record "] :string \"wrong-statement\")"
       "(defn settlement-leaf-id [node-id :string nonce :string] :string \"wrong-id\")"
       "(defn known-action? [action :string] :i64 (string=? action \"nope\"))"
       "(defn expired? [expires-at :i64 now :i64 skew :i64] :i64 1)"
       "(defn not-yet-valid? [issued-at :i64 now :i64] :i64 0)"
       "(defn within-limit? [max-bytes :i64 transferred :i64 n :i64] :i64 0)"
       "(defn signature-check-needed? [content-problems :i64 verifier-present :i64] :i64 1)"
       "(defn admit-ok? [content-problems :i64 verifier-present :i64 signature-valid :i64] :i64 1)"))

(defn- with-core
  "Run `f` against a substituted core, then put the shipped one back."
  [kir f]
  (try
    (oracle/register-kir! :order kir)
    (f)
    (finally (oracle/deregister-kir! :order))))

(def ^:private valid-order
  {:node-id "n1" :action :get :shard-id "s1" :max-bytes 100
   :expires-at 500 :issued-at 400 :nonce "x9"})

(defn- recording-verifier
  "A verify-fn that accepts everything and records what it was asked to verify.
  Whether it was called AT ALL is the observation: the shipped core's
  `signature-check-needed?` is why a node under a flood of malformed orders
  does no asymmetric crypto."
  [seen]
  (fn [message _signature] (swap! seen conj message) true))

(deftest the-host-reads-the-artifact-rather-than-keeping-a-copy
  (let [wrong (:kir (compiler/compile-source wrong-order-source gen/target {}))]
    (testing "the shipped answers"
      (let [seen (atom [])
            verdict (o/admit valid-order {:node-id "n1" :action :get :now 450
                                          :verify-fn (recording-verifier seen)
                                          :signature "sig"})]
        (is (true? (:ok? verdict)))
        (is (= [] (:reasons verdict)))
        (is (= [(o/signing-bytes valid-order)] @seen)
            "the crypto saw the statement the core builds"))
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4002:x9" (o/signing-bytes valid-order)))
      (is (= "n1|x9" (:id (o/settlement-leaf valid-order 10 "h"))))
      (is (true? (o/within-limit? valid-order 0 100)))
      (is (false? (o/within-limit? valid-order 0 101)))
      (testing "and the crypto is skipped when the order is already rejected"
        (let [seen (atom [])
              verdict (o/admit (assoc valid-order :action :nonsense)
                               {:node-id "n1" :now 450
                                :verify-fn (recording-verifier seen)
                                :signature "sig"})]
          (is (false? (:ok? verdict)))
          (is (= [:unknown-action] (mapv :reason (:reasons verdict))))
          (is (= [] @seen) "no asymmetric crypto on an incoherent order"))))

    (with-core wrong
      (fn []
        ;; A host that had kept `(contains? actions …)`,
        ;; `(> now (+ expires-at skew))`, `(<= (+ transferred n) max-bytes)`,
        ;; `(if (seq content) …)` and `(empty? problems)` would answer exactly
        ;; as it did above, and nothing else in this repository would say so.
        (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4002:x9"
               (o/signing-bytes valid-order))
            "signing-bytes did NOT follow — it is deliberately not delegated")
        (is (= "wrong-id" (:id (o/settlement-leaf valid-order 10 "h")))
            "the settlement leaf id followed it")
        (is (false? (o/within-limit? valid-order 0 1))
            "within-limit? followed it")
        (let [seen (atom [])
              verdict (o/admit valid-order {:node-id "n1" :action :get :now 450
                                            :verify-fn (recording-verifier seen)
                                            :signature "sig"})]
          (is (= #{:unknown-action :order-expired} (set (map :reason (:reasons verdict))))
              "the closed set and the expiry comparison followed it")
          (is (true? (:ok? verdict))
              "the verdict came from admit-ok?, not from (empty? reasons)")
          (is (= [(o/signing-bytes valid-order)] @seen)
              "signature-check-needed? ran the crypto on an order with reasons"))))

    (testing "restored"
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4002:x9" (o/signing-bytes valid-order)))
      (is (true? (:ok? (o/admit valid-order {:node-id "n1" :action :get :now 450
                                             :verify-fn (fn [_ _] true)
                                             :signature "sig"})))))))

(deftest the-host-and-the-port-frame-non-ascii-differently
  ;; The divergence `signing-bytes` still carries, pinned rather than left in a
  ;; comment. The host length-delimits with `(count s)` — UTF-16, so 1 for あ
  ;; and 2 for 😀 on the JVM — and the port counts UTF-8 bytes, 3 and 4. Both
  ;; answers are stated here so that whichever side moves, this fails and
  ;; someone decides which framing the wire contract has, rather than the two
  ;; drifting further apart in silence.
  ;;
  ;; ASCII agrees, and `kura.hash/key32` asserts ASCII for the ids it keys on —
  ;; but `admit` hands `signing-bytes` straight to `verify-fn` with no such
  ;; assertion, which is why this is a real divergence and not a curiosity.
  (let [guest (fn [nonce]
                (oracle/call :order 'signing-bytes
                             [(oracle/record (first (oracle/param-types :order 'signing-bytes))
                                             ["n1" "get" "s1" 100 500 400 nonce])]))
        host #(o/signing-bytes (assoc valid-order :nonce %))]
    (testing "ASCII: the two agree, which is what the parity gate covers"
      (is (= (host "x9") (guest "x9")))
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4002:x9" (host "x9"))))
    (testing "non-ASCII: they do not, and here is exactly how"
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4001:あ" (host "あ")))
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4003:あ" (guest "あ")))
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4002:😀" (host "😀")))
      (is (= "kura-order-v1|2:n13:get2:s13:1003:5003:4004:😀" (guest "😀"))))))

(deftest an-order-the-guest-cannot-hold-is-still-rejected-rather-than-crashing
  ;; The stated boundary, as a test rather than a docstring. `admit` exists to
  ;; refuse malformed orders, so it has to be able to HOLD one — and this
  ;; profile has no nil. Under a substituted core that gets every crossable
  ;; answer wrong, an order with no limit is expected to keep the host framing,
  ;; which is the same statement as "these calls are still answered by host
  ;; code", said in the direction that can fail.
  (let [wrong (:kir (compiler/compile-source wrong-order-source gen/target {}))
        no-limit (dissoc valid-order :max-bytes)]
    (with-core wrong
      (fn []
        (is (false? (o/within-limit? no-limit 0 1)))
        (is (false? (o/within-limit? {:max-bytes nil} 0 1)))
        (testing "and the reasons it collects are the host's"
          ;; A verifier is passed because the substituted core answers "check
          ;; the signature" for an order it was told has content problems and
          ;; no verifier. The shipped core cannot: `signature-check-needed?`
          ;; conjoins `verifier-present`. This is what a lying artifact buys —
          ;; and it is bounded by the drift gate above, which is why the host
          ;; does not re-derive the guard it just asked for.
          (is (contains? (set (map :reason (:reasons (o/admit no-limit {:node-id "n1" :now 450
                                                                        :verify-fn (fn [_ _] true)}))))
                         :missing-or-invalid-limit)))))
    (testing "an expiry the guest cannot hold is compared here, as it always was"
      ;; Unchanged by this delegation and worth seeing: an order with no
      ;; expiry is not expired, and `admit` has no reason for a missing one, so
      ;; it is admitted. The comparison that would have rejected it is the
      ;; guest's; the presence question was never asked.
      (is (true? (:ok? (o/admit (assoc valid-order :expires-at nil)
                                {:node-id "n1" :action :get :now 450
                                 :verify-fn (fn [_ _] true) :signature "sig"}))))
      (is (= [] (:reasons (o/admit (assoc valid-order :expires-at nil)
                                   {:node-id "n1" :action :get :now nil
                                    :verify-fn (fn [_ _] true) :signature "sig"})))
          "with no clock there is nothing to compare and no reason to give"))))
