package community.flock.byterails.check

import community.flock.byterails.model.ClassName
import community.flock.byterails.model.NamePattern

/**
 * The sentences of a violation, one function per kind: the headline every violation with the same
 * root cause shares, the message for one class, and the fix. The console, the JSON report and the
 * build tools all print these, so the wording lives in one place.
 */
internal object Messages {

    class Texts(val headline: String, val message: String, val fix: String)

    /** The package a target lives in, or the class itself for a class in the default package. */
    fun targetPackage(target: ClassName): String = target.packageName.ifEmpty { target.name }

    fun notAllowed(cls: ClassName, target: ClassName, declaration: DeclarationRef): Texts {
        val used = targetPackage(target)
        val fix = when {
            declaration.ruleSet != null && declaration.isolated ->
                "move the code; ${declaration.text} comes from the ${declaration.ruleSet} rule set and is isolated, so the rules file cannot widen it"
            declaration.ruleSet != null ->
                "move the code, or add allow(\"$used\") where ${declaration.text} inherits from: the root block or slice { }"
            else -> "move the code, or add allow(\"$used\") to ${declaration.text}"
        }
        return Texts(
            "${packageOf(cls)} uses $used, which no rule allows",
            "$cls uses $target, which no rule allows",
            fix,
        )
    }

    fun denied(cls: ClassName, target: ClassName, rule: RuleRef): Texts {
        val used = targetPackage(target)
        return Texts(
            "${packageOf(cls)} uses $used, which ${rule.text} forbids",
            "$cls uses $target, which ${rule.text} forbids",
            "move the code, or lift the deny",
        )
    }

    fun exclusive(cls: ClassName, target: ClassName, owners: String, firstOwner: String, declaration: DeclarationRef?): Texts {
        val used = targetPackage(target)
        val here = declaration?.text ?: "pkg(\"${packageOf(cls)}\")"
        return Texts(
            "${packageOf(cls)} uses $used, which only $owners may use",
            "$cls uses $target, which only $owners may use",
            "move the code to $firstOwner, or turn the exclusive into a plain allow and add allow(\"$used\") to $here",
        )
    }

    fun naming(cls: ClassName, patterns: List<NamePattern>): Texts {
        val phrase = must(patterns)
        return Texts(
            "classes in ${packageOf(cls)} must $phrase",
            "$cls must $phrase",
            "rename the classes, or move them out of ${packageOf(cls)}",
        )
    }

    /** `end with "UseCase" or "Query"`, `start with "Abstract"`, `match /.*Port/`, the kinds joined with `or`. */
    fun must(patterns: List<NamePattern>): String {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (pattern in patterns) {
            when (pattern) {
                is NamePattern.EndsWith -> groups.getOrPut("end with") { mutableListOf() } += "\"${pattern.suffix}\""
                is NamePattern.StartsWith -> groups.getOrPut("start with") { mutableListOf() } += "\"${pattern.prefix}\""
                is NamePattern.Matches -> groups.getOrPut("match") { mutableListOf() } += "/${pattern.regex.pattern}/"
            }
        }
        return groups.entries.joinToString(" or ") { (verb, values) -> "$verb ${values.joinToString(" or ")}" }
    }

    fun undeclared(cls: ClassName, nearest: DeclarationRef?): Texts {
        val pkg = packageOf(cls)
        val note = nearest?.let { near ->
            val at = near.location?.let { " at $it" } ?: ""
            if (near.flat) "; ${near.text}$at is flat and covers no sub-packages" else "; the nearest declared package is ${near.text}$at"
        } ?: ""
        val fix = if (cls.packageName.isEmpty()) {
            "move the classes into a declared package; the default package cannot be declared"
        } else {
            "declare it with pkg(\"$pkg\") in byterails.kts, or move its classes into a declared package"
        }
        return Texts(
            "package $pkg is not declared$note",
            "$cls lies in package $pkg, which is not declared$note",
            fix,
        )
    }

    fun wrongModule(cls: ClassName, mismatch: ModuleMismatch): Texts {
        val current = mismatch.compiledIn
        val owner = mismatch.owner
        fun sentence(subject: String) = when {
            owner == null -> "$subject is compiled in module \"${current!!.name}\" but lies outside its package ${current.prefix}"
            current == null -> "$subject belongs to module \"${owner.name}\", which owns ${owner.prefix}, but is compiled outside the modules"
            else -> "$subject belongs to module \"${owner.name}\", which owns ${owner.prefix}, but is compiled in module \"${current.name}\""
        }
        val fix = when {
            owner == null -> "move the classes under ${current!!.prefix}, or into the project of the module that owns them"
            current == null -> "move the classes into the project of module \"${owner.name}\", or out of ${owner.prefix}"
            else -> "move the classes into the project of module \"${owner.name}\", or under ${current.prefix}"
        }
        return Texts(sentence(packageOf(cls)), sentence(cls.name), fix)
    }

    fun packageOf(cls: ClassName): String = cls.packageName.ifEmpty { "(default)" }
}
