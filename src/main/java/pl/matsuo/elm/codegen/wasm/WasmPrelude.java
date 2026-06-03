package pl.matsuo.elm.codegen.wasm;

import java.util.Map;

/**
 * The WASM linear-memory backend's standard library: a small set of {@code List}/{@code Maybe}/
 * {@code Result}/{@code String}/{@code Char} functions written in the WASM-supported Elm subset
 * ({@link #SOURCE}), prepended to a module (when used) so they compile like any other code, plus the
 * map from each qualified Elm name to the prelude function that implements it ({@link #NAMES}).
 *
 * <p>This is data, not codegen logic — it changes when a stdlib function is added, on a different
 * cadence from the compiler itself — so it lives apart from {@link WasmCompiler}.
 */
final class WasmPrelude {

  private WasmPrelude() {}

  /** The prelude source, in the WASM-supported subset. Each function maps from its qualified Elm
   * name via {@link #NAMES}. */
  static final String SOURCE =
      """
      listMap f xs = case xs of
          [] -> []
          h :: t -> f h :: listMap f t
      listFoldl f acc xs = case xs of
          [] -> acc
          h :: t -> listFoldl f (f h acc) t
      listFoldr f acc xs = case xs of
          [] -> acc
          h :: t -> f h (listFoldr f acc t)
      listFilter pred xs = case xs of
          [] -> []
          h :: t -> if pred h then h :: listFilter pred t else listFilter pred t
      listLength xs = case xs of
          [] -> 0
          h :: t -> 1 + listLength t
      listSum xs = case xs of
          [] -> 0
          h :: t -> h + listSum t
      listRange lo hi = if lo > hi then [] else lo :: listRange (lo + 1) hi
      listAppend a b = case a of
          [] -> b
          h :: t -> h :: listAppend t b
      listReverse xs = listFoldl (\\h acc -> h :: acc) [] xs
      listConcat xss = listFoldr listAppend [] xss
      listConcatMap f xs = listFoldr (\\x acc -> listAppend (f x) acc) [] xs
      listIsEmpty xs = case xs of
          [] -> True
          h :: t -> False
      listTake n xs = if n <= 0 then [] else case xs of
          [] -> []
          h :: t -> h :: listTake (n - 1) t
      listDrop n xs = if n <= 0 then xs else case xs of
          [] -> []
          h :: t -> listDrop (n - 1) t
      listRepeat n x = if n <= 0 then [] else x :: listRepeat (n - 1) x
      listProduct xs = listFoldl (\\x acc -> x * acc) 1 xs
      listAll pred xs = listFoldl (\\x acc -> acc && pred x) True xs
      listAny pred xs = listFoldl (\\x acc -> acc || pred x) False xs
      listMap2 f xs ys = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> f hx hy :: listMap2 f tx ty
      maxOf a b = if a > b then a else b
      minOf a b = if a < b then a else b
      listMaximum xs = case xs of
          [] -> Nothing
          h :: t -> Just (listFoldl maxOf h t)
      listMinimum xs = case xs of
          [] -> Nothing
          h :: t -> Just (listFoldl minOf h t)
      listMember x xs = listFoldl (\\y acc -> acc || x == y) False xs
      listInsert x xs = case xs of
          [] -> x :: []
          h :: t -> if x <= h then x :: h :: t else h :: listInsert x t
      listSort xs = listFoldr listInsert [] xs
      listSortInsert key x xs = case xs of
          [] -> x :: []
          h :: t -> if key x <= key h then x :: h :: t else h :: listSortInsert key x t
      listSortBy key xs = listFoldr (listSortInsert key) [] xs
      listIndexedHelp f i xs = case xs of
          [] -> []
          h :: t -> f i h :: listIndexedHelp f (i + 1) t
      listIndexedMap f xs = listIndexedHelp f 0 xs
      listHead xs = case xs of
          [] -> Nothing
          h :: t -> Just h
      listTail xs = case xs of
          [] -> Nothing
          h :: t -> Just t
      listFilterMap f xs = listFoldr (\\x acc -> case f x of
          Just y -> y :: acc
          Nothing -> acc) [] xs
      listMap3 f xs ys zs = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> case zs of
                  [] -> []
                  hz :: tz -> f hx hy hz :: listMap3 f tx ty tz
      listMap4 f xs ys zs ws = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> case zs of
                  [] -> []
                  hz :: tz -> case ws of
                      [] -> []
                      hw :: tw -> f hx hy hz hw :: listMap4 f tx ty tz tw
      listMap5 f xs ys zs ws vs = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> case zs of
                  [] -> []
                  hz :: tz -> case ws of
                      [] -> []
                      hw :: tw -> case vs of
                          [] -> []
                          hv :: tv -> f hx hy hz hw hv :: listMap5 f tx ty tz tw tv
      stringIsEmpty s = String.length s == 0
      stringRepeat n s = if n <= 0 then "" else String.append s (stringRepeat (n - 1) s)
      stringConcat xs = listFoldr (\\x acc -> String.append x acc) "" xs
      stringJoin sep xs = case xs of
          [] -> ""
          h :: t -> case t of
              [] -> h
              _ -> String.append h (String.append sep (stringJoin sep t))
      fromIntDigit d = if d == 1 then "1" else if d == 2 then "2" else if d == 3 then "3" else if d == 4 then "4" else if d == 5 then "5" else if d == 6 then "6" else if d == 7 then "7" else if d == 8 then "8" else if d == 9 then "9" else "0"
      stringFromInt n = if n < 0 then "-" ++ stringFromInt (0 - n) else if n < 10 then fromIntDigit n else stringFromInt (n // 10) ++ fromIntDigit (modBy 10 n)
      maybeWithDefault d m = case m of
          Just x -> x
          Nothing -> d
      maybeMap f m = case m of
          Just x -> Just (f x)
          Nothing -> Nothing
      maybeAndThen f m = case m of
          Just x -> f x
          Nothing -> Nothing
      resultWithDefault d r = case r of
          Ok x -> x
          Err e -> d
      resultMap f r = case r of
          Ok x -> Ok (f x)
          Err e -> Err e
      resultMapError f r = case r of
          Ok x -> Ok x
          Err e -> Err (f e)
      resultToMaybe r = case r of
          Ok x -> Just x
          Err e -> Nothing
      resultFromMaybe err m = case m of
          Just x -> Ok x
          Nothing -> Err err
      identity x = x
      always a b = a
      max a b = maxOf a b
      min a b = minOf a b
      clamp lo hi x = maxOf lo (minOf hi x)
      listIntersperse sep xs = case xs of
          [] -> []
          h :: t -> h :: prependEach sep t
      prependEach sep xs = case xs of
          [] -> []
          h :: t -> sep :: h :: prependEach sep t
      stringRight n s = String.dropLeft (String.length s - n) s
      stringDropRight n s = String.left (String.length s - n) s
      clampIdx s i = if i < 0 then maxOf 0 (String.length s + i) else minOf i (String.length s)
      stringSlice start end s = String.left (clampIdx s end - clampIdx s start) (String.dropLeft (clampIdx s start) s)
      stringStartsWith pre s = String.left (String.length pre) s == pre
      stringEndsWith suf s = String.right (String.length suf) s == suf
      stringContains sub s = containsFrom sub s 0
      containsFrom sub s i = if i + String.length sub > String.length s then False else if String.left (String.length sub) (String.dropLeft i s) == sub then True else containsFrom sub s (i + 1)
      isWsHead h = h == " " || h == "\\t" || h == "\\n" || h == "\\u{000D}"
      stringTrimLeft s = if isWsHead (String.left 1 s) then stringTrimLeft (String.dropLeft 1 s) else s
      stringTrimRight s = if isWsHead (String.right 1 s) then stringTrimRight (String.dropRight 1 s) else s
      stringTrim s = stringTrimRight (stringTrimLeft s)
      stringPadLeft n c s = if String.length s >= n then s else stringPadLeft n c (String.cons c s)
      stringPadRight n c s = if String.length s >= n then s else stringPadRight n c (String.append s (String.fromChar c))
      stringIndexes sub s = if String.length sub == 0 then [] else indexesFrom sub s 0
      indexesFrom sub s i = if i + String.length sub > String.length s then [] else if String.left (String.length sub) (String.dropLeft i s) == sub then i :: indexesFrom sub s (i + 1) else indexesFrom sub s (i + 1)
      stringSplit sep s = case listHead (stringIndexes sep s) of
          Nothing -> [ s ]
          Just i -> String.left i s :: stringSplit sep (String.dropLeft (i + String.length sep) s)
      stringLines s = stringSplit "\\n" s
      stringReplace old new s = stringJoin new (stringSplit old s)
      stringFromList chars = listFoldr stringCons "" chars
      stringCons c s = String.append (String.fromChar c) s
      stringFoldl f acc s = listFoldl f acc (String.toList s)
      stringFoldr f acc s = listFoldr f acc (String.toList s)
      stringMap f s = stringFromList (listMap f (String.toList s))
      stringFilter pred s = stringFromList (listFilter pred (String.toList s))
      stringAny pred s = listAny pred (String.toList s)
      stringAll pred s = listAll pred (String.toList s)
      stringToInt s = case String.toList s of
          [] -> Nothing
          c :: rest -> if Char.toCode c == 45 then stringNegateMaybe (stringDigits rest) else stringDigits (c :: rest)
      stringDigits chars = stringDigitsAcc chars 0 False
      stringDigitsAcc chars acc seen = case chars of
          [] -> if seen then Just acc else Nothing
          c :: rest -> if Char.toCode c >= 48 && Char.toCode c <= 57 then stringDigitsAcc rest (acc * 10 + (Char.toCode c - 48)) True else Nothing
      stringNegateMaybe m = case m of
          Just n -> Just (0 - n)
          Nothing -> Nothing
      charIsDigit c = c >= 48 && c <= 57
      charIsUpper c = c >= 65 && c <= 90
      charIsLower c = c >= 97 && c <= 122
      charIsAlpha c = charIsUpper c || charIsLower c
      charIsAlphaNum c = charIsAlpha c || charIsDigit c
      charToUpper c = if c >= 97 && c <= 122 then c - 32 else c
      charToLower c = if c >= 65 && c <= 90 then c + 32 else c
      charIsSpace c = c == 32 || c == 9 || c == 10 || c == 13
      charIsOctDigit c = c >= 48 && c <= 55
      charIsHexDigit c = charIsDigit c || (c >= 65 && c <= 70) || (c >= 97 && c <= 102)
      listSingleton x = [ x ]
      tupleFirst t = case t of
          ( a, b ) -> a
      tupleSecond t = case t of
          ( a, b ) -> b
      tupleMapFirst f t = case t of
          ( a, b ) -> ( f a, b )
      tupleMapSecond g t = case t of
          ( a, b ) -> ( a, g b )
      tupleMapBoth f g t = case t of
          ( a, b ) -> ( f a, g b )
      listPartition pred xs = ( listFilter pred xs, listReject pred xs )
      listReject pred xs = case xs of
          [] -> []
          h :: t -> if pred h then listReject pred t else h :: listReject pred t
      listUnzip pairs = case pairs of
          [] -> ( [], [] )
          ( a, b ) :: rest -> case listUnzip rest of
              ( xs, ys ) -> ( a :: xs, b :: ys )
      maybeMap2 f ma mb = maybeAndThen (\\a -> maybeMap (f a) mb) ma
      maybeMap3 f ma mb mc = maybeAndThen (\\a -> maybeMap2 (f a) mb mc) ma
      maybeMap4 f ma mb mc md = maybeAndThen (\\a -> maybeMap3 (f a) mb mc md) ma
      maybeMap5 f ma mb mc md me = maybeAndThen (\\a -> maybeMap4 (f a) mb mc md me) ma
      resultAndThen f r = case r of
          Ok x -> f x
          Err e -> Err e
      resultMap2 f ra rb = resultAndThen (\\a -> resultMap (f a) rb) ra
      resultMap3 f ra rb rc = resultAndThen (\\a -> resultMap2 (f a) rb rc) ra
      """;

  /** Maps qualified standard-library names to the prelude function that implements them. */
  static final Map<String, String> NAMES =
      Map.ofEntries(
          Map.entry("List.map", "listMap"),
          Map.entry("List.foldl", "listFoldl"),
          Map.entry("List.foldr", "listFoldr"),
          Map.entry("List.filter", "listFilter"),
          Map.entry("List.length", "listLength"),
          Map.entry("List.sum", "listSum"),
          Map.entry("List.range", "listRange"),
          Map.entry("List.append", "listAppend"),
          Map.entry("List.reverse", "listReverse"),
          Map.entry("List.concat", "listConcat"),
          Map.entry("List.concatMap", "listConcatMap"),
          Map.entry("List.isEmpty", "listIsEmpty"),
          Map.entry("List.take", "listTake"),
          Map.entry("List.drop", "listDrop"),
          Map.entry("List.repeat", "listRepeat"),
          Map.entry("List.product", "listProduct"),
          Map.entry("List.all", "listAll"),
          Map.entry("List.any", "listAny"),
          Map.entry("List.map2", "listMap2"),
          Map.entry("List.maximum", "listMaximum"),
          Map.entry("List.minimum", "listMinimum"),
          Map.entry("List.member", "listMember"),
          Map.entry("List.sort", "listSort"),
          Map.entry("List.sortBy", "listSortBy"),
          Map.entry("List.indexedMap", "listIndexedMap"),
          Map.entry("List.head", "listHead"),
          Map.entry("List.tail", "listTail"),
          Map.entry("List.filterMap", "listFilterMap"),
          Map.entry("List.map3", "listMap3"),
          Map.entry("List.map4", "listMap4"),
          Map.entry("List.map5", "listMap5"),
          Map.entry("List.intersperse", "listIntersperse"),
          Map.entry("List.partition", "listPartition"),
          Map.entry("List.singleton", "listSingleton"),
          Map.entry("Tuple.first", "tupleFirst"),
          Map.entry("Tuple.second", "tupleSecond"),
          Map.entry("Tuple.mapFirst", "tupleMapFirst"),
          Map.entry("Tuple.mapSecond", "tupleMapSecond"),
          Map.entry("Tuple.mapBoth", "tupleMapBoth"),
          Map.entry("List.unzip", "listUnzip"),
          Map.entry("Maybe.map2", "maybeMap2"),
          Map.entry("Maybe.map3", "maybeMap3"),
          Map.entry("Maybe.map4", "maybeMap4"),
          Map.entry("Maybe.map5", "maybeMap5"),
          Map.entry("Result.andThen", "resultAndThen"),
          Map.entry("Result.map2", "resultMap2"),
          Map.entry("Result.map3", "resultMap3"),
          Map.entry("Result.mapError", "resultMapError"),
          Map.entry("Result.toMaybe", "resultToMaybe"),
          Map.entry("Result.fromMaybe", "resultFromMaybe"),
          Map.entry("String.isEmpty", "stringIsEmpty"),
          Map.entry("String.fromInt", "stringFromInt"),
          Map.entry("String.repeat", "stringRepeat"),
          Map.entry("String.right", "stringRight"),
          Map.entry("String.dropRight", "stringDropRight"),
          Map.entry("String.slice", "stringSlice"),
          Map.entry("String.startsWith", "stringStartsWith"),
          Map.entry("String.endsWith", "stringEndsWith"),
          Map.entry("String.contains", "stringContains"),
          Map.entry("String.indexes", "stringIndexes"),
          Map.entry("String.indices", "stringIndexes"),
          Map.entry("String.split", "stringSplit"),
          Map.entry("String.lines", "stringLines"),
          Map.entry("String.replace", "stringReplace"),
          Map.entry("String.fromList", "stringFromList"),
          Map.entry("String.trim", "stringTrim"),
          Map.entry("String.trimLeft", "stringTrimLeft"),
          Map.entry("String.trimRight", "stringTrimRight"),
          Map.entry("String.padLeft", "stringPadLeft"),
          Map.entry("String.padRight", "stringPadRight"),
          Map.entry("String.cons", "stringCons"),
          Map.entry("String.foldl", "stringFoldl"),
          Map.entry("String.foldr", "stringFoldr"),
          Map.entry("String.map", "stringMap"),
          Map.entry("String.filter", "stringFilter"),
          Map.entry("String.any", "stringAny"),
          Map.entry("String.all", "stringAll"),
          Map.entry("String.toInt", "stringToInt"),
          Map.entry("Char.toCode", "identity"),
          Map.entry("Char.fromCode", "identity"),
          Map.entry("Char.isDigit", "charIsDigit"),
          Map.entry("Char.isUpper", "charIsUpper"),
          Map.entry("Char.isLower", "charIsLower"),
          Map.entry("Char.isAlpha", "charIsAlpha"),
          Map.entry("Char.isAlphaNum", "charIsAlphaNum"),
          Map.entry("Char.toUpper", "charToUpper"),
          Map.entry("Char.toLower", "charToLower"),
          Map.entry("Char.isSpace", "charIsSpace"),
          Map.entry("Char.isOctDigit", "charIsOctDigit"),
          Map.entry("Char.isHexDigit", "charIsHexDigit"),
          Map.entry("String.concat", "stringConcat"),
          Map.entry("String.join", "stringJoin"),
          Map.entry("Maybe.withDefault", "maybeWithDefault"),
          Map.entry("Maybe.map", "maybeMap"),
          Map.entry("Maybe.andThen", "maybeAndThen"),
          Map.entry("Result.withDefault", "resultWithDefault"),
          Map.entry("Result.map", "resultMap"),
          Map.entry("Basics.identity", "identity"),
          Map.entry("Basics.always", "always"));
}
