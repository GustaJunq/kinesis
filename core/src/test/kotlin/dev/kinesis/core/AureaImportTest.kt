package dev.kinesis.core

import dev.kinesis.core.format.AureaFormatException
import dev.kinesis.core.format.AureaImport
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Primitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.aureaproj` import, against a project saved by Aurea 0.2.2 (one cube, 960×720, 24 fps, 10 s). */
class AureaImportTest {
    private fun sample() = javaClass.getResourceAsStream("/aurea/cubo.aureaproj")!!.readBytes()

    @Test
    fun cubeProjectIsConverted() {
        val r = AureaImport.read(sample())
        val p = r.project
        assertEquals("Projeto 3", p.meta.title)
        assertEquals(960, p.composition.width)
        assertEquals(720, p.composition.height)
        assertEquals(24.0, p.composition.fps, 0.0)
        assertEquals(10.0, p.composition.duration, 1e-9)
        assertEquals(1, p.layers.size)
        val cube = p.layers.single()
        assertEquals(LayerType.OBJECT3D, cube.type)
        assertEquals("Cubo", cube.name)
        assertEquals(Primitive.CUBE, cube.object3d!!.primitive)
        assertEquals(0.0, cube.start, 1e-9)
        assertEquals(10.0, cube.duration, 1e-9)
        // Centred in the Aurea composition (480, 360) = origin in Kinesis.
        val pos = cube.transform3d.position.value
        assertEquals(0f, pos.x, 1e-3f); assertEquals(0f, pos.y, 1e-3f)
        assertTrue(r.warnings.any { it.contains("uma cor por objeto") })
    }

    @Test
    fun convertedProjectIsValidKnsproj() {
        val p = AureaImport.read(sample()).project
        val report = ProjectValidator.validate(KnsprojJson.decode(KnsprojJson.encode(p)))
        assertTrue(report.errors.joinToString(), report.isValid)
    }

    @Test(expected = AureaFormatException::class)
    fun corruptedSectionIsRejected() {
        val zip = sample()
        // Flip a byte inside project.aurea (stored uncompressed in this sample) after the header.
        val at = String(zip, Charsets.ISO_8859_1).indexOf("Composicao principal")
        zip[at] = 'X'.code.toByte()
        AureaImport.read(zip)
    }

    @Test
    fun recognisesAureaEntries() {
        assertTrue(AureaImport.isAureaEntry("project.aurea"))
        assertTrue(!AureaImport.isAureaEntry("project.json"))
    }
}
