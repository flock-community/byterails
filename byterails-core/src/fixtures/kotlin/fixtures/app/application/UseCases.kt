package fixtures.app.application

import fixtures.app.domain.OrderService
import fixtures.lib.annotations.Generated

class PlaceOrderUseCase(private val service: OrderService) {
    fun place(): Int = service.timeout()

    companion object {
        fun create(): PlaceOrderUseCase = PlaceOrderUseCase(OrderService())
    }
}

class Wrong

@Generated
class GeneratedThing
