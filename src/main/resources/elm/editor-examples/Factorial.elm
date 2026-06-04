module Factorial exposing (main)

main = text (String.fromInt (fact 5))

fact n =
    if n <= 1 then
        1

    else
        n * fact (n - 1)
