package fixtures.slices.orders.application

import fixtures.slices.orders.api.OrderApi
import fixtures.slices.orders.domain.Order

class PlaceOrder(private val api: OrderApi) {
    fun place(): Order = Order(api.orderIds().first())
}
