# Pocket AI v1.4

Pocket AI does not bundle an AI model in the APK. On first launch it downloads only **Qwen3-4B-Instruct-2507 Q6_K** (~3.31 GB), shows real GB/percent progress, verifies SHA-256, and then loads it locally through llama-android.

Interrupted downloads resume using HTTP Range. If the server does not honor Range, the partial file is safely restarted. Chat remains disabled until the exact Qwen3 model passes checksum verification.

Legacy Qwen2.5 build-time download/package logic has been removed. The 64dp bottom navigation clearance is retained.
