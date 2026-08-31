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
import android.widget.SeekBar
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
        private const val BASE_SYSTEM_PROMPT =
            "You are Pocket AI, a helpful general-purpose offline assistant. " +
            "Answer the user's actual question directly. Prefer the common everyday meaning of words " +
            "unless the user gives a different context. Do not invent definitions or facts. " +
            "If you are genuinely uncertain, say so briefly."
    }

    private enum class Preset(
        val label: String,
        val contextSize: Int,
        val maxTokens: Int,
        val historyTurns: Int,
    ) {
        FAST("Fast", 1024, 160, 1),
        BALANCED("Balanced", 2048, 256, 2),
        SMART("Smart", 3072, 384, 3),
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
    private lateinit var balancedButton: Button
    private lateinit var smartButton: Button
    private lateinit var thinkingButton: Button
    private lateinit var creativitySeek: SeekBar
    private lateinit var creativityValue: TextView

    private var model: LlamaModel? = null
    private var modelFile: File? = null
    private var activeConfigKey: String? = null
    private var selectedPreset = Preset.BALANCED
    private var thinkingEnabled = false
    private var creativity = 20
    private var generationBusy = true
    private var settingsDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedSettings()
        buildUi()
        prepareModel()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("pocket_ai_settings", MODE_PRIVATE)
        selectedPreset = runCatching {
            Preset.valueOf(prefs.getString("preset", Preset.BALANCED.name) ?: Preset.BALANCED.name)
        }.getOrDefault(Preset.BALANCED)
        thinkingEnabled = prefs.getBoolean("thinking", false)
        creativity = prefs.getInt("creativity", 20).coerceIn(0, 100)
    }

    private fun saveSettings() {
        getSharedPreferences("pocket_ai_settings", MODE_PRIVATE).edit()
            .putString("preset", selectedPreset.name)
            .putBoolean("thinking", thinkingEnabled)
            .putInt("creativity", creativity)
            .apply()
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
                        maxOf(minimumBottomClearance, baseBottom + maxOf(bars.bottom, keyboard.bottom)),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        baseLeft + insets.systemWindowInsetLeft,
                        baseTop + insets.systemWindowInsetTop,
                        baseRight + insets.systemWindowInsetRight,
                        maxOf(minimumBottomClearance, baseBottom + insets.systemWindowInsetBottom),
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

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fastButton = makePresetButton("Fast") { selectPreset(Preset.FAST) }
        balancedButton = makePresetButton("Balanced") { selectPreset(Preset.BALANCED) }
        smartButton = makePresetButton("Smart") { selectPreset(Preset.SMART) }
        presetRow.addView(fastButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        presetRow.addView(balancedButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        presetRow.addView(smartButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(presetRow, LinearLayout.LayoutParams(-1, -2))

        thinkingButton = Button(this).apply {
            setAllCaps(false)
            minHeight = dp(42)
            setOnClickListener { toggleThinking() }
        }
        root.addView(thinkingButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })

        val creativityHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val creativityTitle = TextView(this).apply {
            text = "Factual"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        creativityHeader.addView(creativityTitle, LinearLayout.LayoutParams(0, -2, 1f))
        creativityValue = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        creativityHeader.addView(creativityValue, LinearLayout.LayoutParams(-2, -2))
        val creativeLabel = TextView(this).apply {
            text = "Creative"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.END
        }
        creativityHeader.addView(creativeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(creativityHeader, LinearLayout.LayoutParams(-1, -2))

        creativitySeek = SeekBar(this).apply {
            max = 100
            progress = creativity
            setPadding(0, 0, 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    creativity = value.coerceIn(0, 100)
                    updateCreativityLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    markSettingsChanged()
                    saveSettings()
                }
            })
        }
        root.addView(creativitySeek, LinearLayout.LayoutParams(-1, dp(36)))

        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messages, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addMessage("AI", "Hello. Choose Fast, Balanced, or Smart. Thinking is optional; the slider controls factual vs creative responses.")

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
        updateControls()
        updateCreativityLabel()
    }

    private fun makePresetButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setAllCaps(false)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun selectPreset(preset: Preset) {
        if (generationBusy || preset == selectedPreset) return
        selectedPreset = preset
        markSettingsChanged()
        saveSettings()
        updateControls()
    }

    private fun toggleThinking() {
        if (generationBusy) return
        thinkingEnabled = !thinkingEnabled
        saveSettings()
        updateControls()
        status.text = settingsStatus("Ready")
    }

    private fun markSettingsChanged() {
        settingsDirty = true
        if (!generationBusy) status.text = settingsStatus("Applies next message")
    }

    private fun updateControls() {
        if (!::fastButton.isInitialized) return
        fastButton.text = if (selectedPreset == Preset.FAST) "Fast ✓" else "Fast"
        balancedButton.text = if (selectedPreset == Preset.BALANCED) "Balanced ✓" else "Balanced"
        smartButton.text = if (selectedPreset == Preset.SMART) "Smart ✓" else "Smart"
        fastButton.alpha = if (selectedPreset == Preset.FAST) 1f else 0.62f
        balancedButton.alpha = if (selectedPreset == Preset.BALANCED) 1f else 0.62f
        smartButton.alpha = if (selectedPreset == Preset.SMART) 1f else 0.62f
        thinkingButton.text = if (thinkingEnabled) "Thinking: On ✓" else "Thinking: Off"
        thinkingButton.alpha = if (thinkingEnabled) 1f else 0.72f
        val enabled = !generationBusy
        fastButton.isEnabled = enabled
        balancedButton.isEnabled = enabled
        smartButton.isEnabled = enabled
        thinkingButton.isEnabled = enabled
        if (::creativitySeek.isInitialized) creativitySeek.isEnabled = enabled
    }

    private fun updateCreativityLabel() {
        if (!::creativityValue.isInitialized) return
        creativityValue.text = when {
            creativity <= 15 -> "Very factual"
            creativity <= 35 -> "Mostly factual"
            creativity <= 60 -> "Balanced"
            creativity <= 80 -> "Creative"
            else -> "Very creative"
        }
    }

    private fun prepareModel() {
        setBusy("Checking Qwen3 1.7B model…")
        scope.launch {
            try {
                val readyFile = withContext(Dispatchers.IO) { downloadModelIfNeeded() }
                modelFile = readyFile
                status.text = "Loading Qwen3 1.7B…"
                model = loadModelForCurrentSettings(readyFile)
                activeConfigKey = currentConfigKey()
                settingsDirty = false
                setReady(settingsStatus("Offline"))
            } catch (t: Throwable) {
                setError("Could not load AI: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun currentConfigKey(): String = "${selectedPreset.name}:$creativity"

    private fun samplerTemperature(): Float {
        val c = creativity / 100f
        return 0.25f + (0.75f * c)
    }

    private fun samplerTopP(): Float {
        val c = creativity / 100f
        return 0.75f + (0.20f * c)
    }

    private suspend fun loadModelForCurrentSettings(file: File): LlamaModel {
        val threads = min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        return Llama.loadModel(
            file.absolutePath,
            LlamaConfig(
                contextSize = selectedPreset.contextSize,
                threads = threads,
                temperature = samplerTemperature(),
                topP = samplerTopP(),
                topK = 20,
            ),
        )
    }

    private suspend fun ensureCurrentConfiguration(): LlamaModel {
        val file = modelFile ?: error("Model file is unavailable")
        val key = currentConfigKey()
        val current = model
        if (!settingsDirty && current != null && activeConfigKey == key) return current

        withContext(Dispatchers.Main) { status.text = "Applying ${selectedPreset.label} settings…" }
        if (current != null) {
            withContext(Dispatchers.Default) { Llama.releaseModel(current) }
        }
        model = null
        val reloaded = loadModelForCurrentSettings(file)
        model = reloaded
        activeConfigKey = key
        settingsDirty = false
        return reloaded
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
            connection.setRequestProperty("User-Agent", "PocketAI/1.7")
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
        file.inputStream().buffered().use { inputStream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = inputStream.read(buffer)
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
        if (text.isEmpty() || modelFile == null) return

        input.setText("")
        addMessage("You", text)
        setBusy(if (thinkingEnabled) "Thinking deeply…" else "Thinking…")

        val presetForRequest = selectedPreset
        val thinkingForRequest = thinkingEnabled
        val creativityForRequest = creativity
        scope.launch {
            try {
                val loadedModel = ensureCurrentConfiguration()
                val result = Llama.complete(
                    loadedModel,
                    prompt = buildUserPrompt(text, thinkingForRequest),
                    systemPrompt = buildSystemPrompt(presetForRequest, creativityForRequest, thinkingForRequest),
                    maxTokens = if (thinkingForRequest) presetForRequest.maxTokens * 2 else presetForRequest.maxTokens,
                )
                val answer = cleanModelOutput(result.text).ifEmpty { "I couldn't produce a response." }
                history.addLast(text to answer)
                while (history.size > 5) history.removeFirst()
                addMessage("AI", answer)
                setReady(
                    "Offline · ${presetForRequest.label} · ${if (thinkingForRequest) "Thinking" else "Fast answer"} · %.1f tok/s".format(result.tokensPerSecond),
                )
            } catch (t: Throwable) {
                addMessage("AI", "Error: ${t.message ?: "generation failed"}")
                setReady(settingsStatus("Offline"))
            }
        }
    }

    private fun buildUserPrompt(newMessage: String, thinking: Boolean): String =
        "$newMessage\n\n${if (thinking) "/think" else "/no_think"}"

    private fun buildSystemPrompt(preset: Preset, creativityValue: Int, thinking: Boolean): String = buildString {
        append(BASE_SYSTEM_PROMPT)
        if (creativityValue <= 35) {
            append(" Be literal and conservative with factual claims. If unsure, do not guess.")
        } else if (creativityValue >= 70) {
            append(" You may be imaginative for creative tasks, but do not fabricate factual claims.")
        }
        if (thinking) {
            append(" Think carefully, then present only the useful final answer.")
        }

        val recent = history.toList().takeLast(preset.historyTurns)
        if (recent.isNotEmpty()) {
            append("\n\nRelevant memory from earlier turns:")
            recent.forEach { (user, assistant) ->
                append("\n- Earlier the user asked: ")
                append(user.replace("\n", " ").take(280))
                append("\n  Pocket AI answered: ")
                append(assistant.replace("\n", " ").take(420))
            }
            append("\nUse that memory only when relevant. Answer the current user message directly.")
        }
    }

    private fun cleanModelOutput(raw: String): String {
        var text = raw.trim()
        val endThink = text.lastIndexOf("</think>")
        if (endThink >= 0) {
            text = text.substring(endThink + "</think>".length).trim()
        } else if (text.startsWith("<think>")) {
            return "I used the available response budget while reasoning. Try a narrower question or turn Thinking off."
        }
        text = text.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim()
        if (text.startsWith("Assistant:", ignoreCase = true)) {
            text = text.substringAfter(':').trim()
        }
        return text
    }

    private fun settingsStatus(prefix: String): String =
        "$prefix · ${selectedPreset.label} · Thinking ${if (thinkingEnabled) "On" else "Off"}"

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
        updateControls()
    }

    private fun setReady(message: String) {
        generationBusy = false
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = true
        input.isEnabled = true
        updateControls()
        input.requestFocus()
    }

    private fun setError(message: String) {
        generationBusy = true
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = false
        input.isEnabled = false
        updateControls()
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
