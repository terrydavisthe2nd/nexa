package fish.nexa.data

import fish.nexa.Nexa
import fish.nexa.ui.profile.NexaOwner
import fish.nexa.ui.t

/**
 * lib/staffRank.ts: Nexa's staff ranks, as the server keeps them
 * (internal/api/staff.go), each holding everything the ones below it hold.
 * A helper triages reports and handles bug reports, acting on nobody; a mod
 * takes reported content down and mutes or bans for 30 days at most; an admin
 * does the rest of the panel; a developer runs the instance's settings, stats
 * and crash reports, with no rights in other people's servers or group chats;
 * the owner is the oldest developer and the only one who gives and takes
 * ranks. A rank is a public flag bit. What a rank may do is [StaffRanks.can].
 */
enum class StaffRank(val key: String, val icon: String) {
    HELPER("helper", "LifeBuoy"),
    MOD("mod", "ShieldCheck"),
    ADMIN("admin", "Hammer"),
    DEVELOPER("developer", "CodeXml"),
    OWNER("owner", "Crown");

    fun atLeast(need: StaffRank) = ordinal >= need.ordinal

    val label: String
        get() = when (this) {
            OWNER -> t("profile.profileCard.owner")
            DEVELOPER -> t("staff.rank.developer")
            ADMIN -> t("profile.profileCard.admin")
            MOD -> t("staff.rank.mod")
            HELPER -> t("staff.rank.helper")
        }

    /** What the badge says when it is described: "Owner of Nexa". */
    val tip: String
        get() = when (this) {
            OWNER -> t("profile.profileCard.ownerTip")
            DEVELOPER -> t("staff.rank.developerTip")
            ADMIN -> t("profile.profileCard.adminTip")
            MOD -> t("staff.rank.modTip")
            HELPER -> t("staff.rank.helperTip")
        }

    /** What a rank the owner gives can do, in a line. */
    val powers: String
        get() = when (this) {
            DEVELOPER -> t("staff.rank.developerPowers")
            ADMIN -> t("staff.rank.adminPowers")
            HELPER -> t("staff.rank.helperPowers")
            else -> t("staff.rank.modPowers")
        }
}

/** The things staff do, each with the least rank it takes (lib/staffRank.ts STAFF_CAN). */
enum class StaffCan(val least: StaffRank) {
    TRIAGE_REPORTS(StaffRank.HELPER),
    READ_PANEL(StaffRank.HELPER),
    HANDLE_BUGS(StaffRank.HELPER),
    TAKE_DOWN_CONTENT(StaffRank.MOD),
    RESTRICT(StaffRank.MOD),
    HANDLE_OWN_CASES(StaffRank.MOD),
    BROWSE_SERVERS(StaffRank.MOD),
    RESTRICT_ANY_LENGTH(StaffRank.ADMIN),
    HANDLE_ANY_CASE(StaffRank.ADMIN),
    TAKE_DOWN_SERVERS(StaffRank.ADMIN),
    SEE_ALTS_AND_CALLS(StaffRank.ADMIN),
    RUN_NEXA(StaffRank.ADMIN),
    BULK_MODERATE(StaffRank.ADMIN),
    CLOSE_ACCOUNTS(StaffRank.ADMIN),
    INSTANCE(StaffRank.DEVELOPER),
    RANKS(StaffRank.OWNER),
}

object StaffRanks {
    const val DEVELOPER = 1L
    const val ADMIN = 256L
    const val MOD = 512L
    const val HELPER = 1024L
    const val ALL = DEVELOPER or ADMIN or MOD or HELPER

    /** The longest mute or ban a mod can give. */
    const val MOD_MAX_MS = 30L * 24 * 60 * 60 * 1000

    /** Someone's rank from their flags, null for none. The owner's account always reads as the owner. */
    fun of(userId: String?, flags: Long, ownerId: String? = null): StaffRank? = when {
        userId != null && userId == (ownerId ?: NexaOwner.id()) -> StaffRank.OWNER
        flags and DEVELOPER != 0L -> StaffRank.DEVELOPER
        flags and ADMIN != 0L -> StaffRank.ADMIN
        flags and MOD != 0L -> StaffRank.MOD
        flags and HELPER != 0L -> StaffRank.HELPER
        else -> null
    }

    /** The signed-in account's rank. */
    fun mine(): StaffRank? = Nexa.users.me.value?.let { of(it.id, it.flags) }

    /** Whether a rank (or none) may do something. */
    fun can(rank: StaffRank?, what: StaffCan) = rank != null && rank.atLeast(what.least)

    /** Whether the signed-in account may do something. */
    fun can(what: StaffCan) = can(mine(), what)
}

/** Whether a rank (or none) is need or above it. */
fun StaffRank?.atLeast(need: StaffRank) = this != null && this.atLeast(need)
