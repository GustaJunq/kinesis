package dev.kinesis.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.format.Severity
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

    private val schema: JsonSchema by lazy {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(root, "schema/knsproj-v1.schema.json").readText())
    }

    private fun schemaErrors(json: String) = schema.validate(mapper.readTree(json)).map { it.message }

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
        // basicos/ and receitas/ must validate cleanly; erros/ holds deliberately invalid projects (see invalidExamplesReportTheDocumentedCode).
        val examples = File(root, ".claude/skills/knsproj/examples")
        val dirs = listOf("basicos", "receitas").flatMap { group ->
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
            // Re-encoding keeps the document valid.
            assertEquals(emptyList<String>(), schemaErrors(KnsprojJson.encode(project)))
            // Every frame evaluates.
            for (f in 0 until project.composition.frameCount step 5) SceneEvaluator.evaluate(project, project.composition.frameTime(f))
            assertTrue(LayerTree.all(project).isNotEmpty())
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
