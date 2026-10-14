package dev.kinesis.editor.render

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

private const val TAG = "KinesisGL"

class GlException(message: String) : RuntimeException(message)

object Gl {
    fun check(op: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "$op: GL error 0x${Integer.toHexString(err)}")
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    fun intBuffer(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(data); position(0) }

    fun genTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    /** Creates an RGBA8 2D texture with linear filtering and clamped edges. */
    fun create2DTexture(width: Int, height: Int): Int {
        val id = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        setDefaultParams(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return id
    }

    fun createExternalTexture(): Int {
        val id = genTexture()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        setDefaultParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return id
    }

    fun setDefaultParams(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun maxTextureSize(): Int {
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        return if (v[0] > 0) v[0] else 4096
    }
}

/** A linked shader program with cached uniform locations. */
class Program(vertexSource: String, fragmentSource: String, private val name: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw GlException("Cannot link $name: $log")
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlException("Cannot compile $name: $log")
        }
        return shader
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(uniform: String): Int = locations.getOrPut(uniform) { GLES30.glGetUniformLocation(id, uniform) }

    fun set1i(u: String, v: Int) = GLES30.glUniform1i(loc(u), v)
    fun set1f(u: String, v: Float) = GLES30.glUniform1f(loc(u), v)
    fun set2f(u: String, x: Float, y: Float) = GLES30.glUniform2f(loc(u), x, y)
    fun set3f(u: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(u), x, y, z)
    fun set4f(u: String, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(loc(u), x, y, z, w)
    fun setMat4(u: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(u), 1, false, m, 0)
    fun setMat3(u: String, m: FloatArray) = GLES30.glUniformMatrix3fv(loc(u), 1, false, m, 0)
    fun set4fv(u: String, count: Int, v: FloatArray) = GLES30.glUniform4fv(loc(u), count, v, 0)
    fun set3fv(u: String, count: Int, v: FloatArray) = GLES30.glUniform3fv(loc(u), count, v, 0)
    fun set2fv(u: String, count: Int, v: FloatArray) = GLES30.glUniform2fv(loc(u), count, v, 0)
    fun set1fv(u: String, count: Int, v: FloatArray) = GLES30.glUniform1fv(loc(u), count, v, 0)
    fun set1iv(u: String, count: Int, v: IntArray) = GLES30.glUniform1iv(loc(u), count, v, 0)

    fun release() = GLES30.glDeleteProgram(id)
}

/** Loads shaders from `assets/shaders`, expanding `//#include file` lines (see tools/check_shaders.py). */
class ShaderLibrary(private val assets: AssetManager) {
    private val programs = HashMap<String, Program>()
    private val includeRegex = Regex("^//#include\\s+(\\S+)\\s*$", RegexOption.MULTILINE)

    private fun source(name: String): String {
        val text = assets.open("shaders/$name").bufferedReader().use { it.readText() }
        return includeRegex.replace(text) { m -> source(m.groupValues[1]) }
    }

    fun get(vertex: String, fragment: String): Program =
        programs.getOrPut("$vertex|$fragment") { Program(source(vertex), source(fragment), "$vertex+$fragment") }

    fun release() {
        programs.values.forEach { it.release() }
        programs.clear()
    }
}

/** Offscreen framebuffer: RGBA8 colour texture plus a depth renderbuffer. */
class RenderTarget(val width: Int, val height: Int, withDepth: Boolean = true) {
    val texture: Int = Gl.create2DTexture(width, height)
    val framebuffer: Int
    private val depth: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        if (withDepth) {
            GLES30.glGenRenderbuffers(1, ids, 0)
            depth = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        } else {
            depth = 0
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            throw GlException("Framebuffer incomplete: 0x${Integer.toHexString(status)}")
        }
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 0f) {
        bind()
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (depth != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        Gl.deleteTexture(texture)
    }
}

/** Reuses offscreen targets of the output size between passes and frames. */
class TargetPool {
    private val free = ArrayList<RenderTarget>()
    private val all = ArrayList<RenderTarget>()
    private var width = 0
    private var height = 0

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        releaseAll()
        width = w
        height = h
    }

    /** A cleared (transparent) target. Must be returned with [release]. */
    fun acquire(): RenderTarget {
        val t = if (free.isNotEmpty()) free.removeAt(free.size - 1) else RenderTarget(width, height).also { all += it }
        t.clear()
        return t
    }

    fun release(t: RenderTarget) {
        if (t.width == width && t.height == height && t in all && t !in free) free += t
    }

    fun releaseAll() {
        all.forEach { it.release() }
        all.clear()
        free.clear()
    }
}

/** Unit quad (0..1) in a vertex array object; attribute location 0. */
class UnitQuad {
    private val vao: Int
    private val vbo: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val data = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, Gl.floatBuffer(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }
}

