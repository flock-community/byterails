# byterails

Whitelist-based architecture guardrails for JVM projects, checked on bytecode.

byterails reads the compiled class files of a module and fails the build when a class references
something its package was never allowed to reference. One `byterails.kts` file at the root of the
project lists which packages may exist, what each of them may use, and how their classes must be
named. Anything the file does not mention is a violation.

Because it works on class files, one rule set covers Kotlin, Java and any other JVM language in the
same module, and the references it checks are the ones the compiler actually emitted.

## The rules file

```kotlin
// byterails.kts
byterails {
    // Root rules are inherited by every declared package. Nothing is allowed implicitly,
    // so a Kotlin project starts with the three prefixes its compiler writes into every class.
    allow("kotlin")
    allow("java.lang")
    allow("org.jetbrains.annotations")
    allow("java.util")
    allow("java.time")

    // Entry point and wiring only. A declared subtree may always reference itself,
    // so this package can reach every layer; that right is not inherited by its sub-packages.
    pkg("com.acme") {
        allow("org.springframework.boot")
        allow("org.springframework.context")
    }

    pkg("com.acme.domain") {
        deny("java.util.concurrent")   // narrows the inherited java.util
    }

    pkg("com.acme.application") {
        allow("com.acme.domain")
        exclusive("org.springframework.transaction")
        naming { endsWith("UseCase") }
    }

    pkg("com.acme.infra.persistence") {
        allow("com.acme.domain")
        allow("com.acme.application")
        exclusive("org.jooq")
        naming { endsWith("Repository") }
    }

    pkg("com.acme.infra.web") {
        allow("com.acme.application")
        exclusive("org.springframework.web")
        naming {
            endsWith("Controller")
            endsWith("Advice")
        }
    }
}
```

| Concept | Meaning |
| --- | --- |
| `pkg("a.b")` | The package `a.b` and its sub-packages may exist. A class in a package no declaration covers is a violation. The subtree may always reference itself. |
| `allow("x.y")` | Classes in this subtree may reference anything under `x.y`. Local to the subtree; says nothing about other packages. |
| `deny("x.y")` | Classes in this subtree may not reference anything under `x.y`. Deny wins over allow, whatever the prefix length and wherever it was declared. |
| `exclusive("x.y")` | An allow for this subtree plus a deny for every other declared package. Two packages claiming overlapping prefixes is an error at load time. |
| Inheritance | A package's effective rules are its own plus those of every enclosing declaration and the root block. A child may add allows and may deny what it inherited. |
| Matching | Prefixes match on dot boundaries: `com.acme.domain` covers `com.acme.domain.model`, not `com.acme.domainservice`. A prefix may name a class: `deny("java.lang.System")`. |
| `naming { }` | Patterns (`endsWith`, `startsWith`, `matches`) on the simple class name. A class passes when one pattern matches. Nested and generated classes are skipped. |

Every reference from a class in package P to a type T is decided in this order: T inside P's own
declaration, T exclusive to another package, a deny in P's effective rules, an allow in P's effective
rules, otherwise not allowed. The last branch is what makes byterails a whitelist.

The file is validated before any class file is read. Duplicate declarations, clashing exclusives,
allows that can never apply, and malformed prefixes fail the build with the line that caused it.
Cycles between declared packages and Kotlin package names that compile to Java ones are warnings.

## Slices

An application cut into vertical slices, each with the same internal structure, describes that
structure once in the rules file and names the slices in the build:

```kotlin
// byterails.kts
byterails {
    allow("kotlin")
    allow("java.lang")
    allow("org.jetbrains.annotations")

    pkg("common")                   // shared code, relative to the base package

    slice {
        exported("api")             // every slice may use the api package of every other slice
        allow("org.slf4j")          // rules of each slice root, inherited by its packages

        pkg("api")
        pkg("domain")
        pkg("application") {
            allow("domain")
            allow("api")
        }
        pkg("infra") {
            allow("domain")
            exclusive("org.jooq")   // owned by the infra package of every slice, and by nothing else
        }
    }
}
```

```kotlin
// build.gradle.kts
byterails {
    basePackage.set("com.acme")
    slices.set(listOf("orders", "customers", "shipping"))
}
```

Maven takes the same as `<slices><slice>orders</slice>...</slices>` or `-Dbyterails.slices=orders,customers`.
Slice names are packages relative to the base package. The template expands into
`com.acme.orders`, `com.acme.orders.domain` and so on for each slice. Template rules are relative to
the slice: `allow("domain")` in `orders` means `com.acme.orders.domain`, while a prefix that points
outside the template, such as `org.slf4j`, is taken as written. Slices cannot see each other, because
nothing allows them to, except through exported packages. An exclusive in the template is owned by
that package of every slice together, so the slices' copies never clash and the library stays denied
outside the slices. A rules file with a `slice { }` block and no configured slices fails to load, and
so does a build that names slices for a file without one, so the two sides cannot drift apart.

## Gradle

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    id("community.flock.byterails") version "0.1.0"
}
```

The plugin adds a `byterailsCheck` task, wired into `check`, that reads the main classes of the
project against `byterails.kts` in the root project. Every module of a multi-module build checks its
own classes against the same file. Violations fail the task and are written to
`build/reports/byterails/violations.json`.

```
byterails: DENIED       com.acme.domain.Order
  field    entityManager : jakarta.persistence.EntityManager
  rule     deny("jakarta.persistence")              byterails.kts:14  in "com.acme.domain"
  source   Order.kt

byterails: NOT ALLOWED  com.acme.domain.OrderService
  method   place(Order) : void
  ref      org.springframework.web.client.RestTemplate
  allows   com.acme.domain, java.lang, java.time, java.util, kotlin
  source   OrderService.kt:42

byterails: 2 violations in 1,204 classes, 17 packages
```

A project whose packages all live under one root can set `byterails { basePackage.set("com.acme") }`
and write the rules file relative to it: `pkg("domain")` then means `com.acme.domain`. A rule prefix
is prefixed too when it points into the declared package tree, so `allow("domain")` becomes
`allow("com.acme.domain")` while `allow("kotlin")` stays as written. The CLI takes `--base-package` and `--slices`.

While adopting byterails on an existing code base, `-Pbyterails.reportOnly=true` prints every
violation and keeps the build green. The same switch is available as `byterails { reportOnly = true }`.
The tool runs in a class loader of its own, so neither Gradle's nor the build's Kotlin version
matters; `byterails { toolClasspath.setFrom(...) }` overrides where the core comes from.

## Maven

```xml
<plugin>
  <groupId>community.flock.byterails</groupId>
  <artifactId>byterails-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <basePackage>com.acme</basePackage>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The `check` goal runs in the `verify` phase and reads the module's compiled classes against
`byterails.kts` in the multi-module root directory. Each module checks its own classes, violations
fail the build, and the JSON report lands in `target/byterails/violations.json`. Properties:
`-Dbyterails.reportOnly=true`, `-Dbyterails.skip=true`, `-Dbyterails.rulesFile=...`,
`-Dbyterails.basePackage=...` and `-Dbyterails.slices=...`. The same rules file gives the same result from Gradle and Maven,
because both call the same core.

## Command line and library

The core ships a small CLI, exit status 1 on violations and 2 on a broken rules file:

```
java -cp <byterails-core and its dependencies> community.flock.byterails.cli.Main \
    --rules byterails.kts --classes build/classes/kotlin/main:build/classes/java/main \
    --report build/byterails.json --report-only
```

The same rules can be built in code with the DSL and checked from a test:

```kotlin
val rules = byterails { allow("kotlin"); allow("java.lang"); pkg("com.acme") }
val result = Byterails.check(rules, listOf(File("build/classes/kotlin/main")))
```

## What byterails reads

A reference is any class name the compiler wrote into the class file: superclasses and interfaces,
generic signatures, annotations and their arguments, field and method descriptors, every instruction
in a method body, invokedynamic call sites with their bootstrap arguments, local variable tables,
exception handlers, record components and permitted subclasses. Nested classes are checked as
classes of their own, so lambda and coroutine bodies are covered.

## What it cannot see

- **Inlined constants.** A Kotlin `const val` or a Java `static final` primitive or String is copied
  into the user at compile time and leaves no reference.
- **Inline functions.** The body of a Kotlin `inline fun` is copied into every caller, so the caller
  carries the references of the inlined body and the violation appears there.
- **Strings and reflection.** `Class.forName`, component scanning by package name and serialization
  type ids are invisible.
- **Type aliases, source-retention annotations, value classes.** Erased before bytecode.
- **Multiplatform.** Only JVM targets produce class files.
- **Members.** Rules see types, not members. Banning a class bans every use of it.

## Design

The product requirements, the rule model with its evaluation order, the decisions log and the open
questions are in [docs/PRD.md](docs/PRD.md).

## Building

```
./gradlew build
./gradlew publishToMavenLocal && mvn -f byterails-maven-plugin/pom.xml verify
```

The Gradle build compiles a corpus of Kotlin and Java fixtures and asserts where every kind of
reference is found, runs the Gradle plugin against real builds with TestKit, and checks
`byterails-core` against this repository's own [`byterails.kts`](byterails.kts). The Maven plugin is
built by Maven, because its descriptor comes from Maven's plugin tooling, and runs its integration
tests against sample projects with the invoker plugin.

## Status

0.1: core library, `byterails.kts` loader, Gradle plugin, Maven plugin, CLI. Planned next:
kind-aware and annotation-conditioned naming, expiring allows, SARIF and JUnit XML reports.
