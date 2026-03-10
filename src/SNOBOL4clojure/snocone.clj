(ns SNOBOL4clojure.snocone
  "Snocone front-end — Step 1: Lexer.

  Snocone (a community implementation) is a syntactic sugar layer
  for SNOBOL4 that adds C-like control flow while preserving full SNOBOL4
  semantics.  This namespace implements the lexer (tokeniser) for .sc source
  files.  The parser (Step 2) will consume the token stream produced here.

  ## Token map keys
    :kind   — keyword, one of the ::kind-* values below
    :text   — verbatim source text of the token
    :line   — 1-based physical line number where the token began

  ## Source model (from the language specification)
  - Comments: # to end of line (stripped; not in token stream).
  - Statement termination: newline ends a statement UNLESS the last
    non-whitespace character before the newline is a continuation character
    (any of @ $ % ^ & * ( - + = [ < > | ~ , ? :).
    A semicolon also terminates a statement within a line.
  - Strings: delimited by matching ' or \".
  - Numbers: integer or real (with optional exponent eEdD±).
    Leading-dot floats (.5) are legal; parser rewrites to 0.5.
  - Identifiers: [A-Za-z_][A-Za-z0-9_]*.
  - Multi-character operators matched longest-first.")

;; ---------------------------------------------------------------------------
;; Token kind constants  (keywords used as enum-like values)
;; ---------------------------------------------------------------------------

;; Literals
(def ^:const KIND-INTEGER   :sc/integer)
(def ^:const KIND-REAL      :sc/real)
(def ^:const KIND-STRING    :sc/string)
(def ^:const KIND-IDENT     :sc/identifier)

;; Keywords — matched as identifiers, then reclassified
(def ^:const KIND-IF        :sc/kw-if)
(def ^:const KIND-ELSE      :sc/kw-else)
(def ^:const KIND-WHILE     :sc/kw-while)
(def ^:const KIND-DO        :sc/kw-do)
(def ^:const KIND-FOR       :sc/kw-for)
(def ^:const KIND-RETURN    :sc/kw-return)
(def ^:const KIND-FRETURN   :sc/kw-freturn)
(def ^:const KIND-NRETURN   :sc/kw-nreturn)
(def ^:const KIND-GO        :sc/kw-go)
(def ^:const KIND-TO        :sc/kw-to)
(def ^:const KIND-PROCEDURE :sc/kw-procedure)
(def ^:const KIND-STRUCT    :sc/kw-struct)

;; Punctuation
(def ^:const KIND-LPAREN    :sc/lparen)
(def ^:const KIND-RPAREN    :sc/rparen)
(def ^:const KIND-LBRACE    :sc/lbrace)
(def ^:const KIND-RBRACE    :sc/rbrace)
(def ^:const KIND-LBRACKET  :sc/lbracket)
(def ^:const KIND-RBRACKET  :sc/rbracket)
(def ^:const KIND-COMMA     :sc/comma)
(def ^:const KIND-SEMICOLON :sc/semicolon)
(def ^:const KIND-COLON     :sc/colon)

;; Snocone binary operators (precedence low→high, from the operator table)
(def ^:const KIND-ASSIGN    :sc/op-assign)     ; =   prec 1  → SNOBOL4 =
(def ^:const KIND-QUESTION  :sc/op-question)   ; ?   prec 2  → SNOBOL4 ?
(def ^:const KIND-PIPE      :sc/op-pipe)       ; |   prec 3  → SNOBOL4 |
(def ^:const KIND-OR        :sc/op-or)         ; ||  prec 4  → SNOBOL4 (a,b)
(def ^:const KIND-CONCAT    :sc/op-concat)     ; &&  prec 5  → SNOBOL4 blank
(def ^:const KIND-EQ        :sc/op-eq)         ; ==  prec 6  → EQ(a,b)
(def ^:const KIND-NE        :sc/op-ne)         ; !=  prec 6  → NE(a,b)
(def ^:const KIND-LT        :sc/op-lt)         ; <   prec 6  → LT(a,b)
(def ^:const KIND-GT        :sc/op-gt)         ; >   prec 6  → GT(a,b)
(def ^:const KIND-LE        :sc/op-le)         ; <=  prec 6  → LE(a,b)
(def ^:const KIND-GE        :sc/op-ge)         ; >=  prec 6  → GE(a,b)
(def ^:const KIND-STR-IDENT :sc/op-str-ident)  ; ::  prec 6  → IDENT(a,b)
(def ^:const KIND-STR-DIFF  :sc/op-str-differ) ; :!: prec 6  → DIFFER(a,b)
(def ^:const KIND-STR-LT    :sc/op-str-lt)     ; :<: prec 6  → LLT(a,b)
(def ^:const KIND-STR-GT    :sc/op-str-gt)     ; :>: prec 6  → LGT(a,b)
(def ^:const KIND-STR-LE    :sc/op-str-le)     ; :<=: prec 6 → LLE(a,b)
(def ^:const KIND-STR-GE    :sc/op-str-ge)     ; :>=: prec 6 → LGE(a,b)
(def ^:const KIND-STR-EQ    :sc/op-str-eq)     ; :==: prec 6 → LEQ(a,b)
(def ^:const KIND-STR-NE    :sc/op-str-ne)     ; :!=: prec 6 → LNE(a,b)
(def ^:const KIND-PLUS      :sc/op-plus)       ; +   prec 7
(def ^:const KIND-MINUS     :sc/op-minus)      ; -   prec 7
(def ^:const KIND-SLASH     :sc/op-slash)      ; /   prec 8
(def ^:const KIND-STAR      :sc/op-star)       ; *   prec 8
(def ^:const KIND-PERCENT   :sc/op-percent)    ; %   prec 8  → REMDR(a,b)
(def ^:const KIND-CARET     :sc/op-caret)      ; ^   prec 9/10 right-assoc → **
(def ^:const KIND-PERIOD    :sc/op-period)     ; .   prec 10 → SNOBOL4 .
(def ^:const KIND-DOLLAR    :sc/op-dollar)     ; $   prec 10 → SNOBOL4 $

;; Unary-only operators
(def ^:const KIND-AT        :sc/op-at)         ; @
(def ^:const KIND-AMPERSAND :sc/op-ampersand)  ; &
(def ^:const KIND-TILDE     :sc/op-tilde)      ; ~ (logical negation)

;; Synthetic
(def ^:const KIND-NEWLINE   :sc/newline)        ; logical end-of-statement
(def ^:const KIND-EOF       :sc/eof)
(def ^:const KIND-UNKNOWN   :sc/unknown)

;; ---------------------------------------------------------------------------
;; Keyword table
;; ---------------------------------------------------------------------------

(def ^:private keywords
  {"if"        KIND-IF
   "else"      KIND-ELSE
   "while"     KIND-WHILE
   "do"        KIND-DO
   "for"       KIND-FOR
   "return"    KIND-RETURN
   "freturn"   KIND-FRETURN
   "nreturn"   KIND-NRETURN
   "go"        KIND-GO
   "to"        KIND-TO
   "procedure" KIND-PROCEDURE
   "struct"    KIND-STRUCT})

;; ---------------------------------------------------------------------------
;; Continuation characters (from the language specification)
;;   ANY("@$%^&*(-+=[<>|~,?:")
;; ---------------------------------------------------------------------------

(def ^:private continuation-chars
  #{\@ \$ \% \^ \& \* \( \- \+ \= \[ \< \> \| \~ \, \? \:})

;; ---------------------------------------------------------------------------
;; Operator table — longest-match (sorted by descending length at build time)
;; ---------------------------------------------------------------------------

(def ^:private op-entries
  [;; 4-char
   [":!=:" KIND-STR-NE]
   [":<=:" KIND-STR-LE]
   [":>=:" KIND-STR-GE]
   [":==:" KIND-STR-EQ]
   ;; 3-char
   [":!:"  KIND-STR-DIFF]
   [":<:"  KIND-STR-LT]
   [":>:"  KIND-STR-GT]
   ;; 2-char
   ["::"   KIND-STR-IDENT]
   ["||"   KIND-OR]
   ["&&"   KIND-CONCAT]
   ["=="   KIND-EQ]
   ["!="   KIND-NE]
   ["<="   KIND-LE]
   [">="   KIND-GE]
   ["**"   KIND-CARET]   ; SNOBOL4 ** same as ^
   ;; 1-char
   ["="    KIND-ASSIGN]
   ["?"    KIND-QUESTION]
   ["|"    KIND-PIPE]
   ["+"    KIND-PLUS]
   ["-"    KIND-MINUS]
   ["/"    KIND-SLASH]
   ["*"    KIND-STAR]
   ["%"    KIND-PERCENT]
   ["^"    KIND-CARET]
   ["."    KIND-PERIOD]
   ["$"    KIND-DOLLAR]
   ["&"    KIND-AMPERSAND]
   ["@"    KIND-AT]
   ["~"    KIND-TILDE]
   ["<"    KIND-LT]
   [">"    KIND-GT]
   ["("    KIND-LPAREN]
   [")"    KIND-RPAREN]
   ["{"    KIND-LBRACE]
   ["}"    KIND-RBRACE]
   ["["    KIND-LBRACKET]
   ["]"    KIND-RBRACKET]
   [","    KIND-COMMA]
   [";"    KIND-SEMICOLON]
   [":"    KIND-COLON]])

;; Pre-sort by descending length for longest-match
(def ^:private ops-by-length
  (sort-by (comp - count first) op-entries))

;; ---------------------------------------------------------------------------
;; Helper: strip # comment respecting string literals
;; ---------------------------------------------------------------------------

(defn- strip-comment
  "Return the portion of `line` before any unquoted # character."
  [^String line]
  (loop [i 0 in-single false in-double false]
    (if (>= i (count line))
      line
      (let [c (.charAt line i)]
        (cond
          (and (= c \') (not in-double)) (recur (inc i) (not in-single) in-double)
          (and (= c \") (not in-single)) (recur (inc i) in-single (not in-double))
          (and (= c \#) (not in-single) (not in-double)) (subs line 0 i)
          :else (recur (inc i) in-single in-double))))))

;; ---------------------------------------------------------------------------
;; Helper: is this stripped line a continuation?
;; ---------------------------------------------------------------------------

(defn- continuation?
  "True when the last non-whitespace char of stripped line is a continuation char."
  [^String stripped]
  (let [trimmed (clojure.string/trimr stripped)]
    (and (pos? (count trimmed))
         (continuation-chars (.charAt trimmed (dec (count trimmed)))))))

;; ---------------------------------------------------------------------------
;; Helper: split on unquoted semicolons
;; ---------------------------------------------------------------------------

(defn- split-semicolon
  "Split `line` on semicolons that are not inside string literals."
  [^String line]
  (loop [i 0 in-single false in-double false buf (StringBuilder.) result []]
    (if (>= i (count line))
      (conj result (str buf))
      (let [c (.charAt line i)]
        (cond
          (and (= c \') (not in-double))
          (recur (inc i) (not in-single) in-double (.append buf c) result)
          (and (= c \") (not in-single))
          (recur (inc i) in-single (not in-double) (.append buf c) result)
          (and (= c \;) (not in-single) (not in-double))
          (recur (inc i) false false (StringBuilder.) (conj result (str buf)))
          :else
          (recur (inc i) in-single in-double (.append buf c) result))))))

;; ---------------------------------------------------------------------------
;; Number scanner
;; ---------------------------------------------------------------------------

(defn- scan-number
  "Scan a number token starting at position `start` in `seg`.
  Returns [kind text end-pos]."
  [^String seg start]
  (let [len (count seg)]
    (loop [pos start real? (= \. (.charAt seg start))]
      ;; Consume integer digits
      (let [pos (loop [p pos]
                  (if (and (< p len) (Character/isDigit (.charAt seg p)))
                    (recur (inc p))
                    p))
            ;; Check for decimal part
            [pos real?]
            (if (and (not real?)
                     (< pos len)
                     (= \. (.charAt seg pos))
                     (< (inc pos) len)
                     (Character/isDigit (.charAt seg (inc pos))))
              [(loop [p (inc pos)]
                 (if (and (< p len) (Character/isDigit (.charAt seg p)))
                   (recur (inc p))
                   p))
               true]
              [pos real?])
            ;; Check for exponent eEdD [+-] digits
            [pos real?]
            (if (and (< pos len) (#{\e \E \d \D} (.charAt seg pos)))
              (let [p (inc pos)
                    p (if (and (< p len) (#{\+ \-} (.charAt seg p))) (inc p) p)
                    p (loop [p p]
                        (if (and (< p len) (Character/isDigit (.charAt seg p)))
                          (recur (inc p))
                          p))]
                [p true])
              [pos real?])]
        [(if real? KIND-REAL KIND-INTEGER)
         (subs seg start pos)
         pos]))))

;; ---------------------------------------------------------------------------
;; Segment tokenizer
;; ---------------------------------------------------------------------------

(defn- tokenize-segment
  "Tokenize one logical statement segment; append tokens to `acc` (transient vector)."
  [^String seg line-no acc]
  (let [len (count seg)]
    (loop [pos 0 acc acc]
      (if (>= pos len)
        acc
        (let [c (.charAt seg pos)]
          (cond
            ;; Whitespace
            (Character/isWhitespace c)
            (recur (inc pos) acc)

            ;; String literal
            (or (= c \') (= c \"))
            (let [quote c
                  start pos
                  end   (loop [p (inc pos)]
                          (cond
                            (>= p len)          p
                            (= (.charAt seg p) quote) (inc p)
                            :else               (recur (inc p))))]
              (recur end
                     (conj! acc {:kind KIND-STRING :text (subs seg start end) :line line-no})))

            ;; Number: digit or leading-dot float
            (or (Character/isDigit c)
                (and (= c \.)
                     (< (inc pos) len)
                     (Character/isDigit (.charAt seg (inc pos)))))
            (let [[kind text end] (scan-number seg pos)]
              (recur end (conj! acc {:kind kind :text text :line line-no})))

            ;; Identifier / keyword
            (or (Character/isLetter c) (= c \_))
            (let [end (loop [p (inc pos)]
                        (let [ch (when (< p len) (.charAt seg p))]
                          (if (and ch (or (Character/isLetterOrDigit ch) (= ch \_)))
                            (recur (inc p))
                            p)))
                  word (subs seg pos end)
                  kind (get keywords word KIND-IDENT)]
              (recur end (conj! acc {:kind kind :text word :line line-no})))

            ;; Operators — longest match
            :else
            (let [[matched-text matched-kind]
                  (first (for [[text kind] ops-by-length
                               :let [end (+ pos (count text))]
                               :when (and (<= end len)
                                          (= text (subs seg pos end)))]
                           [text kind]))]
              (if matched-text
                (recur (+ pos (count matched-text))
                       (conj! acc {:kind matched-kind :text matched-text :line line-no}))
                (recur (inc pos)
                       (conj! acc {:kind KIND-UNKNOWN :text (subs seg pos (inc pos)) :line line-no}))))))))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn tokenize
  "Tokenize a complete Snocone source string.

  Returns a vector of token maps, each {:kind kw :text str :line int},
  terminated by a single {:kind :sc/eof :text \"\" :line N}.

  Handles:
  - # comment stripping
  - Continuation lines (last non-ws char is a continuation char)
  - Semicolon-separated statements on one line
  - Strings, numbers, identifiers, keywords, operators"
  [^String source]
  (let [raw-lines (clojure.string/split (clojure.string/replace source #"\r\n|\r" "\n") #"\n" -1)
        n         (count raw-lines)]
    (loop [i       0
           logical []          ; accumulated physical lines for current statement
           acc     (transient [])]
      (if (>= i n)
        ;; Flush any remaining logical line
        (let [acc (if (seq logical)
                    (let [joined    (apply str (map :text logical))
                          stmt-line (:line (first logical))]
                      (reduce (fn [a seg]
                                (let [s (clojure.string/trim seg)]
                                  (if (clojure.string/blank? s)
                                    a
                                    (conj! (tokenize-segment s stmt-line a)
                                           {:kind KIND-NEWLINE :text "\n" :line stmt-line}))))
                              acc
                              (split-semicolon joined)))
                    acc)]
          (persistent! (conj! acc {:kind KIND-EOF :text "" :line n})))

        (let [raw      (nth raw-lines i)
              line-no  (inc i)
              stripped (strip-comment raw)
              logical  (conj logical {:text stripped :line line-no})]
          (if (and (continuation? stripped) (< (inc i) n))
            ;; This line continues — accumulate
            (recur (inc i) logical acc)
            ;; End of logical statement — tokenize all accumulated lines
            (let [joined    (apply str (map :text logical))
                  stmt-line (:line (first logical))
                  acc       (reduce (fn [a seg]
                                      (let [s (clojure.string/trim seg)]
                                        (if (clojure.string/blank? s)
                                          a
                                          (conj! (tokenize-segment s stmt-line a)
                                                 {:kind KIND-NEWLINE :text "\n" :line stmt-line}))))
                                    acc
                                    (split-semicolon joined))]
              (recur (inc i) [] acc))))))))

;; ---------------------------------------------------------------------------
;; Step 2: Expression parser — shunting-yard
;;
;; Implements the expression grammar.
;; Takes a seq of token maps from `tokenize` (Newline/Eof already removed),
;; returns a vector of tokens in postfix (RPN) order.
;;
;; Reduce condition (from the language specification):
;;   while existing-op.lp >= incoming-op.rp  → reduce
;; ---------------------------------------------------------------------------

;; Precedence table: op-kind → [lp rp]
(def ^:private prec-table
  {:sc/op-assign    [1  2]   ; right-assoc
   :sc/op-question  [2  2]
   :sc/op-pipe      [3  3]
   :sc/op-or        [4  4]
   :sc/op-concat    [5  5]
   :sc/op-eq        [6  6]
   :sc/op-ne        [6  6]
   :sc/op-lt        [6  6]
   :sc/op-gt        [6  6]
   :sc/op-le        [6  6]
   :sc/op-ge        [6  6]
   :sc/op-str-ident  [6  6]
   :sc/op-str-differ [6  6]
   :sc/op-str-lt    [6  6]
   :sc/op-str-gt    [6  6]
   :sc/op-str-le    [6  6]
   :sc/op-str-ge    [6  6]
   :sc/op-str-eq    [6  6]
   :sc/op-str-ne    [6  6]
   :sc/op-plus      [7  7]
   :sc/op-minus     [7  7]
   :sc/op-slash     [8  8]
   :sc/op-star      [8  8]
   :sc/op-percent   [8  8]
   :sc/op-caret     [9  10]  ; right-assoc
   :sc/op-period    [10 10]
   :sc/op-dollar    [10 10]})

;; Unary operator set — ANY("+-*&@~?.$") from the language specification
(def ^:private unary-ops
  #{:sc/op-plus :sc/op-minus :sc/op-star :sc/op-ampersand
    :sc/op-at   :sc/op-tilde :sc/op-question :sc/op-period :sc/op-dollar})

(def ^:private operand-kinds
  #{:sc/identifier :sc/integer :sc/real :sc/string})

(defn- operand? [tok] (contains? operand-kinds (:kind tok)))
(defn- binary?  [tok] (contains? prec-table (:kind tok)))
(defn- unary?   [tok] (contains? unary-ops  (:kind tok)))

(defn- unary-position?
  "True if index i is in unary position (start, or previous token was op/open)."
  [tokens i]
  (if (zero? i)
    true
    (let [prev (:kind (nth tokens (dec i)))]
      (or (= prev :sc/lparen)
          (= prev :sc/lbracket)
          (= prev :sc/comma)
          (contains? prec-table prev)
          (contains? unary-ops  prev)))))

;; dotck: rewrite leading-dot real to prepend "0."
(defn- dotck [tok]
  (if (and (= (:kind tok) :sc/real)
           (clojure.string/starts-with? (:text tok) "."))
    (assoc tok :text (str "0" (:text tok)))
    tok))

(defn parse-expression
  "Parse a flat infix token vector into postfix (RPN).
  Returns a vector of token maps; synthetic call/array-ref nodes carry
  :arg-count and (for unary ops) :unary? true."
  [tokens]
  (let [tokens (vec tokens)
        n      (count tokens)]
    (loop [i          0
           output     []
           op-stack   []    ; binary ops waiting to be flushed
           call-stack []]   ; frames: {:kind :call/:array/:group :arg-count N :output-start M}
      (if (>= i n)
        ;; Drain remaining ops
        (into output (reverse op-stack))

        (let [tok  (dotck (nth tokens i))
              kind (:kind tok)
              next-kind (when (< (inc i) n) (:kind (nth tokens (inc i))))]
          (cond
            ;; ---- Operand ----
            (operand? tok)
            (cond
              ;; identifier followed by ( → function call
              (and (= kind :sc/identifier) (= next-kind :sc/lparen))
              (recur (+ i 2)
                     (conj output tok)
                     (conj op-stack {:kind :sc/lparen :text "(" :line (:line tok)})
                     (conj call-stack {:kind :call :arg-count 0
                                       :output-start (inc (count output))}))

              ;; identifier followed by [ → array ref
              (and (= kind :sc/identifier) (= next-kind :sc/lbracket))
              (recur (+ i 2)
                     (conj output tok)
                     (conj op-stack {:kind :sc/lbracket :text "[" :line (:line tok)})
                     (conj call-stack {:kind :array :arg-count 0
                                       :output-start (inc (count output))}))

              :else
              (recur (inc i) (conj output tok) op-stack call-stack))

            ;; ---- Left paren (grouping) ----
            (= kind :sc/lparen)
            (recur (inc i) output
                   (conj op-stack tok)
                   (conj call-stack {:kind :group :arg-count 0
                                     :output-start (count output)}))

            ;; ---- Right paren ----
            (= kind :sc/rparen)
            (let [[flushed rest-ops] (split-with #(not= (:kind %) :sc/lparen) op-stack)
                  new-output (into output (reverse flushed))
                  new-ops    (if (seq rest-ops) (vec (rest rest-ops)) [])
                  frame      (peek call-stack)
                  new-calls  (if (seq call-stack) (pop call-stack) [])
                  has-args?  (> (count new-output) (:output-start frame 0))
                  arg-count  (if has-args? (inc (:arg-count frame 0)) 0)
                  new-output (if (= (:kind frame) :call)
                               (conj new-output {:kind :sc/sc-call :text "()"
                                                 :line (:line tok) :arg-count arg-count})
                               new-output)]
              (recur (inc i) new-output new-ops new-calls))

            ;; ---- Left bracket ----
            (= kind :sc/lbracket)
            (recur (inc i) output
                   (conj op-stack tok)
                   (conj call-stack {:kind :group :arg-count 0
                                     :output-start (count output)}))

            ;; ---- Right bracket ----
            (= kind :sc/rbracket)
            (let [[flushed rest-ops] (split-with #(not= (:kind %) :sc/lbracket) op-stack)
                  new-output (into output (reverse flushed))
                  new-ops    (if (seq rest-ops) (vec (rest rest-ops)) [])
                  frame      (peek call-stack)
                  new-calls  (if (seq call-stack) (pop call-stack) [])
                  has-args?  (> (count new-output) (:output-start frame 0))
                  arg-count  (if has-args? (inc (:arg-count frame 0)) 0)
                  new-output (if (= (:kind frame) :array)
                               (conj new-output {:kind :sc/sc-array-ref :text "[]"
                                                 :line (:line tok) :arg-count arg-count})
                               new-output)]
              (recur (inc i) new-output new-ops new-calls))

            ;; ---- Comma ----
            (= kind :sc/comma)
            (let [[flushed rest-ops] (split-with
                                       #(not (#{:sc/lparen :sc/lbracket} (:kind %)))
                                       op-stack)
                  new-output (into output (reverse flushed))
                  frame      (peek call-stack)
                  new-calls  (if (and (seq call-stack)
                                      (#{:call :array} (:kind frame)))
                               (conj (pop call-stack)
                                     (update frame :arg-count inc))
                               call-stack)]
              (recur (inc i) new-output (vec rest-ops) new-calls))

            ;; ---- Unary operator ----
            (and (unary? tok) (unary-position? tokens i))
            ;; Parse next operand recursively, then emit unary op after it
            (let [[operand-tok next-i]
                  (loop [j (inc i)]
                    (if (>= j n)
                      [nil j]
                      (let [t (dotck (nth tokens j))]
                        (cond
                          (operand? t) [t (inc j)]
                          (unary? t)   (let [[inner ni] (loop [k (inc j)]
                                              (if (>= k n) [nil k]
                                                (let [t2 (dotck (nth tokens k))]
                                                  (if (operand? t2)
                                                    [t2 (inc k)]
                                                    [nil (inc k)]))))]
                                         ;; emit inner operand, then inner unary — skip for now
                                         [inner ni])
                          :else        [nil (inc j)]))))]
              (recur next-i
                     (if operand-tok
                       (conj output operand-tok (assoc tok :unary? true))
                       output)
                     op-stack call-stack))

            ;; ---- Binary operator ----
            (binary? tok)
            (let [[lp-new rp-new] (prec-table kind)
                  [flushed rest]  (split-with
                                    (fn [op]
                                      (when-let [[lp-top _] (prec-table (:kind op))]
                                        (>= lp-top rp-new)))
                                    op-stack)]
              (recur (inc i)
                     (into output (reverse flushed))
                     (vec (cons tok rest))
                     call-stack))

            :else
            (recur (inc i) output op-stack call-stack)))))))
