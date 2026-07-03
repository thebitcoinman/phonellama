"""PhoneLlama Proxmox orchestrator — handles complex-tier requests from the phone."""

import os
from collections import deque

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from router import pick_target
from weather import fetch_weather_answer, is_weather_query

app = FastAPI(title="PhoneLlama Orchestrator", version="1.0.0")

OLLAMA_THINK = os.environ.get("OLLAMA_THINK", "false").lower() == "true"

# Spoken answers need to be short; at ~7 tok/s on the CPU box every extra
# paragraph is another minute of silence on the phone.
SYSTEM_PROMPT = os.environ.get(
    "SYSTEM_PROMPT",
    "You are a voice assistant. Answer in a few short conversational sentences. "
    "Only give long or detailed answers (lists, code, step-by-step) when the user "
    "explicitly asks for them.",
)

# OpenClaw agent (web search, browser, skills) for wake-word-prefixed prompts.
OPENCLAW_URL = os.environ.get("OPENCLAW_URL", "").rstrip("/")
OPENCLAW_TOKEN = os.environ.get("OPENCLAW_TOKEN", "")
OPENCLAW_TRIGGERS = [
    t.strip().lower()
    for t in os.environ.get("OPENCLAW_TRIGGERS", "use claw,ask claw,hey claw").split(",")
    if t.strip()
]
# Cold-cache agent turns prefill ~8.5k tokens at ~45 tok/s on this CPU (~3 min).
OPENCLAW_TIMEOUT = float(os.environ.get("OPENCLAW_TIMEOUT", "340"))

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Model files the phone downloads from us (e.g. the sherpa-onnx STT model) —
# repackaged as zips because Android can't unpack tar.bz2 natively.
if os.path.isdir("models"):
    app.mount("/models", StaticFiles(directory="models"), name="models")


class RouteRequest(BaseModel):
    prompt: str = Field(..., min_length=1)
    stream: bool = False


# ---------------------------------------------------------------------------
# Ramble mode: the phone streams transcript chunks of a long think-aloud
# session (10-20 min); on `final` the whole transcript is analyzed against a
# mode-specific system prompt.

RAMBLE_MODES = {
    "challenge": (
        "You are a rigorous critical thinker. The user thought out loud; the "
        "transcript is imperfect speech-to-text. Find the holes: logical "
        "fallacies, unstated assumptions, contradictions, missing evidence, "
        "and the strongest counterargument. Be direct and specific — quote "
        "the claim you are attacking. End with the single weakest point."
    ),
    "solve": (
        "You are a pragmatic problem-solver. The user described a problem out "
        "loud; the transcript is imperfect speech-to-text. Restate the core "
        "problem in two sentences, then propose concrete solutions ranked by "
        "effort-to-impact, with a clear recommended first step."
    ),
    "summarize": (
        "Distill this spoken monologue into its essential structure: the main "
        "thesis, the key points in order, and any open questions the speaker "
        "left unresolved. Imperfect speech-to-text — infer intent."
    ),
}

RAMBLE_MAX_WORDS = int(os.environ.get("RAMBLE_MAX_WORDS", "6000"))
_ramble_sessions: dict[str, dict] = {}
# Fire-and-forget segment uploads can arrive after their session was aborted;
# remember recent aborts so a late chunk doesn't silently resurrect a session.
_aborted_session_ids: deque = deque(maxlen=200)


class RambleRequest(BaseModel):
    session_id: str = Field(..., min_length=8)
    mode: str = "challenge"
    chunk: str = ""
    final: bool = False
    abort: bool = False


@app.post("/v1/ramble")
async def ramble(req: RambleRequest):
    if req.abort:
        _ramble_sessions.pop(req.session_id, None)
        _aborted_session_ids.append(req.session_id)
        return {"ok": True, "aborted": True}

    if req.session_id in _aborted_session_ids:
        return {"ok": True, "ignored": True}

    session = _ramble_sessions.setdefault(req.session_id, {"chunks": []})
    if req.chunk.strip():
        session["chunks"].append(req.chunk.strip())

    words = sum(len(c.split()) for c in session["chunks"])
    if not req.final:
        return {"ok": True, "segments": len(session["chunks"]), "words": words}

    transcript = " ".join(_ramble_sessions.pop(req.session_id)["chunks"]).strip()
    if not transcript:
        raise HTTPException(status_code=400, detail="Empty ramble transcript")

    tokens = transcript.split()
    truncated = len(tokens) > RAMBLE_MAX_WORDS
    if truncated:
        transcript = " ".join(tokens[-RAMBLE_MAX_WORDS:])

    system = RAMBLE_MODES.get(req.mode, RAMBLE_MODES["challenge"])
    if not OLLAMA_THINK:
        system = f"/no_think {system}"

    ollama_base, model = pick_target(transcript)
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": transcript},
        ],
        "stream": False,
    }
    try:
        async with httpx.AsyncClient(timeout=570.0) as client:
            resp = await client.post(f"{ollama_base}/v1/chat/completions", json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Ramble analysis failed: {exc}") from exc

    content = _strip_thinking(_extract_content(data))
    if truncated:
        content = f"(Transcript truncated to last {RAMBLE_MAX_WORDS} words.)\n\n{content}"
    return {
        "target": f"{ollama_base} ({model}, mode={req.mode})",
        "words": words,
        "choices": [{"message": {"role": "assistant", "content": content}}],
        "response": content,
    }


@app.get("/health")
def health():
    return {"status": "ok", "service": "phonellama-orchestrator"}


@app.post("/v1/route")
async def route(req: RouteRequest):
    # Explicit wake word wins over every other route — the user asked for the agent.
    claw_prompt = _match_openclaw_trigger(req.prompt)
    if claw_prompt is not None:
        return await _route_openclaw(claw_prompt)

    if is_weather_query(req.prompt):
        answer = fetch_weather_answer(req.prompt)
        return {
            "target": "Open-Meteo (live)",
            "choices": [{"message": {"role": "assistant", "content": answer}}],
            "response": answer,
        }

    ollama_base, model = pick_target(req.prompt)
    target_label = f"{ollama_base} ({model})"

    # Hybrid reasoning models (qwen3) burn minutes of CPU on thinking tokens
    # before answering — unusable latency for a voice assistant. Qwen3's
    # "/no_think" soft switch suppresses that; _strip_thinking catches any
    # think block that slips through anyway.
    system = SYSTEM_PROMPT
    if not OLLAMA_THINK:
        system = f"/no_think {system}".strip()
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": req.prompt},
    ]

    payload = {
        "model": model,
        "messages": messages,
        "stream": False,
    }

    try:
        async with httpx.AsyncClient(timeout=180.0) as client:
            resp = await client.post(f"{ollama_base}/v1/chat/completions", json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Ollama request failed: {exc}") from exc

    content = _strip_thinking(_extract_content(data))
    return {
        "target": target_label,
        "choices": [{"message": {"role": "assistant", "content": content}}],
        "response": content,
    }


def _match_openclaw_trigger(prompt: str) -> str | None:
    """Return the prompt with the wake word stripped, or None if no trigger matches."""
    if not (OPENCLAW_URL and OPENCLAW_TOKEN):
        return None
    lowered = prompt.strip().lower()
    for trigger in OPENCLAW_TRIGGERS:
        if lowered.startswith(trigger):
            rest = prompt.strip()[len(trigger):].lstrip(" ,.:;")
            if rest.lower().startswith("to "):
                rest = rest[3:]
            return rest or prompt
    return None


async def _route_openclaw(prompt: str):
    payload = {
        "model": "openclaw",
        "messages": [{"role": "user", "content": prompt}],
    }
    headers = {"Authorization": f"Bearer {OPENCLAW_TOKEN}"}
    try:
        async with httpx.AsyncClient(timeout=OPENCLAW_TIMEOUT) as client:
            resp = await client.post(
                f"{OPENCLAW_URL}/v1/chat/completions", json=payload, headers=headers
            )
            resp.raise_for_status()
            data = resp.json()
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"OpenClaw request failed: {exc}") from exc

    content = _strip_thinking(_extract_content(data))
    return {
        "target": f"OpenClaw ({OPENCLAW_URL})",
        "choices": [{"message": {"role": "assistant", "content": content}}],
        "response": content,
    }


def _extract_content(data: dict) -> str:
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    return message.get("content") or choices[0].get("text") or ""


def _strip_thinking(text: str) -> str:
    if "</think>" in text:
        text = text.split("</think>")[-1]
    return text.strip()


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8080"))
    uvicorn.run(app, host="0.0.0.0", port=port)
