# Plan: clear messages and a verbose mode

As of 2026-09-25. A proposal, not yet decided; the open decisions are listed at the end.

## Summary

The console output is correct but hard to read: a violation is a tag and two package names followed
by an unlabelled table, it never says what to do, one line of source code shows up as six members,
`NOT ALLOWED` never points at the rules file, and configuration errors range from readable to a bare
stack trace. This plan turns every message into a sentence with a fix, folds compiler-generated
noise, gives undeclared packages one table instead of one block each, and adds a `verbose` switch
to the CLI, Gradle and Maven that prints every reference, every diagnostic and every stack trace.
It is four pull requests: the model, the default console format, the verbose mode, the
configuration errors.

## What a developer sees today

The samples come from running the core's CLI on the test fixtures (`byterails-core/src/fixtures`)
with the rules of `Fixtures.rules()`.

One suspend lambda that captures a `RestTemplate`, reported as six members of two classes:

```
byterails: EXCLUSIVE    fixtures.app.domain -> fixtures.lib.web
  rule     exclusive("fixtures.lib.web")            byterails.kts:28  in "fixtures.app.infra.web"
  OrderService.lambdaOnly$lambda$0() : Object                                RestTemplate  OrderService.kt:17
  OrderService.later(RestTemplate, Continuation) : Object                    RestTemplate  OrderService.kt
  OrderService$later$fetch$1.invokeSuspend(Object) : Object                  RestTemplate  OrderService.kt:39
  OrderService$later$fetch$1.$template                                       RestTemplate  OrderService.kt
  OrderService$later$fetch$1.constructor(RestTemplate, Continuation) : void  RestTemplate  OrderService.kt
  OrderService$later$fetch$1.create(Continuation) : Continuation             RestTemplate  OrderService.kt
```

A first run with `--default-rules kotlin,hexagonal` and nothing else: 24 blocks like this one, and
nothing that says byterails is a whitelist and every package has to be declared:

```
byterails: UNDECLARED   fixtures.app.application
  package  "fixtures.app.application" is not declared
  GeneratedThing               UseCases.kt
  HelpersKt                    Helpers.kt
  PlaceOrderUseCase            UseCases.kt
  PlaceOrderUseCase$Companion  UseCases.kt
  Wrong                        UseCases.kt
```

A rules file that throws, a rules file that does not compile, and a class file byterails cannot read:

```
byterails: invalid configuration
  rules file byterails.kts failed: ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 2

byterails: 2 configuration errors
  byterails.kts:3: Unresolved reference 'alow'.
  byterails.kts:5: Syntax error: Expecting ')'.

Exception in thread "main" java.lang.IllegalArgumentException: Unsupported class file major version 10161
	at org.objectweb.asm.ClassReader.<init>(ClassReader.java:200)
	... 10 more frames, exit status 1, the same as "violations found"
```

### Findings

1. **The header is not a sentence.** `NOT ALLOWED  a.b -> c.d` needs the reader to know what the
   six kinds mean; `NOT ALLOWED`, `DENIED` and `EXCLUSIVE` read as synonyms and the arrow is unexplained.
2. **The member table has no headings.** `OrderService.place(Order) : void  RestTemplate  OrderService.kt:42`:
   nothing says the middle column is the referenced class and the last one the source file. Per-class
   kinds have two columns, reference kinds three, and the widths differ per group.
3. **The rule row is ambiguous.** `rule  deny("x")   byterails.kts:14  in "com.acme.domain"`: `in` can
   be read as the violating package instead of the block that holds the rule, and `padEnd(40)` leaves a
   gap or none depending on the rule's length (see the naming group with two patterns).
4. **`NOT ALLOWED` gives no location.** It is the default outcome of a whitelist and so the most common
   kind, yet it is the only one without a `byterails.kts:N`, although the package's declaration and its
   line are known.
5. **Nothing says what to do.** Every kind has exactly two remedies, move the code or change one line of
   the rules file, and the output names neither.
6. **Generated members inflate the count.** A Kotlin property is a field, a constructor parameter and a
   getter; a Java record component is a field, an accessor, `equals`, `hashCode`, `toString` and the
   constructor; a suspend lambda is a synthetic class with four members. Each is a line, and each counts:
   the fixture run says 29 violations for 16 distinct class-and-type pairs, and the failure message repeats the 29.
7. **Undeclared packages are the first-run experience and the noisiest.** One block per package with
   every class listed, and the row `package  "x" is not declared` repeats the header.
8. **Rule-set hints repeat.** `[hexagonal] is the language baseline: ...` prints under every group; a
   package under `hexagonalSpring` lists fifteen allows on one line.
9. **The cap hides the rest on the console.** `... and 13 more; the JSON report lists them all`, and no
   switch prints them.
10. **Per-class text is sliced out of the message string.** `ViolationGroup.detail` does
    `substringAfter("package ")` and `substringAfter("$className ")`, which is why the `module` row starts
    mid-sentence.
11. **Configuration errors are one at a time and of mixed shape.** A malformed prefix or an empty naming
    pattern throws inside the script, so the validator never runs and the other errors show on the next
    run. Compiler errors arrive with no lead-in, no column and no source line. A script that throws
    loses its line and its stack trace. Some messages have a location, some do not. The dead-allow check
    reports the same allow once per owner of a narrowed exclusive.
12. **Internal errors are a stack trace and the wrong exit status.** The CLI exits 1, indistinguishable
    from violations; the Gradle plugin rethrows `cause.message` with nothing else; Maven prints
    `byterails failed: <exception>`. The PRD asks for a message with an issue link.
13. **The build failure line does not say which project.** `byterails found 3 violations; see the lines
    above or /abs/path/violations.json`: in a parallel multi-project build the lines above may belong to
    another project.
14. **Warnings come first and are not counted.** They scroll away before the violations and the summary
    does not mention them.

## Principles

- Every message is a sentence: subject, what is wrong, where in the rules file, what to do.
- One spelling per thing: packages bare, rules as written (`deny("x")`), blocks as `pkg("x")`,
  locations as `byterails.kts:14`, the block that holds a rule as `in pkg("x")`.
- The default output is for the developer who broke the build: grouped, folded, capped, with the fix.
  The verbose output is for whoever debugs the rules: everything, unfolded, uncapped, with origins.
- Internals stay out of the default output: no "prefix", "declaration", "effective rules", "descriptor".
- One function per kind produces the text; the console, the JSON `message`, and the build tools'
  failure messages all use it.

## The new default output

The label column stays eight characters wide, the kind labels stay as they are (tests, docs and the
JSON `kind` use them), and the sentence after the label carries the meaning. Member lines start with
the source location, then say who uses what. The sample is illustrative: it mixes the fixture run,
a modular build and a default-rules-only run so that every kind appears once.

```
byterails: NOT ALLOWED  fixtures.app.domain uses java.io, which no rule allows
  package  pkg("fixtures.app.domain") at byterails.kts:11
  may use  java.lang, java.util, kotlin, org.jetbrains.annotations
  fix      move the code, or add allow("java.io") to pkg("fixtures.app.domain")
  OrderService.kt:22,28  OrderService uses PrintStream in local, caught

byterails: DENIED       fixtures.app.domain uses fixtures.lib.persistence, which deny("fixtures.lib.persistence") forbids
  rule     deny("fixtures.lib.persistence") in pkg("fixtures.app.domain") at byterails.kts:12
  fix      move the code, or lift the deny
  OrderService.kt  OrderService uses EntityManager in managers
  OrderService.kt  OrderService uses PersistenceException in caught

byterails: EXCLUSIVE    fixtures.app.domain uses fixtures.lib.web, which only fixtures.app.infra.web may use
  rule     exclusive("fixtures.lib.web") in pkg("fixtures.app.infra.web") at byterails.kts:28
  fix      move the code to fixtures.app.infra.web, or make the exclusive a plain allow and allow it here too
  OrderService.kt:17,39  OrderService uses RestTemplate in lambdaOnly, later (+4 generated members)

byterails: NAMING       classes in fixtures.app.application must end with "UseCase"
  rule     naming { endsWith("UseCase") } in pkg("fixtures.app.application") at byterails.kts:17
  fix      rename the classes, or move them
  Helpers.kt   HelpersKt
  UseCases.kt  Wrong

byterails: WRONG MODULE com.acme.shared is compiled in module "customers" but lies outside its package com.acme.customers
  fix      move the classes under com.acme.customers, or into the project of the module that owns them
  Misplaced.java  Misplaced

byterails: UNDECLARED   24 packages are not declared in byterails.kts
  fix      declare each with pkg("..."), or move its classes into a declared package; only declared packages may exist
  fixtures.app                       1 class    Application.kt
  fixtures.app.application           5 classes  UseCases.kt, Helpers.kt
  fixtures.app.infra.persistence     1 class    OrderRepository.kt
  fixtures.app.infra.web             2 classes  Controllers.kt
  fixtures.app.javainterop           5 classes  JavaUser.java   under flat pkg("fixtures.app") at byterails.kts:9, which covers no sub-packages
  ... and 19 more packages; --verbose lists every package and class

byterails: [hexagonal] stands for the language baseline: kotlin, org.jetbrains.annotations, java.lang, java.util, java.time, java.math, java.text
byterails: warning: byterails.kts:6: allow("kotlin.collections") matches only the Kotlin helper classes; kotlin.collections compiles to java.util, so add allow("java.util")
byterails: 15 violations in 6 groups, 39 classes, 22 packages, 1 warning
```

What changed, row by row:

- **Header.** One sentence per kind. `EXCLUSIVE` names the owner (`and 2 more slices` for a template
  exclusive). `NAMING` renders the patterns as words: `must end with "UseCase"`, `must start with`,
  `must match /regex/`, joined with `or`. `WRONG MODULE` prints the whole sentence the checker already
  builds instead of splitting it.
- **`package` and `may use`** replace `allows` for `NOT ALLOWED` and add the declaration's location.
  When the declaration comes from a rule set: `pkg("com.acme.sales.domain") from rule set hexagonal,
  isolated`. When nothing is allowed: `nothing; the package is isolated`.
- **`rule`** reads `<rule> in pkg("<block>") at <location>`; a root rule reads `at the top of byterails.kts:4`.
- **`fix`** names both remedies. For `NOT ALLOWED` the allow to add and the block to add it to; for a
  package declared by a rule set, the block that can widen it (`slice { }` or the root block) or, for an
  isolated one, that it cannot be widened. For `EXCLUSIVE` the owner to move to. The wording is fixed per
  kind, so it is one function to review.
- **Members** are one line per class and referenced type, not per site: the location column lists the
  distinct lines, the sentence lists the user-written members, and members the compiler generated
  (accessors, `equals`, `hashCode`, `toString`, `componentN`, `copy`, `$lambda$`, constructors of
  synthetic classes) fold into `(+N generated members)`. A synthetic class such as `OrderService$later$fetch$1`
  is attributed to its outer class; the sites stay in the JSON. A generated member is recognised by
  `ACC_SYNTHETIC` or `ACC_BRIDGE`, by the `Kotlin` metadata's synthetic-class kind, and by the record
  and data-class method names on a class that has record components or the Kotlin data flag.
- **`UNDECLARED`** is one table for the whole run: package, class count, source files, and where a
  flat or absent declaration explains it. Capped at twenty packages.
- **Rule-set legend** once at the end, only for the ids that appeared, replacing the per-group hint.
  The three compiler hints (`kotlin`, `java.lang`, `org.jetbrains.annotations`) stay as a `hint` row,
  since they belong to the group they explain.
- **Warnings** move to the end, just before the summary, which counts them.
- **Cap.** A group shows ten member lines, then `... and N more; <verbose switch> prints them all`,
  where the switch is the one of the tool that is running (`--verbose`, `-Pbyterails.verbose=true`,
  `-Dbyterails.verbose=true`).
- **Summary.** Counts violations (one per class and referenced type), groups, classes, packages and
  warnings. The Gradle and Maven failure messages become
  `byterails: 15 violations in :customers, grouped above; every reference is in
  customers/build/reports/byterails/violations.json, or run with -Pbyterails.verbose=true`.

## Verbose mode

Verbose prints, in this order, in addition to the default output rather than instead of it:

1. **The run.** Which rules files were loaded and how they were named (`byterails.kts`,
   `orders/byterails.kts`), the default rules, the base package, the slices, the module being checked
   and the other modules, and the class directories.
2. **Every diagnostic in full.** Kotlin compiler warnings as well as errors, with line and column; the
   stack trace of a script that throws; the stack trace of an internal error.
3. **Every reference, one line each, unfolded and uncapped**, in the order of the JSON report: the kind,
   the source location, the fully qualified class and member with the JVM descriptor as it is in the class
   file, the fully qualified referenced type, and the deciding rule with its location.
   `byterails: EXCLUSIVE    OrderService.kt:39  fixtures.app.domain.OrderService$later$fetch$1.invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object; -> fixtures.lib.web.RestTemplate  exclusive("fixtures.lib.web") in pkg("fixtures.app.infra.web") at byterails.kts:28`
4. **The effective rules of every package that had a violation**, each rule with the block it comes from
   and its location, so a surprising `NOT ALLOWED` can be traced without opening the file.
5. The grouped output and the summary as above, with the summary also giving the reference count.

Switches, all three doing the same thing:

| Tool | Switch | Also on with |
| --- | --- | --- |
| CLI | `--verbose` | |
| Gradle | `byterails { verbose.set(true) }`, `-Pbyterails.verbose=true` | `--info` and `--debug` |
| Maven | `<verbose>true</verbose>`, `-Dbyterails.verbose=true` | `-X` |

The core writes the verbose lines to a second sink, `detail`, next to `out`. Gradle maps `detail` to
`logger.info` and, with the switch on, to `logger.lifecycle`; Maven maps it to `debug` and `info`;
the CLI prints it or drops it. So the raw list is always in a `--info` or `-X` log, and the switch is
how a developer asks for it on a normal run. The JSON report does not change with verbose; it already
holds every reference.

Plumbing: `ByterailsRunner.run` gets one more overload with an `options: Map<String, Any?>` (keys
`verbose`, `verboseSwitch`, later `maxMembers`) and the `detail` consumer, JDK types only as before.
`ToolRunner` looks the new signature up by reflection and falls back to the old one when the core on
`toolClasspath` is older, logging once that verbose output needs a newer core. Maven calls the core
directly.

## Configuration errors

One shape for every failure to start, with the phase in the first line and one indented problem per
line, each with a location, the source line and a caret where the column is known:

```
byterails: byterails.kts does not compile
  byterails.kts:3:5: unresolved reference 'alow'
      alow("java.lang")
      ^
  byterails.kts:5:26: expecting ')'
      allow("fixtures.lib"
                         ^

byterails: byterails.kts failed while running
  byterails.kts:4: ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 2
      pkg(names[5])
  --verbose prints the stack trace

byterails: byterails.kts has 3 problems
  byterails.kts:17: pkg("fixtures.app") is declared twice; the first declaration is at byterails.kts:6
  byterails.kts:7: allow("java.util.concurrent") in pkg("fixtures.app") can never apply: deny("java.util") at the top of byterails.kts:4 always wins
  byterails.kts:14: allow("fixtures.lib.web") in pkg("fixtures.lib") can never apply: pkg("fixtures.app") owns it through exclusive("fixtures.lib.web") at byterails.kts:8

byterails: the build settings do not match byterails.kts
  slices a, b are configured in the build, but byterails.kts has no slice { } block; add one or remove the slices

byterails: internal error while reading build/classes/java/main/com/acme/Broken.class
  IllegalArgumentException: Unsupported class file major version 10161
  --verbose prints the stack trace; please report it at https://github.com/flock-community/byterails/issues
```

The changes behind it:

- **Collect instead of throw.** The DSL builders record a malformed prefix, an empty pattern, a bad regex
  or a second naming block as problems and `build()` throws them together, so the validator runs on
  the rest and one run shows every problem. `ConfigProblem` gains a column and the phase.
- **Compiler diagnostics keep their column**, and the loader prints the source line with a caret; it has
  the file. A script that throws is located through the `.kts` frame of the exception, as
  `SourceLocation.capture()` already does, and the exception stays attached as the cause.
- **A message rewrite pass** over the forty-odd sites in `Dsl.kt`, `RuleSetValidator.kt`,
  `ScriptLoader.kt`, `Module.kt`, `SliceTemplate.kt`, `BasePackage.kt`, `DefaultRuleSet.kt` and
  `Byterails.kt`, against the principles above: subject first, `pkg("x")` for blocks, `at the top of
  byterails.kts` for the root block, a location wherever one exists, and the module file named the way
  a violation names it (`orders/byterails.kts:7`) instead of the `module "orders": ` prefix. The
  dead-allow check reports an allow once, against the outermost owner. `unknown default rule set
  "hexagonl"` adds `did you mean hexagonal?` on a close match.
- **Internal errors** are caught at the three entry points and printed as above, with the file being
  read when there is one. The CLI exits 2 for them, not 1, so a CI script can tell "the tool could not
  run" from "violations found". An `Unsupported class file major version N` from ASM is translated to
  the JDK that produced it, as FR-32 asks.
- **Gradle and Maven** keep the exception cause attached, so `--stacktrace` and `-e` still work, and
  append their own switch to the `--verbose` hints.

## Steps

Four pull requests, each green on its own, in this order.

1. **Model.** A violation becomes one class, kind, referenced type and deciding rule with a list of
   sites; the checker collects sites instead of deduplicating on them; generated members and synthetic
   classes are marked in `AnalyzedClass`; the per-class kinds carry their facts as fields (nearest
   declaration, module and owner) instead of a message to slice; `NOT ALLOWED` carries the declaration
   as a `RuleRef`. A `Messages` object in `check` builds the sentence, the rows and the fix for each
   kind and the JSON `message` uses it. JSON schema 2: `sites` per violation, `fix`, `declaration`.
   Files: `Violation.kt`, `ViolationGroup.kt`, `Checker.kt`, `AnalyzedClass.kt`,
   `ReferenceCollector.kt`, `JsonReporter.kt`, `CheckerTest.kt`, `ReportersTest.kt`.
2. **Default console format.** `ConsoleReporter` as specified above, the undeclared table, the legend,
   warnings last, the tool-specific cap hint through the options map, the new failure lines in
   `ByterailsCheckTask` and `CheckMojo`. The functional tests, the Maven integration tests, the README
   samples and PRD FR-33, FR-35 and decision 24 follow.
3. **Verbose.** The `detail` sink and verbose renderer in the core, `--verbose` in `Main`, the runner
   overload, `ToolRunner` lookup with fallback, the `verbose` property and Gradle property in the plugin
   with the `--info` mapping, the Maven parameter with the `-X` mapping. A functional test with
   `-Pbyterails.verbose=true`, one with `--info`, a Maven integration test, README sections for the
   three tools, a PRD requirement.
4. **Configuration errors.** Collected DSL problems, columns and source lines, the phase headers, the
   message rewrite with a before/after table in the pull request, internal-error handling and the CLI
   exit status, the class-file-version translation. `ScriptLoaderTest`, `RuleSetValidatorTest`,
   `DslTest`, `ModulesTest` and the functional test for the broken rules file follow.

Later, outside this plan: attributing a synthetic class to its enclosing method through the
`EnclosingMethod` attribute so the member column names `later` rather than `+4 generated members`;
resolving `OrderService.kt` to a path through the plugin's source directories so IDE consoles make the
location clickable; colour on a terminal.

## Tests and documentation

`ReportersTest` keeps its style of asserting exact lines, one test per kind plus one for the
undeclared table, the legend, the cap and the verbose renderer, so the format is pinned. The Gradle
functional tests and the Maven `verify.bsh` scripts assert the new lines. The README gets a short
"Reading a violation" section with one block per kind and what each row means, and the verbose switch
in the Gradle, Maven and CLI sections. The PRD's reporting requirements and its sample are updated in
step 2 and gain a verbose requirement in step 3.

## Decisions to take

1. **Count violations per class and referenced type, and bump the JSON schema to 2.** Recommended; it is
   what makes the numbers honest. The alternative keeps schema 1 and folds only on the console, with a
   summary that says `15 violations (29 references)`.
2. **A `fix` row on every group.** Recommended for the default output; it is what a newcomer needs and
   one line per group for everyone else. The alternative prints it only for the first group of each kind.
3. **`--info` and `-X` turn verbose on.** Recommended, since the raw list then costs nothing on a build
   that is already being investigated. The alternative is the explicit switch only.
4. **Keep the six kind labels.** Recommended; the sentences carry the meaning now and the labels are
   stable for grep and for the JSON. The alternative renames `NOT ALLOWED` to `NOT DECLARED ALLOWED`
   or similar, which the sentence makes unnecessary.
