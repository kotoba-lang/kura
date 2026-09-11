(ns kura.kotoba-decision-parity-test
  "Equality gate between `kura.placement` / `kura.order` and their `.kotoba`
  ports (`kotoba/placement_core.kotoba`, `kotoba/order_core.kotoba`).

  `kura.kotoba-parity-test` already pins the placement *arithmetic*. This pins
  the *judgements* built on it, which fail differently: an arithmetic
  disagreement scatters shards, whereas a cap comparison off by one, or a
  signing string assembled in a different order, produces output that is
  internally consistent and quietly wrong — a rack over its shard budget, or a
  verifier that rejects every honest order because it is checking a signature
  over a statement nobody made.

  Every expectation below comes from calling the live `.cljc`. Where the
  `.cljc` keeps a decision inside a larger function (`admit`'s check ordering,
  `select`'s reported shortfall) the real function is driven and its own
  output is the oracle, rather than the decision being restated here — a gate
  that compares a port against a copy of itself proves nothing."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [kura.kotoba-harness :as harness]
            [kura.order :as o]
            [kura.placement :as p]))

(def ^:private placement-port "kotoba/placement_core.kotoba")
(def ^:private order-port "kotoba/order_core.kotoba")

(defn- placement-cases [cases] (harness/run-cases placement-port "placement-core" cases))
(defn- order-cases [cases] (harness/run-cases order-port "order-core" cases))

(defn- lit
  "A `.kotoba` string literal for `s`. The reader accepts \\\\ \\\" \\n \\t \\r,
  which is exactly what `pr-str` emits for the printable-ASCII fixtures here."
  [s]
  (pr-str (str s)))

(defn- b
  "1/0 as the guest reports a truth value — every comparison in this profile
  is `:i64`, so a parity test that expected `true`/`false` would compare
  against the wrong thing."
  [x]
  (if x 1 0))

;; --- placement: the group name a placement is keyed on --------------------

(deftest group-name-matches
  (testing "the string `select` is keyed on, and therefore the string whose
            key32 picks the winning nodes — a host that builds it differently
            places the same object somewhere else"
    (let [is' [0 1 2 9 10 11 99 100 101 999 1000 1023 65535 1000000]
          cases (into {} (map-indexed
                          (fn [i n] [(str "gn_" i) [:string (str "(group-name " n ")")]]))
                      is')
          actual (placement-cases cases)]
      (doseq [[i n] (map-indexed vector is')]
        (is (= (p/group-name n) (get actual (str "gn_" i)))
            (str "group-name " n))))))

;; --- placement: the caps that decide whether a node may take a shard ------

(def ^:private cap-nodes
  "Six nodes chosen so that every branch of `would-exceed?` is reachable: two
  sharing a rack and an operator, one sharing only the operator, two
  intermittent, and one with no domains at all (the `(some? v)` branch)."
  [(p/node {:id "n-a" :weight 1 :domains {:rack "r1" :operator "acme"} :availability :always-on})
   (p/node {:id "n-b" :weight 1 :domains {:rack "r1" :operator "acme"} :availability :always-on})
   (p/node {:id "n-c" :weight 1 :domains {:rack "r2" :operator "acme"} :availability :always-on})
   (p/node {:id "n-d" :weight 1 :domains {:rack "r2" :operator "bolt"} :availability :intermittent})
   (p/node {:id "n-e" :weight 1 :domains {:rack "r3" :operator "bolt"} :availability :intermittent})
   (p/node {:id "n-f" :weight 1 :domains {} :availability :always-on})])

(def ^:private cap-policies
  [(p/policy {})
   (p/policy {:caps {:rack 1}})
   (p/policy {:caps {:rack 2} :max-intermittent 1})
   (p/policy {:caps {:rack 2 :operator 2} :max-intermittent 2})])

(def ^:private cap-chosen
  (mapv #(vec (take % cap-nodes)) [0 1 2 4]))

(def ^:private cap-scenarios
  (vec (for [[pi pol] (map-indexed vector cap-policies)
             [ci chosen] (map-indexed vector cap-chosen)
             [ni node] (map-indexed vector cap-nodes)]
         {:key (str pi "_" ci "_" ni) :pol pol :chosen chosen :node node})))

(defn- domain-inputs
  "Per-kind `[kind shared cap]` for one candidate against one chosen set.

  This is the part of `would-exceed?` that stays host-side: `caps` is a map of
  arbitrarily many kinds and the count comes from filtering the chosen set.
  `(some? v)` — a candidate with no value for a kind is unconstrained by it —
  is host-side too, and is why a node with no domains at all is in the
  fixture."
  [{:keys [caps]} chosen node]
  (keep (fn [[kind cap]]
          (when-let [v (get-in node [:domains kind])]
            [kind (count (filter #(= v (get-in % [:domains kind])) chosen)) cap]))
        caps))

(deftest cap-decisions-match
  (testing "whether a candidate node is refused a shard: the availability cap
            (asymmetric on purpose — always-on is uncapped), each domain cap,
            and their composition, held to the private `would-exceed?` the
            greedy walk actually calls"
    (let [dce-cases
          (into {} (mapcat (fn [{:keys [key pol chosen node]}]
                             (map-indexed
                              (fn [k [_kind shared cap]]
                                [(str "dce_" key "_" k)
                                 (str "(domain-cap-exceeded? " shared " " cap ")")])
                              (domain-inputs pol chosen node)))
                           cap-scenarios))
          dce (placement-cases dce-cases)

          enriched
          (mapv (fn [{:keys [key pol chosen node] :as s}]
                  (let [kinds (domain-inputs pol chosen node)
                        any (reduce (fn [acc k]
                                      (max acc (get dce (str "dce_" key "_" k))))
                                    0
                                    (range (count kinds)))]
                    (assoc s
                           :any-domain any
                           :candidate-intermittent (b (= :intermittent (:availability node)))
                           :chosen-intermittent (count (filter #(= :intermittent (:availability %)) chosen))
                           :max-intermittent (:max-intermittent pol))))
                cap-scenarios)

          we-cases
          (into {} (mapcat (fn [{:keys [key any-domain candidate-intermittent
                                        chosen-intermittent max-intermittent]}]
                             [[(str "ice_" key)
                               (str "(intermittent-cap-exceeded? " candidate-intermittent " "
                                    chosen-intermittent " " max-intermittent ")")]
                              [(str "we_" key)
                               (str "(would-exceed? " candidate-intermittent " "
                                    chosen-intermittent " " max-intermittent " "
                                    any-domain ")")]])
                           enriched))
          we (placement-cases we-cases)]

      (is (seq dce-cases) "the domain-cap fixtures must actually exercise a cap")

      (doseq [{:keys [key pol chosen node
                      chosen-intermittent max-intermittent]} enriched]
        ;; Each domain kind on its own, against the count the host produced.
        (doseq [[k [kind shared cap]] (map-indexed vector (domain-inputs pol chosen node))]
          (is (= (b (>= shared cap)) (get dce (str "dce_" key "_" k)))
              (str "domain cap " kind " shared=" shared " cap=" cap)))
        ;; The availability cap on its own — always-on must be uncapped even
        ;; when the chosen set is already at max-intermittent.
        (is (= (b (and (= :intermittent (:availability node))
                       (>= chosen-intermittent max-intermittent)))
               (get we (str "ice_" key)))
            (str "intermittent cap " key
                 " candidate=" (:availability node)
                 " chosen-intermittent=" chosen-intermittent
                 " max=" max-intermittent))
        ;; And the composition, against the real private predicate.
        (is (= (b (#'p/would-exceed? pol chosen node)) (get we (str "we_" key)))
            (str "would-exceed? " key " node=" (:id node)
                 " chosen=" (mapv :id chosen) " pol=" pol)))

      (testing "the fixtures reach both answers, or the gate is measuring nothing"
        (let [answers (set (map #(get we (str "we_" (:key %))) enriched))]
          (is (= #{0 1} answers) (str "would-exceed? answers observed: " answers)))))))

;; --- placement: what a finished selection reports -------------------------

(def ^:private select-scenarios
  "Driven through the real `select`, including the cases a happy-path gate
  would miss: no candidates at all, one candidate, n=0, n above the node
  count, and a cap tight enough that a greedy walk runs out of eligible nodes
  even though there were enough nodes to begin with."
  [{:label "no candidates"        :nodes []                :n 3 :pol (p/policy {})}
   {:label "one candidate, n=1"   :nodes [(first cap-nodes)] :n 1 :pol (p/policy {})}
   {:label "one candidate, n=3"   :nodes [(first cap-nodes)] :n 3 :pol (p/policy {})}
   {:label "n=0"                  :nodes cap-nodes         :n 0 :pol (p/policy {})}
   {:label "n = node count"       :nodes cap-nodes         :n 6 :pol (p/policy {})}
   {:label "n above node count"   :nodes cap-nodes         :n 9 :pol (p/policy {})}
   {:label "equal weights, n=3"   :nodes cap-nodes         :n 3 :pol (p/policy {})}
   {:label "rack cap underfills"  :nodes cap-nodes         :n 5 :pol (p/policy {:caps {:rack 1}})}
   {:label "no sleeping nodes"    :nodes cap-nodes         :n 6 :pol (p/policy {:caps {}})}
   {:label "one sleeping node"    :nodes cap-nodes         :n 6 :pol (p/policy {:max-intermittent 1})}])

(deftest select-outcome-matches
  (testing "`:complete?` and `:shortfall` — underfill is reported, not hidden,
            and the floor at zero has to hold when the walk stopped early"
    (let [selections (mapv (fn [{:keys [nodes n pol] :as s}]
                             (assoc s :sel (p/select "pg-0" nodes n pol)))
                           select-scenarios)
          cases (into {} (mapcat (fn [[i {:keys [n sel]}]]
                                   (let [chosen (count (:nodes sel))]
                                     [[(str "sc_" i) (str "(select-complete? " n " " chosen ")")]
                                      [(str "sf_" i) (str "(select-shortfall " n " " chosen ")")]]))
                                 (map-indexed vector selections)))
          actual (placement-cases cases)]
      (doseq [[i {:keys [label sel]}] (map-indexed vector selections)]
        (is (= (b (:complete? sel)) (get actual (str "sc_" i)))
            (str label " :complete?"))
        (is (= (:shortfall sel) (get actual (str "sf_" i)))
            (str label " :shortfall")))
      (testing "the fixtures reach a shortfall and a complete group"
        (is (contains? (set (map #(:shortfall (:sel %)) selections)) 0))
        (is (some pos? (map #(:shortfall (:sel %)) selections)))))))

;; --- order: the canonical signing string ----------------------------------

(def ^:private orders
  "Deliberately includes an order with nil fields. `signing-bytes` resolves
  them host-side — `(str nil)` is the empty string and `(name (or action
  :nil))` is \"nil\" — and the guest takes the resolved values, so these
  fixtures are where that boundary is written down."
  [{:node-id "n-1" :action :get :shard-id "sh-1" :max-bytes 1024
    :expires-at 1700000000 :issued-at 1699999000 :nonce "abc"}
   {:node-id "" :action :put :shard-id "" :max-bytes 0
    :expires-at 0 :issued-at 0 :nonce ""}
   {:node-id "node-with-a-much-longer-identifier-0123456789"
    :action :repair-get :shard-id "sh-999" :max-bytes 67108864
    :expires-at 2147483647 :issued-at -1 :nonce "n0nce-!@#$%^&*()"}
   {:node-id "n" :action :audit-get :shard-id "s" :max-bytes 9007199254740993
    :expires-at -1700000000 :issued-at 0 :nonce "10"}
   {:node-id "10" :action :delete :shard-id "0" :max-bytes -1
    :expires-at 5 :issued-at 5 :nonce "0"}
   ;; A field that looks like the delimiter, which is the whole reason the
   ;; components are length-delimited rather than joined on a separator.
   {:node-id "a|b" :action :get :shard-id "1:2" :max-bytes 7
    :expires-at 8 :issued-at 9 :nonce "3:x|y"}
   ;; nil in every field that has a string rendering. `(str nil)` is "" and
   ;; `(name (or action :nil))` is "nil", so the guest sees "" and "nil". The
   ;; integers have no "absent" representation in the guest at all, which is
   ;; why they stay concrete here and why the `(some? ...)` guards in `admit`
   ;; did not cross.
   {:node-id nil :action nil :shard-id nil :max-bytes 0
    :expires-at 0 :issued-at 0 :nonce nil}])

(def ^:private order-descriptor
  (str "[:record :order/unsigned [[:node-id :string] [:action :string]"
       " [:shard-id :string] [:max-bytes :i64] [:expires-at :i64]"
       " [:issued-at :i64] [:nonce :string]]]"))

(defn- record-expr
  "How the host presents one order to the guest: strings for the identifiers,
  i64 for the limit and the two clocks, `(name (or action :nil))` already
  applied. nil becomes \"\" for the string fields, which is what `(str nil)`
  produces — but there is no i64 that means \"absent\", so a nil limit or
  clock has no guest representation at all and those orders are only checked
  on the fields that do."
  [{:keys [node-id action shard-id max-bytes expires-at issued-at nonce]}]
  (str "(record-new " order-descriptor " "
       (lit node-id) " " (lit (name (or action :nil))) " " (lit shard-id) " "
       (or max-bytes 0) " " (or expires-at 0) " " (or issued-at 0) " "
       (lit nonce) ")"))

(deftest signing-bytes-matches
  (testing "byte-for-byte: field order, the length delimiter, and how an
            integer is printed. A verifier that reassembles this differently
            verifies a different statement."
    (let [cases (into {} (mapcat (fn [[i ord]]
                                   [[(str "sb_" i) [:string (str "(signing-bytes " (record-expr ord) ")")]]
                                    [(str "sh_" i) [:string (str "(signing-head " (lit (:node-id ord)) " "
                                                                 (lit (name (or (:action ord) :nil))) " "
                                                                 (lit (:shard-id ord)) ")")]]])
                                 (map-indexed vector orders)))
          actual (order-cases cases)]
      (doseq [[i ord] (map-indexed vector orders)]
        (is (= (o/signing-bytes ord) (get actual (str "sb_" i)))
            (str "signing-bytes " (pr-str ord)))
        (is (str/starts-with? (o/signing-bytes ord) (get actual (str "sh_" i)))
            (str "signing-head is a prefix of the whole statement for " (pr-str ord)))))))

(deftest field-part-matches
  (testing "one length-delimited component, including the empty string and a
            component that contains the delimiter — the whole reason the parts
            are length-prefixed rather than joined on a separator.

            The oracle is the `.cljc`'s own output: put the string in the
            first field and the real `signing-bytes` must begin with the
            version tag followed by exactly what the guest rendered."
    (let [ss ["" "a" "abc" "0" "1:2" "a|b" "0123456789" (apply str (repeat 130 \x))]
          cases (into {} (map-indexed (fn [i s] [(str "fp_" i) [:string (str "(field-part " (lit s) ")")]]) ss))
          actual (order-cases cases)]
      (doseq [[i s] (map-indexed vector ss)]
        (let [guest (get actual (str "fp_" i))
              whole (o/signing-bytes {:node-id s :action :get :shard-id "s"
                                      :max-bytes 0 :expires-at 0 :issued-at 0 :nonce "n"})]
          (is (= (str "kura-order-v1|" guest)
                 (subs whole 0 (+ (count "kura-order-v1|") (count guest))))
              (str "field-part " (pr-str s) " -- guest " (pr-str guest)
                   " against " (pr-str whole))))))))

(deftest settlement-leaf-id-matches
  (let [pairs [["n-1" "abc"] ["" ""] ["n" "0"] ["a|b" "c|d"] ["node-1" "7"]]
        cases (into {} (map-indexed (fn [i [nid nonce]]
                                      [(str "sl_" i) [:string (str "(settlement-leaf-id " (lit nid) " " (lit nonce) ")")]])
                                    pairs))
        actual (order-cases cases)]
    (doseq [[i [nid nonce]] (map-indexed vector pairs)]
      (is (= (:id (o/settlement-leaf {:node-id nid :nonce nonce} 0 "h"))
             (get actual (str "sl_" i)))
          (str "settlement-leaf-id " nid " " nonce)))))

;; --- order: admission -----------------------------------------------------

(deftest known-action-matches
  (testing "the closed set an order may authorise — repair-get is separate
            from get on purpose and must not fall through to it"
    (let [names ["put" "get" "delete" "repair-get" "audit-get"
                 "GET" "Put" "" "get " " get" "repair" "repair-get-x" "audit"]
          cases (into {} (map-indexed (fn [i n] [(str "ka_" i) (str "(known-action? " (lit n) ")")]) names))
          actual (order-cases cases)]
      (doseq [[i n] (map-indexed vector names)]
        (is (= (b (contains? o/actions (keyword n))) (get actual (str "ka_" i)))
            (str "known-action? " (pr-str n)))))))

(deftest clock-decisions-match
  (testing "expiry is inclusive of the skew window and validity has no skew at
            all — the asymmetry IS the clock policy"
    (let [triples [[100 99 0] [100 100 0] [100 101 0]
                   [100 104 5] [100 105 5] [100 106 5]
                   [0 0 0] [-5 -5 0] [-5 -4 0]
                   [1700000000 1700000000 60] [1700000000 1700000060 60] [1700000000 1700000061 60]]
          pairs [[100 99] [100 100] [100 101] [0 0] [-5 -6] [-5 -5]]
          cases (merge
                 (into {} (map-indexed (fn [i [e n s]]
                                         [(str "ex_" i) (str "(expired? " e " " n " " s ")")])
                                       triples))
                 (into {} (map-indexed (fn [i [ia n]]
                                         [(str "nv_" i) (str "(not-yet-valid? " ia " " n ")")])
                                       pairs)))
          actual (order-cases cases)]
      (doseq [[i [e n s]] (map-indexed vector triples)]
        (is (= (b (#'o/expired? e n s)) (get actual (str "ex_" i)))
            (str "expired? expires-at=" e " now=" n " skew=" s)))
      (doseq [[i [ia n]] (map-indexed vector pairs)]
        ;; `not-yet-valid?` has no named `.cljc` function; drive the real
        ;; `admit` and read whether it raised the reason.
        (let [res (o/admit {:node-id "n" :action :get :shard-id "s" :max-bytes 1
                            :expires-at 999999 :issued-at ia :nonce "x"}
                           {:node-id "n" :action :get :now n
                            :verify-fn (fn [_ _] true) :signature "sig"})
              raised (some #(= :order-not-yet-valid (:reason %)) (:reasons res))]
          (is (= (b raised) (get actual (str "nv_" i)))
              (str "not-yet-valid? issued-at=" ia " now=" n)))))))

(deftest within-limit-matches
  (testing "checked against the running total, and inclusive at exactly the
            limit — the client controls how much it sends"
    (let [triples [[100 0 0] [100 0 100] [100 0 101] [100 99 1] [100 99 2]
                   [100 100 0] [100 100 1] [0 0 0] [0 0 1] [100 60 40] [100 60 41]]
          cases (into {} (map-indexed (fn [i [m t n]]
                                        [(str "wl_" i) (str "(within-limit? " m " " t " " n ")")])
                                      triples))
          actual (order-cases cases)]
      (doseq [[i [m t n]] (map-indexed vector triples)]
        (is (= (b (o/within-limit? {:max-bytes m} t n)) (get actual (str "wl_" i)))
            (str "within-limit? max=" m " transferred=" t " more=" n))))))

(def ^:private good-order
  {:node-id "n-1" :action :get :shard-id "sh-1" :max-bytes 1024
   :expires-at 1000 :issued-at 500 :nonce "abc"})

(def ^:private admit-scenarios
  [{:label "valid, good signature"  :ord good-order :verify true  :sig "sig"}
   {:label "valid, bad signature"   :ord good-order :verify false :sig "sig"}
   {:label "valid, no verifier"     :ord good-order :verify nil   :sig "sig"}
   {:label "missing signature"      :ord good-order :verify true  :sig nil}
   {:label "addressed elsewhere"    :ord (assoc good-order :node-id "n-2") :verify true :sig "sig"}
   {:label "expired"                :ord (assoc good-order :expires-at 100) :verify true :sig "sig"}
   {:label "not yet valid"          :ord (assoc good-order :issued-at 900) :verify true :sig "sig"}
   {:label "unknown action"         :ord (assoc good-order :action :frobnicate) :verify true :sig "sig"}
   {:label "action mismatch"        :ord (assoc good-order :action :put) :verify true :sig "sig"}
   {:label "missing nonce"          :ord (dissoc good-order :nonce) :verify true :sig "sig"}
   {:label "negative limit"         :ord (assoc good-order :max-bytes -1) :verify true :sig "sig"}
   {:label "missing shard"          :ord (dissoc good-order :shard-id) :verify true :sig "sig"}
   {:label "two problems at once"   :ord (assoc good-order :node-id "n-2" :action :put) :verify true :sig "sig"}])

(def ^:private crypto-reasons #{:no-verifier-configured :bad-coordinator-signature})

(deftest admit-composition-matches
  (testing "the two structural decisions inside `admit`: that the signature
            check comes last and is SKIPPED when the order is already rejected
            on its contents (a node under a flood of malformed orders does no
            asymmetric crypto), and that `:ok?` needs all three of coherent
            contents, a configured verifier, and a good signature.

            The oracle for 'did crypto run' is the real `verify-fn` counting
            its own invocations, not a restatement of the rule."
    (let [observed
          (mapv (fn [{:keys [ord verify sig] :as s}]
                  (let [calls (atom 0)
                        res (o/admit ord (cond-> {:node-id "n-1" :action :get :now 700
                                                  :signature sig}
                                           (some? verify)
                                           (assoc :verify-fn (fn [_ _] (swap! calls inc) verify))))]
                    (assoc s
                           :res res
                           :called @calls
                           :content-problems (count (remove #(crypto-reasons (:reason %)) (:reasons res)))
                           :verifier-present (b (some? verify))
                           :signature-valid (b verify))))
                admit-scenarios)
          cases (into {} (mapcat (fn [[i {:keys [content-problems verifier-present signature-valid]}]]
                                   [[(str "scn_" i) (str "(signature-check-needed? " content-problems
                                                         " " verifier-present ")")]
                                    [(str "ok_" i) (str "(admit-ok? " content-problems " "
                                                        verifier-present " " signature-valid ")")]])
                                 (map-indexed vector observed)))
          actual (order-cases cases)]
      (doseq [[i {:keys [label res called]}] (map-indexed vector observed)]
        (is (= (b (pos? called)) (get actual (str "scn_" i)))
            (str label ": verify-fn was " (if (pos? called) "" "not ") "called"))
        (is (= (b (:ok? res)) (get actual (str "ok_" i)))
            (str label ": :ok? was " (:ok? res) " reasons " (mapv :reason (:reasons res)))))
      (testing "and the fixtures actually reach both sides of each decision"
        (is (some #(pos? (:called %)) observed))
        (is (some #(zero? (:called %)) observed))
        (is (some #(:ok? (:res %)) observed))
        (is (some #(not (:ok? (:res %))) observed))
        (is (some #(and (pos? (:content-problems %)) (zero? (:called %))) observed)
            "an order rejected on its contents must reach no crypto at all")))))

;; --- backend admission ----------------------------------------------------

(deftest placement-core-is-native-qualifiable
  (testing "every function in the placement port is i64/string in and out,
            which is what the x86-64 and aarch64 AOT backends admit. Run
            through the real gate rather than asserted from the source."
    (is (true? (harness/native-qualifiable? placement-port "placement-core")))))

(deftest order-core-is-not-native-qualifiable
  (testing "and the order port is not, for exactly one reason: `signing-bytes`
            takes a 7-field record and `kotoba.kir` restricts native record
            fields to #{:i64 :bool}, while three of these are :string. Pinned
            so that the day the language admits string record fields this
            fails and someone gets to delete a caveat, rather than the caveat
            outliving the limit."
    (is (false? (harness/native-qualifiable? order-port "order-core")))))
