module Lang exposing (Value(..), Expr(..), Pattern(..), Decl, Globals, Env)

{-| The data model of the interpreted Elm-like language: runtime values, the expression AST, the
pattern AST, and top-level declarations. Shared by the lexer/parser, the evaluator and the editor.
-}


type Value
    = VNum Float
    | VBool Bool
    | VStr String
    | VList (List Value)
    | VCtor String (List Value)
    | VClosure (List String) Expr (List ( String, Value ))
    | VRec String (List String) Expr (List ( String, Value ))
    | VBuiltin String


type Expr
    = Num Float
    | Str String
    | Boolean Bool
    | ListE (List Expr)
    | Var String
    | Ctor String
    | Neg Expr
    | BinOp String Expr Expr
    | If Expr Expr Expr
    | Lam (List String) Expr
    | App Expr Expr
    | Let String Expr Expr
    | Case Expr (List ( Pattern, Expr ))


type Pattern
    = PVar String
    | PWild
    | PInt Float
    | PBool Bool
    | PStr String
    | PCtor String (List Pattern)
    | PNil
    | PCons Pattern Pattern


{-| A top-level definition `name args = body`. -}
type alias Decl =
    { name : String
    , params : List String
    , body : Expr
    }


{-| All top-level definitions of a project, keyed by name (a mutually-recursive scope). -}
type alias Globals =
    List ( String, Decl )


{-| A local binding environment. -}
type alias Env =
    List ( String, Value )
