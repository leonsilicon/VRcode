package dev.improve.vrcode.cmux

import dev.improve.vrcode.TerminalSize
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalCreationTest {
  private val source = "term_source"
  private val created = JSONObject("""{"screen_id":"screen_new","terminal_id":"term_new"}""")
  private val requests = mutableListOf<Triple<String, JSONObject, Boolean>>()
  private val failures = mutableListOf<Exception>()
  private var process = JSONObject()
  private var readError: Exception? = null
  private var creationError: Exception? = null
  private var afterRead: () -> Unit = {}
  private var afterWorkspace: () -> Unit = {}
  private val creation = TerminalCreation({ operation, params, mutation ->
    requests += Triple(operation, params, mutation)
    when (operation) {
      "terminal.process.get" -> {
        assertEquals(source, params.getString("terminal"))
        assertFalse(mutation)
        afterRead()
        readError?.let { throw it }
        JSONObject().put("result", process)
      }
      "workspace.create" -> {
        assertTrue(mutation)
        assertEquals("empty", params.getString("initial_content"))
        afterWorkspace()
        JSONObject().put("result", JSONObject().put("value", JSONObject().put("workspace_id", "ws_new")))
      }
      "screen.create", "tab.create_terminal" -> {
        assertTrue(mutation)
        if (operation == "tab.create_terminal") assertEquals("ws_new", params.getString("workspace"))
        // A new floating screen, regardless of which existing pane has daemon focus.
        for (selector in listOf("screen", "pane", "tab", "terminal")) assertFalse(params.has(selector))
        creationError?.let { throw it }
        JSONObject().put("result", JSONObject().put("value", created))
      }
      else -> error("Unexpected operation: $operation")
    }
  }, failures::add)

  private fun open(terminal: String? = source, size: TerminalSize = TerminalSize(132, 43), isCurrent: () -> Boolean = { true }) = runBlocking {
    creation.create(terminal, "Codex", size, isCurrent)
  }

  private fun parameters() = requests.last().second

  @Test fun startsInTheForegroundProgramsDirectoryWithoutTypingIntoTheSource() {
    val directory = "/Users/me/项目 with 'quotes' and ${'$'}(literal)\nnext"
    process.put("cwd", "/old-shell-directory").put("foreground_cwd", directory)
    assertEquals(created, open())
    assertEquals(directory, parameters().getString("cwd"))
    assertEquals("Codex", parameters().getString("name"))
    assertEquals(132, parameters().getInt("cols"))
    assertEquals(43, parameters().getInt("rows"))
    assertEquals(listOf("terminal.process.get", "workspace.create", "tab.create_terminal"), requests.map { it.first })
  }

  @Test fun fallsBackToTheShellDirectoryForOlderServersAndUnknownForegroundDirectories() {
    for (foreground in listOf(null, JSONObject.NULL, "", "   ")) {
      process = JSONObject().put("cwd", "/current-shell-directory").put("foreground_cwd", foreground)
      open()
      assertEquals("/current-shell-directory", parameters().getString("cwd"))
    }
  }

  @Test fun inheritedDimensionsAreNotClampedToTheDefaultSizePreferences() {
    process.put("cwd", "/project")
    open(size = TerminalSize(228, 83))
    assertEquals(228, parameters().getInt("cols"))
    assertEquals(83, parameters().getInt("rows"))
  }

  @Test fun unknownDirectoriesUseTheDefaultWithoutPassingJsonNullAsAPath() {
    process.put("cwd", JSONObject.NULL).put("foreground_cwd", JSONObject.NULL)
    open()
    assertFalse(parameters().has("cwd"))
  }

  @Test fun noFocusedTerminalCreatesNormallyWithoutLookingUpAnotherTerminalsDirectory() {
    open(null)
    assertEquals(listOf("screen.create"), requests.map { it.first })
    assertFalse(parameters().has("cwd"))
  }

  @Test fun aClosedSourceStillAllowsANewTerminalWithTheDefaultDirectory() {
    readError = IOException("source terminal closed")
    assertEquals(created, open())
    assertEquals(listOf(readError), failures)
    assertFalse(parameters().has("cwd"))
  }

  @Test fun disconnectingWhileReadingDoesNotCreateInTheNextSession() {
    var current = true
    afterRead = { current = false }
    assertNull(open(isCurrent = { current }))
    assertEquals(listOf("terminal.process.get"), requests.map { it.first })
  }

  @Test fun cancellationDoesNotFallThroughToCreation() {
    readError = CancellationException("cancelled")
    assertThrows(CancellationException::class.java) { open() }
    assertTrue(failures.isEmpty())
    assertEquals(listOf("terminal.process.get"), requests.map { it.first })
  }

  @Test fun disconnectingWhileCreatingTheWorkspaceDoesNotLaunchATerminalInTheNextSession() {
    process.put("cwd", "/project")
    var current = true
    afterWorkspace = { current = false }
    assertNull(open(isCurrent = { current }))
    assertEquals(listOf("terminal.process.get", "workspace.create"), requests.map { it.first })
  }

  @Test fun aFailedCreationIsNeverRetried() {
    process.put("cwd", "/project")
    creationError = IOException("outcome unknown")
    assertThrows(IOException::class.java) { open() }
    assertEquals(1, requests.count { it.first == "tab.create_terminal" })
  }
}
