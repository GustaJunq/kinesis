package dev.kinesis.core.alight.cli

import dev.kinesis.core.alight.AlightFormatException
import dev.kinesis.core.alight.AlightImport
import dev.kinesis.core.alight.AlightMedia
import dev.kinesis.core.alight.AlightMediaMatcher
import dev.kinesis.core.alight.AlightScan
import dev.kinesis.core.alight.MatchKind
import dev.kinesis.core.alight.MediaCandidate
import dev.kinesis.core.alight.MediaMatch
import dev.kinesis.core.alight.ReportKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO

/**
 * `alight2kns`: Alight Motion `.xml` to Kinesis `.knsproj`, for scripts and agents.
 *
 *     alight2kns scan    project.xml [--media DIR]...
 *     alight2kns convert project.xml -o out.knsproj [--media DIR]... [--report report.json] [--allow-name-match]
 *
 * Output is JSON on stdout. Exit status: 0 done (a partial conversion is still 0: read the report), 1 the input is not a
 * valid Alight project or the output could not be written, 2 wrong usage.
 */
object Alight2Kns {
    @JvmStatic
    fun main(args: Array<String>) {
        System.exit(run(args, PrintStream(System.out, true, "UTF-8"), PrintStream(System.err, true, "UTF-8")))
    }

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.isEmpty() || args[0] !in setOf("scan", "convert")) { err.println(USAGE); return 2 }
        val cmd = args[0]
        var xml: File? = null; var output: File? = null; var report: File? = null
        val media = ArrayList<File>(); var allowName = false
        var i = 1
        while (i < args.size) {
            when (val a = args[i]) {
                "--media" -> { media += File(args.getOrNull(++i) ?: return usage(err, "--media precisa de um diretório")) }
                "-o", "--output" -> output = File(args.getOrNull(++i) ?: return usage(err, "-o precisa de um arquivo"))
                "--report" -> report = File(args.getOrNull(++i) ?: return usage(err, "--report precisa de um arquivo"))
                "--allow-name-match" -> allowName = true
                else -> if (a.startsWith("-")) return usage(err, "opção desconhecida: $a") else if (xml == null) xml = File(a) else return usage(err, "arquivo a mais: $a")
            }
            i++
        }
        val src = xml ?: return usage(err, "faltou o arquivo .xml")
        if (cmd == "convert" && output == null) return usage(err, "convert precisa de -o")
        val scan: AlightScan = try {
            AlightImport.scan(src.readBytes())
        } catch (e: AlightFormatException) { err.println("erro: ${e.message}"); return 1 }
        catch (e: java.io.IOException) { err.println("erro: não foi possível ler ${src.path}: ${e.message}"); return 1 }

        val candidates = media.flatMap { candidatesIn(it) }
        val matches = AlightMediaMatcher.matchAll(scan.scene.media, candidates)
        if (cmd == "scan") { out.println(pretty(scanJson(scan, matches))); return 0 }

        val usable = matches.filterValues { it.automatic || allowName }
        val conversion = AlightImport.convert(scan, usable.keys)
        try {
            output!!.parentFile?.mkdirs()
            output.outputStream().buffered().use { o ->
                AlightImport.write(conversion, { uri -> usable[uri]?.candidate?.key?.let { File(it).inputStream() } }, o)
            }
        } catch (e: AlightFormatException) { err.println("erro: ${e.message}"); output!!.delete(); return 1 }
        catch (e: java.io.IOException) { err.println("erro: não foi possível escrever ${output!!.path}: ${e.message}"); output.delete(); return 1 }
        val json = JsonObject(
            mapOf(
                "output" to JsonPrimitive(output.path),
                "layers" to JsonPrimitive(count(conversion.project.layers)),
                "duration" to JsonPrimitive(conversion.project.composition.duration),
                "report" to reportJson(conversion.report),
            ),
        )
        report?.writeText(pretty(json) + "\n")
        out.println(pretty(json))
        return 0
    }

    private fun count(layers: List<dev.kinesis.core.model.Layer>): Int = layers.sumOf { 1 + count(it.layers) }
    private fun usage(err: PrintStream, msg: String): Int { err.println("uso: $msg\n$USAGE"); return 2 }

    private fun candidatesIn(dir: File): List<MediaCandidate> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.map { f ->
            var w: Int? = null; var h: Int? = null
            if (f.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp")) {
                runCatching {
                    ImageIO.createImageInputStream(f)?.use { s ->
                        val r = ImageIO.getImageReaders(s).asSequence().firstOrNull() ?: return@use
                        r.input = s; w = r.getWidth(0); h = r.getHeight(0); r.dispose()
                    }
                }
            }
            MediaCandidate(f.path, f.name, f.length(), w, h)
        }.toList()
    }

    private fun scanJson(scan: AlightScan, matches: Map<String, MediaMatch>): JsonElement {
        val s = scan.scene
        fun layers(ls: List<dev.kinesis.core.alight.AlightLayer>): Int = ls.sumOf { 1 + (it.scene?.let { sc -> layers(sc.layers) } ?: 0) }
        return JsonObject(
            mapOf(
                "title" to JsonPrimitive(s.title), "width" to JsonPrimitive(s.width), "height" to JsonPrimitive(s.height),
                "fps" to JsonPrimitive(s.fps), "durationMs" to JsonPrimitive(s.totalTimeMs), "layers" to JsonPrimitive(layers(s.layers)),
                "media" to JsonArray(s.media.map { mediaJson(it, matches[it.uri]) }),
            ),
        )
    }

    private fun mediaJson(m: AlightMedia, match: MediaMatch?): JsonElement = JsonObject(
        mapOf(
            "name" to JsonPrimitive(m.filename),
            "kind" to JsonPrimitive(m.kind.name.lowercase()),
            "sizeBytes" to (m.sizeBytes?.let { JsonPrimitive(it) } ?: JsonNull),
            "width" to (m.width?.let { JsonPrimitive(it) } ?: JsonNull),
            "height" to (m.height?.let { JsonPrimitive(it) } ?: JsonNull),
            "status" to JsonPrimitive(when (match?.kind) { MatchKind.EXACT -> "found"; MatchKind.SIZE -> "found-by-size"; MatchKind.NAME -> "name-only"; null -> "missing" }),
            "file" to (match?.candidate?.key?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
    )

    private fun reportJson(r: dev.kinesis.core.alight.ImportReport): JsonElement {
        val byKind = ReportKind.entries.associate { k -> k.name.lowercase() to JsonPrimitive(r.count(k)) }
        return JsonObject(
            mapOf(
                "counts" to JsonObject(byKind),
                "entries" to JsonArray(
                    r.entries.filter { it.kind != ReportKind.CONVERTED }.map {
                        JsonObject(mapOf("kind" to JsonPrimitive(it.kind.name.lowercase()), "where" to JsonPrimitive(it.where), "message" to JsonPrimitive(it.message)))
                    },
                ),
            ),
        )
    }

    private val printer = Json { prettyPrint = true }
    private fun pretty(e: JsonElement) = printer.encodeToString(JsonElement.serializer(), e)

    private const val USAGE = """alight2kns scan    projeto.xml [--media DIR]...
alight2kns convert projeto.xml -o saida.knsproj [--media DIR]... [--report relatorio.json] [--allow-name-match]"""
}
