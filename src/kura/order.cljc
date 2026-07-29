(ns kura.order
  "Whether a node should do a piece of work, and be paid for having done it.

  An order is a capability: the coordinator signs a statement saying *this*
  node may perform *this* action on *this* shard, up to *this* many bytes,
  until *this* time. A node that checks the signature and nothing else will
  serve an order addressed to someone else, or one that expired last month, or
  one that authorises a read when the request is a write. Each of those is a
  way to be used as somebody else's storage, for free.

  The shape is lifted deliberately from `storj.node.orders/admit`, already in
  this workspace — including the two judgement calls that make it good, which
  are worth restating because they are easy to undo by accident:

  - **Every reason, not the first.** A caller wanting yes/no reads `:ok?`. A
    caller debugging a coordinator integration wants all of them at once, and
    stopping at the first failure turns that into a guessing game.
  - **The signature check is last, and is skipped when the order is already
    rejected on its contents.** That is not an optimisation. It means a node
    under a flood of malformed orders does no asymmetric crypto on any of
    them.

  Orders are also the unit of payment (ADR-2607299200 section 6d): a node
  accumulates the orders it honoured and presents them at epoch close, where
  they are folded into a `merkle-sum` tree whose root goes on chain. So an
  order that `admit` accepted is a claim on money, and the checks here are the
  only thing standing between a coordinator's signature and an invoice."
  (:require [kura.hash :as h]))

(def actions
  "What an order can authorise. `:put` and `:get` are client traffic; `:repair-get`
  is a peer reading a shard to rebuild another, and is separated because it is
  paid differently and must not be usable to serve a client read."
  #{:put :get :delete :repair-get :audit-get})

(def default-clock-skew-seconds
  "How far past its expiry an order is still honoured.

  Nodes and coordinators do not share a clock, and a node that trusts its own
  to the second rejects work it was legitimately given. It cuts one way only:
  an order is never honoured *before* it exists."
  0)

(defn order
  "Construct an unsigned order. `:nonce` makes two otherwise identical orders
  distinguishable, so that replaying a settled order is detectable at
  settlement rather than only at admission."
  [{:keys [node-id action shard-id max-bytes expires-at issued-at nonce]}]
  {:node-id node-id :action action :shard-id shard-id
   :max-bytes max-bytes :expires-at expires-at
   :issued-at issued-at :nonce nonce})

(defn signing-bytes
  "The canonical string an order's signature covers.

  Field order is fixed here and is part of the wire contract — a verifier that
  reassembles it differently verifies a different statement. Every component
  is length-delimited so that no two distinct orders can serialise the same
  way by shifting a delimiter into a field."
  [{:keys [node-id action shard-id max-bytes expires-at issued-at nonce]}]
  (let [part (fn [x] (let [s (str x)] (str (count s) ":" s)))]
    (apply str "kura-order-v1|"
           (map part [node-id (name (or action :nil)) shard-id
                      max-bytes expires-at issued-at nonce]))))

(defn- expired? [expires-at now skew]
  (and (some? expires-at) (some? now) (> now (+ expires-at skew))))

(defn admit
  "Decide whether `ord` may be acted on. Returns
  `{:ok? :reasons :action :max-bytes :shard-id}`.

  `opts`: `:node-id` (this node's own id — `is this addressed to me` is the
  check most worth not skipping), `:action` (what the request is actually
  asking for), `:now` (seconds), `:verify-fn` (`(fn [message signature] bool)`,
  injected so this namespace holds no crypto), `:signature`, `:skew-seconds`,
  and `:seen-nonce?` (`(fn [nonce] bool)` for replay)."
  [ord {:keys [node-id action now verify-fn signature skew-seconds seen-nonce?]
        :or {skew-seconds default-clock-skew-seconds}}]
  (let [content
        (cond-> []
          (nil? (:node-id ord))
          (conj {:reason :missing-node-id})

          (nil? node-id)
          (conj {:reason :no-node-id-configured})

          (and (some? (:node-id ord)) (some? node-id) (not= (:node-id ord) node-id))
          (conj {:reason :addressed-to-another-node
                 :expected node-id :found (:node-id ord)})

          (nil? (:shard-id ord))
          (conj {:reason :missing-shard-id})

          (nil? (:action ord))
          (conj {:reason :missing-action})

          (and (some? (:action ord)) (not (contains? actions (:action ord))))
          (conj {:reason :unknown-action :value (:action ord)})

          (and (some? action) (some? (:action ord)) (not= action (:action ord)))
          (conj {:reason :action-mismatch :requested action :authorised (:action ord)})

          (or (nil? (:max-bytes ord)) (not (integer? (:max-bytes ord)))
              (neg? (:max-bytes ord)))
          (conj {:reason :missing-or-invalid-limit :value (:max-bytes ord)})

          (expired? (:expires-at ord) now skew-seconds)
          (conj {:reason :order-expired :expired-at (:expires-at ord) :now now})

          (and (some? (:issued-at ord)) (some? now) (< now (:issued-at ord)))
          (conj {:reason :order-not-yet-valid :issued-at (:issued-at ord) :now now})

          (nil? (:nonce ord))
          (conj {:reason :missing-nonce})

          (and (some? seen-nonce?) (some? (:nonce ord)) (seen-nonce? (:nonce ord)))
          (conj {:reason :replayed-nonce :nonce (:nonce ord)})

          (nil? signature)
          (conj {:reason :missing-signature}))

        ;; Only reached when the order is internally coherent — see the
        ;; namespace docstring on why the crypto comes last.
        problems
        (if (seq content)
          content
          (cond-> []
            (nil? verify-fn)
            (conj {:reason :no-verifier-configured})

            (and (some? verify-fn)
                 (not (verify-fn (signing-bytes ord) signature)))
            (conj {:reason :bad-coordinator-signature})))]
    {:ok? (empty? problems)
     :reasons problems
     :action (:action ord)
     :max-bytes (:max-bytes ord)
     :shard-id (:shard-id ord)}))

(defn within-limit?
  "Whether transferring `n` more bytes stays inside what the order allows.

  A node that checks the limit once at the start and then streams has not
  checked it: the client controls how much it sends."
  [{:keys [max-bytes]} transferred n]
  (and (some? max-bytes) (<= (+ transferred n) max-bytes)))

;; --- settlement ------------------------------------------------------------

(defn settlement-leaf
  "One honoured order as a `merkle-sum` leaf: `:sum` is the bytes actually
  transferred, so an epoch's root sums to the total volume the coordinator
  owes for.

  This is what makes the payout auditable (ADR-2607299200 section 6d). The
  coordinator cannot understate the epoch total without contradicting some
  node's inclusion proof, and cannot inflate a node's share without
  contradicting the root it published on chain."
  [ord bytes-transferred order-hash]
  (assert (and (integer? bytes-transferred) (not (neg? bytes-transferred)))
          "bytes-transferred must be a non-negative integer")
  {:id (str (:node-id ord) "|" (:nonce ord))
   :hash order-hash
   :sum bytes-transferred})

(defn order-id
  "Stable identifier of an order, for dedupe and settlement leaf ordering."
  [ord]
  (h/key32 (signing-bytes ord)))
