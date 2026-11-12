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

    fun scan(xml: ByteArray): AlightScan = scan(ByteArrayInputStream(xml))

    fun scan(input: InputStream): AlightScan {
        val root = readRoot(input)
        val report = ImportReport()
        val scene = readScene(root, report)
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

    private fun readScene(root: Element, report: ImportReport): AlightScene {
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
        walk(root, true)
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
