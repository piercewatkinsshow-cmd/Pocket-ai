# Pocket AI v1.5

Offline Android chat app using Qwen3-1.7B Q4_K_M.

## v1.5 changes
- Replaces the previous Qwen3 4B Q6_K model with Qwen3 1.7B Q4_K_M (~1.28 GB).
- Deletes obsolete GGUF model files from the app's private model directory on startup.
- Adds Fast mode (default) using Qwen3 `/no_think`.
- Adds Thinking mode using Qwen3 `/think` for harder questions.
- Keeps a 2048-token context and the existing 64dp bottom navigation clearance.
- Shows generation speed in tokens/second after each response.

The GGUF model is not bundled in the APK. It downloads once, verifies SHA-256, then runs offline.
