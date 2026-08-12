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
  disagree about 32-bit arithmetic."
  (:require [clojure.test :as t :refer [run-tests]]
            [kura.audit-test]
            [kura.manifest-test]
            [kura.order-test]
            [kura.placement-test]
            [kura.repair-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'kura.placement-test 'kura.manifest-test 'kura.repair-test
             'kura.audit-test 'kura.order-test))
