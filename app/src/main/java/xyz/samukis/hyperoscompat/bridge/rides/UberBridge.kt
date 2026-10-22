package xyz.samukis.hyperoscompat.bridge.rides

import android.content.Context
import android.service.notification.StatusBarNotification
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole
import xyz.samukis.hyperoscompat.bridge.ActionRole.CALL
import xyz.samukis.hyperoscompat.bridge.ActionRole.MESSAGE
import xyz.samukis.hyperoscompat.bridge.ActionRole.SHARE
import xyz.samukis.hyperoscompat.bridge.BaseBridge
import xyz.samukis.hyperoscompat.bridge.BridgeCategory
import xyz.samukis.hyperoscompat.bridge.IslandAction
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
 * always the same layout: headline, vehicle, PIN chip, a progress bar with a car, and buttons.
 *
 * Observed on device (Oct 2026, es-PE): live title "Inicio de viaje en 1 min", text
 * "A0A475 • Chevrolet Aveo\nAmarillo" (plate • model, colour). Other phrasings are heuristic.
 */
class UberBridge(context: Context) : BaseBridge(
    context,
    packageName = "com.ubercab",
    nameRes = R.string.app_uber,
    descriptionRes = R.string.desc_uber,
    category = BridgeCategory.TRANSPORT,
    accentColor = "#FFFFFF",
) {
    override val singleIsland: Boolean = true

    private enum class Phase { SEARCHING, PICKUP, ARRIVED, TRIP }

    // ---- What we know about the current ride, from the live notification and Uber's pushes.
    private var liveKey: String? = null
    private var pin: String? = null
    private var vehicle: String? = null
    private var phase: Phase? = null
    /** Largest minutes-left seen in the current phase: the 0% point of the derived progress bar. */
    private var phaseMinutesMax = 0
    private val borrowed = LinkedHashMap<ActionRole, IslandAction>()
    private var touchedAt = 0L
    private var refresh = false

    override fun takeRefreshRequest(): Boolean = refresh.also { refresh = false }

    override fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == liveKey) reset()
    }

    override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        val h = x.haystack
        val now = System.currentTimeMillis()
        if (touchedAt != 0L && now - touchedAt > STALE_MS) reset()

        if (kw.looksTripOver(h)) {
            if (x.ongoing || x.sbn.key == liveKey) reset()
            return null
        }
        absorb(x, now)
        // Only the live trip notification is island-worthy. Pushes and promos never are.
        if (!x.ongoing) return null

        val eta = Text.minutes(h)
        val clock = Text.clockTime(h, now)
        val next = phaseOf(x, eta, clock) ?: return null

        liveKey = x.sbn.key
        touchedAt = now
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
            Phase.TRIP -> text.takeUnless { PLATE.containsMatchIn(it) }?.ifEmpty { null } ?: vehicle
            else -> vehicle ?: text.ifEmpty { null }
        }?.takeIf { it != title }
        val detail = when {
            next == Phase.PICKUP && eta != null && clockText != null -> str(R.string.ride_arrival_at, clockText)
            next == Phase.TRIP && clockText != null && eta != null -> str(R.string.minutes_short, eta)
            else -> null
        }

        val pill = when (next) {
            Phase.SEARCHING -> str(R.string.ride_searching_short)
            Phase.PICKUP, Phase.ARRIVED -> pinLabel ?: plate ?: str(R.string.ride_pickup_short)
            Phase.TRIP -> str(R.string.ride_arrival_short)
        }
        val trailing = when (next) {
            Phase.SEARCHING -> "…"
            Phase.PICKUP -> eta?.let { str(R.string.minutes_short, it) } ?: clockText
            Phase.ARRIVED -> str(R.string.ride_arrived)
            Phase.TRIP -> clockText ?: eta?.let { str(R.string.minutes_short, it) }
        } ?: x.shortCritical

        val progress = progressFor(next, x, eta, clock, now)

        // Contact on the left, share on the right; borrowed from ride pushes when the live one has none.
        val own = x.pick(arrayOf(CALL, MESSAGE), arrayOf(SHARE))
        val contact = own.firstOrNull { it.role == CALL || it.role == MESSAGE } ?: borrowed[CALL] ?: borrowed[MESSAGE]
        val share = own.firstOrNull { it.role == SHARE } ?: borrowed[SHARE]

        return IslandSpec(
            kind = IslandKind.STATUS,
            title = title,
            subtitle = content,
            detail = detail,
            trailing = trailing,
            highlight = if (next == Phase.TRIP) null else pinLabel,
            pillText = pill,
            progress = progress,
            progressGlyph = if (progress != null) R.drawable.ic_car else 0,
            progressEndGlyph = if (progress == null) 0 else if (next == Phase.TRIP) R.drawable.ic_flag else R.drawable.ic_place,
            actions = listOfNotNull(contact, share),
            accentColor = accentColor,
            expandFirst = next == Phase.ARRIVED,
            popOnUpdate = popNow,
        )
    }

    /** Learns the PIN and contact/share buttons from any ride-related Uber notification. */
    private fun absorb(x: Notif, now: Long) {
        val h = x.haystack
        if (kw.looksPromo(h)) return
        val fromPush = !x.ongoing && x.sbn.key != liveKey

        Text.pin(h)?.takeUnless { h.contains("verific") }?.let { found ->
            if (found != pin) {
                pin = found
                if (fromPush) refresh = true
            }
            touchedAt = now
        }
        if (!fromPush || !kw.looksRide(h)) return
        for (role in BORROWABLE) {
            val a = x.pick(arrayOf(role)).firstOrNull() ?: continue
            if (borrowed[role]?.intent == a.intent) continue
            // sourceIndex -1: the button is fired directly, not looked up in the live notification.
            borrowed[role] = IslandAction(role, a.title, null, a.intent)
            refresh = true
        }
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

    private fun reset() {
        liveKey = null
        pin = null
        vehicle = null
        phase = null
        phaseMinutesMax = 0
        borrowed.clear()
        touchedAt = 0L
    }

    /** "A0A475 • Chevrolet Aveo\nAmarillo" -> "A0A475 · Chevrolet Aveo · Amarillo". */
    private fun clean(s: String): String = s.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .replace(" • ", " · ")

    companion object {
        private const val STALE_MS = 3 * 60 * 60_000L
        private val BORROWABLE = listOf(CALL, MESSAGE, SHARE)

        /** A licence plate: 5–8 upper-case letters/digits (one dash allowed), with both kinds. */
        private val PLATE = Regex("""\b(?=[A-Z0-9-]{5,8}\b)(?=[A-Z0-9-]*\d)(?=[A-Z0-9-]*[A-Z])[A-Z0-9]+(?:-[A-Z0-9]+)?\b""")
    }
}
