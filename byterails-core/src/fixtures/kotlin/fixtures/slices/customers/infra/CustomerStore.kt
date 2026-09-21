package fixtures.slices.customers.infra

import fixtures.lib.messaging.EventBus
import fixtures.slices.customers.domain.Customer

class CustomerStore(private val bus: EventBus) {
    fun save(customer: Customer): String = bus.publish(customer.name)
}
