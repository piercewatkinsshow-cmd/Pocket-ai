package com.pierce.pocketai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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
import android.os.StatFs
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
        private const val MODEL_FILE = "Qwen_Qwen3-4B-Instruct-2507-Q6_K.gguf"
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q6_K.gguf?download=true"
        private const val MODEL_SHA256 = "324bcc583feabe9485df2521099bf913e2613048e7aa2bdcdbfe74f1acc7531e"
        private const val MODEL_MIN_BYTES = 3_250_000_000L
        private const val SYSTEM_PROMPT =
            "You are Pocket AI, a concise and helpful offline assistant. Be accurate and admit uncertainty."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = ArrayDeque<Pair<String, String>>()

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button

    private var model: LlamaModel? = null

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
            setPadding(0, dp(3), 0, dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

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

        addMessage("AI", "Hello. I run entirely on this phone. Ask me something.")

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
    }

    private fun prepareModel() {
        setBusy("Checking Qwen3 model…")
        scope.launch {
            try {
                val modelFile = withContext(Dispatchers.IO) { downloadModelIfNeeded() }
                status.text = "Loading AI…"
                val threads = min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                val loadedModel = Llama.loadModel(
                    modelFile.absolutePath,
                    LlamaConfig(contextSize = 2048, threads = threads),
                )
                model = loadedModel
                setReady("Offline · Qwen3 4B · Q6_K")
            } catch (t: Throwable) {
                setError("Could not load AI: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun downloadModelIfNeeded(): File {
        val dir = File(filesDir, "models").apply { mkdirs() }
        val target = File(dir, MODEL_FILE)

        if (target.exists()) {
            if (target.length() >= MODEL_MIN_BYTES && sha256(target) == MODEL_SHA256) return target
            target.delete()
        }

        val partial = File(dir, "$MODEL_FILE.part")
        var downloaded = if (partial.exists()) partial.length() else 0L
        val required = (MODEL_MIN_BYTES - downloaded).coerceAtLeast(0L) + 350_000_000L
        if (StatFs(dir.absolutePath).availableBytes < required) {
            error("Not enough storage. Free at least %.1f GB and try again.".format(required / 1e9))
        }

        withContextProgress(
            if (downloaded > 0) "Qwen3 4B Q6_K · resuming download…" else "Qwen3 4B Q6_K · downloading…",
            0,
        )

        var current = URL(MODEL_URL)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "PocketAI/1.4")
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

            val total = if (connection.contentLengthLong > 0) {
                downloaded + connection.contentLengthLong
            } else 3_310_000_000L

            connection.inputStream.buffered().use { inputStream ->
                java.io.FileOutputStream(partial, downloaded > 0).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val doneGb = downloaded / 1_000_000_000.0
                            val totalGb = total / 1_000_000_000.0
                            withContextProgress(
                                "Qwen3 4B Q6_K · %.2f / %.2f GB · %d%%".format(doneGb, totalGb, percent),
                                percent,
                            )
                        }
                    }
                }
            }
            connection.disconnect()
            break
        }

        if (partial.length() < MODEL_MIN_BYTES) error("Downloaded Qwen3 model is incomplete")
        withContextProgress("Qwen3 4B Q6_K · verifying…", 100)
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
        setBusy("Thinking…")

        val prompt = buildPrompt(text)
        scope.launch {
            try {
                val result = Llama.complete(
                    loadedModel,
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    maxTokens = 256,
                )
                val answer = result.text.trim().ifEmpty { "I couldn't produce a response." }
                history.addLast(text to answer)
                while (history.size > 5) history.removeFirst()
                addMessage("AI", answer)
                setReady("Offline · %.1f tok/s".format(result.tokensPerSecond))
            } catch (t: Throwable) {
                addMessage("AI", "Error: ${t.message ?: "generation failed"}")
                setReady("Offline · ready")
            }
        }
    }

    private fun buildPrompt(newMessage: String): String = buildString {
        if (history.isNotEmpty()) {
            append("Conversation so far:\n")
            history.forEach { (user, assistant) ->
                append("User: ").append(user).append('\n')
                append("Assistant: ").append(assistant).append('\n')
            }
            append('\n')
        }
        append("User: ").append(newMessage).append("\nAssistant:")
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
        status.text = message
        progress.visibility = View.VISIBLE
        send.isEnabled = false
        input.isEnabled = false
    }

    private fun setReady(message: String) {
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = true
        input.isEnabled = true
        input.requestFocus()
    }

    private fun setError(message: String) {
        status.text = message
        progress.visibility = View.GONE
        send.isEnabled = false
        input.isEnabled = false
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
