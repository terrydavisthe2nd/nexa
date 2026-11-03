package fish.nexa.data

import fish.nexa.Nexa
import fish.nexa.net.Billing
import fish.nexa.net.PlanLimits
import fish.nexa.net.PlansInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The plans (the web's lib/plans.ts, the server's api/billing.go): what each
 * allows, the account's own, and its side of the subscription once read. A
 * plan only raises what costs money to keep; everything else is the same.
 */
object Plans {
    val PAID = listOf("supporter", "pro")

    /** The name effects only plans have (store.PaidNameEffects). */
    val PAID_EFFECTS = setOf("holo", "neon", "flame")

    /** The table the instance answered with, or the built-in one before it has. */
    fun info(): PlansInfo {
        val inst = Nexa.auth.instance.value
        return inst?.plans ?: PlansInfo(free = PlanLimits(fileMax = inst?.maxUploadBytes ?: (1L shl 30)))
    }

    fun mine(): String = Nexa.users.me.value?.plan ?: ""

    fun limitsOf(plan: String?): PlanLimits = info().let { if (plan == "pro") it.pro else if (plan == "supporter") it.supporter else it.free }

    fun myLimits(): PlanLimits = limitsOf(mine())

    fun boosted(serverId: String?): Boolean = serverId != null && (Nexa.servers.get(serverId)?.server?.boosts ?: 0) > 0

    /** The largest file this account may send to a channel: its plan's, or a boosted server's when that is more. */
    fun fileMaxIn(channelId: String?): Long {
        val own = myLimits().fileMax
        val serverId = channelId?.let { Nexa.channels.get(it)?.serverId }
        return if (boosted(serverId)) maxOf(own, info().boostFileMax) else own
    }

    /** What this account's plan lets a screen share do. */
    fun shareAllowance(): fish.nexa.voice.VideoCap.Allowance = myLimits().let { fish.nexa.voice.VideoCap.Allowance(it.shareQuality, it.shareMaxBps.toInt()) }

    /** The cheapest plan that sends a file this big, or null when none does. */
    fun planForFile(size: Long): String? = PAID.firstOrNull { limitsOf(it).fileMax >= size }

    /** Whether a plan is offered for a file this big: one would send it, and this account is not on the biggest. */
    fun offersFor(size: Long): Boolean = planForFile(size) != null && mine() != "pro"

    private val _billing = MutableStateFlow<Billing?>(null)
    /** The account's side of its subscription (GET /billing), once read. */
    val billing: StateFlow<Billing?> = _billing
    private val loading = Mutex()

    /** Reads it again; the Support page and the tray's meter ask, and billing_update. */
    suspend fun load() {
        if (!loading.tryLock()) return
        try {
            _billing.value = Nexa.api.get("/billing", Billing.serializer())
        } catch (_: Exception) {
        } finally {
            loading.unlock()
        }
    }

    fun clear() {
        _billing.value = null
    }

    /** Why a plan is offered: something the account's plan stopped (the web's stores/upsell.ts). */
    sealed interface Offer {
        data class File(val name: String, val size: Long) : Offer
        data object Uploads : Offer
        data object Share : Offer
        data object Effect : Offer
    }

    private val _offer = MutableStateFlow<Offer?>(null)
    /** The offer on screen (ui/support/Upsell.kt draws it), or null. */
    val offered: StateFlow<Offer?> = _offer

    /** Offers a plan where the free one stopped something; the sheet says what and which plan would do it. */
    fun offer(o: Offer) {
        _offer.value = o
    }

    /** Someone to start Gift a Plan with picked (a profile's Gift a Plan); read once by the Support page. */
    var giftTo: String? = null

    fun dismissOffer() {
        _offer.value = null
    }

    /** Pay what you want, in cents: from a dollar to the most one payment may be (api/billing.go pwywMin, pwywMax). */
    const val PWYW_MIN = 100L
    const val PWYW_MAX = 99_999_999L

    /** What a pay-what-you-want amount buys: months of Pro, then of Supporter (the web's payWhatYouWant, the server's pwywSplit). */
    data class Bought(val pro: Long, val supporter: Long) {
        val months get() = pro + supporter
    }

    fun payWhatYouWant(amount: Long, supporterMonth: Long = 500, proMonth: Long = 1000): Bought {
        if (supporterMonth <= 0 || proMonth <= 0 || amount < PWYW_MIN) return Bought(0, 0)
        val pro = amount / proMonth
        return Bought(pro, (amount - pro * proMonth) / supporterMonth)
    }

    /** The one-time month prices pay what you want divides by, in cents: the gift prices once read, else the list prices. */
    fun monthPrices(b: Billing? = billing.value): Pair<Long, Long> {
        fun usd(plan: String, list: Long) = b?.giftPrices?.get(plan)?.get("month")?.takeIf { it.currency.equals("usd", true) && it.amount > 0 }?.amount ?: list
        return usd("supporter", 500) to usd("pro", 1000)
    }

    private val AMOUNT = Regex("""^\s*\$?\s*(\d{1,9})(?:[.,](\d{0,2}))?\s*$""")

    /** Cents from what someone typed as dollars ("12", "12.5", "12,50"), or null when it isn't an amount. */
    fun centsOf(text: String): Long? {
        val m = AMOUNT.find(text) ?: return null
        return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
    }
}
