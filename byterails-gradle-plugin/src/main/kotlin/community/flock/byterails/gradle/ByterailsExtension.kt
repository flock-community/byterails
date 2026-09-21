package community.flock.byterails.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** `byterails { }` in a build script. */
abstract class ByterailsExtension {

    /**
     * The rules file. Defaults to `byterails.kts` in the root project directory. It may be absent
     * when [defaultRules] is set; the check then runs on the default rules alone.
     */
    abstract val rulesFile: RegularFileProperty

    /**
     * A package every declaration in the rules file is relative to, for example `com.acme`, so the
     * file can say `pkg("domain")` for `com.acme.domain`. A rule prefix is prefixed too when it points
     * into the declared package tree, so `allow("domain")` and `allow("kotlin")` both mean what they
     * say. Unset by default: every name in the file is then absolute.
     */
    abstract val basePackage: Property<String>

    /**
     * The slices of the application: package names relative to the base package, for example
     * `orders` and `customers`. The rules file's `slice { }` block is applied to each of them.
     * Empty by default; a rules file with a slice block then fails to load.
     */
    abstract val slices: ListProperty<String>

    /**
     * Rule sets byterails ships, by id, applied on top of the rules file or instead of it.
     * `hexagonal`: a `domain` package, in every slice or under the base package, that cannot have any
     * external dependency.
     */
    abstract val defaultRules: ListProperty<String>

    /**
     * When true, violations are printed and the build stays green. Defaults to the Gradle
     * property `byterails.reportOnly`, so `-Pbyterails.reportOnly=true` works without editing the build.
     */
    abstract val reportOnly: Property<Boolean>

    /**
     * The byterails core and its dependencies, loaded in a class loader of their own so that neither
     * Gradle's nor the build's Kotlin version interferes. Defaults to the `byterails` configuration,
     * which resolves the core matching this plugin's version.
     */
    abstract val toolClasspath: ConfigurableFileCollection
}
