package dev.kinesis.core.knsfx

import dev.kinesis.core.model.EffectParam

/**
 * Fragment shaders of `.knsfx` packages. The contract (see `docs/knsfx.md`): `uTexture`, `uResolution`, `uTime`,
 * `uProgress`, the texture coordinate `vTexCoord`, and one uniform per parameter with the parameter's id.
 * GLSL ES 3.00 is used as is; shaders written in the GLSL ES 1.00 style (`varying`, `texture2D`, `gl_FragColor`)
 * are translated to 3.00 so they run in the same pipeline.
 */
object KnsfxShader {
    /** Name of the colour output added when a 1.00-style shader writes `gl_FragColor`. */
    const val OUTPUT = "knsfx_FragColor"

    private val versionLine = Regex("^\\s*#version[^\\n]*\\n?", RegexOption.MULTILINE)

    /** The source compiled by the app: GLSL ES 3.00 with a default float precision. */
    fun prepare(source: String): String {
        val is300 = Regex("^\\s*#version\\s+300\\s+es", RegexOption.MULTILINE).containsMatchIn(source)
        if (is300) {
            if (source.contains("precision ")) return source
            return versionLine.replaceFirst(source, "#version 300 es\nprecision highp float;\n")
        }
        var body = versionLine.replace(source, "")
        body = body.replace(Regex("\\bvarying\\b"), "in")
            .replace(Regex("\\btexture2D\\s*\\("), "texture(")
            .replace(Regex("\\bgl_FragColor\\b"), OUTPUT)
        val header = StringBuilder("#version 300 es\n")
        if (!body.contains("precision ")) header.append("precision highp float;\n")
        if (body.contains(OUTPUT)) header.append("out vec4 $OUTPUT;\n")
        return header.append(body).toString()
    }

    /** Checks that can be made without a GPU (the real compile happens on import, in the app). */
    fun checkUniforms(source: String, params: List<EffectParam>) {
        if (!Regex("\\bvoid\\s+main\\s*\\(").containsMatchIn(source)) throw KnsfxException("O shader não tem a função main()")
        if (!source.contains("uTexture")) throw KnsfxException("O shader precisa ler a camada pelo uniform uTexture")
        if (params.size > 32) throw KnsfxException("Shader com parâmetros demais (máximo 32 uniforms)")
    }
}
