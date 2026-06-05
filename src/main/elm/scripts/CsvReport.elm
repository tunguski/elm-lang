module CsvReport exposing (main)

{-| `elm script CsvReport.elm <file.csv>` — read a CSV (with a header row) and render it as an HTML
table page, using the bundled `Csv` and `Site` libraries.

    $ elm script CsvReport.elm people.csv > report.html
-}

import Csv
import Posix exposing (..)
import Site


main : Io
main =
    getArgs
        (\args ->
            case args of
                path :: _ ->
                    readFile path
                        (\result ->
                            case result of
                                Ok text ->
                                    print (report text) done

                                Err message ->
                                    print ("error: " ++ message) (exit 1)
                        )

                _ ->
                    print "usage: CsvReport <file.csv>" (exit 1)
        )


{-| Renders the CSV as an HTML document with a table (the first row styled as the header). -}
report : String -> String
report text =
    Site.render
        (Site.page "report.html" "CSV report" [ Site.raw (table (Csv.parse text)) ])


table : List (List String) -> String
table rows =
    case rows of
        header :: body ->
            "<table><thead>"
                ++ rowHtml "th" header
                ++ "</thead><tbody>"
                ++ String.concat (List.map (rowHtml "td") body)
                ++ "</tbody></table>"

        [] ->
            "<table></table>"


rowHtml : String -> List String -> String
rowHtml cell cells =
    "<tr>" ++ String.concat (List.map (\c -> "<" ++ cell ++ ">" ++ escape c ++ "</" ++ cell ++ ">") cells) ++ "</tr>"


escape : String -> String
escape s =
    s |> String.replace "&" "&amp;" |> String.replace "<" "&lt;" |> String.replace ">" "&gt;"
