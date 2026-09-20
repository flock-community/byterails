package fixtures.app.domain

import fixtures.lib.config.Constants
import fixtures.lib.jooq.DSLContext
import fixtures.lib.persistence.EntityManager
import fixtures.lib.persistence.PersistenceException
import fixtures.lib.util.timed
import fixtures.lib.web.RestTemplate

/** Each member references a type from outside the domain in one specific bytecode location. */
class OrderService {

    /** Only the generic signature names EntityManager; the descriptor says java.util.List. */
    fun managers(): List<EntityManager> = emptyList()

    /** RestTemplate appears only inside the lambda body, which compiles to a synthetic method. */
    fun lambdaOnly(): () -> Any = { RestTemplate() }

    /** DSLContext appears only in the local variable table. */
    fun local() {
        val ctx: DSLContext? = null
        println(ctx)
    }

    /** PersistenceException appears only as a catch type and a local variable. */
    fun caught() {
        try {
            println("work")
        } catch (e: PersistenceException) {
            println(e)
        }
    }

    /** A const val is inlined: nothing in the class file names Constants. */
    fun timeout(): Int = Constants.TIMEOUT

    /** A suspend lambda compiles to a nested class whose body references RestTemplate. */
    suspend fun later(template: RestTemplate): String {
        val fetch: suspend () -> String = { template.get("later") }
        return fetch()
    }

    /** The inlined body of timed() references fixtures.lib.util.Timer from inside this class. */
    fun timedWork(): Int = timed { 42 }
}
