package fixtures.app.infra.persistence

import fixtures.app.domain.Order
import fixtures.lib.jooq.DSLContext
import fixtures.lib.persistence.EntityManager

class OrderRepository(private val ctx: DSLContext, private val entityManager: EntityManager) {
    fun save(order: Order): String = ctx.query(order.lines.joinToString())
}
