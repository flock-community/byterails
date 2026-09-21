package fixtures.slices.customers.application

import fixtures.slices.customers.domain.Customer
import fixtures.slices.orders.api.OrderApi
import fixtures.slices.orders.domain.Order

/** Reaches into the other slice: its api is exported, its domain is not. */
class RegisterCustomer(private val orders: OrderApi) {
    fun register(customer: Customer): Int = orders.orderIds().size

    fun peek(): Order? = null
}
