package fish.nexa.data

import fish.nexa.net.AppCommand
import fish.nexa.net.CommandChoice
import fish.nexa.net.CommandOption
import fish.nexa.net.InteractionFile
import fish.nexa.ui.apps.CmdSlot
import fish.nexa.ui.apps.SlotIssue
import fish.nexa.ui.apps.slotIssue
import fish.nexa.ui.apps.slotValue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Apps on the phone hold to what the web and the server hold to (web lib/perms.test.ts, CommandMode.test.ts, lib/apps.test.ts). */
class AppsTest {
    @Test
    fun permissionBitsMatchTheServer() {
        // the same totals the web's test expects, which the server's own values give
        assertEquals("1154615270161370689", java.lang.Long.toUnsignedString(P.DEFAULT_EVERYONE))
        assertEquals("1161380584429711359", java.lang.Long.toUnsignedString(P.ALL))
    }

    private fun slot(type: Int, text: String = "", required: Boolean = false, value: JsonPrimitive? = null, file: InteractionFile? = null, choices: List<CommandChoice>? = null, minValue: Double? = null, maxValue: Double? = null, minLength: Int? = null, maxLength: Int? = null) =
        CmdSlot(CommandOption(type, "x", required = required, choices = choices, minValue = minValue, maxValue = maxValue, minLength = minLength, maxLength = maxLength), text, value = value, file = file)

    @Test
    fun slotsNeedWhatIsRequired() {
        assertNotNull(slotIssue(slot(3, required = true)))
        assertNull(slotIssue(slot(3)))
        assertNull(slotIssue(slot(3, "hi", required = true)))
    }

    @Test
    fun numbersHoldToTheirKindAndRange() {
        assertEquals(SlotIssue.WholeNumber, slotIssue(slot(4, "1.5")))
        assertEquals(SlotIssue.AtMost(5.0), slotIssue(slot(4, "9", minValue = 1.0, maxValue = 5.0)))
        assertNull(slotIssue(slot(4, "3", minValue = 1.0, maxValue = 5.0)))
        assertNotNull(slotIssue(slot(10, "abc")))
        assertEquals(JsonPrimitive(2.5), slotValue(slot(10, "2.5")))
        assertEquals(JsonPrimitive(3L), slotValue(slot(4, "3")))
    }

    @Test
    fun wordsHoldToTheirLength() {
        assertNotNull(slotIssue(slot(3, "ab", minLength = 3)))
        assertNotNull(slotIssue(slot(3, "abcd", maxLength = 3)))
    }

    @Test
    fun choicesPeopleAndFilesOnlyWhenPicked() {
        val choices = listOf(CommandChoice("A", value = JsonPrimitive("a")))
        assertNotNull(slotIssue(slot(3, "a", choices = choices)))
        assertNull(slotIssue(slot(3, "", choices = choices, value = JsonPrimitive("a"))))
        assertNotNull(slotIssue(slot(6, "bob")))
        assertEquals(JsonPrimitive("42"), slotValue(slot(6, value = JsonPrimitive("42"))))
        assertNotNull(slotIssue(slot(11)))
        assertEquals(JsonPrimitive("7"), slotValue(slot(11, file = InteractionFile("7", "a.png", 1, "image/png"))))
    }

    @Test
    fun subcommandsAreCommandsOfTheirOwn() {
        val cmd = AppCommand(
            "1", "2", name = "config", description = "Settings",
            options = listOf(
                CommandOption(1, "show", description = "Shows them"),
                CommandOption(2, "set", description = "Changes one", options = listOf(CommandOption(1, "colour", description = "The colour", options = listOf(CommandOption(3, "value", required = true))))),
            ),
        )
        val leaves = AppCommands.leaves(cmd)
        assertEquals(listOf("config show", "config set colour"), leaves.map { it.label })
        assertEquals(listOf("set", "colour"), leaves[1].path)
        assertEquals(listOf("value"), leaves[1].options.map { it.name })
        // a command without subcommands is itself
        assertEquals(listOf("ping"), AppCommands.leaves(AppCommand("3", "2", name = "ping")).map { it.label })
        // options go under the subcommand path
        assertEquals(
            """[{"name":"set","options":[{"name":"colour","options":[{"name":"value","value":"red","focused":true}]}]}]""",
            AppCommands.nest(listOf("set", "colour"), listOf(AppCommands.Given("value", JsonPrimitive("red"), focused = true))).toString(),
        )
    }

    @Test
    fun namesInTheReadersLanguage() {
        val names = mapOf("en-US" to "colour", "es-ES" to "color", "de" to "farbe")
        assertEquals("colour", AppCommands.localized("color", names, "en"))
        assertEquals("color", AppCommands.localized("x", names, "es"))
        assertEquals("farbe", AppCommands.localized("x", names, "de"))
        assertEquals("x", AppCommands.localized("x", names, "ja"))
        assertEquals("en-US", AppCommands.protocolLocale("en"))
        assertEquals("ar", AppCommands.protocolLocale("ar"))
    }
}
