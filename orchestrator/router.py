"""Complexity-based routing for Proxmox orchestrator (complex tier from PhoneLlama)."""

import os


def pick_target(prompt: str) -> tuple[str, str]:
    """Return (ollama_base_url, model_name)."""
    tokens = len(prompt.split())
    desktop = os.environ.get("OLLAMA_DESKTOP", "http://192.168.2.38:11434").rstrip("/")
    r740 = os.environ.get("OLLAMA_R740", "http://127.0.0.1:11434").rstrip("/")
    desktop_model = os.environ.get("OLLAMA_DESKTOP_MODEL", "qwen2.5:32b")
    r740_model = os.environ.get("OLLAMA_R740_MODEL", "qwen2.5:7b")

    if tokens >= 200:
        return desktop, desktop_model
    return r740, r740_model
