package com.aidj.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.aidj.app.brain.Announcer
import com.aidj.app.brain.ClaudeOrchestrator
import com.aidj.app.brain.DjBrain
import com.aidj.app.brain.LiveRemixer
import com.aidj.app.brain.McpServer
import com.aidj.app.data.AnalysisQueue
import com.aidj.app.data.LibraryRepository
import com.aidj.app.data.SettingsStore
import com.aidj.app.discovery.Discovery
import com.aidj.app.engine.Performer
import com.aidj.app.voice.VoiceAssistant
import com.aidj.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Process-wide object graph. The engine and brain live as long as the process (the service keeps it alive). */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val library = LibraryRepository(context)
    val analysis = AnalysisQueue(context, library)
    val discovery = Discovery(context, settings, library)
    val engine = NativeEngine()
    val performer = Performer(context, engine, analysis, settings, scope)
    val brain = DjBrain(scope, performer, library, analysis, settings)
    val announcer = Announcer(context, performer, settings)
    val claude = ClaudeOrchestrator(context, brain, library, analysis, discovery, settings)
    val voice = VoiceAssistant(context, scope, brain, announcer, settings)
    val liveRemixer = LiveRemixer(scope, performer, settings, brain)
    val mcp = McpServer(claude, { settings.mcpToken }, BuildConfig.VERSION_NAME)

    init {
        brain.claude = claude
        brain.announcer = announcer
        voice.claude = claude
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        performer.start()
        performer.setMasterVolume(settings.current.masterVolume)
        brain.start()
        liveRemixer.start()
        analysis.start()
        scope.launch {
            settings.settings.distinctUntilChangedBy { Triple(it.mcpServerEnabled, it.mcpPort, it.claudeEnabled) }.collect { s ->
                if (s.mcpServerEnabled) runCatching { mcp.start(s.mcpPort) }.onFailure { Log.e("AppGraph", "MCP start failed", it) }
                else mcp.stop()
            }
        }
    }

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(): AppGraph = instance ?: error("AppGraph not initialised")
        internal fun init(context: Context) { if (instance == null) instance = AppGraph(context) }
    }
}

class AiDjApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Crash recovery: remember that the app died mid-party so the UI can offer to resume.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { getSharedPreferences("aidj_settings", MODE_PRIVATE).edit().putBoolean("crashed", true).commit() }
            prev?.uncaughtException(t, e)
        }
    }
}
