main = div [] (List.map square (List.range 1 5))

square n =
    div [] [ text (String.fromInt (n * n)) ]
