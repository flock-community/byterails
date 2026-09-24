package community.flock.byterails.gradle

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.io.Serializable

/**
 * One module of the build as the check task sees it: the module's name, its rules file when it has
 * one, and the slices and default rules configured on its project. The rules file is a task input,
 * so a change in any module's file re-runs every module's check.
 */
class ModuleSpec(
    @get:Input val name: String,
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) val rulesFile: File?,
    @get:Input val slices: List<String>,
    @get:Input val defaultRules: List<String>,
) : Serializable {

    /** The module as JDK types, for the runner behind the class loader boundary. */
    fun toMap(): Map<String, Any?> = java.util.LinkedHashMap<String, Any?>().apply {
        put("name", name)
        put("rulesFile", rulesFile)
        put("slices", java.util.ArrayList(slices))
        put("defaultRules", java.util.ArrayList(defaultRules))
    }

    override fun toString(): String = "module $name"
}
