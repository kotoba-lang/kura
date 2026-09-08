(ns kura.kotoba-harness
  "Compile a `.kotoba` port in this JVM and run named probes against it.

  Same shape as `erasure.kotoba-parity-test`'s harness, factored out because
  there is now more than one port under `kotoba/`: take the port source, throw
  away its `ns` form, append one zero-arg `defn` per case, export exactly the
  case names, compile, and run each on the KIR interpreter. No marshalling and
  no runtime boundary — the guest value comes back as a Clojure value, so the
  comparison in a parity test is against the real `.cljc` function rather than
  against a serialisation of it.

  A case is `name -> expr-string` (returning `:i64`) or
  `name -> [return-type expr-string]`. Case names must not collide with the
  port's own function names: the generated export list would then contain a
  name twice and the compiler rejects it as not unique."
  (:require [kotoba.lang.text :as str]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(defn strip-ns-form
  "The port source with its leading `(ns ...)` form removed.

  Paren-balanced rather than line-based, because the form spans lines. It does
  NOT understand strings, so a port whose `ns` form carries a docstring with an
  unbalanced paren in it would break this — none do, and the convention in
  this repo is a bare `ns` form followed by `;;` prose."
  [src]
  (let [start (str/index-of src "(ns ")]
    (loop [i start depth 0]
      (let [c (.charAt ^String src i)
            depth (cond (= c \() (inc depth) (= c \)) (dec depth) :else depth)]
        (if (and (zero? depth) (> i start))
          (subs src (inc i))
          (recur (inc i) depth))))))

(defn- case-source [n case]
  (let [[ret expr] (if (vector? case) case [:i64 case])]
    (str "(defn " n " [] " ret " " expr ")")))

(defn port-source
  "The compilable source for `port-path` plus one probe per case."
  [port-path guest-ns cases]
  (let [names (sort (keys cases))]
    (str "(ns " guest-ns " (:export [" (str/join " " names) "]))\n"
         (strip-ns-form (slurp port-path)) "\n"
         (str/join "\n" (map #(case-source % (get cases %)) names)))))

(defn compile-kir [port-path guest-ns cases]
  (:kir (compiler/compile-source (port-source port-path guest-ns cases)
                                 :wasm32-kotoba-v1 {})))

(defn run-cases
  "Case name -> the value the guest computed for it."
  [port-path guest-ns cases]
  (let [kir (compile-kir port-path guest-ns cases)]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])])) (keys cases))))

(defn native-qualifiable?
  "Whether every function in the compiled port clears
  `kotoba.kir/only-native-word-typed-features?` — the admission gate the
  x86-64 and aarch64 AOT backends are behind.

  Run rather than asserted from the source: the gate walks the lowered HIR,
  and reasoning about it from `.kotoba` text is exactly the kind of claim that
  is wrong for a year before anyone checks."
  [port-path guest-ns]
  ;; One trivial case, because the harness always needs at least one export.
  (ir/only-native-word-typed-features?
   (compile-kir port-path guest-ns {"probe0" "0"})))
