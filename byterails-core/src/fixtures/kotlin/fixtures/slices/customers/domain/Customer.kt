package fixtures.slices.customers.domain

import fixtures.lib.messaging.EventBus

/** Uses the message bus that only the infra package of each slice owns. */
class Customer(val name: String, private val bus: EventBus) {
    fun rename(newName: String): String = bus.publish(newName)
}
