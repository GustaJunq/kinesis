package dev.kinesis.core.alight

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Result of [AlightParser.scan]: the composition header and the media list, plus what was odd about them. */
class AlightScan(val scene: AlightScene, val report: ImportReport)

/**
 * Reads the XML that Alight Motion exports. Parsing is defensive: no DTDs or external entities, a size limit, typed
 * errors ([AlightFormatException]) instead of crashes, and anything unexpected goes to the [ImportReport].
 */
object AlightParser {
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_DEPTH = 24

    fun scan(xml: ByteArray): AlightScan = scan(ByteArrayInputStream(xml))

    fun scan(input: InputStream): AlightScan {
        val root = readRoot(input)
        val report = ImportReport()
        val scene = readScene(root, report, 0)
        return AlightScan(scene, report)
    }

    private fun readRoot(input: InputStream): Element {
        val bytes = input.readLimited()
        if (bytes.isEmpty()) throw AlightFormatException("o arquivo está vazio")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // Hardening against XXE and entity bombs; some parsers (Android's) do not know every feature.
            for ((f, v) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )) runCatching { setFeature(f, v) }
            runCatching { isXIncludeAware = false }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: SAXException) {
            throw AlightFormatException("XML inválido: ${e.message?.take(160)}")
        } catch (e: java.io.IOException) {
            throw AlightFormatException("não foi possível ler o XML: ${e.message}")
        }
        val root = doc.documentElement ?: throw AlightFormatException("XML sem elemento raiz")
        if (root.tagName != "scene") throw AlightFormatException("não é um projeto do Alight Motion (raiz <${root.tagName}>, esperado <scene>)")
        return root
    }

    private fun InputStream.readLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw AlightFormatException("XML grande demais (limite ${MAX_BYTES / 1024 / 1024} MB)")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readScene(root: Element, report: ImportReport, depth: Int): AlightScene {
        if (depth > MAX_DEPTH) throw AlightFormatException("pré-composições aninhadas demais (limite $MAX_DEPTH)")
        fun int(name: String, default: Int, min: Int, max: Int): Int {
            val v = root.getAttribute(name).toIntOrNull()
            if (v == null || v !in min..max) {
                report.add(ReportKind.WARNING, "scene@$name", "valor ausente ou inválido (\"${root.getAttribute(name)}\"), usado $default")
                return default
            }
            return v
        }
        val fps = root.getAttribute("fps").toDoubleOrNull()?.takeIf { it in 1.0..240.0 } ?: 30.0.also {
            report.add(ReportKind.WARNING, "scene@fps", "valor ausente ou inválido, usado 30")
        }
        val total = root.getAttribute("totalTime").toLongOrNull()?.takeIf { it > 0 } ?: 1000L.also {
            report.add(ReportKind.WARNING, "scene@totalTime", "duração ausente ou inválida, usado 1 s")
        }
        val media = LinkedHashMap<String, AlightMedia>()
        val bookmarks = ArrayList<Long>()
        // Media lives in the root scene; nested scenes are scanned too so a pointer is never missed.
        fun walk(e: Element, isRoot: Boolean) {
            var c: Node? = e.firstChild
            while (c != null) {
                if (c is Element) {
                    when (c.tagName) {
                        "media" -> readMedia(c, report)?.let { m ->
                            if (m.uri in media) report.add(ReportKind.WARNING, "media", "mídia repetida ignorada: ${m.filename}") else media[m.uri] = m
                        }
                        "bookmark" -> if (isRoot) c.getAttribute("t").toLongOrNull()?.let { bookmarks += it }
                        else -> if (c.tagName == "scene" || c.tagName == "embedScene" || c.tagName == "shape") walk(c, false)
                    }
                }
                c = c.nextSibling
            }
        }
        if (depth == 0) walk(root, true)
        return AlightScene(
            title = root.getAttribute("title"),
            width = int("width", 1080, 16, 16384),
            height = int("height", 1920, 16, 16384),
            fps = fps,
            totalTimeMs = total,
            background = root.getAttribute("bgcolor").ifBlank { "#ff000000" },
            exportedBy = root.getAttribute("am").ifBlank { null },
            media = media.values.toList(),
            bookmarksMs = bookmarks.sorted(),
            layers = readLayers(root, report, depth),
        )
    }

    private fun children(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var c: Node? = e.firstChild
        while (c != null) { if (c is Element && c.tagName == name) out += c; c = c.nextSibling }
        return out
    }

    private fun numbers(text: String): List<Double>? {
        val parts = text.split(',').map { it.trim().toDoubleOrNull() }
        return if (parts.isEmpty() || parts.any { it == null || !it.isFinite() }) null else parts.map { it!! }
    }

    private fun readProp(e: Element?, where: String, report: ImportReport): AlightProp? {
        e ?: return null
        val kfs = children(e, "kf").mapNotNull { k ->
            val v = numbers(k.getAttribute("v"))
            val t = k.getAttribute("t").toDoubleOrNull()
            if (v == null || t == null) { report.add(ReportKind.WARNING, where, "keyframe inválido ignorado"); null }
            else AlightKeyframe(t, v, k.getAttribute("e").ifBlank { null })
        }.sortedBy { it.t }
        if (kfs.isNotEmpty()) return AlightProp(keyframes = kfs)
        val v = numbers(e.getAttribute("value"))
        if (v == null && e.hasAttribute("value")) report.add(ReportKind.WARNING, where, "valor inválido \"${e.getAttribute("value")}\", ignorado")
        return v?.let { AlightProp(static = it) }
    }

    private fun readLayers(scene: Element, report: ImportReport, depth: Int): List<AlightLayer> {
        val out = ArrayList<AlightLayer>()
        var c: Node? = scene.firstChild
        while (c != null) {
            if (c is Element) {
                val kind = when (c.tagName) { "shape" -> AlightLayerKind.SHAPE; "embedScene" -> AlightLayerKind.EMBED; "audio" -> AlightLayerKind.AUDIO; else -> null }
                if (kind != null) readLayer(c, kind, report, depth)?.let { out += it }
            }
            c = c.nextSibling
        }
        return out
    }

    private fun readLayer(e: Element, kind: AlightLayerKind, report: ImportReport, depth: Int): AlightLayer? {
        val id = e.getAttribute("id")
        val label = e.getAttribute("label").ifBlank { id }
        val start = e.getAttribute("startTime").toLongOrNull()
        val end = e.getAttribute("endTime").toLongOrNull()
        if (id.isBlank() || start == null || end == null || end <= start) {
            report.add(ReportKind.WARNING, "layer:$label", "camada sem id ou com tempos inválidos ignorada")
            return null
        }
        val t = children(e, "transform").firstOrNull()
        fun prop(name: String) = readProp(t?.let { children(it, name).firstOrNull() }, "layer:$label/$name", report)
        val size = children(e, "property").firstOrNull { it.getAttribute("name") == "size" }?.let { numbers(it.getAttribute("value")) }
        return AlightLayer(
            id = id, label = label, kind = kind, startMs = start, endMs = end,
            parentId = e.getAttribute("parent").ifBlank { null },
            fillType = e.getAttribute("fillType").ifBlank { null },
            mediaUri = listOf("fillImage", "fillVideo", "src").map { e.getAttribute(it) }.firstOrNull { it.isNotBlank() },
            mediaFillMode = e.getAttribute("mediaFillMode").ifBlank { null },
            size = size,
            inTimeMs = e.getAttribute("inTime").toLongOrNull(),
            outTimeMs = e.getAttribute("outTime").toLongOrNull(),
            speed = e.getAttribute("speed").toDoubleOrNull(),
            scene = if (kind == AlightLayerKind.EMBED) children(e, "scene").firstOrNull()?.let { readScene(it, report, depth + 1) } else null,
            linkId = e.getAttribute("link").ifBlank { null },
            shapeType = if (kind == AlightLayerKind.SHAPE) e.getAttribute("s").ifBlank { null } else null,
            effects = children(e, "effect").map { fx ->
                val fid = fx.getAttribute("id")
                val props = LinkedHashMap<String, AlightProp>(); val text = LinkedHashMap<String, String>()
                for (pe in children(fx, "property")) {
                    val n = pe.getAttribute("name")
                    if (n.isBlank()) continue
                    if (pe.getAttribute("type") in setOf("bool", "color", "string")) text[n] = pe.getAttribute("value")
                    else readProp(pe, "layer:$label/effect/${fid.substringAfterLast('.')}/$n", report)?.let { props[n] = it }
                }
                AlightEffect(fid, props, text)
            },
            shadows = children(e, "shadow").map { sh ->
                fun sp(n: String) = children(sh, n).firstOrNull()?.let { readProp(it, "layer:$label/shadow/$n", report) }
                AlightShadow(
                    direction = sh.getAttribute("direction").ifBlank { "outside" },
                    color = children(sh, "color").firstOrNull()?.getAttribute("value")?.ifBlank { null } ?: "#ff000000",
                    opacity = sp("opacity"), size = sp("size"), offset = sp("offset"),
                )
            },
            gain = children(e, "gain").firstOrNull()?.let { readProp(it, "layer:$label/gain", report) },
            transform = AlightTransform(prop("location"), prop("scale"), prop("rotation"), prop("pivot"), prop("opacity")),
        )
    }

    private fun readMedia(e: Element, report: ImportReport): AlightMedia? {
        val uri = e.getAttribute("uri")
        if (uri.isBlank()) { report.add(ReportKind.WARNING, "media", "mídia sem uri ignorada"); return null }
        val mime = e.getAttribute("type")
        val kind = when {
            mime.startsWith("image/") -> AlightMediaKind.IMAGE
            mime.startsWith("video/") -> AlightMediaKind.VIDEO
            mime.startsWith("audio/") -> AlightMediaKind.AUDIO
            else -> AlightMediaKind.OTHER
        }
        if (kind == AlightMediaKind.OTHER) report.add(ReportKind.WARNING, "media", "tipo \"$mime\" desconhecido: ${e.getAttribute("filename")}")
        return AlightMedia(
            uri = uri,
            filename = e.getAttribute("filename").ifBlank { e.getAttribute("title") }.ifBlank { uri.substringAfterLast('/') },
            mimeType = mime,
            kind = kind,
            sizeBytes = e.getAttribute("size").toLongOrNull(),
            width = e.getAttribute("width").toIntOrNull(),
            height = e.getAttribute("height").toIntOrNull(),
            durationUs = e.getAttribute("duration").toLongOrNull(),
        )
    }
}
