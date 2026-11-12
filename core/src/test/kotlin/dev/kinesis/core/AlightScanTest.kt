package dev.kinesis.core

import dev.kinesis.core.alight.AlightFormatException
import dev.kinesis.core.alight.AlightMediaKind
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Step 1 of the Alight Motion importer: composition header and media list of `novo-projeto-185.xml` (Alight 5.0.83). */
class AlightScanTest {
    private fun fixture() = javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()

    @Test
    fun compositionHeader() {
        val s = AlightParser.scan(fixture()).scene
        assertEquals("Novo projeto 185", s.title)
        assertEquals(1440, s.width)
        assertEquals(1080, s.height)
        assertEquals(30.0, s.fps, 0.0)
        assertEquals(19_865L, s.totalTimeMs)
        assertEquals("#ff000000", s.background)
        assertTrue(s.exportedBy!!.startsWith("com.alightcreative.motion/5.0.83"))
        // 17 bookmarks in the file; one belongs to a nested scene and is not a marker of the main timeline.
        assertEquals(16, s.bookmarksMs.size)
        assertEquals(s.bookmarksMs.sorted(), s.bookmarksMs)
    }

    /** The file lists 15 media (the task said 14): 13 images and 2 videos, every one referenced by a layer. */
    @Test
    fun mediaList() {
        val scan = AlightParser.scan(fixture())
        val m = scan.scene.media
        assertEquals(15, m.size)
        assertEquals(13, m.count { it.kind == AlightMediaKind.IMAGE })
        assertEquals(2, m.count { it.kind == AlightMediaKind.VIDEO })
        assertEquals(15, m.map { it.uri }.toSet().size)
        val video = m.first { it.filename == "db8765a076ac99e592132cf7d6d4b851.mp4" }
        assertEquals("content://media/external/video/media/127922", video.uri)
        assertEquals(576, video.width); assertEquals(1024, video.height)
        assertEquals(1_614_601L, video.sizeBytes)
        // Alight writes durations in microseconds: 19 867 000 µs for a 19 865 ms scene.
        assertEquals(19_867_000L, video.durationUs)
        assertEquals(0, scan.report.entries.size)
    }

    @Test
    fun malformedInputGivesTypedErrors() {
        fun bad(xml: String): String {
            try { AlightParser.scan(xml.toByteArray()) } catch (e: AlightFormatException) { return e.message.orEmpty() }
            fail("accepted: $xml")
            return ""
        }
        assertTrue(bad("").contains("vazio"))
        assertTrue(bad("<scene").contains("XML inválido"))
        assertTrue(bad("<project/>").contains("não é um projeto do Alight"))
        // Entities must not be expanded: a DTD is refused or ignored, never followed.
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><scene title=\"&x;\" width=\"10\" height=\"10\"/>"
        val r = runCatching { AlightParser.scan(xxe.toByteArray()) }
        assertTrue(r.isFailure && r.exceptionOrNull() is AlightFormatException || r.getOrNull()!!.scene.title != "root:")
    }

    @Test
    fun oddValuesBecomeReportEntriesNotCrashes() {
        val s = AlightParser.scan("<scene title=\"t\" width=\"abc\" fps=\"0\"><media uri=\"u\" type=\"text/x\" filename=\"f\"/></scene>".toByteArray())
        assertEquals(1080, s.scene.width)
        assertEquals(30.0, s.scene.fps, 0.0)
        assertTrue(s.report.count(ReportKind.WARNING) >= 4)
        assertEquals(AlightMediaKind.OTHER, s.scene.media.single().kind)
    }
}
