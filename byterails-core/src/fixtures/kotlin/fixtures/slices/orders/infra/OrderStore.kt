package fixtures.slices.orders.infra

import fixtures.lib.messaging.EventBus
import fixtures.slices.orders.domain.Order

class OrderStore(private val bus: EventBus) {
    fun save(order: Order): String = bus.publish(order.id)
}
