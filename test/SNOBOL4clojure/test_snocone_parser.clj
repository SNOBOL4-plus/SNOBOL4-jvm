(ns SNOBOL4clojure.test-snocone-parser
  "Tests for the Snocone expression parser (Step 2) in snocone.clj.

  snocone/parse-expression takes a flat token vector from the lexer
  and returns a postfix (RPN) vector, mirroring Parser.ShuntYardAlgorithm.

  Reduce condition (from the language specification):
    while existing-op.lp >= incoming-op.rp  → reduce

  Precedence table (lp / rp from the operator table):
    =    lp=1  rp=2   right-assoc
    ?    lp=2  rp=2   left
    |    lp=3  rp=3   left
    ||   lp=4  rp=4   left
    &&   lp=5  rp=5   left
    comparisons lp=6 rp=6 left
    + -  lp=7  rp=7   left
    / * % lp=8 rp=8   left
    ^    lp=9  rp=10  RIGHT-assoc
    . $  lp=10 rp=10  left"
  (:require [clojure.test :refer :all]
            [SNOBOL4clojure.snocone :as sc]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- parse [src]
  (let [tokens (->> (sc/tokenize src)
                    (remove #(#{:sc/newline :sc/eof} (:kind %))))]
    (sc/parse-expression tokens)))

(defn- kinds [src]
  (mapv :kind (parse src)))

;; ===========================================================================
;; 1. Single operands — pass through unchanged
;; ===========================================================================

(deftest test-single-identifier
  (is (= [:sc/identifier] (kinds "x"))))

(deftest test-single-integer
  (is (= [:sc/integer] (kinds "42"))))

(deftest test-single-string
  (is (= [:sc/string] (kinds "'hello'"))))

(deftest test-single-real
  (is (= [:sc/real] (kinds "3.14"))))

;; ===========================================================================
;; 2. Simple binary — postfix: a b op
;; ===========================================================================

(deftest test-binary-add
  (is (= [:sc/identifier :sc/identifier :sc/op-plus] (kinds "a + b"))))

(deftest test-binary-assign
  (is (= [:sc/identifier :sc/identifier :sc/op-assign] (kinds "x = y"))))

(deftest test-binary-concat
  (is (= [:sc/identifier :sc/identifier :sc/op-concat] (kinds "a && b"))))

(deftest test-binary-or
  (is (= [:sc/identifier :sc/identifier :sc/op-or] (kinds "a || b"))))

;; ===========================================================================
;; 3. Precedence
;; ===========================================================================

(deftest test-precedence-mul-before-add
  ;; a + b * c  →  a b c * +
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-star :sc/op-plus]
         (kinds "a + b * c"))))

(deftest test-precedence-add-before-compare
  ;; a == b + c  →  a b c + ==
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-plus :sc/op-eq]
         (kinds "a == b + c"))))

(deftest test-precedence-concat-before-or
  ;; a || b && c  →  a b c && ||
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-concat :sc/op-or]
         (kinds "a || b && c"))))

(deftest test-precedence-dot-higher-than-add
  ;; a + b . c  →  a b c . +
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-period :sc/op-plus]
         (kinds "a + b . c"))))

(deftest test-precedence-caret-higher-than-mul
  ;; a * b ^ c  →  a b c ^ *
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-caret :sc/op-star]
         (kinds "a * b ^ c"))))

(deftest test-precedence-compare-before-assign
  ;; x = a == b  →  x a b == =
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-eq :sc/op-assign]
         (kinds "x = a == b"))))

;; ===========================================================================
;; 4. Associativity
;; ===========================================================================

(deftest test-assoc-add-left
  ;; a + b + c  →  a b + c +
  (is (= [:sc/identifier :sc/identifier :sc/op-plus :sc/identifier :sc/op-plus]
         (kinds "a + b + c"))))

(deftest test-assoc-mul-left
  (is (= [:sc/identifier :sc/identifier :sc/op-star :sc/identifier :sc/op-star]
         (kinds "a * b * c"))))

(deftest test-assoc-concat-left
  (is (= [:sc/identifier :sc/identifier :sc/op-concat :sc/identifier :sc/op-concat]
         (kinds "a && b && c"))))

(deftest test-assoc-caret-right
  ;; a ^ b ^ c  →  a b c ^ ^   (lp=9 < rp=10, second ^ does NOT reduce first)
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-caret :sc/op-caret]
         (kinds "a ^ b ^ c"))))

(deftest test-assoc-assign-right
  ;; a = b = c  →  a b c = =   (lp=1 < rp=2)
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-assign :sc/op-assign]
         (kinds "a = b = c"))))

;; ===========================================================================
;; 5. Unary operators — postfix with :unary? true
;; ===========================================================================

(deftest test-unary-minus
  (let [r (parse "-x")]
    (is (= 2 (count r)))
    (is (= :sc/identifier (:kind (first r))))
    (is (= :sc/op-minus   (:kind (second r))))
    (is (:unary? (second r)))))

(deftest test-unary-tilde
  (let [r (parse "~x")]
    (is (= :sc/op-tilde (:kind (second r))))
    (is (:unary? (second r)))))

(deftest test-unary-star-unevaluated
  (let [r (parse "*p")]
    (is (= :sc/op-star (:kind (second r))))
    (is (:unary? (second r)))))

(deftest test-unary-binds-tighter-than-binary
  ;; a + -b  →  a b unary- +
  (let [r (parse "a + -b")]
    (is (= 4 (count r)))
    (is (= :sc/identifier (:kind (nth r 0))))
    (is (= :sc/identifier (:kind (nth r 1))))
    (is (= :sc/op-minus   (:kind (nth r 2))))
    (is (:unary? (nth r 2)))
    (is (= :sc/op-plus    (:kind (nth r 3))))
    (is (not (:unary? (nth r 3))))))

;; ===========================================================================
;; 6. Parentheses — override precedence
;; ===========================================================================

(deftest test-parens-override-precedence
  ;; (a + b) * c  →  a b + c *
  (is (= [:sc/identifier :sc/identifier :sc/op-plus :sc/identifier :sc/op-star]
         (kinds "(a + b) * c"))))

(deftest test-parens-nested
  ;; (a + (b * c))  →  a b c * +
  (is (= [:sc/identifier :sc/identifier :sc/identifier :sc/op-star :sc/op-plus]
         (kinds "(a + (b * c))"))))

;; ===========================================================================
;; 7. Function calls and array refs
;; ===========================================================================

(deftest test-call-no-args
  (let [r (parse "f()")]
    (is (= :sc/identifier (:kind (nth r 0))))
    (is (= "f"            (:text (nth r 0))))
    (is (= :sc/sc-call    (:kind (nth r 1))))
    (is (= 0              (:arg-count (nth r 1))))))

(deftest test-call-one-arg
  (let [r (parse "f(x)")]
    (is (= :sc/identifier (:kind (nth r 0))))
    (is (= :sc/identifier (:kind (nth r 1))))
    (is (= :sc/sc-call    (:kind (nth r 2))))
    (is (= 1              (:arg-count (nth r 2))))))

(deftest test-call-two-args
  (let [r (parse "f(x, y)")]
    (is (= :sc/identifier (:kind (nth r 0))))
    (is (= :sc/identifier (:kind (nth r 1))))
    (is (= :sc/identifier (:kind (nth r 2))))
    (is (= :sc/sc-call    (:kind (nth r 3))))
    (is (= 2              (:arg-count (nth r 3))))))

(deftest test-array-ref-one-index
  (let [r (parse "arr[i]")]
    (is (= :sc/identifier  (:kind (nth r 0))))
    (is (= :sc/identifier  (:kind (nth r 1))))
    (is (= :sc/sc-array-ref (:kind (nth r 2))))
    (is (= 1               (:arg-count (nth r 2))))))

(deftest test-call-arg-is-expression
  ;; f(a + b)  →  f a b + CALL(1)
  (let [r (parse "f(a + b)")]
    (is (= :sc/identifier (:kind (nth r 0))))
    (is (= :sc/identifier (:kind (nth r 1))))
    (is (= :sc/identifier (:kind (nth r 2))))
    (is (= :sc/op-plus    (:kind (nth r 3))))
    (is (= :sc/sc-call    (:kind (nth r 4))))
    (is (= 1              (:arg-count (nth r 4))))))

;; ===========================================================================
;; 8. String comparison operators (all lp=6 rp=6)
;; ===========================================================================

(deftest test-str-eq
  (is (= [:sc/identifier :sc/identifier :sc/op-str-eq] (kinds "a :==: b"))))

(deftest test-str-ne
  (is (= [:sc/identifier :sc/identifier :sc/op-str-ne] (kinds "a :!=: b"))))

(deftest test-str-same-prec-as-numeric
  ;; a == b :==: c  →  a b == c :==:  (left-assoc, lp=rp=6)
  (is (= [:sc/identifier :sc/identifier :sc/op-eq :sc/identifier :sc/op-str-eq]
         (kinds "a == b :==: c"))))

;; ===========================================================================
;; 9. dotck — leading-dot float gets "0." prepended
;; ===========================================================================

(deftest test-dotck-leading-dot-rewritten
  (let [r (parse ".5")]
    (is (= 1         (count r)))
    (is (= :sc/real  (:kind (first r))))
    (is (= "0.5"     (:text (first r))))))

(deftest test-dotck-normal-float-unchanged
  (let [r (parse "3.14")]
    (is (= "3.14" (:text (first r))))))
