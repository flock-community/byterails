package fixtures.app.infra.web

import fixtures.app.application.PlaceOrderUseCase
import fixtures.lib.web.RestController
import fixtures.lib.web.RestTemplate

@RestController
class OrderController(private val useCase: PlaceOrderUseCase, private val template: RestTemplate) {
    fun place(): String = template.get("orders/" + useCase.place())
}

class Whatever
