package community.flock.byterails.check

/**
 * Violations that share a root cause: the same source package, kind and, for references, the same
 * target package and deciding rule. One group is one line to change in the rules file, or one move.
 */
data class ViolationGroup(
    val kind: ViolationKind,
    val packageName: String,
    /** The package every target of the group lives in; null for the per-class kinds. */
    val targetPackage: String?,
    val rule: RuleRef?,
    val allows: List<String>,
    val hint: String?,
    /** What the per-class kinds report about the package: the undeclared package, or the module mismatch. */
    val detail: String?,
    val members: List<Violation>,
) {
    /** True for the kinds that report a class as a whole rather than a reference in it. */
    val perClass: Boolean get() = targetPackage == null

    companion object {
        fun of(violations: List<Violation>): List<ViolationGroup> =
            violations.groupBy { key(it) }.values
                .map { members ->
                    val first = members[0]
                    ViolationGroup(
                        first.kind, first.className.packageName, targetPackage(first), first.rule,
                        first.allows, first.hint, detail(first), members,
                    )
                }
                .sortedWith(compareBy<ViolationGroup> { it.packageName }.thenBy { it.kind.ordinal }.thenBy { it.targetPackage ?: "" }.thenBy { it.rule?.text ?: "" })

        private fun key(v: Violation) = listOf(v.className.packageName, v.kind, targetPackage(v), v.rule, v.allows, v.hint, detail(v))

        private fun targetPackage(v: Violation): String? = when (v.kind) {
            ViolationKind.UNDECLARED_PACKAGE, ViolationKind.WRONG_MODULE, ViolationKind.NAMING -> null
            else -> v.target?.packageName ?: ""
        }

        private fun detail(v: Violation): String? = when (v.kind) {
            ViolationKind.UNDECLARED_PACKAGE -> v.message.substringAfter("package ")
            ViolationKind.WRONG_MODULE -> v.message.substringAfter("${v.className} ")
            else -> null
        }
    }
}
