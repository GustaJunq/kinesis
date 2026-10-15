package dev.kinesis.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.geom.GltfLoader
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.format.Severity
import dev.kinesis.core.model.FORMAT_VERSION
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.scene.SceneEvaluator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the Kotlin implementation, the official JSON Schema, the examples and the Python CLI in agreement.
 * The Python side runs the same fixtures in tools/tests/test_knsproj.py.
 */
class CrossLanguageTest {
    private val root = TestProjects.repoRoot
    private val mapper = ObjectMapper()

    private val schemas = HashMap<Int, JsonSchema>()

    /** Validates against the schema of the document's own format version. */
    private fun schemaErrors(json: String): List<String> {
        val tree = mapper.readTree(json)
        val version = tree.path("formatVersion").asInt(FORMAT_VERSION)
        val schema = schemas.getOrPut(version) {
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(File(root, "schema/knsproj-v$version.schema.json").readText())
        }
        return schema.validate(tree).map { it.message }
    }

    @Test
    fun kotlinOutputConformsToTheSchema() {
        val json = KnsprojJson.encode(TestProjects.full())
        assertEquals(emptyList<String>(), schemaErrors(json))
    }

    @Test
    fun sharedFixtureMatchesTheSerializer() {
        val fixture = File(root, "tools/tests/fixtures/full-kotlin.json")
        val json = KnsprojJson.encode(TestProjects.full())
        if (System.getenv("KINESIS_UPDATE_FIXTURES") == "1" || !fixture.exists()) fixture.writeText(json)
        assertEquals("Regenerate with KINESIS_UPDATE_FIXTURES=1", fixture.readText(), json)
    }

    @Test
    fun examplesAreValidAndRender() {
        // basicos/, receitas/ and efeitos/ must validate cleanly; erros/ holds deliberately invalid projects (see invalidExamplesReportTheDocumentedCode).
        val examples = File(root, ".claude/skills/knsproj/examples")
        val dirs = listOf("basicos", "receitas", "efeitos").flatMap { group ->
            File(examples, group).listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        }
        assertTrue("examples present", dirs.isNotEmpty())
        for (dir in dirs) {
            val text = File(dir, "project.json").readText()
            assertEquals("${dir.name} schema", emptyList<String>(), schemaErrors(text))
            val project = KnsprojJson.decode(text)
            val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            val report = ProjectValidator.validate(project, files)
            assertTrue("${dir.name}: ${report.issues}", report.issues.isEmpty())
            // Bundled 3D models must load with the glTF reader.
            for (asset in project.assets.filter { it.type == AssetType.MODEL }) {
                val model = GltfLoader.load(File(dir, asset.path).readBytes())
                assertTrue("${dir.name}: ${asset.path} has geometry", model.triangleCount > 0)
            }
            // Re-encoding keeps the document valid.
            assertEquals(emptyList<String>(), schemaErrors(KnsprojJson.encode(project)))
            // Every frame evaluates.
            for (f in 0 until project.composition.frameCount step 5) SceneEvaluator.evaluate(project, project.composition.frameTime(f))
            assertTrue(LayerTree.all(project).isNotEmpty())
        }
    }

    @Test
    fun invalidExamplesReportTheDocumentedCode() {
        val expected = Regex("""\*\*C\u00f3digo esperado:\*\* `([A-Za-z.]+)`""")
        val dirs = File(root, ".claude/skills/knsproj/examples/erros").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("error examples present", dirs.size >= 4)
        for (dir in dirs) {
            val code = expected.find(File(dir, "README.md").readText())!!.groupValues[1]
            val text = File(dir, "project.json").readText()
            assertEquals("${dir.name} must be schema-valid", emptyList<String>(), schemaErrors(text))
            val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            val found = ProjectValidator.validate(KnsprojJson.decode(text), files).issues.filter { it.severity == Severity.ERROR }.map { it.code }
            assertEquals(dir.name, listOf(code), found)
        }
    }

    @Test
    fun invalidFixturesReportTheSameCodesAsTheCli() {
        val dir = File(root, "tools/tests/fixtures/invalid")
        val expected = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject
        for ((name, codes) in expected) {
            val text = File(dir, name).readText()
            assertEquals("$name must be schema-valid", emptyList<String>(), schemaErrors(text))
            val project = KnsprojJson.decode(text)
            val found = ProjectValidator.validate(project, null).issues.filter { it.severity == Severity.ERROR }.map { it.code }.toSortedSet()
            assertEquals(name, codes.jsonArray.map { it.jsonPrimitive.content }.toSortedSet(), found)
        }
    }
}
