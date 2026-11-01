package dev.improve.vrcode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

data class CmuxField(
  val name: String, val required: Boolean, val type: JSONObject, val selector: Boolean = false,
  val description: String = "",
) {
  val label: String get() = name.replace('_', ' ').replaceFirstChar(Char::uppercase)
  val nullable: Boolean get() = type.optString("kind") == "nullable"
  val inputType: JSONObject get() = if (nullable) type.getJSONObject("value") else type
}

data class CmuxOperation(val name: String, val kind: String, val fields: List<CmuxField>, val constraints: List<String>) {
  val label: String get() = name.split('.').joinToString(" · ") { it.replace('_', ' ').replaceFirstChar(Char::uppercase) }
  val mutation: Boolean get() = kind == "mutation"
}

/** Forms are generated from the exact public cmux catalog used by the Mac's operation allowlist. */
class CmuxCatalog(context: Context) {
  private val catalog = JSONObject(context.assets.open("cmux-catalog.json").bufferedReader().use { it.readText() })
  val revision: String = catalog.getString("revision")
  private val types = catalog.getJSONObject("types")
  val operations: List<CmuxOperation> = catalog.getJSONObject("operations").let { ops ->
    ops.keys().asSequence().map { name ->
      val op = ops.getJSONObject(name)
      val params = op.getJSONObject("params")
      val selectors = params.getJSONObject("selectors")
      val fields = params.getJSONObject("fields")
      CmuxOperation(name, op.getString("class"), buildList {
        for (key in selectors.keys()) if (key != "machine" && key != "session") {
          add(CmuxField(key, selectors.getString(key) == "required", JSONObject().put("kind", "resource_id").put("resource", key), true))
        }
        for (key in fields.keys()) {
          val field = fields.getJSONObject(key)
          add(CmuxField(key, field.getBoolean("required"), resolve(field.getJSONObject("type")), description = field.optString("description")))
        }
      }, params.optJSONArray("constraints")?.strings() ?: emptyList())
    }.sortedBy { it.name }.toList()
  }

  fun operation(name: String): CmuxOperation = operations.first { it.name == name }

  private fun resolve(type: JSONObject): JSONObject = when (type.optString("kind")) {
    "ref" -> types.optJSONObject(type.optString("name"))?.let(::resolve) ?: type
    "nullable" -> JSONObject(type.toString()).put("value", resolve(type.getJSONObject("value")))
    else -> type
  }

  /** Keep only the deepest opaque selector; never borrow the Mac frontend's current pane/tab. */
  fun defaults(op: CmuxOperation, target: Map<String, String>): Map<String, String> = buildMap {
    val fields = op.fields.associateBy { it.name }
    val deepest = listOf("terminal", "browser", "tab", "pane", "screen", "workspace")
      .firstOrNull { it in fields && it in target }
    if (deepest != null) put(deepest, target.getValue(deepest))
    if (op.kind == "stream_open") put("stream_id", "stream_" + UUID.randomUUID().toString().replace("-", ""))
    for (field in op.fields) if (field.required && field.selector && field.name in target) put(field.name, target.getValue(field.name))
    for (field in op.fields) if (field.required && !field.selector && field.inputType.optString("kind") == "primitive" &&
      field.inputType.optString("name") == "string" && field.name !in this) put(field.name, "")
  }

  fun parameters(op: CmuxOperation, values: Map<String, String>, nullFields: Set<String> = emptySet()): JSONObject = JSONObject().apply {
    for (field in op.fields) {
      if (field.name in nullFields) {
        require(field.nullable) { "${field.label} cannot be cleared" }
        put(field.name, JSONObject.NULL)
        continue
      }
      val value = values[field.name]
      require(value != null || !field.required) { "${field.label} is required" }
      if (value == null) continue
      val kind = field.inputType.optString("kind")
      val primitive = field.inputType.optString("name")
      val parsed: Any = when {
        field.selector || kind == "resource_id" -> value.also { require(it.isNotBlank()) { "Choose ${field.label}" } }
        kind == "enum" -> value.also { require(it in field.inputType.getJSONArray("values").strings()) { "Choose ${field.label}" } }
        kind == "primitive" && primitive == "boolean" -> value.toBooleanStrict()
        kind == "primitive" && primitive in listOf("int32", "uint16", "uint32", "int64", "uint64") -> value.toLong()
        kind == "primitive" && primitive == "float64" -> value.toDouble().also { require(it.isFinite()) }
        kind == "primitive" -> value // decimal counters and names preserve exact bytes
        else -> {
          val parser = JSONTokener(value)
          val parsed = parser.nextValue()
          require(parser.nextClean() == '\u0000') { "${field.label} must contain one JSON value" }
          if (kind in listOf("object", "map")) require(parsed is JSONObject) { "${field.label} must be a JSON object" }
          if (kind == "array") require(parsed is JSONArray) { "${field.label} must be a JSON array" }
          parsed
        }
      }
      put(field.name, parsed)
    }
  }
}

fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

data class CmuxResource(val kind: String, val id: String, val label: String)

fun cmuxResources(snapshot: JSONObject?): List<CmuxResource> = buildList {
  for ((plural, singular) in listOf("workspaces" to "workspace", "screens" to "screen", "panes" to "pane",
    "tabs" to "tab", "terminals" to "terminal", "browsers" to "browser", "clients" to "client", "sidebar_views" to "sidebar_view")) {
    val resources = snapshot?.optJSONArray(plural) ?: continue
    for (i in 0 until resources.length()) {
      val item = resources.getJSONObject(i)
      val id = item.getString("id")
      val name = (if (!item.isNull("name")) item.optString("name") else item.optString("title"))
        .takeIf { it.isNotBlank() } ?: id.takeLast(8)
      add(CmuxResource(singular, id, "$singular · $name"))
    }
  }
}
