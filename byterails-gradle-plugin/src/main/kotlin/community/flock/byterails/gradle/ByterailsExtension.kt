package community.flock.byterails.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** `byterails { }` in a build script. */
abstract class ByterailsExtension {

    /** The rules file. Defaults to `byterails.kts` in the root project directory. */
    abstract val rulesFile: RegularFileProperty

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
