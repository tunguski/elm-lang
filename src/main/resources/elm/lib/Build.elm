module Build exposing
    ( Project
    , Module
    , Dependency
    , Goal
    , Phase(..)
    , Task(..)
    , Backend(..)
    , Step
    , project
    , module_
    , dependency
    , goal
    , withEntry
    , withSources
    , withDependencies
    , withOutput
    , withBackend
    , withGoals
    , addGoal
    , exec
    , check
    , checkAll
    , compile
    , compileAll
    , test
    , archive
    , copy
    , makeDir
    , remove
    , writeFile
    , log
    , phases
    , phaseName
    , phaseRank
    , phasesUpTo
    , defaultGoals
    , effectiveGoals
    , buildOrder
    , transitiveDeps
    , withDependencySources
    , plan
    , cleanPlan
    )

{-| A small, Maven-flavoured build tool described in Elm. The build is **declarative** like a Maven
POM — a `Project` of one or more `Module`s — and runs through a **fixed lifecycle of phases**
(`validate → compile → test → package → verify → install`). Asking for a phase runs every earlier
phase first, exactly like Maven.

What each phase does for a module is a list of **goals**, and a goal is just a function
`Module -> List Task` — so anything Maven would need a plugin for, you write inline as an ordinary
Elm function. A single file can describe a whole **multi-module** build (`project … [ a, b, c ]`),
and the modules share the same lifecycle.

The `elm build [phase]` runner loads your `project`, asks `plan phase project` for the ordered list
of steps (a pure computation — fully testable without running anything), then executes each step's
`Task`s, stopping on the first failure (fail-fast, Maven-style).

    import Build exposing (..)

    project : Project
    project =
        Build.project "my-app" "1.0.0"
            [ module_ "app" "."
                |> withEntry "src/Main.elm"
                |> withGoals
                    (defaultGoals
                        ++ [ goal Package "bundle" (\m -> [ copy (m.output ++ "/app.js") "dist/app.js" ]) ]
                    )
            ]

-}


-- DATA --------------------------------------------------------------------


{-| A whole build: a name, a version, and the modules it contains (Maven's reactor). -}
type alias Project =
    { name : String
    , version : String
    , modules : List Module
    }


{-| One buildable unit (a Maven module): where its source lives, what to build, and the goals bound
to each phase. `entry` is the module's main file; `output` is its target directory. -}
type alias Module =
    { name : String
    , path : String
    , entry : String
    , sources : List String
    , dependencies : List Dependency
    , output : String
    , backend : Backend
    , goals : List Goal
    }


{-| A dependency on another package, by name and version (resolution is the runner's concern). -}
type alias Dependency =
    { name : String
    , version : String
    }


{-| A unit of work bound to a phase. `tasks` is the custom function: given the module, it produces
the concrete build actions to run. Built-in behaviour and your own steps are written the same way. -}
type alias Goal =
    { phase : Phase
    , name : String
    , tasks : Module -> List Task
    }


{-| The fixed build lifecycle, in order. Running a phase runs every earlier phase first. `clean` is
deliberately *not* in this list — it is a separate operation (see `cleanPlan`), as in Maven. -}
type Phase
    = Validate
    | Compile
    | Test
    | Package
    | Verify
    | Install


{-| A compilation target understood by the runner. -}
type Backend
    = JS
    | Wasm
    | WasmGc


{-| An atomic build action the runner knows how to perform. `Run` is the general escape hatch (run
any command); the rest map onto the toolchain and the filesystem. Prefer the lowercase builders. -}
type Task
    = Run String (List String)
    | Check (List String)
    | CompileModule Backend (List String) String
    | RunTests String
    | Archive String String
    | Copy String String
    | MakeDir String
    | Remove String
    | WriteFile String String
    | Log String


{-| One planned step: a phase, the module it runs for, the goal's name, and its concrete tasks. This
is what `plan` produces and the runner executes — and what a `--dry-run` prints. -}
type alias Step =
    { phase : String
    , moduleName : String
    , goal : String
    , tasks : List Task
    }



-- BUILDERS ----------------------------------------------------------------


{-| A project from a name, a version and its modules. -}
project : String -> String -> List Module -> Project
project name version modules =
    { name = name, version = version, modules = modules }


{-| A module with sensible defaults: `entry = <path>/src/Main.elm`, `output = <path>/build`, no
dependencies and no goals (so it uses `defaultGoals`). Refine it with the `with*` helpers. -}
module_ : String -> String -> Module
module_ name path =
    { name = name
    , path = path
    , entry = path ++ "/src/Main.elm"
    , sources = []
    , dependencies = []
    , output = path ++ "/build"
    , backend = JS
    , goals = []
    }


dependency : String -> String -> Dependency
dependency name version =
    { name = name, version = version }


{-| A goal: a phase, a name, and the function producing its tasks. -}
goal : Phase -> String -> (Module -> List Task) -> Goal
goal phase name tasks =
    { phase = phase, name = name, tasks = tasks }


withEntry : String -> Module -> Module
withEntry entry m =
    { m | entry = entry }


withSources : List String -> Module -> Module
withSources sources m =
    { m | sources = sources }


withDependencies : List Dependency -> Module -> Module
withDependencies deps m =
    { m | dependencies = deps }


withOutput : String -> Module -> Module
withOutput output m =
    { m | output = output }


withBackend : Backend -> Module -> Module
withBackend backend m =
    { m | backend = backend }


withGoals : List Goal -> Module -> Module
withGoals goals m =
    { m | goals = goals }


addGoal : Goal -> Module -> Module
addGoal g m =
    { m | goals = m.goals ++ [ g ] }



-- TASK BUILDERS -----------------------------------------------------------


{-| Run an external command with arguments (a custom build step). -}
exec : String -> List String -> Task
exec =
    Run


{-| Type-check a single entry file (failing the build on a type error). -}
check : String -> Task
check entry =
    Check [ entry ]


{-| Type-check a set of source files together as one project (so cross-module imports resolve). -}
checkAll : List String -> Task
checkAll =
    Check


{-| Compile a single entry file to a backend, writing the artifact to a path. -}
compile : Backend -> String -> String -> Task
compile backend entry out =
    CompileModule backend [ entry ] out


{-| Compile a set of source files (the entry first) as one project to a backend. The JS and
linear-memory WASM backends bundle all the modules; the WasmGC backend compiles the entry only. -}
compileAll : Backend -> List String -> String -> Task
compileAll =
    CompileModule


{-| Run the tests under a directory. -}
test : String -> Task
test =
    RunTests


{-| Zip a directory tree into an archive file. -}
archive : String -> String -> Task
archive =
    Archive


copy : String -> String -> Task
copy =
    Copy


makeDir : String -> Task
makeDir =
    MakeDir


remove : String -> Task
remove =
    Remove


writeFile : String -> String -> Task
writeFile =
    WriteFile


log : String -> Task
log =
    Log



-- LIFECYCLE ---------------------------------------------------------------


{-| The lifecycle, in order. -}
phases : List Phase
phases =
    [ Validate, Compile, Test, Package, Verify, Install ]


phaseName : Phase -> String
phaseName phase =
    case phase of
        Validate ->
            "validate"

        Compile ->
            "compile"

        Test ->
            "test"

        Package ->
            "package"

        Verify ->
            "verify"

        Install ->
            "install"


{-| A phase's position in the lifecycle (0-based), for ordering. -}
phaseRank : Phase -> Int
phaseRank phase =
    case phase of
        Validate ->
            0

        Compile ->
            1

        Test ->
            2

        Package ->
            3

        Verify ->
            4

        Install ->
            5


{-| Every phase up to and including `target`, in order. -}
phasesUpTo : Phase -> List Phase
phasesUpTo target =
    List.filter (\p -> phaseRank p <= phaseRank target) phases


{-| The default lifecycle bindings, used for any module that declares no goals of its own: validate
type-checks the entry, compile makes the output dir and emits a JS bundle, test runs the module's
`tests/` directory, package zips the output to `dist/<name>.zip`, and install copies that archive
into `build-repo/`. Include them explicitly (`defaultGoals ++ yours`) to keep them when you add your
own. -}
defaultGoals : List Goal
defaultGoals =
    [ goal Validate "validate" (\m -> [ checkAll (m.entry :: m.sources) ])
    , goal Compile "compile" (\m -> [ makeDir m.output, compileAll m.backend (m.entry :: m.sources) (artifactPath m) ])
    , goal Test "test" (\m -> [ test (m.path ++ "/tests") ])
    , goal Package "package" (\m -> [ makeDir "dist", archive m.output ("dist/" ++ m.name ++ ".zip") ])
    , goal Install "install" (\m -> [ makeDir "build-repo", copy ("dist/" ++ m.name ++ ".zip") ("build-repo/" ++ m.name ++ ".zip") ])
    ]


{-| The default compiled-artifact path for a module: `<output>/<name>.js` for JS, `.wasm` otherwise. -}
artifactPath : Module -> String
artifactPath m =
    m.output ++ "/" ++ m.name ++ (if m.backend == JS then ".js" else ".wasm")


{-| A module's goals: its own if it declares any, otherwise the defaults. -}
effectiveGoals : Module -> List Goal
effectiveGoals m =
    if List.isEmpty m.goals then
        defaultGoals

    else
        m.goals



-- PLANNING ----------------------------------------------------------------


{-| Modules in build order: each module after the sibling modules it depends on (Maven reactor
order). A dependency that names a non-sibling (an external package) does not affect ordering. A cycle
can't be ordered, so the modules it involves are left in declared order. -}
buildOrder : Project -> List Module
buildOrder proj =
    let
        siblings =
            List.map .name proj.modules

        deps m =
            m.dependencies
                |> List.map .name
                |> List.filter (\d -> List.member d siblings)

        go remaining ordered =
            case remaining of
                [] ->
                    List.reverse ordered

                _ ->
                    let
                        placed =
                            List.map .name ordered

                        ready =
                            List.filter (\m -> List.all (\d -> List.member d placed) (deps m)) remaining
                    in
                    case ready of
                        next :: _ ->
                            go (List.filter (\m -> m.name /= next.name) remaining) (next :: ordered)

                        [] ->
                            -- A dependency cycle (or a self-dependency): keep the rest as declared.
                            List.reverse ordered ++ remaining
    in
    go proj.modules []


{-| Every sibling module a module depends on, transitively (the modules whose sources it needs to
compile). External-package dependencies are ignored; a module never includes itself. -}
transitiveDeps : Project -> Module -> List Module
transitiveDeps proj m =
    let
        siblings =
            List.map .name proj.modules

        depNamesOf mod =
            mod.dependencies |> List.map .name |> List.filter (\d -> List.member d siblings)

        collect toVisit visited =
            case toVisit of
                [] ->
                    visited

                name :: rest ->
                    if List.member name visited then
                        collect rest visited

                    else
                        case byName proj name of
                            Just dm ->
                                collect (rest ++ depNamesOf dm) (visited ++ [ name ])

                            Nothing ->
                                collect rest visited
    in
    List.filterMap (byName proj) (collect (depNamesOf m) [])


byName : Project -> String -> Maybe Module
byName proj name =
    List.head (List.filter (\m -> m.name == name) proj.modules)


{-| Adds the sources of a module's (transitive sibling) dependencies to its own, so compiling and
validating it sees those modules without listing them by hand. -}
withDependencySources : Project -> Module -> Module
withDependencySources proj m =
    { m | sources = m.sources ++ List.concatMap (\d -> d.entry :: d.sources) (transitiveDeps proj m) }


{-| The ordered steps to reach `target`: phase by phase (each phase across every module in dependency
order), and within a phase, goal by goal. Each module's sources are expanded with its dependencies'.
Pure — running it computes the whole build plan without performing anything, which is exactly what
makes a build testable and a `--dry-run` possible. -}
plan : Phase -> Project -> List Step
plan target proj =
    let
        ordered =
            List.map (withDependencySources proj) (buildOrder proj)
    in
    phasesUpTo target
        |> List.concatMap (\phase -> stepsForPhase phase ordered)


stepsForPhase : Phase -> List Module -> List Step
stepsForPhase phase modules =
    List.concatMap
        (\m ->
            effectiveGoals m
                |> List.filter (\g -> g.phase == phase)
                |> List.map
                    (\g ->
                        { phase = phaseName phase
                        , moduleName = m.name
                        , goal = g.name
                        , tasks = g.tasks m
                        }
                    )
        )
        modules


{-| The clean operation (outside the lifecycle): remove each module's output directory. -}
cleanPlan : Project -> List Step
cleanPlan proj =
    List.map
        (\m -> { phase = "clean", moduleName = m.name, goal = "clean", tasks = [ remove m.output ] })
        proj.modules
