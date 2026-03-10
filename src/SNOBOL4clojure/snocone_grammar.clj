(ns SNOBOL4clojure.snocone-grammar
  "Snocone expression grammar (instaparse PEG).

  Precedence levels from the operator table (low → high):
    asn  prec 1/2  =          right-assoc
    qst  prec 2    ?
    pip  prec 3    |
    or   prec 4    ||
    cat  prec 5    &&
    cmp  prec 6    == != < > <= >= :: :!: :>: :<: :>=: :<=: :==: :!=:
    sum  prec 7    + -
    mul  prec 8    * / %
    xp   prec 9/10 ^          right-assoc
    cap  prec 10   . $
    uop  unary     + - * & @ ~ ? . $
    ndx           f(...) f[...]"
  (:require [instaparse.core :as insta :refer [defparser]]))

(def sc-grammar
  " expr    ::= <ws*> asn <ws*>
    asn     ::= qst | qst  <ws* '='  ws*>  asn
    qst     ::= pip | pip  <ws* '?'  ws*>  pip
    pip     ::= or  | or   <ws* '|'  ws*>  or  (<ws* '|' ws*> or)*
    or      ::= cat | cat  <ws* '||' ws*>  cat (<ws* '||' ws*> cat)*
    cat     ::= cmp | cmp  <ws* '&&' ws*>  cmp (<ws* '&&' ws*> cmp)*
    cmp     ::= sum | sum  ws* cmpop ws*   sum
   <cmpop>  ::= ':!=:' | ':>=:' | ':<=:' | ':==:' | ':>:' | ':<:' | '::' | ':!:'
              | '>=' | '<=' | '==' | '!=' | '<' | '>'
    sum     ::= mul | sum  ws* sumop ws*   mul
   <sumop>  ::= '+' | '-'
    mul     ::= xp  | mul  ws* mulop ws*   xp
   <mulop>  ::= '*' | '/' | '%'
    xp      ::= cap | cap  <ws*> '^'  <ws*>  xp
    cap     ::= uop | uop  <ws*> capop <ws*>  cap
   <capop>  ::= '.' | '$'
    uop     ::= ndx | unaryop <ws*> uop
   <unaryop> ::= '+' | '-' | '*' | '&' | '@' | '~' | '?' | '.' | '$'
    ndx     ::= atom | ndx <'('> arglist <')'> | ndx <'['> arglist <']'>
    arglist  ::= expr? | expr (<ws* ',' ws*> expr)*
   <atom>   ::= integer | real | string | ident | <'('> expr <')'>
    ident   ::= #'[A-Za-z_][A-Za-z0-9_]*'
    integer ::= #'[0-9]+'
    real    ::= #'[0-9]+\\.[0-9]*([eEdD][+-]?[0-9]+)?'
              | #'[0-9]+[eEdD][+-]?[0-9]+'
              | #'\\.[0-9]+([eEdD][+-]?[0-9]+)?'
    string  ::= #'\"[^\"]*\"' | #'\\'[^\\']*\\''
   <ws>     ::= #'[ \\t]+'
  ")

(defparser parse-sc-expr sc-grammar :start :expr)
