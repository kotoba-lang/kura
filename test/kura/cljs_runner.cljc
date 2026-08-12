(ns kura.cljs-runner
  "Portable suite under a real ClojureScript host (fleet runtime priority puts
  cljs ahead of the JVM).

    clojure -M:cljs -m cljs.main --target node -m kura.cljs-runner

  The `.kotoba` parity gates (`kura.kotoba-parity-test`,
  `kura.kotoba-decision-parity-test`) are absent: they need
  kotoba-lang/compiler, which is JVM-only. What they gate — the placement
  arithmetic, the cap decisions, the order signing string — is exercised here
  too through the `.cljc` itself, against the second host, which is its own
  kind of parity check: `kura.hash` is the one namespace where clj and cljs
  disagree about 32-bit arithmetic.

  The order decisions are no longer computed by the `.cljc` at all — they run
  from `resources/kura/oracle/order.kir.edn`, and this runtime has no
  classpath to read that from. So `-main` registers it, which is the same one
  line any ClojureScript host of `kura.order` now needs (see that namespace's
  boundary note). Reading the shipped file rather than embedding a copy is the
  point: this suite exercises the artifact a JVM consumer would load."
  (:require [clojure.test :as t :refer [run-tests]]
            #?(:cljs [cljs.reader :as reader])
            [kura.kotoba-oracle :as oracle]
            [kura.audit-test]
            [kura.manifest-test]
            [kura.order-test]
            [kura.placement-test]
            [kura.repair-test]))

(defn register-shipped-cores!
  "Install every shipped decision core, read from the repository tree.

  Node has a filesystem even though ClojureScript has no classpath, so the
  artifact this reads is the same bytes `io/resource` hands the JVM. A host
  without a filesystem — a Worker, a browser — has to bring the parsed KIR
  some other way; `register-kir!` is that seam either way."
  []
  #?(:cljs (let [fs (js/require "fs")]
             (doseq [id (keys oracle/cores)]
               (oracle/register-kir!
                id (reader/read-string
                    (.readFileSync fs (str "resources/" (oracle/resource-path id)) "utf8")))))
     :clj nil))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (register-shipped-cores!)
  (run-tests 'kura.placement-test 'kura.manifest-test 'kura.repair-test
             'kura.audit-test 'kura.order-test))
