package center.mycrm.driver.core

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure business rules shared by the UI and the unit tests. No Android imports here.
 */
object Rules {

    val DUBAI: ZoneId = ZoneId.of("Asia/Dubai")

    /** Undo window after "Delivered" (mirrors the DB function driver_undo_delivered). */
    const val UNDO_WINDOW_MS: Long = 5 * 60 * 1000L

    /** Statuses the dispatcher offers in "↩ Failed"; the driver app uses the same list. */
    val FAIL_REASONS: List<String> = listOf(
        "Customer not answering",
        "Customer postponed",
        "Customer refused",
        "Address problem",
        "Driver could not finish route",
        "Other…"
    )

    /**
     * What the driver has to do about money at the door. Derived from orders.payment_status
     * (refs.paymentStatus on 16.10.2026: Cash on delivery · Link on delivery · Crypto · Accounts ·
     * Paid via Tabby/Tamara · Paid cash · Paid via transfer · Paid via link · Gift · Replacement).
     */
    enum class PaymentMode {
        /** Collect cash = order total. */
        CASH,
        /** Customer pays by payment link at the door; no cash, but the amount must be settled. */
        LINK,
        /** Nothing to collect: prepaid, on account, gift, replacement, crypto. */
        NONE,
        /** payment_status is empty — show the amount and warn. */
        UNKNOWN
    }

    /**
     * Only statuses known to be settled before the door mean "nothing to collect". Anything containing
     * "on delivery" is collected at the door (the core's rule: payment_status includes 'on delivery').
     * Any other / new / misspelt status → UNKNOWN: the amount is shown in red with "ask the dispatcher",
     * never silently "nothing to collect" (v1.0.1, ORD-123330).
     */
    fun paymentMode(paymentStatus: String?): PaymentMode {
        val s = paymentStatus?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ") ?: ""
        if (s.isEmpty()) return PaymentMode.UNKNOWN
        if (s.contains("link on delivery")) return PaymentMode.LINK
        if (s.contains("on delivery") || s == "cod" || s == "cash") return PaymentMode.CASH
        if (s.startsWith("paid") || s in SETTLED_STATUSES) return PaymentMode.NONE
        return PaymentMode.UNKNOWN
    }

    /** Settled before delivery (refs.paymentStatus on 16.10.2026), besides every "Paid …" value. */
    val SETTLED_STATUSES: Set<String> = setOf("accounts", "crypto", "gift", "replacement")

    fun isPaid(paymentStatus: String?): Boolean = paymentMode(paymentStatus) == PaymentMode.NONE

    /**
     * Amount due at the door, the way the core's driver WhatsApp message and ✓ Delivered dialog compute it:
     * Σ(item price × qty) + delivery − discount. Without loaded items: orders.total, else subtotal + delivery − discount.
     */
    fun amountDue(
        total: Double?,
        subtotal: Double?,
        deliveryCharge: Double?,
        discount: Double?,
        itemsSubtotal: Double? = null
    ): Double {
        val t = when {
            itemsSubtotal != null -> itemsSubtotal + (deliveryCharge ?: 0.0) - (discount ?: 0.0)
            total != null -> total
            else -> (subtotal ?: 0.0) + (deliveryCharge ?: 0.0) - (discount ?: 0.0)
        }
        return if (t < 0) 0.0 else Math.round(t * 100.0) / 100.0
    }

    /** Cash the driver takes: only for "Cash on delivery". Link / paid / unknown → 0 (the UI shows the amount separately). */
    fun cashToCollect(
        total: Double?,
        subtotal: Double?,
        deliveryCharge: Double?,
        discount: Double?,
        paymentStatus: String?,
        itemsSubtotal: Double? = null
    ): Double = when (paymentMode(paymentStatus)) {
        PaymentMode.CASH -> amountDue(total, subtotal, deliveryCharge, discount, itemsSubtotal)
        else -> 0.0
    }

    /** Σ(price × qty) of the order lines (qty defaults to 1, price to 0 — same as the core). */
    fun itemsSubtotal(lines: List<Pair<Double?, Double?>>): Double =
        lines.sumOf { (price, qty) -> (price ?: 0.0) * (qty ?: 1.0) }

    /** Priority 1 (urgent) … 3 (normal). Accepts numbers, "1", "P1", "Urgent"; anything else → 3. */
    fun priorityOf(raw: Any?): Int {
        when (raw) {
            null -> return 3
            is Number -> return raw.toInt().coerceIn(1, 3)
        }
        val s = raw.toString().trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return 3
        if (s.startsWith("urgent") || s == "high") return 1
        val digit = s.firstOrNull { it.isDigit() } ?: return 3
        return (digit - '0').coerceIn(1, 3)
    }

    /** Sort key: priority → scheduled date → scheduled start time → order number. */
    fun <T> sortForRoute(
        list: List<T>,
        priority: (T) -> Int,
        schedDate: (T) -> String?,
        schedFrom: (T) -> String?,
        orderNum: (T) -> String
    ): List<T> = list.sortedWith(
        compareBy<T> { priority(it) }
            .thenBy(nullsLast<String>()) { schedDate(it)?.takeIf { d -> d.isNotBlank() } }
            .thenBy(nullsLast<String>()) { schedFrom(it)?.takeIf { t -> t.isNotBlank() } }
            .thenBy { orderNum(it) }
    )

    // ---- Phone numbers -------------------------------------------------------------------------

    fun digitsOnly(raw: String?): String = raw?.filter { it.isDigit() } ?: ""

    /** "971501234567" → "+971 50 123 4567"; local "0501234567" → "+971 50 123 4567"; otherwise "+digits". */
    fun formatPhone(raw: String?): String {
        var d = digitsOnly(raw)
        if (d.isEmpty()) return ""
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0") && d.length == 10) d = "971" + d.substring(1)
        if (d.startsWith("971") && d.length == 12) {
            return "+971 " + d.substring(3, 5) + " " + d.substring(5, 8) + " " + d.substring(8)
        }
        return "+$d"
    }

    /** International digits for tel:/wa.me links (no plus). */
    fun phoneForLinks(raw: String?): String {
        var d = digitsOnly(raw)
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0") && d.length == 10) d = "971" + d.substring(1)
        return d
    }

    fun whatsappLink(phoneRaw: String?, text: String?): String {
        val base = "https://wa.me/" + phoneForLinks(phoneRaw)
        return if (text.isNullOrBlank()) base else base + "?text=" + urlEncode(text)
    }

    // ---- Navigation ----------------------------------------------------------------------------

    /** Latitude/longitude from a Google Maps / Waze / geo link, when the link carries them. */
    fun coordsFrom(url: String?): Pair<Double, Double>? {
        if (url.isNullOrBlank()) return null
        val patterns = listOf(
            Regex("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
            Regex("[?&](?:q|query|ll|destination|daddr)=(-?\\d{1,2}\\.\\d+)(?:,|%2C)(-?\\d{1,3}\\.\\d+)"),
            Regex("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"),
            Regex("^geo:(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
        )
        for (p in patterns) {
            val m = p.find(url) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) return lat to lng
        }
        return null
    }

    fun wazeLink(lat: Double, lng: Double): String = "https://waze.com/ul?ll=$lat,$lng&navigate=yes"

    /** Address text for a map search when the order has no location link. */
    fun addressText(emirate: String?, city: String?, villa: String?, apt: String?): String =
        listOf(villa, apt, city, emirate)
            .map { it?.trim() ?: "" }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    fun geoSearchLink(address: String): String =
        "geo:0,0?q=" + urlEncode(if (address.lowercase(Locale.ROOT).contains("uae") || address.lowercase(Locale.ROOT).contains("emirates")) address else "$address, UAE")

    /** Which link "Navigate" opens: the order's own link, or a map search by address. */
    fun navigateLink(locationUrl: String?, emirate: String?, city: String?, villa: String?, apt: String?): String? {
        val u = locationUrl?.trim()
        if (!u.isNullOrEmpty()) return u
        val addr = addressText(emirate, city, villa, apt)
        return if (addr.isEmpty()) null else geoSearchLink(addr)
    }

    // ---- Versions ------------------------------------------------------------------------------

    /** "v1.2.3" / "1.2.3-dev" → [1,2,3]. */
    fun parseVersion(v: String?): List<Int> {
        if (v.isNullOrBlank()) return emptyList()
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** true when remote is strictly newer than local. */
    fun isNewerVersion(remote: String?, local: String?): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        if (r.isEmpty()) return false
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** versionCode used by CI: 1.2.3 → 10203. */
    fun versionCodeOf(v: String?): Int {
        val p = parseVersion(v)
        val code = p.getOrElse(0) { 0 } * 10000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 }
        return if (code < 1) 1 else code
    }

    // ---- Dates / money -------------------------------------------------------------------------

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT)
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT)

    /** ISO instant of today's 00:00 in Dubai — the lower bound for "Done today". */
    fun todayStartDubaiIso(now: Instant = Instant.now()): String =
        now.atZone(DUBAI).toLocalDate().atStartOfDay(DUBAI).toInstant().toString()

    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso)
        } catch (_: Exception) {
            try {
                ZonedDateTime.parse(iso).toInstant()
            } catch (_: Exception) {
                try {
                    // PostgREST may return "2026-10-16T10:05:00.123456+00:00"; java.time parses that with OffsetDateTime
                    java.time.OffsetDateTime.parse(iso).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun fmtTimeDubai(iso: String?): String {
        val i = parseInstant(iso) ?: return ""
        return i.atZone(DUBAI).format(timeFmt)
    }

    fun fmtDateTimeDubai(iso: String?): String {
        val i = parseInstant(iso) ?: return ""
        return i.atZone(DUBAI).format(dateTimeFmt)
    }

    /** "Today 14:00–16:00", "17.10 14:00", "17.10" or "" when nothing is scheduled. */
    fun fmtSchedule(schedDate: String?, from: String?, to: String?, today: LocalDate = LocalDate.now(DUBAI)): String {
        val d = schedDate?.trim()?.takeIf { it.isNotEmpty() }
        val f = from?.trim()?.takeIf { it.isNotEmpty() }?.let(::trimSeconds)
        val t = to?.trim()?.takeIf { it.isNotEmpty() }?.let(::trimSeconds)
        val dayText = d?.let {
            try {
                val ld = LocalDate.parse(it.substring(0, 10))
                when {
                    ld == today -> "Today"
                    ld == today.plusDays(1) -> "Tomorrow"
                    else -> ld.format(dateFmt)
                }
            } catch (_: Exception) {
                it
            }
        }
        val window = when {
            f != null && t != null -> "$f–$t"
            f != null -> "from $f"
            t != null -> "until $t"
            else -> null
        }
        return listOfNotNull(dayText, window).joinToString(" ")
    }

    /** "14:30:00" → "14:30". */
    fun trimSeconds(t: String): String =
        if (t.length >= 8 && t[2] == ':' && t[5] == ':') t.substring(0, 5) else t

    /** 1250.0 → "1,250 AED"; 1250.5 → "1,250.50 AED". */
    fun fmtAed(amount: Double): String {
        val whole = amount == Math.rint(amount)
        val s = if (whole) String.format(Locale.US, "%,.0f", amount) else String.format(Locale.US, "%,.2f", amount)
        return "$s AED"
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- WhatsApp messages to the customer (v1.2, table driver_messages) ------------------------

    /** Built-in text when the admin has no messages yet (or the table does not exist). */
    val DEFAULT_MESSAGES: List<MessageTemplate> = listOf(
        MessageTemplate(0, "On my way", "Hello, this is the {seller} driver. I'm on my way with your order {order}.", null, 0)
    )

    private fun normKey(s: String?): String = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT) ?: ""

    /**
     * Messages for one order: the seller's own messages + the general ones whose title the seller did not override.
     * Ordered by sort_order, then title. Empty input → the built-in default.
     */
    fun pickMessages(all: List<MessageTemplate>, orderSeller: String?): List<MessageTemplate> {
        if (all.isEmpty()) return DEFAULT_MESSAGES
        val seller = normKey(orderSeller)
        val general = all.filter { normKey(it.sellerName).isEmpty() }
        val own = if (seller.isEmpty()) emptyList() else all.filter { normKey(it.sellerName) == seller }
        val ownTitles = own.map { normKey(it.title) }.toSet()
        val list = (own + general.filter { normKey(it.title) !in ownTitles })
            .sortedWith(compareBy<MessageTemplate>({ it.sortOrder }, { it.title.lowercase(Locale.ROOT) }))
        return list.ifEmpty { DEFAULT_MESSAGES }
    }

    /**
     * Fills {client} {order} {driver} {seller} {amount} {window} {address} (case-insensitive). Unknown {words} stay.
     * Empty values do not leave "Hello , this" behind: spaces before punctuation and double spaces are removed.
     */
    fun fillMessage(body: String, vars: Map<String, String>): String {
        val lower = vars.mapKeys { it.key.lowercase(Locale.ROOT) }
        val filled = Regex("\\{([A-Za-z_]+)\\}").replace(body) { m ->
            lower[m.groupValues[1].lowercase(Locale.ROOT)] ?: m.value
        }
        return filled
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" +([,.!?;:])"), "$1")
            .replace(Regex("\\( +"), "(")
            .replace(Regex(" +\\)"), ")")
            .lines().joinToString("\n") { it.trim() }
            .trim()
    }

    // ---- Photo at the door (v1.1) --------------------------------------------------------------

    /** "ORD-123456 · Rufat" / "18.10.2026 14:32 Dubai" / "GPS 25.20480, 55.27080 ±12 m" (or "GPS: off"). */
    fun podStampLines(orderNum: String, driver: String, takenAtMs: Long, lat: Double?, lng: Double?, accuracyM: Float?): List<String> {
        val t = Instant.ofEpochMilli(takenAtMs).atZone(DUBAI)
        val time = t.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT)) + " Dubai"
        val gps = if (lat == null || lng == null) "GPS: off" else String.format(
            Locale.US, "GPS %.5f, %.5f%s", lat, lng,
            accuracyM?.let { " ±" + Math.round(it) + " m" } ?: ""
        )
        return listOf(if (driver.isBlank()) orderNum else "$orderNum · $driver", time, gps)
    }

    /** Must match the bucket policy in add_driver_photos.sql. */
    val POD_OBJECT_NAME = Regex("^[0-9a-f-]{36}/[A-Za-z0-9._-]{1,80}\\.(jpg|jpeg|png)$")

    /** "<order uuid lower-case>/<yyyyMMdd-HHmmss Dubai>-<4 hex>.jpg" */
    fun podObjectName(orderId: String, takenAtMs: Long, rnd: Int): String {
        val t = Instant.ofEpochMilli(takenAtMs).atZone(DUBAI)
        val stamp = t.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        val hex = Integer.toHexString(rnd and 0xFFFF).padStart(4, '0')
        return "${orderId.trim().lowercase(Locale.ROOT)}/$stamp-$hex.jpg"
    }

    /** Short, single-line quantity label: "2 pcs" / "1 pc". */
    fun pcs(qty: Double): String {
        val q = if (qty == Math.rint(qty)) qty.toLong().toString() else qty.toString()
        return if (qty == 1.0) "$q pc" else "$q pcs"
    }

    // ---- Cash taken at the door (v1.3) ---------------------------------------------------------

    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    private val CASH_PLAIN = Regex("^\\d{1,7}(\\.\\d{1,2})?$")
    private val CASH_THOUSANDS = Regex("^\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?$")
    private val CASH_DECIMAL_COMMA = Regex("^\\d{1,7},\\d{1,2}$")

    /**
     * The amount the driver typed: "880", "880.5", "880,50", "1 200", "1,200", "1,200.50" → a number with ≤ 2 decimals.
     * null when it is not a plain non-negative amount ("", "abc", "-5", "8.8.0", "880.505").
     */
    fun parseCash(input: String?): Double? {
        val s = input?.replace(" ", "")?.replace(" ", "")?.trim() ?: return null
        if (s.isEmpty()) return null
        val plain = when {
            CASH_PLAIN.matches(s) -> s
            CASH_THOUSANDS.matches(s) -> s.replace(",", "")
            CASH_DECIMAL_COMMA.matches(s) -> s.replace(',', '.')
            else -> return null
        }
        return plain.toDoubleOrNull()?.let { round2(it) }
    }

    enum class CashKind {
        /** exactly the due amount */
        EXACT,
        /** a bit more — accepted, shown as "+X over" */
        OVER,
        /** a lot more — the driver must confirm it once more */
        BIG_OVER,
        /** less than due — refused (call the dispatcher) */
        LESS,
        /** so much that it must be a typo — refused, same limit as the database (due × 2 + 1000) */
        TOO_MUCH
    }

    data class CashCheck(val kind: CashKind, val received: Double, val due: Double) {
        val over: Double get() = round2(received - due)
        val canSave: Boolean get() = kind == CashKind.EXACT || kind == CashKind.OVER || kind == CashKind.BIG_OVER
    }

    /** Must agree with add_driver_cash.sql: less than due is refused, more than due × 2 + 1000 is refused. */
    fun checkCash(received: Double, due: Double): CashCheck {
        val r = round2(received)
        val d = round2(due)
        val kind = when {
            r < d - 0.005 -> CashKind.LESS
            r > d * 2 + 1000 -> CashKind.TOO_MUCH
            r - d < 0.005 -> CashKind.EXACT
            r - d > maxOf(100.0, d * 0.25) -> CashKind.BIG_OVER
            else -> CashKind.OVER
        }
        return CashCheck(kind, r, d)
    }

    /** Cash the driver has from delivered orders: the amount he entered, or the estimate for orders closed without one. */
    fun cashTaken(received: Double?, estimate: Double): Double = received ?: estimate
}

/** One WhatsApp template from driver_messages (sellerName null = for every seller). */
data class MessageTemplate(
    val id: Long,
    val title: String,
    val body: String,
    val sellerName: String?,
    val sortOrder: Int
)
