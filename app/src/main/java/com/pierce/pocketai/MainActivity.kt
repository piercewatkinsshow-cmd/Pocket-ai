package com.pierce.pocketai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.min

class MainActivity : Activity() {
    companion object {
        private const val MODEL_FILE = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf?download=true"
        private const val MODEL_SHA256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb"
        private const val MODEL_EXPECTED_BYTES = 1_282_439_584L
        private const val SYSTEM_PROMPT =
            "You are Pocket AI, a concise and helpful offline assistant. Be accurate and admit uncertainty."
    }

    private enum class ResponseMode(
        val label: String,
        val directive: String,
        val maxTokens: Int,
        val historyTurns: Int,
    ) {
        FAST("Fast", "/no_think", 256, 3),
        THINKING("Thinking", "/think", 512, 4),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = ArrayDeque<Pair<String, String>>()

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var fastButton: Button
    private lateinit var thinkingButton: Button

    private var model: LlamaModel? = null
    private var responseMode = ResponseMode.FAST
    private var generationBusy = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        prepareModel()
    }

    private fun buildUi() {
        val baseLeft = dp(16)
        val baseTop = dp(16)
        val baseRight = dp(16)
        val baseBottom = dp(12)
        val minimumBottomClearance = dp(64)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseLeft, baseTop, baseRight, minimumBottomClearance)
            setBackgroundColor(Color.rgb(247, 247, 247))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    val keyboard = insets.getInsets(WindowInsets.Type.ime())
                    view.setPadding(
                        baseLeft + bars.left,
                        baseTop + bars.top,
                        baseRight + bars.right,
                        maxOf(
                            minimumBottomClearance,
                            baseBottom + maxOf(bars.bottom, keyboard.bottom),
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        baseLeft + insets.systemWindowInsetLeft,
                        baseTop + insets.systemWindowInsetTop,
                        baseRight + insets.systemWindowInsetRight,
                        maxOf(
                            minimumBottomClearance,
                            baseBottom + insets.systemWindowInsetBottom,
                        ),
                    )
                }
                insets
            }
        }

        val title = TextView(this).apply {
            text = "Pocket AI"
            textSize = 24f
            setTextColor(Color.rgb(25, 25, 25))
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "Preparing offline AI…"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(3), 0, dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        fastButton = Button(this).apply {
            text = "Fast ✓"
            setAllCaps(false)
            minHeight = dp(42)
            setOnClickListener { selectMode(ResponseMode.FAST) }
        }
        modeRow.addView(fastButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })

        thinkingButton = Button(this).apply {
            text = "Thinking"
            setAllCaps(false)
            minHeight = dp(42)
            setOnClickListener { selectMode(ResponseMode.THINKING) }
        }
        modeRow.addView(thinkingButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(modeRow, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messages, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addMessage("AI", "Hello. I run entirely on this phone. Fast mode is on by default; use Thinking for harder questions.")

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        input = EditText(this).apply {
            hint = "Message"
            textSize = 16f
            minHeight = dp(48)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND && isEnabled) {
                    submitMessage()
                    true
                } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })

        send = Button(this).apply {
            text = "Send"
            isEnabled = false
            minHeight = dp(48)
            setOnClickListener { submitMessage() }
        }
        composer.addView(send, LinearLayout.LayoutParams(-2, -2))
        root.addView(composer, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        root.requestApplyInsets()
        updateModeButtons()
    }

    private fun selectMode(mode: ResponseMode) {
        if (generationBusy || model == null) return
        responseMode = mode
        updateModeButtons()
        status.text = "Offline · Qwen3 1.7B · ${mode.label} mode"
    }

    private fun updateModeButtons() {
        if (!::fastButton.isInitialized || !::thinkingButton.isInitialized) return
        fastButton.text = if (responseMode == ResponseMode.FAST) "Fast ✓" else "Fast"
        thinkingButton.text = if (responseMode == ResponseMode.THINKING) "Thinking ✓" else "Thinking"
        fastButton.alpha = if (responseMode == ResponseMode.FAST) 1f else 0.62f
        thinkingButton.alpha = if (responseMode == ResponseMode.THINKING) 1f else 0.62f
        val enabled = !generationBusy && model != null
        fastButton.isEnabled = enabled
        thinkingButton.isEnabled = enabled
    }

    private fun prepareModel() {
        setBusy("Replacing previous model with Qwen3 1.7B…")
        scope.launch {
            try {
                val modelFile = withContext(Dispatchers.IO) { downloadModelIfNeeded() }
                status.text = "Loading Qwen3 1.7B…"
                val threads = min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                val loadedModel = Llama.loadModel(
                    modelFile.absolutePath,
                    LlamaConfig(contextSize = 2048, threads = threads),
                )
                model = loadedModel
                setReady("Offline · Qwen3 1.7B · Fast mode")
            } catch (t: Throwable) {
                setError("Could not load AI: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun downloadModelIfNeeded(): File {
        val dir = File(filesDir, "models").apply { mkdirs() }
        cleanupObsoleteModels(dir)

        val target = File(dir, MODEL_FILE)
        if (target.exists()) {
            if (target.length() == MODEL_EXPECTED_BYTES && sha256(target) == MODEL_SHA256) return target
            target.delete()
        }

        val partial = File(dir, "$MODEL_FILE.part")
        if (partial.exists() && partial.length() > MODEL_EXPECTED_BYTES) partial.delete()
        var downloaded = if (partial.exists()) partial.length() else 0L

        val required = (MODEL_EXPECTED_BYTES - downloaded).coerceAtLeast(0L) + 250_000_000L
        if (StatFs(dir.absolutePath).availableBytes < required) {
            error("Not enough storage. Free at least %.1f GB and try again.".format(required / 1e9))
        }

        withContextProgress(
            if (downloaded > 0) "Qwen3 1.7B Q4_K_M · resuming download…" else "Qwen3 1.7B Q4_K_M · downloading…",
            ((downloaded * 100) / MODEL_EXPECTED_BYTES).toInt().coerceIn(0, 100),
        )

        var current = URL(MODEL_URL)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "PocketAI/1.5")
            if (downloaded > 0) connection.setRequestProperty("Range", "bytes=$downloaded-")
            connection.connect()

            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Download redirect failed")
                connection.disconnect()
                current = URL(current, location)
                if (++redirects > 10) error("Too many download redirects")
                continue
            }

            if (downloaded > 0 && connection.responseCode == HttpURLConnection.HTTP_OK) {
                partial.delete()
                downloaded = 0L
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK &&
                connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val code = connection.responseCode
                connection.disconnect()
                error("Model download failed: HTTP $code")
            }

            connection.inputStream.buffered().use { inputStream ->
                java.io.FileOutputStream(partial, downloaded > 0).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = ((downloaded * 100) / MODEL_EXPECTED_BYTES).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val doneGb = downloaded / 1_000_000_000.0
                            val totalGb = MODEL_EXPECTED_BYTES / 1_000_000_000.0
                            withContextProgress(
                                "Qwen3 1.7B Q4_K_M · %.2f / %.2f GB · %d%%".format(doneGb, totalGb, percent),
                                percent,
                            )
                        }
                    }
                }
            }
            connection.disconnect()
            break
        }

        if (partial.length() != MODEL_EXPECTED_BYTES) {
            error("Downloaded Qwen3 model is incomplete (${partial.length()} of $MODEL_EXPECTED_BYTES bytes)")
        }
        withContextProgress("Qwen3 1.7B Q4_K_M · verifying…", 100)
        if (sha256(partial) != MODEL_SHA256) {
            partial.delete()
            error("Qwen3 model verification failed. Download removed; retry the app.")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    private fun cleanupObsoleteModels(dir: File) {
        dir.listFiles()?.forEach { file ->
            val isModelFile = file.name.endsWith(".gguf", ignoreCase = true) ||
                file.name.endsWith(".gguf.part", ignoreCase = true)
            val keep = file.name == MODEL_FILE || file.name == "$MODEL_FILE.part"
            if (isModelFile && !keep) file.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withContextProgress(message: String, percent: Int) {
        runOnUiThread {
            status.text = message
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = percent
        }
    }

    private fun submitMessage() {
        val text = input.text.toString().trim()
        val loadedModel = model ?: return
        if (text.isEmpty()) return

        input.setText("")
        addMessage("You", text)
        setBusy(if (responseMode == ResponseMode.THINKING) "Thinking deeply…" else "Thinking…")

        val modeForRequest = responseMode
        val prompt = buildPrompt(text, modeForRequest)
        scope.launch {
            try {
                val result = Llama.complete(
                    loadedModel,
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    maxTokens = modeForRequest.maxTokens,
                )
                val answer = cleanModelOutput(result.text).ifEmpty { "I couldn't produce a response." }
                history.addLast(text to answer)
                while (history.size > 5) history.removeFirst()
                addMessage("AI", answer)
                setReady(
                    "Offline · ${modeForRequest.label} · %.1f tok/s".format(result.tokensPerSecond),
                )
            } catch (t: Throwable) {
                addMessage("AI", "Error: ${t.message ?: "generation failed"}")
                setReady("Offline · Qwen3 1.7B · ${responseMode.label} mode")
            }
        }
    }

    private fun buildPrompt(newMessage: String, mode: ResponseMode): String = buildString {
        val recent = history.toList().takeLast(mode.historyTurns)
        if (recent.isNotEmpty()) {
            append("Conversation so far:\n")
            recent.forEach { (user, assistant) ->
                append("User: ").append(user).append('\n')
                append("Assistant: ").append(assistant).append('\n')
            }
            append('\n')
        }
        append("User: ").append(newMessage).append(' ').append(mode.directive)
        append("\nAssistant:")
    }

    private fun cleanModelOutput(raw: String): String {
        val text = raw.trim()
        val endThink = text.lastIndexOf("</think>")
        if (endThink >= 0) return text.substring(endThink + "</think>".length).trim()
        if (text.startsWith("<think>")) {
            return "I used the available response budget while reasoning. Try a narrower question or switch to Fast mode."
        }
        return text
    }

    private fun addMessage(who: String, text: String) {
        val bubble = TextView(this).apply {
            this.text = "$who\n$text"
            textSize = 16f
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(if (who == "You") Color.rgb(226, 232, 240) else Color.WHITE)
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
            if (who == "You") leftMargin = dp(28) else rightMargin = dp(28)
        }
        messages.addView(bubble, params)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(message: String) {
        generationBusy = true
        status.text = message
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        send.isEnabled = false
        input.isEnabled = false
        updateModeButtons()
    }

    private fun setReady(message: String) {
        generationBusy = false
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = true
        input.isEnabled = true
        updateModeButtons()
        input.requestFocus()
    }

    private fun setError(message: String) {
        generationBusy = true
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = false
        input.isEnabled = false
        updateModeButtons()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        val loadedModel = model
        model = null
        if (loadedModel != null) {
            runCatching { Llama.releaseModel(loadedModel) }
        }
        scope.cancel()
        super.onDestroy()
    }
}
