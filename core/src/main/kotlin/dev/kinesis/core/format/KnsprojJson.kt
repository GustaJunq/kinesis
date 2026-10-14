package dev.kinesis.core.format

import dev.kinesis.core.model.FORMAT_ID
import dev.kinesis.core.model.FORMAT_VERSION
import dev.kinesis.core.model.KnsProject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown when a document cannot be read as a Kinesis project. The message is meant for users. */
class KnsprojFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** JSON encoding of `project.json`. */
object KnsprojJson {

    val json: Json = Json {
        // Forward compatibility: fields added by newer minor revisions are ignored.
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
    }

    /** Pretty-printed `project.json` (2-space indent, short numeric arrays on one line, trailing newline). */
    fun encode(project: KnsProject): String = JsonPretty.print(encodeToElement(project)) + "\n"

    fun encodeToElement(project: KnsProject): JsonElement = json.encodeToJsonElement(KnsProject.serializer(), project)

    /** Parses, checks the header, migrates older versions and decodes a project. */
    fun decode(text: String): KnsProject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw KnsprojFormatException("project.json is not valid JSON: ${e.message}", e)
        }
        return decodeElement(element)
    }

    fun decodeElement(element: JsonElement): KnsProject {
        val root = element as? JsonObject ?: throw KnsprojFormatException("project.json must contain a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.content
        if (format != FORMAT_ID) {
            throw KnsprojFormatException("Not a Kinesis project: \"format\" must be \"$FORMAT_ID\" (found ${format ?: "nothing"})")
        }
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw KnsprojFormatException("\"formatVersion\" is missing or not an integer")
        if (version > FORMAT_VERSION) {
            throw KnsprojFormatException(
                "This project uses format version $version, but this version of Kinesis reads up to $FORMAT_VERSION. Update Kinesis to open it.",
            )
        }
        if (version < 1) throw KnsprojFormatException("Invalid formatVersion $version")
        val migrated = Migrations.migrate(root, version)
        return try {
            json.decodeFromJsonElement(KnsProject.serializer(), migrated)
        } catch (e: SerializationException) {
            throw KnsprojFormatException("Invalid project: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw KnsprojFormatException("Invalid project: ${e.message}", e)
        }
    }
}

/** Human-friendly JSON printer: arrays of numbers/strings stay on one line, integral numbers drop ".0". */
object JsonPretty {
    fun print(element: JsonElement): String = StringBuilder().also { write(it, element, 0) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement, indent: Int) {
        when (e) {
            is JsonObject -> {
                if (e.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val entries = e.entries.toList()
                entries.forEachIndexed { i, (k, v) ->
                    pad(sb, indent + 1)
                    sb.append(JsonPrimitive(k).toString()).append(": ")
                    write(sb, v, indent + 1)
                    if (i < entries.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent)
                sb.append('}')
            }
            is kotlinx.serialization.json.JsonArray -> {
                if (e.isEmpty()) { sb.append("[]"); return }
                if (e.all { it is JsonPrimitive }) {
                    sb.append('[')
                    e.forEachIndexed { i, v -> if (i > 0) sb.append(", "); write(sb, v, indent) }
                    sb.append(']')
                    return
                }
                sb.append("[\n")
                e.forEachIndexed { i, v ->
                    pad(sb, indent + 1)
                    write(sb, v, indent + 1)
                    if (i < e.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent)
                sb.append(']')
            }
            is JsonPrimitive -> sb.append(number(e) ?: e.toString())
        }
    }

    private fun number(p: JsonPrimitive): String? {
        if (p.isString) return null
        val text = p.content
        if (text == "true" || text == "false" || text == "null") return null
        val d = text.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else text
    }

    private fun pad(sb: StringBuilder, n: Int) {
        repeat(n) { sb.append("  ") }
    }
}

/**
 * Upgrades older documents to [FORMAT_VERSION], one version at a time. Each step receives a document of
 * version N and returns one of version N + 1. Version 1 is the first public version, so there are no steps yet.
 */
object Migrations {
    private val steps: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    fun migrate(root: JsonObject, fromVersion: Int): JsonObject {
        var doc = root
        var v = fromVersion
        while (v < FORMAT_VERSION) {
            val step = steps[v] ?: throw KnsprojFormatException("No migration from format version $v")
            doc = step(doc)
            v++
        }
        return JsonObject(doc + ("formatVersion" to JsonPrimitive(FORMAT_VERSION)))
    }
}
