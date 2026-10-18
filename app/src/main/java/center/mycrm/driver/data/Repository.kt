package center.mycrm.driver.data

import android.os.Build
import center.mycrm.driver.BuildConfig
import center.mycrm.driver.core.Rules
import org.json.JSONObject

/** The driver's working set: En Route orders with their items, and today's delivered orders. */
data class Snapshot(
    val orders: List<Order>,
    val items: Map<String, List<OrderItem>>,
    val doneToday: List<Order>
)

/** Thrown when the account exists but is not a usable driver account. */
class NotADriverException(message: String) : Exception(message)

class Repository(private val sb: SupabaseClient) {

    val appVersion: String = "driver v" + BuildConfig.VERSION_NAME

    private val orderColumns = listOf(
        "id", "order_num", "client_name", "client_number", "seller_name",
        "emirate", "city", "villa", "apt", "location_url",
        "sched_date", "sched_from", "sched_to", "driver_note", "priority",
        "payment_status", "delivery_status", "subtotal", "delivery_charge", "discount", "total",
        "delivered_at", "updated_by", "driver", "content_type"
    ).joinToString(",")

    /**
     * Resolves who is signed in: profile must have role 'driver' and be active, and a drivers row
     * must point at this user (drivers.profile_id) and be active.
     */
    suspend fun loadIdentity(): DriverIdentity {
        val uid = sb.userId ?: throw AuthRequiredException()
        val profiles = sb.select(
            "profiles",
            mapOf("id" to "eq.$uid", "select" to "id,email,name,role,active,deleted_at", "limit" to "1")
        )
        if (profiles.length() == 0) throw NotADriverException("No profile found for this account. Ask the admin.")
        val p = profiles.getJSONObject(0)
        val role = p.str("role")?.lowercase() ?: ""
        val active = !p.has("active") || p.isNull("active") || p.optBoolean("active", true)
        val deleted = p.str("deleted_at") != null
        if (role != "driver") throw NotADriverException("This account is not a driver account (role: ${role.ifEmpty { "none" }}). Ask the admin.")
        if (!active || deleted) throw NotADriverException("This account is deactivated. Ask the admin.")

        val drivers = sb.select(
            "drivers",
            mapOf("profile_id" to "eq.$uid", "select" to "*", "limit" to "1")
        )
        if (drivers.length() == 0) throw NotADriverException("Your account is not linked to a driver yet. Ask the admin (Directory → Drivers).")
        val d = drivers.getJSONObject(0)
        val dActive = !d.has("active") || d.isNull("active") || d.optBoolean("active", true)
        if (!dActive) throw NotADriverException("Driver record is inactive. Ask the admin.")
        val name = d.str("name") ?: throw NotADriverException("Driver record has no name.")

        return DriverIdentity(
            userId = uid,
            email = sb.userEmail ?: p.str("email") ?: "",
            profileName = p.str("name"),
            driverName = name,
            driverPhone = d.str("phone")
        )
    }

    suspend fun loadSnapshot(driverName: String): Snapshot {
        val raw = sb.select(
            "orders",
            mapOf(
                "driver" to "eq.$driverName",
                "delivery_status" to "eq.En Route",
                "deleted" to "not.is.true",
                "select" to orderColumns,
                "order" to "created_at.asc",
                "limit" to "200"
            )
        )
        val loaded = Parse.orders(raw)
        val items: Map<String, List<OrderItem>> = if (loaded.isEmpty()) emptyMap() else {
            val ids = loaded.map { it.id }.filter { it.isNotEmpty() }
            val all = ArrayList<OrderItem>()
            // in.(...) lists are kept short to stay well below URL limits
            for (chunk in ids.chunked(40)) {
                val arr = sb.select(
                    "order_items",
                    mapOf(
                        "order_id" to "in.(" + chunk.joinToString(",") + ")",
                        "select" to "order_id,product_name,quantity,unit,price",
                        "order" to "product_name.asc"
                    )
                )
                all.addAll(Parse.items(arr))
            }
            all.groupBy { it.orderId }
        }

        // cash = Σ(price × qty) + delivery − discount, like the core's WhatsApp message to the driver
        val withAmounts = loaded.map { o ->
            val lines = items[o.id]
            if (lines.isNullOrEmpty()) o else o.copy(itemsSubtotal = Rules.itemsSubtotal(lines.map { it.price to it.quantity }))
        }
        val orders = Rules.sortForRoute(
            withAmounts,
            priority = { it.priority },
            schedDate = { it.schedDate },
            schedFrom = { it.schedFrom },
            orderNum = { it.orderNum }
        )

        val doneRaw = sb.select(
            "orders",
            mapOf(
                "driver" to "eq.$driverName",
                "delivery_status" to "eq.Delivered",
                "delivered_at" to "gte." + Rules.todayStartDubaiIso(),
                "deleted" to "not.is.true",
                "select" to orderColumns,
                "order" to "delivered_at.desc",
                "limit" to "200"
            )
        )
        return Snapshot(orders, items, Parse.orders(doneRaw))
    }

    // ---- Writes: only through the DB functions (see migration add_driver_app.sql) ----------------

    /** Uploads the stamped photo into delivery-photos/<order>/<name>.jpg and returns the object name. */
    suspend fun uploadPhoto(orderId: String, file: java.io.File, takenAtMs: Long): String {
        val name = center.mycrm.driver.pod.Pod.objectName(orderId, takenAtMs)
        sb.uploadObject("delivery-photos", name, file)
        return name
    }

    private fun JSONObject.putFix(fix: center.mycrm.driver.pod.Fix?): JSONObject {
        if (fix != null) {
            put("p_lat", fix.lat); put("p_lng", fix.lng)
            fix.accuracyM?.let { put("p_accuracy", it.toDouble()) }
        }
        return this
    }

    suspend fun markDelivered(orderId: String, photoPath: String?, skipReason: String?, fix: center.mycrm.driver.pod.Fix?): JSONObject? {
        val args = JSONObject().put("p_order_id", orderId).put("p_app_version", appVersion)
        if (photoPath != null) args.put("p_photo_path", photoPath)
        if (skipReason != null) args.put("p_skip_reason", skipReason)
        return sb.rpc("driver_mark_delivered", args.putFix(fix)) as? JSONObject
    }

    suspend fun markFailed(orderId: String, reason: String, photoPath: String?, fix: center.mycrm.driver.pod.Fix?): JSONObject? {
        val args = JSONObject().put("p_order_id", orderId).put("p_reason", reason).put("p_app_version", appVersion)
        if (photoPath != null) args.put("p_photo_path", photoPath)
        return sb.rpc("driver_mark_failed", args.putFix(fix)) as? JSONObject
    }

    suspend fun undoDelivered(orderId: String): JSONObject? =
        sb.rpc("driver_undo_delivered", JSONObject().put("p_order_id", orderId).put("p_app_version", appVersion)) as? JSONObject

    /** LOGIN row in logs, like the core does — lets the admin see who runs which app version. Failures are ignored. */
    suspend fun logLogin(identity: DriverIdentity) {
        try {
            val details = JSONObject()
                .put("action", "driver_app_login")
                .put("app_version", appVersion)
                .put("driver", identity.driverName)
                .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                .put("android", Build.VERSION.RELEASE ?: "")
            val row = JSONObject()
                .put("user_id", identity.userId)
                .put("user_email", identity.email)
                .put("role", "driver")
                .put("action", "LOGIN")
                .put("entity_type", "session")
                .put("details", details)
            sb.insert("logs", row)
        } catch (_: Exception) {
            // logging must never break the app
        }
    }
}
