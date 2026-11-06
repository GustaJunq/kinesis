package dev.kinesis.editor.plugins

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import dev.kinesis.core.plugin.PluginException
import dev.kinesis.core.plugin.PluginScriptError
import dev.kinesis.core.plugin.ScriptLimits
import dev.kinesis.core.plugin.ScriptSandbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Runs plugin scripts in the separate process `:plugins` (see the manifest). A script that eats the memory or hangs
 * only takes that process down: the editor gets an error and keeps the project as it was. Data is exchanged through
 * files in the app cache (the binder limit is 1 MB and a project can be larger); the script itself never sees them.
 */
class PluginScriptService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("plugin-script").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_RUN) return
                val reply = msg.replyTo ?: return
                val dir = File(msg.data.getString(KEY_DIR) ?: return)
                val out = Bundle()
                val log = ArrayList<String>()
                var fatal = false
                try {
                    val texts = (Json.parseToJsonElement(File(dir, "texts.json").readText()) as JsonObject)
                        .mapValues { (it.value as JsonPrimitive).content }
                    val result = ScriptSandbox.run(
                        source = File(dir, "script.js").readText(),
                        function = msg.data.getString(KEY_FUNCTION)!!,
                        inputJson = File(dir, "input.json").readText(),
                        files = texts,
                        limits = ScriptLimits(maxMillis = msg.data.getLong(KEY_MILLIS, 10_000L)),
                    ) { log += it }
                    File(dir, "output.json").writeText(result)
                    out.putBoolean(KEY_OK, true)
                } catch (e: PluginScriptError) {
                    out.putString(KEY_ERROR, e.message)
                } catch (e: OutOfMemoryError) {
                    out.putString(KEY_ERROR, "o plugin usou memória demais")
                    fatal = true
                } catch (e: Throwable) {
                    out.putString(KEY_ERROR, e.message ?: e.javaClass.simpleName)
                }
                out.putStringArrayList(KEY_LOG, log)
                runCatching { reply.send(Message.obtain(null, MSG_DONE).apply { data = out; arg1 = Process.myPid() }) }
                // After running out of memory the heap may be in a bad state: start over with a fresh process.
                if (fatal) Process.killProcess(Process.myPid())
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val MSG_RUN = 1
        const val MSG_DONE = 2
        const val KEY_DIR = "dir"
        const val KEY_FUNCTION = "function"
        const val KEY_MILLIS = "millis"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_LOG = "log"
        const val PROCESS_SUFFIX = ":plugins"
    }
}

/** Starts a script in [PluginScriptService] and waits for it, with a watchdog that kills the process when it hangs. */
object PluginRunner {
    class Output(val json: String, val log: List<String>)

    suspend fun run(
        context: Context,
        script: String,
        function: String,
        inputJson: String,
        texts: Map<String, String>,
        millis: Long = 10_000L,
    ): Output {
        val app = context.applicationContext
        val dir = File(app.cacheDir, "plugin-run/${UUID.randomUUID()}")
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, "script.js").writeText(script)
            File(dir, "input.json").writeText(inputJson)
            File(dir, "texts.json").writeText(JsonObject(texts.mapValues { JsonPrimitive(it.value) }).toString())
        }
        val done = CompletableDeferred<Bundle>()
        val replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == PluginScriptService.MSG_DONE) done.complete(msg.data)
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                val m = Message.obtain(null, PluginScriptService.MSG_RUN).apply {
                    data = Bundle().apply {
                        putString(PluginScriptService.KEY_DIR, dir.absolutePath)
                        putString(PluginScriptService.KEY_FUNCTION, function)
                        putLong(PluginScriptService.KEY_MILLIS, millis)
                    }
                    this.replyTo = replyTo
                }
                runCatching { Messenger(service).send(m) }.onFailure { done.completeExceptionally(PluginException("o processo dos plugins não respondeu")) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                done.completeExceptionally(PluginException("o plugin parou de funcionar (memória demais ou erro grave)"))
            }
        }
        try {
            if (!app.bindService(Intent(app, PluginScriptService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                throw PluginException("não foi possível iniciar o processo dos plugins")
            }
            // The script stops itself at [millis]; the watchdog gives it some slack and then kills the process.
            val data = withTimeoutOrNull(millis + 5_000L) { done.await() } ?: run {
                killScriptProcess(app)
                throw PluginException("o plugin passou do tempo limite e foi interrompido")
            }
            val error = data.getString(PluginScriptService.KEY_ERROR)
            val log = data.getStringArrayList(PluginScriptService.KEY_LOG).orEmpty()
            if (!data.getBoolean(PluginScriptService.KEY_OK)) throw PluginException(error ?: "erro no plugin")
            val json = withContext(Dispatchers.IO) { File(dir, "output.json").readText() }
            return Output(json, log)
        } finally {
            runCatching { app.unbindService(connection) }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private fun killScriptProcess(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses.orEmpty()
            .filter { it.processName.endsWith(PluginScriptService.PROCESS_SUFFIX) }
            .forEach { Process.killProcess(it.pid) }
    }
}
