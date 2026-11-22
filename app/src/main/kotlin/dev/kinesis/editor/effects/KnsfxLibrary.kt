package dev.kinesis.editor.effects

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.kinesis.core.format.SafeFile
import dev.kinesis.core.knsfx.KinesisVersion
import dev.kinesis.core.knsfx.KnsfxException
import dev.kinesis.core.knsfx.KnsfxPackage
import dev.kinesis.core.knsfx.KnsfxRegistry
import dev.kinesis.editor.BuildConfig
import dev.kinesis.editor.render.GlException
import dev.kinesis.editor.render.OffscreenGl
import dev.kinesis.editor.render.ShaderLibrary
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The custom effects installed on this device: `files/knsfx/<id>.knsfx`, registered at start-up so every project
 * (and the effect picker) sees them. See `docs/knsfx.md`.
 */
object KnsfxLibrary {
    @Volatile private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "knsfx").apply { mkdirs() }

    /** Registers the installed packages (once per process). A package that no longer reads is skipped. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            KinesisVersion.current = BuildConfig.VERSION_NAME
            dir(context).listFiles { f -> f.isFile && f.name.endsWith(".${KnsfxPackage.EXTENSION}") }.orEmpty().forEach { f ->
                runCatching { KnsfxRegistry.install(KnsfxPackage.read(f.readBytes())) }
            }
            loaded = true
        }
    }

    /** Reads and checks a package (manifest, params, script; the shader is compiled on a GPU context). */
    fun inspect(context: Context, uri: Uri): KnsfxPackage {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > KnsfxPackage.MAX_BYTES) throw KnsfxException("O arquivo .knsfx passa de ${KnsfxPackage.MAX_BYTES / 1024 / 1024} MB")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw KnsfxException("Não foi possível abrir o arquivo")
        val pkg = KnsfxPackage.read(bytes)
        pkg.shader?.let { compile(context, it) }
        return pkg
    }

    /** Compiles a custom shader on an offscreen context; a failure carries the compiler's log. */
    private fun compile(context: Context, fragment: String) {
        try {
            OffscreenGl().use { ShaderLibrary(context.assets).checkCustom(fragment) }
        } catch (e: GlException) {
            throw KnsfxException("O shader não compila neste aparelho.", e.message)
        }
    }

    /** Installs (or replaces) a package checked by [inspect]. */
    fun install(context: Context, pkg: KnsfxPackage) {
        SafeFile.write(File(dir(context), "${pkg.id}.${KnsfxPackage.EXTENSION}"), keepBackup = false) { it.write(pkg.bytes) }
        KnsfxRegistry.install(pkg)
    }

    /** Removes an installed effect. Projects that use it keep their own copy (`effects/` in the project). */
    fun remove(context: Context, id: String) {
        File(dir(context), "$id.${KnsfxPackage.EXTENSION}").delete()
        KnsfxRegistry.uninstall(id)
    }

    /** True when [uri] is a `.knsfx`: by its name, or by a `manifest.json` with `"format": "knsfx"`. */
    fun isKnsfx(resolver: ContentResolver, uri: Uri): Boolean {
        if (uri.lastPathSegment?.lowercase(Locale.ROOT)?.endsWith(".${KnsfxPackage.EXTENSION}") == true) return true
        return runCatching {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { z ->
                    var n = 0
                    while (n++ < 16) {
                        val e = z.nextEntry ?: break
                        if (e.name == "manifest.json") {
                            val text = z.readBytes().toString(Charsets.UTF_8)
                            return@use Regex("\"format\"\\s*:\\s*\"knsfx\"").containsMatchIn(text)
                        }
                    }
                    false
                }
            } ?: false
        }.getOrDefault(false)
    }
}
