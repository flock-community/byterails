package fixtures.app.domain

@JvmInline
value class OrderId(val value: String)

data class Order(val id: OrderId, val lines: List<String>)
