package community.flock.byterails.model

/** A module of the build: its name and the package it owns, relative to the base package until [withBasePackage] runs. */
data class ModuleRoot(val name: String, val prefix: Prefix)

/**
 * One module as the build configures it: its name, a package relative to the base package, the rule
 * set its own `byterails.kts` built, with the module's default rules already included, or an empty
 * rule set when the module has no file, and the slices configured on the module, which its file's
 * `slice { }` block applies to.
 */
data class ModuleRules(
    val name: String,
    val rules: RuleSet,
    val slices: List<String> = emptyList(),
)

/**
 * Adds the modules of the build to this rule set, the one the root rules file built.
 *
 * A module owns the package `<module>` under the base package. Its root is declared implicitly and
 * covers the whole subtree, like a slice root; the top-level allows and denies of the module's file
 * are the rules of that root, inherited by the module's packages only, and the root rules of this
 * set are inherited underneath as always. The file's declarations are relative to the module, and a
 * rule prefix is prefixed with the module when it points into the module's declared packages, just
 * as [withBasePackage] does for the root file. A `slice { }` block in the file is applied to the
 * module's slices, which lie under the module. What a module exports, every other module may use;
 * nothing else may cross a module boundary unless the root file allows it.
 *
 * A rule set applied to the module puts its declarations under the module root; one that declares
 * the base package itself, such as `hexagonalSpring`, describes the module root, and its rules join
 * the module's top-level rules. A hand-written `basePackage { }` is not allowed in a module's file.
 *
 * [current] names the module whose classes are being checked, or is null for a project outside the
 * modules; the checker reports a class compiled in the wrong module from it. Apply this before
 * [withBasePackage].
 *
 * @throws ConfigException for a malformed or duplicate module name, a module inside another module, a
 *   current module that is not configured, a `basePackage { }` in a module file, or a slice block
 *   without slices and the reverse.
 */
fun RuleSet.withModules(modules: List<ModuleRules>, current: String?): RuleSet {
    if (modules.isEmpty()) {
        if (current == null) return this
        throw ConfigException("module \"$current\" is configured, but no modules are known to the build")
    }
    val roots = modules.map { module -> ModuleRoot(module.name, parseModuleName(module.name)) }
    roots.groupBy { it.name }.values.filter { it.size > 1 }.forEach {
        throw ConfigException("module \"${it[0].name}\" is configured more than once")
    }
    roots.groupBy { it.prefix }.values.filter { it.size > 1 }.forEach {
        throw ConfigException("modules \"${it[0].name}\" and \"${it[1].name}\" own the same package \"${it[0].prefix}\"")
    }
    for (outer in roots) for (inner in roots) {
        if (outer !== inner && outer.prefix.covers(inner.prefix)) {
            throw ConfigException("module \"${inner.name}\" lies inside module \"${outer.name}\"; modules are disjoint packages")
        }
    }
    if (current != null && roots.none { it.name == current }) {
        throw ConfigException("module \"$current\" is not among the configured modules: ${roots.joinToString(", ") { it.name }}")
    }
    val declarations = modules.zip(roots).flatMap { (module, root) -> expand(module, root, roots, modules) }
    return copy(packages = packages + declarations, modules = roots, module = current)
}

private fun parseModuleName(name: String): Prefix =
    try {
        Prefix.parse(name)
    } catch (e: IllegalArgumentException) {
        throw ConfigException("module name ${e.message}")
    }

/** The declarations one module stands for: its root, its own packages and the packages of its slices. */
private fun expand(module: ModuleRules, root: ModuleRoot, roots: List<ModuleRoot>, all: List<ModuleRules>): List<PackageDeclaration> {
    val raw = module.rules
    raw.packages.firstOrNull { it.prefix.isRoot && it.group == null }?.let {
        throw ConfigException(
            "basePackage { } is not allowed in the rules file of module \"${module.name}\"; the module root \"${root.prefix}\" is declared implicitly",
            it.location,
        )
    }
    val sliced = try {
        raw.withSlices(module.slices)
    } catch (e: ConfigException) {
        throw ConfigException(e.problems.map { it.copy(message = "module \"${module.name}\": ${it.message}") })
    }
    // Relative to the module: a declaration is prefixed, a rule prefix follows when it points into the
    // module's declared packages. The module root itself would cover any candidate, so it does not decide.
    val prefixed = sliced.packages.map { it.copy(prefix = Prefix.concat(root.prefix, it.prefix)) }
    val tree = prefixed.filter { it.prefix != root.prefix }
    fun resolve(rule: Rule): Rule {
        if (rule.prefix.isRoot) return rule
        val candidate = Prefix.concat(root.prefix, rule.prefix)
        return if (tree.any { it.touches(candidate) }) rule.copy(prefix = candidate) else rule
    }
    val exportedByOthers = all.zip(roots).filter { (other, _) -> other !== module }.flatMap { (other, otherRoot) ->
        other.rules.exported.map { export ->
            Rule(RuleKind.ALLOW, Prefix.concat(otherRoot.prefix, export.prefix), export.location, "${Rule.EXPORTED_GROUP}module:${other.name}:${export.prefix.name}")
        }
    }
    val rootRules = sliced.rootRules.map(::resolve) + exportedByOthers
    val declaredRoot = prefixed.firstOrNull { it.prefix == root.prefix }
    val moduleRoot = declaredRoot?.copy(rules = rootRules + declaredRoot.rules.map(::resolve))
        ?: PackageDeclaration(root.prefix, rootRules, null, null)
    val packages = prefixed.filter { it !== declaredRoot }.map { declaration -> declaration.copy(rules = declaration.rules.map(::resolve)) }
    return listOf(moduleRoot) + packages
}
