package fish.nexa.notify

import fish.nexa.Nexa
import fish.nexa.data.Moderation
import fish.nexa.data.NexaPerson
import fish.nexa.data.ReasonTone
import fish.nexa.data.Reports
import fish.nexa.data.SafetyReport
import fish.nexa.ui.nav.Nav
import fish.nexa.ui.t
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What staff are told about (lib/notify.ts onNewReport, lib/staffAlerts.ts):
 * new reports (every one, only urgent ones, or none), members writing in
 * cases, and bug reports, as Settings, Notifications says. They count on the
 * badges either way. Quiet while the place they belong to is on screen.
 */
object StaffAlerts {
    data class Prefs(val reports: String, val cases: Boolean, val bugs: Boolean)

    /** The account's choices with the defaults filled in: everything on. */
    fun prefs(): Prefs {
        val s = Nexa.settings.notifications["staff"] as? JsonObject
        val reports = (s?.get("reports") as? JsonPrimitive)?.contentOrNull.takeIf { it == "urgent" || it == "off" } ?: "all"
        return Prefs(reports, (s?.get("cases") as? JsonPrimitive)?.booleanOrNull != false, (s?.get("bugs") as? JsonPrimitive)?.booleanOrNull != false)
    }

    private fun viewing(prefix: String) = Nav.route.toPath().startsWith(prefix)

    fun install() {
        Reports.onNewReport = { r, people -> onReport(r, people) }
        Moderation.onStaffAlert = { a ->
            when (a) {
                is Moderation.StaffAlert.Appeal -> if (prefs().cases) {
                    val path = "/channels/@admin/cases/${a.case.id}"
                    Notifier.simple("ec-appeal-${a.case.id}", "nexa", "message", t("nexa.notify.appealTitle"), t("nexa.notify.appeal"), path, viewing(path))
                }
                is Moderation.StaffAlert.Bug -> if (prefs().bugs) {
                    val path = "/channels/@admin/bugs/${a.report.id}"
                    Notifier.simple("ec-bug-${a.report.id}", "nexa", "message", t(if (a.isNew) "bugs.notify.newTitle" else "bugs.notify.replyTitle"), a.report.subject, path, viewing(path))
                }
            }
        }
    }

    private fun onReport(r: SafetyReport, people: Map<String, NexaPerson>) {
        if (r.reporterId == Nexa.users.meId) return
        val level = prefs().reports
        val urgent = Reports.tone(r.reason) == ReasonTone.URGENT
        if (level == "off" || (level == "urgent" && !urgent)) return
        val subject = if (r.subjectId != "0") people[r.subjectId] else null
        val name = subject?.displayName?.ifEmpty { subject.username }.orEmpty()
        val kind = t("reports.kind.${r.targetKind}")
        val about = if (name.isNotEmpty()) t("reports.notify.about", "kind" to kind, "name" to name) else kind
        val body = if (r.description.isNotEmpty()) about + "\n" + r.description.take(160) else about
        Notifier.simple(
            "ec-staff-report-${r.id}", "nexa", if (urgent) "mention" else "message",
            t("reports.notify.newTitle", "reason" to t("reports.reason.${r.reason}")), body,
            "/channels/@admin/reports/${r.id}", viewing("/channels/@admin/reports"),
        )
    }
}
