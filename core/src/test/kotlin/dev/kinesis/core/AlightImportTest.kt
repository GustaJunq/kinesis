package dev.kinesis.core

import dev.kinesis.core.alight.AlightImport
import dev.kinesis.core.alight.AlightMedia
import dev.kinesis.core.alight.AlightMediaKind
import dev.kinesis.core.alight.AlightMediaMatcher
import dev.kinesis.core.alight.MatchKind
import dev.kinesis.core.alight.MediaCandidate
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.alight.cli.Alight2Kns
import dev.kinesis.core.format.KnsprojArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** Step 8 of the Alight Motion importer, the part that does not need Android: media matching, packing and the CLI. */
class AlightImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixtureXml get() = javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()
    private val m = AlightMedia("content://x/1", "a.png", "image/png", AlightMediaKind.IMAGE, 1000L, 20, 10, null)

    @Test
    fun matcherPrefersSizeWithNameOrResolutionAndNeverTrustsAFileOfAnotherSize() {
        val renamed = MediaCandidate("r", "other.png", 1000L, 20, 10)
        val sameName = MediaCandidate("n", "A.PNG", 999L, 20, 10)
        val sizeOnly = MediaCandidate("s", "x.bin", 1000L)
        assertEquals(MatchKind.EXACT, AlightMediaMatcher.match(m, listOf(renamed))!!.kind)       // size + pixels, renamed
        assertEquals(MatchKind.NAME, AlightMediaMatcher.match(m, listOf(sameName))!!.kind)        // other size: only a suggestion
        assertTrue(!AlightMediaMatcher.match(m, listOf(sameName))!!.automatic)
        assertEquals(MatchKind.SIZE, AlightMediaMatcher.match(m, listOf(sizeOnly))!!.kind)
        assertEquals("r", AlightMediaMatcher.match(m, listOf(sameName, sizeOnly, renamed))!!.candidate.key)
        assertNull(AlightMediaMatcher.match(m, listOf(MediaCandidate("z", "z.png", 5L, 1, 1))))
    }

    @Test
    fun theSamePixelSizeIsNotEnoughWithoutTheByteSize() {
        assertNull(AlightMediaMatcher.match(m, listOf(MediaCandidate("p", "p.png", 5L, 20, 10))))
    }

    @Test
    fun importPacksAProjectThatReadsBack() {
        val scan = AlightImport.scan(fixtureXml)
        val c = AlightImport.convert(scan, scan.scene.media.map { it.uri }.toSet())
        val out = ByteArrayOutputStream()
        // Every file is a few bytes: the packer only needs the stream.
        AlightImport.write(c, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, out)
        val read = KnsprojArchive.read(ByteArrayInputStream(out.toByteArray())) { _, d -> d.readBytes() }
        assertEquals(c.project.layers.size, read.project.layers.size)
        assertEquals(c.project.assets.size, read.project.assets.size)
    }

    @Test
    fun aMissingFileFailsTheWriteInsteadOfSavingAHalfProject() {
        val scan = AlightImport.scan(fixtureXml)
        val c = AlightImport.convert(scan, scan.scene.media.map { it.uri }.toSet())
        var failed = false
        try { AlightImport.write(c, { null }, ByteArrayOutputStream()) } catch (e: java.io.IOException) { failed = true }
        assertTrue(failed)
    }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = Alight2Kns.run(arrayOf(*args), PrintStream(o), PrintStream(e))
        return Triple(code, o.toString(), e.toString())
    }

    /** Files with the exact byte sizes of the project's media but other names: what the size match is for. */
    private fun mediaDir(skipLast: Int = 0): File {
        val dir = tmp.newFolder("midias")
        val media = AlightImport.scan(fixtureXml).scene.media
        for ((i, md) in media.dropLast(skipLast).withIndex()) File(dir, "renomeado-$i.bin").writeBytes(ByteArray(md.sizeBytes!!.toInt()) { (it % 251).toByte() })
        return dir
    }

    @Test
    fun cliScanReportsWhichMediaWereFound() {
        val xml = tmp.newFile("p.xml").apply { writeBytes(fixtureXml) }
        val (code, out, _) = run("scan", xml.path, "--media", mediaDir(skipLast = 2).path)
        assertEquals(0, code)
        assertEquals(13, Regex("\"status\": \"found-by-size\"").findAll(out).count())
        assertEquals(2, Regex("\"status\": \"missing\"").findAll(out).count())
    }

    @Test
    fun cliConvertWritesAProjectAndAPartialReport() {
        val xml = tmp.newFile("p.xml").apply { writeBytes(fixtureXml) }
        val outFile = File(tmp.root, "saida.knsproj"); val rep = File(tmp.root, "r.json")
        val (code, out, err) = run("convert", xml.path, "-o", outFile.path, "--media", mediaDir(skipLast = 2).path, "--report", rep.path)
        assertEquals(err, 0, code)
        assertTrue(outFile.length() > 0 && rep.exists())
        val read = KnsprojArchive.read(outFile.inputStream()) { _, d -> d.readBytes() }
        assertEquals(11, read.project.layers.size)
        assertTrue("missing media must be in the report", out.contains("missing_media"))
        assertNotNull(read.project.assets.firstOrNull())
    }

    @Test
    fun cliGivesTypedErrorsNotCrashes() {
        assertEquals(2, run().first)
        assertEquals(2, run("convert", "x.xml").first)                       // no -o
        val bad = tmp.newFile("bad.xml").apply { writeText("<nada/>") }
        val (code, _, err) = run("scan", bad.path)
        assertEquals(1, code); assertTrue(err.startsWith("erro:"))
        assertEquals(1, run("scan", File(tmp.root, "nao-existe.xml").path).first)
    }

    @Test
    fun noMediaStillConvertsWithPlaceholders() {
        val xml = tmp.newFile("p.xml").apply { writeBytes(fixtureXml) }
        val outFile = File(tmp.root, "s.knsproj")
        val (code, _, _) = run("convert", xml.path, "-o", outFile.path)
        assertEquals(0, code)
        val read = KnsprojArchive.read(outFile.inputStream()) { _, d -> d.readBytes() }
        assertTrue(read.project.assets.isEmpty())
        assertTrue(AlightImport.convert(AlightImport.scan(fixtureXml), emptySet()).report.count(ReportKind.MISSING_MEDIA) > 0)
    }
}
