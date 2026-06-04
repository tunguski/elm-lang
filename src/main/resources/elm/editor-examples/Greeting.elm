module Greeting exposing (main)

main = text (greet "world")

greet name =
    "Hello, " ++ name ++ "!"
