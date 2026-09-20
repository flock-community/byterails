package fixtures.lib.persistence

class EntityManager {
    fun find(id: String): Any? = id
}

annotation class Entity

class PersistenceException(message: String) : RuntimeException(message)
