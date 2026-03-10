(ns SNOBOL4clojure.main
  "Thin AOT entry point. No requires here — keeps Greek-symbol namespaces
   (env, operators, engine_frame, match) out of AOT compilation entirely.
   Delegates to SNOBOL4clojure.core/-main at runtime after dynamic load."
  (:gen-class
   :main true))

(defn -main [& args]
  (require 'SNOBOL4clojure.core)
  (apply (resolve 'SNOBOL4clojure.core/-main) args))
