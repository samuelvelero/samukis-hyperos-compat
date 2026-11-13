package xyz.samukis.hyperoscompat.bridge.rides

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.CALL
import xyz.samukis.hyperoscompat.bridge.ActionRole.MESSAGE
import xyz.samukis.hyperoscompat.bridge.ActionRole.SHARE
import xyz.samukis.hyperoscompat.bridge.SessionBridge
import xyz.samukis.hyperoscompat.bridge.BridgeCategory
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Uber rider app: exactly one island per ride, driven by Uber's live (ongoing) trip notification.
 *
 * Uber also sends one-off pushes during a ride ("El conductor está a unos minutos", "Usa el
 * código 4821") and promos ("Tiempos de espera menores…"). None of those get an island; ride
 * pushes only feed the live island with what they carry (the PIN, a share or call button).
 *
 * The summary pill follows the ride: PIN + minutes to pickup while waiting, PIN + "Arrived" when
 * the driver is there (the island pops open), arrival time during the trip. The expanded card is
 * always the same layout: headline, vehicle, PIN chip, a dot in the car's colour on the right, a
 * progress bar with a top-view car, and a share button (no call button, by the user's choice).
 *
 * Observed on device (Oct 2026, es-PE): live title "Inicio de viaje en 1 min", text
 * "A0A475 • Chevrolet Aveo\nAmarillo" (plate • model, colour). On the trip (Nov 2026): title "Destino 7:37 PM", text "En camino a Jirón …", so
 * "destino" must win over the pickup keyword "en camino". Other phrasings are heuristic.
 */
class UberBridge(context: Context) : SessionBridge(
    context,
    packageName = "com.ubercab",
    nameRes = R.string.app_uber,
    descriptionRes = R.string.desc_uber,
    category = BridgeCategory.TRANSPORT,
    accentColor = "#FFFFFF",
) {
    private enum class Phase { SEARCHING, PICKUP, ARRIVED, TRIP }

    // ---- What we know about the current ride, from the live notification and Uber's pushes.
    private var pin: String? = null
    private var vehicle: String? = null
    private var phase: Phase? = null
    /** Largest minutes-left seen in the current phase: the 0% point of the derived progress bar. */
    private var phaseMinutesMax = 0

    override fun onEnd() {
        pin = null
        vehicle = null
        phase = null
        phaseMinutesMax = 0
    }

    override fun session(x: Notif, firstSeen: Long, now: Long): IslandSpec? {
        val h = x.haystack
        if (kw.looksTripOver(h)) {
            if (x.ongoing || x.sbn.key == ownerKey) endSession()
            return null
        }
        absorb(x, now)
        // Only the live trip notification is island-worthy. Pushes and promos never are.
        if (!x.ongoing) return null

        val eta = Text.minutes(h)
        val clock = Text.clockTime(h, now)
        val next = phaseOf(x, eta, clock) ?: return null

        claim(x, now)
        val popNow = next == Phase.ARRIVED && phase != Phase.ARRIVED
        if (next != phase) phaseMinutesMax = 0
        phase = next

        val text = clean(x.text)
        if (PLATE.containsMatchIn(text)) vehicle = text
        val plate = vehicle?.let { PLATE.find(it)?.value }
        val pinLabel = pin?.let { str(R.string.ride_pin, it) }
        val clockText = clock?.text

        val title = when (next) {
            Phase.SEARCHING -> x.title.ifEmpty { appName }
            Phase.PICKUP -> eta?.let { str(R.string.ride_pickup_in, it) } ?: x.title.ifEmpty { appName }
            Phase.ARRIVED -> str(R.string.ride_driver_here)
            Phase.TRIP -> clockText?.let { str(R.string.ride_arrive_at, it) }
                ?: eta?.let { str(R.string.ride_arrive_in, it) }
                ?: x.title.ifEmpty { appName }
        }
        // During the trip the live text usually names the destination; before it, the car.
        val content = when (next) {
            Phase.TRIP -> text.takeUnless { PLATE.containsMatchIn(it) }?.replace(HEADING_TO, "")?.ifEmpty { null } ?: vehicle
            else -> vehicle ?: text.ifEmpty { null }
        }?.takeIf { it != title }
        val detail = when {
            next == Phase.PICKUP && eta != null && clockText != null -> str(R.string.ride_arrival_at, clockText)
            // On the trip: minutes left and the plate, so the car stays identifiable.
            next == Phase.TRIP -> listOfNotNull(
                eta?.takeIf { clockText != null }?.let { str(R.string.minutes_short, it) },
                plate,
            ).joinToString(" · ").ifEmpty { null }
            else -> null
        }

        val pill = when (next) {
            Phase.SEARCHING -> str(R.string.ride_searching_short)
            // Both at once when known: the bare PIN digits and the plate ("4821 · A0A475").
            Phase.PICKUP, Phase.ARRIVED -> when {
                pin != null && plate != null -> "$pin · $plate"
                else -> pinLabel ?: plate ?: str(R.string.ride_pickup_short)
            }
            Phase.TRIP -> str(R.string.ride_arrival_short)
        }
        val trailing = when (next) {
            Phase.SEARCHING -> "…"
            Phase.PICKUP -> eta?.let { str(R.string.minutes_short, it) } ?: clockText
            Phase.ARRIVED -> str(R.string.ride_arrived)
            Phase.TRIP -> clockText ?: eta?.let { str(R.string.minutes_short, it) }
        } ?: x.shortCritical

        val progress = progressFor(next, x, eta, clock, now)

        // Only share; borrowed from ride pushes when the live one has none. No call button by design.
        val own = x.pick(arrayOf(CALL, MESSAGE), arrayOf(SHARE))
        val share = own.firstOrNull { it.role == SHARE } ?: borrowed[SHARE]
        val dotColor = carColor(vehicle)

        return IslandSpec(
            kind = IslandKind.STATUS,
            title = title,
            subtitle = content,
            detail = detail,
            trailing = trailing,
            highlight = if (next == Phase.TRIP) null else pinLabel,
            pillText = pill,
            progress = progress,
            progressGlyph = if (progress != null) R.drawable.ic_car_top else 0,
            progressEndGlyph = if (progress == null) 0 else if (next == Phase.TRIP) R.drawable.ic_flag else R.drawable.ic_ring,
            sidePicture = if (next == Phase.SEARCHING) null else dot(dotColor),
            sidePictureId = if (next == Phase.SEARCHING) null else dotColor,
            actions = listOfNotNull(share),
            accentColor = accentColor,
            expandFirst = next == Phase.ARRIVED,
            popOnUpdate = popNow,
        )
    }

    /** Learns the PIN and contact/share buttons from any ride-related Uber notification. */
    private fun absorb(x: Notif, now: Long) {
        val h = x.haystack
        if (kw.looksPromo(h)) return
        val fromPush = !x.ongoing && x.sbn.key != ownerKey

        Text.pin(h)?.takeUnless { h.contains("verific") }?.let { found ->
            if (found != pin) {
                pin = found
                if (fromPush) requestRefresh()
            }
            touch(now)
        }
        if (fromPush && kw.looksRide(h)) borrow(x, CALL, MESSAGE, SHARE)
    }

    private fun phaseOf(x: Notif, eta: Int?, clock: Text.Clock?): Phase? {
        val h = x.haystack
        return when {
            kw.looksArrived(h) -> Phase.ARRIVED
            kw.looksRideTrip(h) -> Phase.TRIP
            kw.looksRideSearching(h) -> Phase.SEARCHING
            kw.looksRidePickup(h) -> Phase.PICKUP
            // A bare minutes count or time keeps the phase we are in.
            eta != null -> if (phase == Phase.TRIP) Phase.TRIP else Phase.PICKUP
            clock != null -> phase ?: Phase.TRIP
            PLATE.containsMatchIn(x.text) -> Phase.PICKUP
            kw.looksRide(h) -> phase ?: Phase.PICKUP
            else -> null
        }
    }

    /**
     * Uber's own progress when it sends one (Android 16 Live Update), otherwise derived from the
     * minutes left: the first value seen in a phase is 0%, so the car moves as the count drops.
     */
    private fun progressFor(next: Phase, x: Notif, eta: Int?, clock: Text.Clock?, now: Long): Int? {
        if (next == Phase.SEARCHING) return null
        if (next == Phase.ARRIVED) return 100
        if (x.progressMax > 0) return (x.progress * 100 / x.progressMax).coerceIn(0, 100)
        val left = eta ?: clock?.let { ((it.millis - now) / 60_000L).toInt() }?.takeIf { it in 0..600 } ?: return null
        phaseMinutesMax = maxOf(phaseMinutesMax, left, 1)
        return ((phaseMinutesMax - left) * 100 / phaseMinutesMax).coerceIn(4, 96)
    }

    /** "A0A475 • Chevrolet Aveo\nAmarillo" -> "A0A475 · Chevrolet Aveo · Amarillo". */
    private fun clean(s: String): String = s.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .replace(" • ", " · ")

    private val dots = HashMap<String, Icon>()

    /** A filled circle in [hex] with a faint rim, so a black car still shows on the black card. */
    private fun dot(hex: String): Icon = dots.getOrPut(hex) {
        val size = 144
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex.toColorInt() })
        canvas.drawCircle(c, c, c - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0x33FFFFFF
        })
        Icon.createWithBitmap(bitmap)
    }

    /** The car's colour from Uber's "plate · model · colour" line; yellow when it names none we know. */
    private fun carColor(vehicle: String?): String {
        val v = vehicle?.lowercase().orEmpty()
        return COLORS.firstOrNull { (words, _) -> words.any { v.contains(it) } }?.second ?: "#F2C230"
    }

    companion object {
        /** "En camino a Jirón …" -> "Jirón …": the card already says it is the trip. */
        private val HEADING_TO = Regex("""^(en camino a|en ruta a|en ruta hacia|rumbo a|on the way to|heading to)\s+""", RegexOption.IGNORE_CASE)

        private val COLORS = listOf(
            listOf("amarill", "yellow") to "#F2C230",
            listOf("blanc", "white") to "#F2F2F7",
            listOf("negr", "black") to "#1C1C1E",
            listOf("plat", "gris", "silver", "gray", "grey") to "#A1A1A6",
            listOf("roj", "red") to "#E5484D",
            listOf("celeste", "light blue") to "#6CC4F0",
            listOf("azul", "blue") to "#2F6FDE",
            listOf("verde", "green") to "#30A46C",
            listOf("naranja", "orange") to "#F76B15",
            listOf("guinda", "vino", "granate", "burgundy", "maroon") to "#7A1F2B",
            listOf("morad", "purple", "violet") to "#7C3AED",
            listOf("marr", "café", "cafe", "brown") to "#8B5E3C",
            listOf("beige", "crema") to "#D8C7A3",
        )

        /** A licence plate: 5–8 upper-case letters/digits (one dash allowed), with both kinds. */
        private val PLATE = Regex("""\b(?=[A-Z0-9-]{5,8}\b)(?=[A-Z0-9-]*\d)(?=[A-Z0-9-]*[A-Z])[A-Z0-9]+(?:-[A-Z0-9]+)?\b""")
    }
}
