# byterails

Whitelist-based architecture guardrails for JVM projects, checked on bytecode.

byterails reads the compiled class files of a module and fails the build when a class references
something its package was never allowed to reference. One `byterails.kts` file at the root of the
project lists which packages may exist, what each of them may use, and how their classes must be
named. Anything the file does not mention is a violation.

Because it works on class files, one rule set covers Kotlin, Java and any other JVM language in the
same module, and the references it checks are the ones the compiler actually emitted.

## Installing

The artifacts are published to Maven Central under the group `community.flock.byterails`:
`byterails-core`, `byterails-rules` with the rule sets described under [Default rules](#default-rules),
`byterails-gradle-plugin` with the plugin id `community.flock.byterails`, and `byterails-maven-plugin`.
Both plugins put the core and the default rules on the tool classpath; a project that uses the
library directly depends on `byterails-rules`, which brings the core with it. The Gradle plugin is resolved from Maven Central rather than the plugin
portal, so add it to the plugin repositories once:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

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
| `flat()` | The package itself only: a class in a sub-package is undeclared unless another declaration covers it, and such a declaration inherits nothing from the flat one. |
| `basePackage { }` | The base package itself, as configured in the build; usually `flat()`, so it holds the entry point and its siblings stay separate subtrees. |
| `allowAnything()` | Classes in this subtree may reference anything at all, short of what the root block denies and what another package owns. For the entry point and the wiring. |

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

## Modules

A build split into Gradle subprojects or Maven modules can make each of them a byterails module: a
package under the base package that the project's classes must live in, with a `byterails.kts` of its
own next to the build file. The root file keeps what every module shares, the module file adds what
lies under the module, and the build names the module:

```kotlin
// build.gradle.kts, the root project
plugins {
    id("community.flock.byterails")
}

byterails {
    basePackage.set("com.acme")     // inherited by every module project
}
```

```kotlin
// orders/build.gradle.kts
byterails {
    module.set("orders")            // this project's classes live in com.acme.orders
}
```

```kotlin
// orders/byterails.kts
byterails {
    exported("api")                 // every other module may use com.acme.orders.api
    allow("org.slf4j")              // rules of the module root, inherited by the module's packages
    allow("common")                 // a package the root file declares

    pkg("api")
    pkg("domain") {
        allow("api")
        exclusive("org.jooq")       // owned across the build: no other module or package may use it
    }
}
```

| Concept | Meaning |
| --- | --- |
| `module` | A build setting: the project's classes belong to `com.acme.orders`, and no other project may put classes there. A project without one is checked against the root file alone, as before. |
| The module root | Declared implicitly and covers the whole subtree, like a slice root. The top-level `allow` and `deny` of the module file are its rules, inherited by the module's packages, with the root file's root block inherited underneath. |
| Declarations | `pkg("domain")` in the module file is `com.acme.orders.domain`. A rule prefix follows when it points into the module's declared packages, so `allow("api")` means `com.acme.orders.api`, while `allow("common")` is left for the base package to place. The root file may declare packages inside a module; the module file may not declare one the root file declared. |
| `exported("api")` | Every other module may reference `com.acme.orders.api`. Nothing else crosses a module boundary unless the root file allows it, and a package the root file declares gets no export. |
| `slice { }` | The slices of a module are configured on the module's project and lie under the module: `com.acme.orders.eu`, `com.acme.orders.us`. |
| `defaultRules` | On a module project the sets apply under the module: `hexagonal` declares `com.acme.orders.domain`, and a set that declares the base package, such as `hexagonalSpring`, describes the module root. |
| `WRONG MODULE` | A class compiled in the orders project whose package is not under `com.acme.orders`, or a class under `com.acme.orders` compiled by any other project. Nothing else is reported for such a class, because it would be judged by the wrong rules. |

Every project's check loads the root file and the rules file of every module, so an exclusive claimed
in one module is enforced in all of them, a cycle between modules is found, and the exports are known
everywhere. Which modules exist is read from the build: in Gradle every project that applies the plugin
and sets `module`, in Maven every module of the reactor whose POM configures `<module>` on this plugin,
so a `-pl` selection still sees all of them. A module without a rules file has the root rules and its
default rules only. A `byterails.kts` next to a build file that sets no module name fails the build, so
a module cannot silently lose its rules. On a module project, `slices` and `defaultRules` belong to the
module; the root project's own settings go with the root file, which may be absent once there are
modules. `basePackage { }` is not allowed in a module file, since the module root is declared implicitly.
A violation names a module file by its path from the root, `orders/byterails.kts:7`.

```
byterails: WRONG MODULE com.acme.shared.Misplaced
  module   is compiled in module "customers", which owns "com.acme.customers", but lies outside it
  source   Misplaced.java
```

Maven takes the module in the plugin configuration of the module's POM:

```xml
<!-- orders/pom.xml -->
<plugin>
  <groupId>community.flock.byterails</groupId>
  <artifactId>byterails-maven-plugin</artifactId>
  <configuration>
    <module>orders</module>
  </configuration>
</plugin>
```

Maven merges a parent's plugin configuration into every module, so `<basePackage>` in the parent POM
reaches all of them, as intended, but so would `<slices>` or `<defaultRules>` meant for one module;
keep those in the module's POM. The goal reads the other modules from the reactor, so run it from the
multi-module root.

## Default rules

byterails ships rule sets you apply by name. A default rule set expands into ordinary package
declarations, so everything above about inheritance, matching and reporting applies to it unchanged.
It can be applied from the build alone, with no `byterails.kts` at all, or on top of a rules file.

The rule sets live in the `byterails-rules` module and are written with the same DSL as a rules
file: one file per set in the package `community.flock.byterails.rules`, so what a set declares
is read the way a `byterails.kts` is read. This is the whole of `hexagonal`:

```kotlin
object Hexagonal : DefaultRuleSet(
    id = "hexagonal",
    description = "a domain package without external dependencies, in every slice",
    allowsLabel = "the language baseline: ${LANGUAGE_BASELINE.joinToString(", ")}",
) {
    override fun ByterailsBuilder.rules() {
        slice {
            pkg("domain") {
                isolated()
                LANGUAGE_BASELINE.forEach { allow(it) }
            }
        }
    }
}
```

Top-level declarations of a set are relative to the base package, and its `slice { }` block holds
what every slice gets: with slices configured it joins the slice template, without it goes under the
base package. The core finds the sets through a service-loader provider, so a rule set of your own is
a class like the one above, listed by a `DefaultRuleSetProvider` in `META-INF/services`.

### The rule sets

| Id | What it declares | What it catches |
| --- | --- | --- |
| `java` | The Java standard library, allowed in every package: the whole `java` namespace, the `javax` and `com.sun` packages the JDK itself exports, the `jdk` namespace, and the W3C DOM, SAX and JGSS packages. Not `javax` as a whole, because `javax.persistence`, `javax.inject` and friends are libraries, not the JDK. | Nothing by itself; it is the grant every Java project needs, so the rules file only has to say what is specific to the application. |
| `kotlin` | The Kotlin standard library, allowed in every package: `kotlin`, the `org.jetbrains.annotations` the compiler writes into every class, and everything in `java`, because Kotlin's collections, strings and boxed numbers compile to `java.util` and `java.lang`. | Nothing by itself; a Kotlin project needs no other root allows. |
| `hexagonal` | A `domain` package that cannot have any external dependency. With slices configured, one in every slice; without, one under the base package. The package is *isolated*: it inherits nothing from the root block or an enclosing declaration, and may reference only the language baseline and its own subtree. | Any class in `domain` that touches a framework, a library, an adapter or another package of the application, whatever the rest of the rules file allows. |
| `hexagonalSpring` | The hexagonal layout of a vertically sliced Spring Boot service: the application class and a `config` package under the base package, and in every slice an isolated `domain` split into `model`, `ports` and `services`, an `application` layer, and `adapters.inbound` and `adapters.outbound` with fixed places for controllers and database code. See [The hexagonalSpring layout](#the-hexagonalspring-layout). | A package outside the layout, a dependency pointing outwards, a `@Configuration` outside `config`, a Spring web annotation outside `controllers`, a controller using more of Spring than HTTP needs, persistence code outside `adapters.outbound.database`, a misnamed port, service or configuration class. |

The language baseline is what a class needs to exist plus the value types a domain model is made of:
`kotlin`, `org.jetbrains.annotations`, `java.lang`, `java.util`, `java.time`, `java.math` and
`java.text`. Nothing in it talks to the outside world.

Rule sets are broad grants, so narrowing one is expected: a `deny("javax.swing")` at the root or an
`exclusive("javax.sql")` in the persistence package wins over the `java` allows, and the validator
never reports a rule-set allow as dead.

A violation reads like any other, except that allows coming from a rule set show as one token, with a
hint that spells the set out. Here `com.acme.sales.domain.Leak` holds a `java.net.URI`:

```
byterails: NOT ALLOWED  com.acme.sales.domain.Leak
  field    endpoint : java.net.URI
  allows   [hexagonal]
  source   Leak.java
  hint     [hexagonal] is the language baseline: kotlin, org.jetbrains.annotations, java.lang, java.util, java.time, java.math, java.text
```

### The hexagonalSpring layout

`hexagonalSpring` is the whitelist form of a layout in common use for Spring Boot services. It needs a base package and is meant for a sliced service; without slices the slice
part goes under the base package. Under `com.acme` with slices `orders` and `customers` it declares:

| Package | May reference | Named |
| --- | --- | --- |
| `com.acme` (flat) | anything the root block does not deny and no other package owns | `*Application` |
| `com.acme.config` | anything, as above; owns `org.springframework.context.annotation.Configuration` | `*Config` |
| `<slice>.domain.model` (flat, isolated) | `kotlin`, `org.jetbrains.annotations`, `java`, `org.springframework.stereotype` | |
| `<slice>.domain.ports` (flat, isolated) | the same plus `domain.model` | `*Port` |
| `<slice>.domain.services` (flat, isolated) | the same plus `domain.model` and `domain.ports` | `*Service` |
| `<slice>.application` | `domain.*`, `org.springframework.stereotype`; denies `adapters.outbound` | |
| `<slice>.adapters.inbound` | `domain.model`, `domain.services`, `application`; denies `domain.ports` and `adapters.outbound` | |
| `<slice>.adapters.inbound.controllers` | plus `org.springframework.http`, `.security.access.prepost`, `.validation.annotation`, `.web.multipart`; owns `org.springframework.web.bind.annotation` | |
| `<slice>.adapters.inbound.controllers.error` | plus `org.springframework.dao`, `.security.access`, `.stereotype`, `.validation`, `.web` | |
| `<slice>.adapters.outbound` | `domain.model`, `domain.ports`, `org.springframework.stereotype`; denies `domain.services` and `adapters.inbound` | |
| `<slice>.adapters.outbound.database` (flat) | plus Spring Data, JPA, jOOQ, MongoDB, R2DBC, Exposed, and its `model` and `mappers` | |
| `<slice>.adapters.outbound.database.mappers` (flat) | plus `database.model` | |
| `<slice>.adapters.outbound.database.model` (flat) | plus the persistence libraries | |

A naming rule on a file-name convention accepts the `Kt` facade too, so `OrderPort.kt` with
top-level functions passes as `OrderPortKt`. Everything except the domain inherits the root block
and the slice root, which is how a rules file widens the layout: `allow("org.slf4j")` in `slice { }`
reaches every adapter, and a sub-package declaration such as `pkg("adapters.inbound.kafka") {
allow("org.springframework.kafka") }` gives one adapter what only it needs. The domain is isolated
and flat, so it cannot be widened; a logging library the domain needs is not expressible with this
rule set. The slice root is declared as always, so a class directly in `<slice>` or `<slice>.domain`
is not undeclared; it gets the slice root's rules, which allow nothing of the domain.

What source-level guardrails for this layout usually check and this rule set does not: rules conditioned on annotations, supertypes
or function names (`@RestController` classes ending with `Controller`, outbound `*Adapter` classes
implementing a port, `toDomain` mappers living in `mappers`), a `@RequestBody` parameter that is a
domain type, the Kafka listener and sender conventions, integration-test conventions, and inline
fully qualified names. Those are source-level or member-level rules; see the PRD for what is planned.

```kotlin
// build.gradle.kts: the layout from the build alone
byterails {
    basePackage.set("com.acme")
    slices.set(listOf("orders", "customers"))
    defaultRules.set(listOf("kotlin", "hexagonalSpring"))
}
```

### Gradle

The plugin's `defaultRules` list names the rule sets. Together with `basePackage` and `slices`
this is a complete configuration; no rules file is needed:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.21"
    id("community.flock.byterails") version "0.0.2"
}

byterails {
    basePackage.set("com.acme")
    slices.set(listOf("orders", "customers", "shipping"))
    defaultRules.set(listOf("kotlin", "hexagonal"))
}
```

`kotlin` allows the standard libraries in every package, and `hexagonal` declares
`com.acme.orders.domain`, `com.acme.customers.domain` and `com.acme.shipping.domain`, each isolated.
Every other package of the application is undeclared and reported as such until the rules file lists
it, which is the whitelist doing its job. Without `slices` the same configuration declares one
`com.acme.domain`. A Java project uses `java` instead of `kotlin`. `./gradlew byterailsCheck` runs the
check, and `check` depends on it.

With a rules file present, the default rules are added to it. The file describes the rest of the
structure and the rule sets supply the standard-library allows and the domain:

```kotlin
// byterails.kts
byterails {
    slice {
        exported("api")
        pkg("api")
        pkg("application") {
            allow("domain")
            allow("api")
        }
        pkg("infra") {
            allow("domain")
            exclusive("org.jooq")
        }
    }
}
```

Declaring `domain` in the file as well would be a duplicate declaration and fails at load time; the
rule set owns it. Root allows the file repeats, such as `allow("kotlin")` next to the `kotlin` rule
set, are harmless.

### Maven

The `check` goal takes the same three settings. `defaultRules` is a list, so each entry is its own
element:

```xml
<plugin>
  <groupId>community.flock.byterails</groupId>
  <artifactId>byterails-maven-plugin</artifactId>
  <version>0.0.2</version>
  <configuration>
    <basePackage>com.acme</basePackage>
    <slices>
      <slice>orders</slice>
      <slice>customers</slice>
      <slice>shipping</slice>
    </slices>
    <defaultRules>
      <defaultRule>java</defaultRule>
      <defaultRule>hexagonal</defaultRule>
    </defaultRules>
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

Every setting is also a property, so a one-off run needs no POM change:

```
mvn verify -Dbyterails.basePackage=com.acme -Dbyterails.slices=orders,customers -Dbyterails.defaultRules=java,hexagonal
```

When the multi-module root holds a `byterails.kts`, the default rules are added to it, exactly as in
Gradle; when it does not, the default rules stand alone. A rules file that is absent while no default
rules are set fails the build with a message naming the expected path.

### In the rules file and by hand

The same rule sets are available as calls in the rules file, for teams that keep everything in one
place: `java()`, `kotlin()`, `hexagonal()` and `hexagonalSpring()` at the top level, and `hexagonal()`
inside `slice { }` as well. A set's slice part goes into the file's `slice { }` block when there is
one and under the base package otherwise, wherever in the file the call is. Any rule set built with
the DSL can be applied the same way with `include(ruleSet)`. The building blocks behind the sets are
available on any package:

```kotlin
pkg("domain") {
    isolated()          // inherit nothing, not even the root block
    allow("kotlin")     // then list exactly what this package may use
    allow("java.lang")
}

pkg("domain.model") {
    flat()              // this package only; domain.model.money would be undeclared
}
```

The CLI takes `--default-rules kotlin,hexagonal`. Naming a rule set that does not exist is a
configuration error that lists the known ids; naming one twice is folded into one. From compiled
code the keywords are extension functions in `community.flock.byterails.rules`, so a test that
builds rules with `byterails { kotlin(); hexagonal() }` imports them from there.

## Gradle

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    id("community.flock.byterails") version "0.1.0"
}
```

The plugin adds a `byterailsCheck` task, wired into `check`, that reads the main classes of the
project against `byterails.kts` in the root project. Every project of a multi-module build checks its
own classes against the same file, plus the rules files of the [modules](#modules) when the build has
them. Violations fail the task and are written to `build/reports/byterails/violations.json`.

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
`allow("com.acme.domain")` while `allow("kotlin")` stays as written. The CLI takes `--base-package`, `--slices` and `--default-rules`.

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
`-Dbyterails.basePackage=...`, `-Dbyterails.slices=...`, `-Dbyterails.defaultRules=...`,
`-Dbyterails.module=...` and `-Dbyterails.moduleRulesFile=...`. A Maven module that configures
`<module>` is a byterails [module](#modules); the goal finds the other modules' rules files through
the reactor. The same rules file gives the same result from Gradle and Maven, because both call the
same core.

## Command line and library

The core ships a small CLI, exit status 1 on violations and 2 on a broken rules file:

```
java -cp <byterails-rules, byterails-core and their dependencies> community.flock.byterails.cli.Main \
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
`byterails-core` and `byterails-rules` against this repository's own [`byterails.kts`](byterails.kts). The Maven plugin is
built by Maven, because its descriptor comes from Maven's plugin tooling, and runs its integration
tests against sample projects with the invoker plugin.

## Releasing

Publishing a GitHub release runs the deploy workflow, which builds, tests and publishes the core, the
Gradle plugin and the Maven plugin to Maven Central under the release tag, with a leading `v`
dropped. Every push to `main` publishes the `-SNAPSHOT` version from `gradle.properties` to the
Central snapshot repository, and the workflow can be dispatched by hand with a version. It needs four
repository secrets: `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`, a Central Portal user token, and
`GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` for signing. The Gradle side uses the Nexus publish plugin
against the Central Portal's staging API; the Maven side uses the Central publishing plugin with the
`release` profile. Both sign with the same key.

## Status

0.1: core library, `byterails.kts` loader, Gradle plugin, Maven plugin, CLI, slices, default rules,
modules. Planned next:
kind-aware and annotation-conditioned naming, expiring allows, SARIF and JUnit XML reports.
