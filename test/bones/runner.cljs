(ns bones.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [bones.client-test]
            ))

(doo-tests 'bones.client-test
           )
