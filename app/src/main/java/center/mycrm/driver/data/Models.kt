package center.mycrm.driver.data

import center.mycrm.driver.core.Rules
import org.json.JSONArray
import org.json.JSONObject

/** Who is logged in: the auth user, the profile and the driver record it is linked to. */
data class DriverIdentity(
    val userId: String,
    val email: String,
    val profileName: String?,
    val driverName: String,
    val driverPhone: String?
)

data class OrderItem(
    val orderId: String,
    val productName: String,
    val quantity: Double,
    val unit: String?,
    val price: Double?
)

data class Order(
    val id: String,
    val orderNum: String,
    val clientName: String?,
    val clientNumber: String?,
    val sellerName: String?,
    val emirate: String?,
    val city: String?,
    val villa: String?,
    val apt: String?,
    val locationUrl: String?,
    val schedDate: String?,
    val schedFrom: String?,
    val schedTo: String?,
    val driverNote: String?,
    val priority: Int,
    val paymentStatus: String?,
    val deliveryStatus: String?,
    val subtotal: Double?,
    val deliveryCharge: Double?,
    val discount: Double?,
    val total: Double?,
    val deliveredAt: String?,
    val updatedBy: String?,
    val driver: String?,
    val contentType: String?,
    /** Σ(price × qty) of the loaded order lines; null when the lines were not loaded (Done today). */
    val itemsSubtotal: Double? = null,
    /** v1.3: due frozen when the order was closed / cash actually taken (Cash on delivery, add_driver_cash.sql). */
    val cashDue: Double? = null,
    val cashReceived: Double? = null,
    /** v1.4: position in the driver's own delivery sequence (1 = next); null = not placed (add_driver_route.sql). */
    val routeSeq: Int? = null,
    /** v1.6: the driver scanned the bag label (took the package from the warehouse); null = not yet. */
    val pickedUpAt: String? = null,
    val pickedUpBy: String? = null
) {
    val cashToCollect: Double
        get() = Rules.cashToCollect(total, subtotal, deliveryCharge, discount, paymentStatus, itemsSubtotal)

    val amountDue: Double get() = Rules.amountDue(total, subtotal, deliveryCharge, discount, itemsSubtotal)

    /** Delivered orders: the cash the driver entered, else the estimate (orders closed by the dispatcher without an amount). */
    val cashTaken: Double get() = if (paymentMode == Rules.PaymentMode.CASH) Rules.cashTaken(cashReceived, cashDue ?: cashToCollect) else 0.0

    val paymentMode: Rules.PaymentMode get() = Rules.paymentMode(paymentStatus)

    val isPaid: Boolean get() = Rules.isPaid(paymentStatus)

    val scheduleText: String get() = Rules.fmtSchedule(schedDate, schedFrom, schedTo)

    val addressText: String get() = Rules.addressText(emirate, city, villa, apt)

    val navigateLink: String? get() = Rules.navigateLink(locationUrl, emirate, city, villa, apt)

    val wazeLink: String? get() = Rules.coordsFrom(locationUrl)?.let { Rules.wazeLink(it.first, it.second) }

    val phoneText: String get() = Rules.formatPhone(clientNumber)
}

/** v1.5: a stop of a trip — the place (snapshot of the dispatcher's library), its map link, the people there. */
data class TripStop(val place: String, val mapUrl: String?, val people: String)

/** v1.5: staff pick-up / drop-off or a task (add_trips.sql). Not an order: one Done for the whole trip. */
data class Trip(
    val id: Long,
    val kind: String,
    val date: String?,
    val time: String?,
    val note: String,
    val status: String,
    val doneAt: String?,
    val doneSource: String?,
    val stops: List<TripStop>
) {
    val kindInfo: Rules.TripKind get() = Rules.tripKind(kind)
    val isDone: Boolean get() = status == "done"
}

/** Lenient JSON helpers: PostgREST returns numerics as numbers, but we accept strings too. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key) ?: return null
    val s = v.toString()
    return if (s.isEmpty()) null else s
}

internal fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(",", "").toDoubleOrNull()
        else -> null
    }
}

object Parse {

    fun order(o: JSONObject): Order = Order(
        id = o.str("id") ?: "",
        orderNum = o.str("order_num") ?: "",
        clientName = o.str("client_name"),
        clientNumber = o.str("client_number"),
        sellerName = o.str("seller_name"),
        emirate = o.str("emirate"),
        city = o.str("city"),
        villa = o.str("villa"),
        apt = o.str("apt"),
        locationUrl = o.str("location_url"),
        schedDate = o.str("sched_date"),
        schedFrom = o.str("sched_from"),
        schedTo = o.str("sched_to"),
        driverNote = o.str("driver_note"),
        priority = Rules.priorityOf(if (o.has("priority") && !o.isNull("priority")) o.opt("priority") else null),
        paymentStatus = o.str("payment_status"),
        deliveryStatus = o.str("delivery_status"),
        subtotal = o.num("subtotal"),
        deliveryCharge = o.num("delivery_charge"),
        discount = o.num("discount"),
        total = o.num("total"),
        deliveredAt = o.str("delivered_at"),
        updatedBy = o.str("updated_by"),
        driver = o.str("driver"),
        contentType = o.str("content_type"),
        cashDue = o.num("cash_due"),
        cashReceived = o.num("cash_received"),
        routeSeq = o.num("route_seq")?.toInt(),
        pickedUpAt = o.str("picked_up_at"),
        pickedUpBy = o.str("picked_up_by")
    )

    fun orders(arr: JSONArray): List<Order> {
        val out = ArrayList<Order>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(order(o))
        }
        return out
    }

    fun trip(o: JSONObject): Trip {
        val arr = o.optJSONArray("stops") ?: JSONArray()
        val stops = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            TripStop(place = s.str("place") ?: "—", mapUrl = s.str("map_url"), people = s.str("people") ?: "")
        }
        return Trip(
            id = o.optLong("id", 0L),
            kind = o.str("kind") ?: "",
            date = o.str("date"),
            time = o.str("time"),
            note = o.str("note") ?: "",
            status = o.str("status") ?: "planned",
            doneAt = o.str("done_at"),
            doneSource = o.str("done_source"),
            stops = stops
        )
    }

    fun trips(arr: JSONArray): List<Trip> {
        val out = ArrayList<Trip>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(trip(o))
        }
        return out
    }

    fun item(o: JSONObject): OrderItem = OrderItem(
        orderId = o.str("order_id") ?: "",
        productName = o.str("product_name") ?: "—",
        quantity = o.num("quantity") ?: 1.0,
        unit = o.str("unit"),
        price = o.num("price")
    )

    fun items(arr: JSONArray): List<OrderItem> {
        val out = ArrayList<OrderItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(item(o))
        }
        return out
    }
}
