module Csv exposing
    ( parse
    , encode
    , parseWithHeader
    , get
    )

{-| RFC-4180 **CSV** parsing and encoding. `parse` turns CSV text into rows of string fields,
honouring quoted fields (`"…"`) that may contain commas, newlines and doubled quotes (`""`);
`encode` is the inverse, quoting any field that needs it. `parseWithHeader` reads the first row as
column names and pairs each later row with them (records), and `get` looks a column up in a record.

    import Csv

    -- [["a","b,c"],["1","2"]]
    rows : List (List String)
    rows =
        Csv.parse "a,\"b,c\"\n1,2"

    -- "name,city\nAda,\"London, UK\""
    out : String
    out =
        Csv.encode [ [ "name", "city" ], [ "Ada", "London, UK" ] ]

-}


{-| Parses CSV text into rows of fields. Quoted fields may hold commas, newlines and `""` (an escaped
quote); a trailing newline does not add an empty row. -}
parse : String -> List (List String)
parse input =
    parseLoop (String.toList input) [] [] [] False


{-| The scanner: `field` is the current field (chars reversed), `row` the fields of the current row
(in order), `rows` the completed rows (in order), `quoted` whether we're inside a quoted field. -}
parseLoop : List Char -> List Char -> List String -> List (List String) -> Bool -> List (List String)
parseLoop chars field row rows quoted =
    let
        text fld =
            String.fromList (List.reverse fld)
    in
    if quoted then
        case chars of
            '"' :: '"' :: rest ->
                parseLoop rest ('"' :: field) row rows True

            '"' :: rest ->
                parseLoop rest field row rows False

            c :: rest ->
                parseLoop rest (c :: field) row rows True

            [] ->
                finish (text field) row rows

    else
        case chars of
            '"' :: rest ->
                if field == [] then
                    parseLoop rest field row rows True

                else
                    parseLoop rest ('"' :: field) row rows False

            ',' :: rest ->
                parseLoop rest [] (row ++ [ text field ]) rows False

            '\u{000D}' :: '\n' :: rest ->
                parseLoop rest [] [] (rows ++ [ row ++ [ text field ] ]) False

            '\n' :: rest ->
                parseLoop rest [] [] (rows ++ [ row ++ [ text field ] ]) False

            '\u{000D}' :: rest ->
                parseLoop rest [] [] (rows ++ [ row ++ [ text field ] ]) False

            c :: rest ->
                parseLoop rest (c :: field) row rows False

            [] ->
                finish (text field) row rows


{-| At end of input: append the final row unless nothing has been accumulated since the last row
ended (i.e. the input ended with a newline). -}
finish : String -> List String -> List (List String) -> List (List String)
finish field row rows =
    if field == "" && row == [] then
        rows

    else
        rows ++ [ row ++ [ field ] ]


{-| Encodes rows of fields back to CSV text, quoting any field containing a comma, quote or newline
(and doubling embedded quotes). Rows are joined with `\n`. -}
encode : List (List String) -> String
encode rows =
    String.join "\n" (List.map encodeRow rows)


encodeRow : List String -> String
encodeRow fields =
    String.join "," (List.map encodeField fields)


encodeField : String -> String
encodeField f =
    if needsQuote f then
        "\"" ++ String.replace "\"" "\"\"" f ++ "\""

    else
        f


needsQuote : String -> Bool
needsQuote f =
    String.contains "," f
        || String.contains "\"" f
        || String.contains "\n" f
        || String.contains "\u{000D}" f


{-| Parses CSV whose first row is a header, pairing every later row's fields with the column names —
a list of records (`List ( column, value )`). -}
parseWithHeader : String -> List (List ( String, String ))
parseWithHeader input =
    case parse input of
        headers :: dataRows ->
            List.map (\row -> List.map2 Tuple.pair headers row) dataRows

        [] ->
            []


{-| Looks up a column's value in a record from {@link parseWithHeader}. -}
get : String -> List ( String, String ) -> Maybe String
get column record =
    case record of
        ( k, v ) :: rest ->
            if k == column then
                Just v

            else
                get column rest

        [] ->
            Nothing
