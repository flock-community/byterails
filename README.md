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
`byterails-dsl` with the rule model, the `byterails { }` DSL and the `byterails.kts` script definition,
`byterails-core` with the bytecode analysis, the checker and the script loader, `byterails-rules` with
the rule sets described under [Default rules](#default-rules), `byterails-gradle-plugin` with the
plugin id `community.flock.byterails`, and `byterails-maven-plugin`. Both plugins put the core and the
default rules on the tool classpath. A project that uses the library directly depends on
`byterails-core`, which brings the DSL with it, and on `byterails-rules` when it applies a default rule
set; a rule set of its own needs `byterails-dsl` only. Everything is compiled for Java 17, so the build
that runs the check needs JDK 17 or newer; the class files it reads may target another version.
The Gradle plugin is resolved from Maven Central rather than the plugin portal, so add it to the plugin
repositories once:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

IntelliJ IDEA resolves `byterails.kts` files, with completion and the DSL's documentation, once the
script definition is on the classpath of a module of the project. The definition lives in
`byterails-dsl`, which carries no bytecode analysis and no script host. The plugins keep the tool off
the project classpath, so add `byterails-rules`, which brings the DSL with it, where the IDE looks,
without shipping it:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("community.flock.byterails:byterails-rules:0.1.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>community.flock.byterails</groupId>
  <artifactId>byterails-rules</artifactId>
  <version>0.1.0</version>
  <scope>provided</scope>
</dependency>
```

The definition applies to every file whose name ends in `byterails.kts`, so a module's `byterails.kts`
and a qualified `orders.byterails.kts` are both recognised.

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
| `exclusive("x.y")` | An allow for this subtree plus a deny for every other declared package. It belongs in a `pkg` block; at the top of the file it is an error, since no package would own it. Two packages claiming overlapping prefixes is an error at load time, unless one lies inside the other and claims part of what the outer one owns. |
| Inheritance | A package's effective rules are its own plus those of every enclosing declaration and the root block. A child may add allows and may deny what it inherited. `isolated()` and `flat()` cut the chain, as described below. |
| Matching | Prefixes match on dot boundaries: `com.acme.domain` covers `com.acme.domain.model`, not `com.acme.domainservice`. A prefix may name a class: `deny("java.lang.System")` covers that class and its nested classes. |
| `naming { }` | Patterns on the simple class name: `endsWith("x")`, `startsWith("x")` and `matches("regex")`, where the regular expression must match the whole name. A class passes when one pattern matches. A declaration takes one naming block, and a package without one uses that of the nearest enclosing declaration. Nested classes, compiler-generated classes, `package-info` and classes carrying an annotation named `Generated` that is kept in the class file are skipped. |
| `isolated()` | Inherit nothing the rules file wrote, not even the root block: classes in this subtree may reference only what the block itself lists, and the subtree. The root rules of a default rule set still apply, so with `kotlin` or `java` applied an isolated package may use the standard library without listing it. |
| `flat()` | The package itself only: a class in a sub-package is undeclared unless another declaration covers it, and such a declaration inherits neither rules nor naming from the flat one. |
| `basePackage { }` | The base package itself, as configured in the build; usually `flat()`, so it holds the entry point and its siblings stay separate subtrees. |
| `allowAnything()` | Classes in this subtree may reference anything at all, short of what the root block denies and what another package owns. For the entry point and the wiring. |

Every reference from a class in package P to a type T is decided in this order: T inside P's own
declaration, T exclusive to another package, a deny in P's effective rules, an allow in P's effective
rules, otherwise not allowed. The last branch is what makes byterails a whitelist.

A rules file calls `byterails { }` once. The file is validated before any class file is read. Duplicate
declarations, clashing exclusives, an exclusive at the top of the file, allows that can never apply
because a deny or another package's exclusive covers them, malformed prefixes, a naming block that is
empty, repeated or holds an invalid regular expression, and a second `slice { }` block fail the build,
every problem of the file in one run, each with the line it is on. Cycles between declared packages and
rules on Kotlin names that compile to Java ones, such as `kotlin.collections`, are warnings: they are
printed as `byterails: warning: ...` and counted in the summary line, and the check goes on.

```
byterails: 2 problems in byterails.kts
  byterails.kts:7: allow("java.util.concurrent") in pkg("com.acme.domain") can never apply: it is shadowed by deny("java.util") in the root block at byterails.kts:4, and deny always wins
      allow("java.util.concurrent")
  byterails.kts:17: pkg("com.acme.domain") is declared twice; the first is at byterails.kts:6
      pkg("com.acme.domain")
```

A file that does not compile prints the compiler's messages the same way, with the column and a caret;
a file that throws names the line; a build setting that does not fit the file, such as slices without
a `slice { }` block, says so under `problems in the build settings`.

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
`com.acme.orders`, `com.acme.orders.domain` and so on for each slice. The slice root is a declaration
of its own that covers the whole slice, so a class in a package of the slice the template does not
name gets the rules of the slice root. Next to `exported` and `pkg`, the block takes `allow`, `deny`,
`exclusive` and `naming` for the slice root, where an exclusive is owned by the roots of all slices
together. Template rules are relative to
the slice: `allow("domain")` in `orders` means `com.acme.orders.domain`, while a prefix that points
outside the template, such as `org.slf4j`, is taken as written. Slices cannot see each other, because
nothing allows them to, except through exported packages. An exclusive in the template is owned by
that package of every slice together, so the slices' copies never clash and the library stays denied
outside the slices. A rules file with a `slice { }` block and no configured slices fails to load, and
so does a build that names slices for a file without one, so the two sides cannot drift apart. The
exception is a build that also applies [default rules](#default-rules): they bring the slice template
with them, so the file needs no block of its own.

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
plugins {
    `java-library`                  // or Kotlin's plugin: the check reads the main classes
    id("community.flock.byterails")
}

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
default rules only; `moduleRulesFile` points the module at a file elsewhere. A `byterails.kts` next to
a build file that sets no module name fails the build, so a module cannot silently lose its rules. On a module project, `slices` and `defaultRules` belong to the
module; the root project's own settings go with the root file, which may be absent once there are
modules. `basePackage { }` is not allowed in a module file, since the module root is declared implicitly.
A violation names a module file by its path from the root, `orders/byterails.kts:7`. Modules are a
feature of the two plugins; the command line checks against one rules file.

```
byterails: WRONG MODULE com.acme.shared is compiled in module "customers" but lies outside its package com.acme.customers
  fix      move the classes under com.acme.customers, or into the project of the module that owns them
  Misplaced.java  Misplaced
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
base package. The `slice { }` block of a set declares packages only, because without slices there is
no slice root to hold anything else. The core finds the sets through a service-loader provider, so a
rule set of your own is a class like the one above, listed by a `DefaultRuleSetProvider` that is
registered in `META-INF/services/community.flock.byterails.rules.DefaultRuleSetProvider`, in a jar on
the tool classpath: `byterails { toolClasspath.from(...) }` in Gradle, a dependency of the plugin in
Maven.

### The rule sets

| Id | What it declares | What it catches |
| --- | --- | --- |
| `java` | The Java standard library, allowed in every package: the whole `java` namespace, the `javax` and `com.sun` packages JDK 21 itself exports, the `jdk` namespace, `netscape.javascript`, and the W3C DOM, SAX and JGSS packages. Not `javax` as a whole, because `javax.persistence`, `javax.inject` and friends are libraries, not the JDK. | Nothing by itself; it is the grant every Java project needs, so the rules file only has to say what is specific to the application. |
| `kotlin` | The Kotlin standard library, allowed in every package: `kotlin`, the `org.jetbrains.annotations` the compiler writes into every class, and everything the `java` set allows, because Kotlin's collections, strings and boxed numbers compile to `java.util` and `java.lang`. | Nothing by itself; a Kotlin project needs no other root allows. |
| `hexagonal` | A `domain` package that cannot have any external dependency. With slices configured, one in every slice; without, one under the base package. The package is *isolated*: it inherits nothing the rules file wrote in the root block or an enclosing declaration, and may reference only the language baseline and its own subtree, plus the standard library when `java` or `kotlin` is applied as well. | Any class in `domain` that touches a framework, a library, an adapter or another package of the application, whatever the rest of the rules file allows. |
| `hexagonalSpring` | The hexagonal layout of a vertically sliced Spring Boot service: the application class and a `config` package under the base package, and in every slice an isolated `domain` split into `model`, `ports` and `services`, an `application` layer, and `adapters.inbound` and `adapters.outbound` with fixed places for controllers and database code. See [The hexagonalSpring layout](#the-hexagonalspring-layout). | A package outside the layout, a dependency pointing outwards, a `@Configuration` outside `config`, a Spring web annotation outside `controllers`, a controller using more of Spring than HTTP needs, persistence code outside `adapters.outbound.database`, a misnamed port, service or configuration class. |

The language baseline is what a class needs to exist plus the value types a domain model is made of:
`kotlin`, `org.jetbrains.annotations`, `java.lang`, `java.util`, `java.time`, `java.math` and
`java.text`. Nothing in it talks to the outside world.

Rule sets are broad grants, so narrowing one is expected: a `deny("javax.swing")` at the root or an
`exclusive("javax.sql")` in the persistence package wins over the `java` allows, and the validator
never reports a rule-set allow as dead.

A violation reads like any other, except that allows coming from a rule set show as one token, the
package row names the set, and the run ends with a legend that spells the token out. Here
`com.acme.sales.domain.Leak` holds a `java.net.URI` and `hexagonal` is the only set applied; with
`java` or `kotlin` next to it the domain may use the whole standard library and this passes:

```
byterails: NOT ALLOWED  com.acme.sales.domain uses java.net, which no rule allows
  package  pkg("com.acme.sales.domain") from the hexagonal rule set, isolated
  may use  [hexagonal]
  fix      move the code; pkg("com.acme.sales.domain") comes from the hexagonal rule set and is isolated, so the rules file cannot widen it
  Leak.java  Leak uses URI in endpoint

byterails: [hexagonal] stands for the language baseline: kotlin, org.jetbrains.annotations, java.lang, java.util, java.time, java.math, java.text
```

### The hexagonalSpring layout

`hexagonalSpring` is the whitelist form of a layout in common use for Spring Boot services. It needs a base package and is meant for a sliced service; without slices the slice
part goes under the base package. Under `com.acme` with slices `orders` and `customers` it declares:

| Package | May reference | Named |
| --- | --- | --- |
| `com.acme` (flat) | anything the root block does not deny and no other package owns | `*Application` |
| `com.acme.config` | anything, as above; owns `org.springframework.context.annotation.Configuration` | `*Config` |
| `<slice>.domain.model` (flat, isolated) | `kotlin`, `org.jetbrains.annotations`, the `java` namespace, `org.springframework.stereotype` | |
| `<slice>.domain.ports` (flat, isolated) | the same plus `domain.model` | `*Port` |
| `<slice>.domain.services` (flat, isolated) | the same plus `domain.model` and `domain.ports` | `*Service` |
| `<slice>.application` | `domain.*`, `org.springframework.stereotype`; denies `adapters.outbound` | |
| `<slice>.adapters.inbound` | `domain.model`, `domain.services`, `application`; denies `domain.ports` and `adapters.outbound` | |
| `<slice>.adapters.inbound.controllers` | plus `org.springframework.http`, `.security.access.prepost`, `.validation.annotation`, `.web.multipart`; owns `org.springframework.web.bind.annotation` | |
| `<slice>.adapters.inbound.controllers.error` | plus `org.springframework.dao`, `.security.access`, `.stereotype`, `.validation`, `.web` | |
| `<slice>.adapters.outbound` | `domain.model`, `domain.ports`, `org.springframework.stereotype`; denies `domain.services` and `adapters.inbound` | |
| `<slice>.adapters.outbound.database` (flat) | what `adapters.outbound` may, plus the persistence libraries and its `model` and `mappers` | |
| `<slice>.adapters.outbound.database.mappers` (flat) | what `adapters.outbound` may, plus `database.model` | |
| `<slice>.adapters.outbound.database.model` (flat) | what `adapters.outbound` may, plus the persistence libraries | |

A row that says "plus" adds to the row of the enclosing package, which it inherits. The persistence
libraries are `org.springframework.data`, `jakarta.persistence`, `javax.persistence`, `org.jooq`,
`com.mongodb`, `io.r2dbc` and `org.jetbrains.exposed`.

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
    id("community.flock.byterails") version "0.1.0"
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
check, and `check` depends on it. In a violation of a package that a rule set declared without
isolating it, the `fix` row points at where the package inherits from, the root block or `slice { }`,
since the declaration itself cannot be edited.

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
  <version>0.1.0</version>
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
    isolated()          // inherit nothing but the default rules, not even the root block
    allow("kotlin")     // then list exactly what this package may use
    allow("java.lang")
}

pkg("domain.model") {
    flat()              // this package only; domain.model.money would be undeclared
}
```

The CLI takes `--default-rules kotlin,hexagonal`. Naming a rule set that does not exist is a
configuration error that lists the known ids and suggests the nearest one; naming one twice is folded
into one. From compiled
code the keywords are extension functions in `community.flock.byterails.rules`, so a test that
builds rules with `byterails { kotlin(); hexagonal() }` imports them from there.

## Gradle

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    id("community.flock.byterails") version "0.1.0"
}
```

The plugin adds a `byterailsCheck` task that reads the classes of the project's `main` source set
against `byterails.kts` in the root project. The classes and the dependency of `check` on the task come
with the `java` plugin or one built on it, such as Kotlin's, so apply the plugin to projects that
compile JVM code. Every project of a multi-module build checks its own classes against the same file,
plus the rules files of the [modules](#modules) when the build has them. Violations fail the task and
are written to `build/reports/byterails/violations.json`. The task is cacheable and up to date until
the classes, a rules file or a setting changes.

| Setting in `byterails { }` | Meaning | Default |
| --- | --- | --- |
| `rulesFile` | The rules file. It may be absent when `defaultRules` is set or the build has modules; otherwise a missing file fails the task. | `byterails.kts` in the root project |
| `basePackage` | The package every name in the rules file is relative to. A subproject inherits the root project's value when the root project applies the plugin. | unset: every name is absolute |
| `slices` | The [slices](#slices), relative to the base package. | none |
| `defaultRules` | Ids of the [default rule sets](#default-rules) to apply. | none |
| `module` | The [module](#modules) this project is. | unset |
| `moduleRulesFile` | The rules file of this project's module; ignored without `module`. | `byterails.kts` in the project directory |
| `reportOnly` | Print the violations and keep the build green. | `-Pbyterails.reportOnly`, else `false` |
| `verbose` | Print every reference and the rules in effect. | `-Pbyterails.verbose`, else `false` |
| `toolClasspath` | The byterails core and rule sets the task runs. | the `byterails` configuration: `byterails-core` and `byterails-rules` in the plugin's version |

### Reading a violation

Every violation prints as a sentence with the package as its subject, followed by what a developer
needs to act: the block of the rules file it concerns with its line, what the package may use, a `fix`
row naming both ways out, and one line per class that starts with the source location and names the
members involved. Violations with the same cause, that is the same package, kind, referenced package
and deciding rule, form one block, because they are one line to change in the rules file or one piece
of code to move. A violation is one class and one referenced type: members the compiler generated,
such as property accessors, record methods and lambda bodies, fold into the member the developer
wrote, and a lambda or anonymous class is reported under the class and method it was written in.
Undeclared packages are one table for the run.

```
byterails: NOT ALLOWED  com.acme.domain uses org.springframework.web.client, which no rule allows
  package  pkg("com.acme.domain") at byterails.kts:12
  may use  java.lang, java.time, java.util, kotlin, org.jetbrains.annotations
  fix      move the code, or add allow("org.springframework.web.client") to pkg("com.acme.domain")
  OrderService.kt:42  OrderService uses RestTemplate in place
  Shipping.kt         Shipping uses RestClient in client

byterails: DENIED       com.acme.domain uses jakarta.persistence, which deny("jakarta.persistence") forbids
  rule     deny("jakarta.persistence") in pkg("com.acme.domain") at byterails.kts:14
  fix      move the code, or lift the deny
  Order.kt  Order uses EntityManager in entityManager

byterails: 3 violations in 2 groups, 1,204 classes, 17 packages
```

The kinds: `UNDECLARED` for a class in a package no declaration covers, `WRONG MODULE` for a class
compiled in the wrong [module](#modules), `NOT ALLOWED` when no rule allows the referenced package,
`DENIED` when a deny forbids it, `EXCLUSIVE` when another package owns it, and `NAMING` for a class
name that matches no pattern. `may use` lists the allows in effect for the package, its own and the
inherited ones; the package's own subtree is always allowed and is not listed. A `hint` row follows the
fix when the reference is one every compiler emits, `kotlin`, `java.lang` or
`org.jetbrains.annotations`, so a first run on a project without those root allows explains itself.

The undeclared packages of a run come first, as one table with the number of classes and the source
files of each package, and a note when a flat declaration sits just above it:

```
byterails: UNDECLARED   2 packages are not declared
  fix      declare each with pkg("...") in byterails.kts, or move its classes into a declared package; only declared packages may exist
  com.acme.billing        3 classes  Invoice.kt, InvoiceLine.kt, Tax.kt
  com.acme.infra.web.dto  1 class    OrderDto.kt   under flat pkg("com.acme.infra.web") at byterails.kts:31, which covers no sub-packages
```

The summary counts one violation per class and referenced type, and per class for the kinds that
report a class as a whole. A block shows at most ten lines, a line names at most three members and
six line numbers, and the undeclared table shows at most twenty packages; what was left out is
announced with the switch that prints it. The JSON report under
`build/reports/byterails/violations.json` holds every reference, one entry per class and referenced
type with every site under `sites` (schema 2), next to the counts and the warnings, and so does the
verbose output. `-Pbyterails.verbose=true`, or `byterails { verbose.set(true) }`, prints the settings
of the run, every reference on a line of its own with the fully qualified names and the JVM descriptor
as they stand in the class file, and the rules in effect for every package that has a violation. The
same lines are logged at Gradle's info level, so `--info` shows them too. A failing task ends with the
count and both places: `byterails: 3 violations, listed above; every reference is in
build/reports/byterails/violations.json and in the output of -Pbyterails.verbose=true`.

A project whose packages all live under one root can set `byterails { basePackage.set("com.acme") }`
and write the rules file relative to it: `pkg("domain")` then means `com.acme.domain`. A rule prefix
is prefixed too when it points into the declared package tree, so `allow("domain")` becomes
`allow("com.acme.domain")` while `allow("kotlin")` stays as written.

While adopting byterails on an existing code base, `-Pbyterails.reportOnly=true` prints every
violation and keeps the build green. The same switch is available as `byterails { reportOnly.set(true) }`.
The tool runs in a class loader of its own, so neither Gradle's nor the build's Kotlin version
matters; `byterails { toolClasspath.setFrom(...) }` overrides where the core comes from. A
configuration error fails the task with the message alone; anything else is reported as an internal
error, with the stack trace under `--stacktrace` or the verbose switch.

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

The `check` goal runs in the `verify` phase and reads the module's compiled classes, the build output
directory, against `byterails.kts` in the multi-module root directory. Each module checks its own
classes, violations fail the build, and the JSON report lands in `target/byterails/violations.json`.
A module with `pom` packaging or without compiled classes is passed over.

| Parameter | Property | Default |
| --- | --- | --- |
| `<rulesFile>` | `byterails.rulesFile` | `byterails.kts` in the multi-module root directory |
| `<basePackage>` | `byterails.basePackage` | unset: every name is absolute |
| `<slices>` | `byterails.slices`, comma-separated | none |
| `<defaultRules>` | `byterails.defaultRules`, comma-separated | none |
| `<module>` | `byterails.module` | unset |
| `<moduleRulesFile>` | `byterails.moduleRulesFile` | `byterails.kts` in the module's directory |
| `<reportOnly>` | `byterails.reportOnly` | `false` |
| `<verbose>` | `byterails.verbose` | `false` |
| `<skip>` | `byterails.skip` | `false` |
| `<reportFile>` | | `target/byterails/violations.json` |
| `<scriptCacheDir>` | | `target/byterails/script-cache` |

`<verbose>true</verbose>` or `-Dbyterails.verbose=true` prints every reference and the rules in effect
as the Gradle plugin's verbose does; the same lines are logged at the debug level, so `-X` shows them
too. A Maven module that configures
`<module>` is a byterails [module](#modules); the goal finds the other modules' rules files through
the reactor. The same rules file gives the same result from Gradle and Maven, because both call the
same core.

## Command line and library

The core ships a small CLI. Its exit status is 0 for a clean run, 1 on violations and 2 when the check
could not run, for a usage error, a configuration error or an internal error:

```
java -cp <byterails-core, byterails-rules and their dependencies> community.flock.byterails.cli.Main \
    --rules byterails.kts --classes build/classes/kotlin/main:build/classes/java/main \
    --report build/byterails.json --report-only --verbose
```

| Option | Meaning |
| --- | --- |
| `--rules <file>` | The rules file. Required unless `--default-rules` is given. |
| `--classes <dir>[:<dir>...]` | The class directories, separated by the platform's path separator; the option may be repeated. Required. |
| `--base-package <package>` | The package the rules file is relative to. |
| `--slices <a,b>` | The slices the `slice { }` block applies to. |
| `--default-rules <a,b>` | Ids of the default rule sets to apply. |
| `--report <file>` | Where to write the JSON report; without it none is written. |
| `--cache <dir>` | Where compiled rules files are kept, by content hash, so an unchanged file is not compiled again. |
| `--report-only` | Print the violations and exit with 0. |
| `--verbose`, `-v` | Print every reference and the rules in effect, and the stack trace behind an error. |
| `--help`, `-h` | Print the usage. |

The same rules can be built in code with the DSL and checked from a test:

```kotlin
val rules = byterails { allow("kotlin"); allow("java.lang"); pkg("com.acme") }
val result = Byterails.check(rules, listOf(File("build/classes/kotlin/main")))
assertTrue(result.isClean, result.violations.joinToString("\n") { it.message })
```

`byterails { }` throws a `ConfigException` for what is wrong in the rules as written, and
`Byterails.check` for what the validator finds. A rule set written relative to a base package or with
a `slice { }` block is resolved first, in this order, with the extension functions of
`community.flock.byterails.model`: `rules.withSlices(listOf("orders")).withBasePackage("com.acme")`.
`Byterails.load(File("byterails.kts"), basePackage = "com.acme")` reads a rules file the way the
plugins do and returns the rule set with its warnings.

## What byterails reads

A reference is any class name the compiler wrote into the class file: superclasses and interfaces,
generic signatures, annotations and their arguments, field and method descriptors, every instruction
in a method body, class literals, invokedynamic call sites with their bootstrap arguments, local
variable tables, exception handlers, record components and permitted subclasses. Nested classes are
checked as classes of their own, so lambda and coroutine bodies are covered. Every `.class` file under
the class directories is read except `module-info.class`. A class file written for a JDK newer than
the release of byterails knows stops the check with a message that names the JDK.

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
`byterails-dsl`, `byterails-core` and `byterails-rules` against this repository's own [`byterails.kts`](byterails.kts)
with the task `byterailsSelfCheck`. The Maven plugin is
built by Maven, because its descriptor comes from Maven's plugin tooling, and runs its integration
tests against sample projects with the invoker plugin.

## Releasing

Publishing a GitHub release runs the deploy workflow, which builds, tests and publishes the DSL, the
core, the rules, the Gradle plugin and the Maven plugin to Maven Central under the release tag, with a leading `v`
dropped. Every push to `main` publishes the `-SNAPSHOT` version from `gradle.properties` to the
Central snapshot repository, and the workflow can be dispatched by hand with a version. It needs four
repository secrets: `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`, a Central Portal user token, and
`GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` for signing. The Gradle side uses the Nexus publish plugin
against the Central Portal's staging API; the Maven side uses the Central publishing plugin with the
`release` profile. Both sign with the same key.

## Status

0.1: core library, `byterails.kts` loader, Gradle plugin, Maven plugin, CLI, slices, default rules,
modules, verbose output. Planned next:
kind-aware and annotation-conditioned naming, expiring allows, SARIF and JUnit XML reports.
