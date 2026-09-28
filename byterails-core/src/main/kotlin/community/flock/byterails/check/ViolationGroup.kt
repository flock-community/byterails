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
    val declaration: DeclarationRef?,
    /** The sentence shared by the members, with the package as its subject. */
    val headline: String,
    val fix: String,
    val members: List<Violation>,
) {
    /** True for the kinds that report a class as a whole rather than a reference in it. */
    val perClass: Boolean get() = targetPackage == null

    /** The default rule sets among the allows: id to what the id stands for. */
    val ruleSets: Map<String, String> get() = members.first().ruleSets

    companion object {
        fun of(violations: List<Violation>): List<ViolationGroup> =
            violations.groupBy { key(it) }.values
                .map { members ->
                    val first = members[0]
                    ViolationGroup(
                        first.kind, first.className.packageName, targetPackage(first), first.rule,
                        first.allows, first.hint, first.declaration, first.headline, first.fix, members,
                    )
                }
                .sortedWith(compareBy<ViolationGroup> { it.packageName }.thenBy { it.kind.ordinal }.thenBy { it.targetPackage ?: "" }.thenBy { it.rule?.text ?: "" })

        private fun key(v: Violation) = listOf(v.className.packageName, v.kind, targetPackage(v), v.rule, v.allows, v.hint, v.declaration, v.headline, v.fix)

        private fun targetPackage(v: Violation): String? = when (v.kind) {
            ViolationKind.UNDECLARED_PACKAGE, ViolationKind.WRONG_MODULE, ViolationKind.NAMING -> null
            else -> v.target?.let { Messages.targetPackage(it) } ?: ""
        }
    }
}
