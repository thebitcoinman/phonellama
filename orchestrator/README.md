# PhoneLlama Proxmox Orchestrator

Handles **complex-tier** voice queries forwarded from the PhoneLlama Android app. Simple and medium queries are handled on-device (Gemma E2B / E4B).

## Deploy on the Proxmox NixOS VM (self-contained, CPU-only)

Runs Ollama and the orchestrator together on the 20-core / 76 GB NixOS VM
(`192.168.1.100` on the LAN, `100.69.62.49` over Tailscale). Both router
targets point at the local Ollama serving `qwen3:30b-a3b` (MoE, ~3B active
params, so it stays responsive without a GPU). Listens on **8081** because
searxng already occupies 8080 on that host.

```bash
scp main.py router.py weather.py requirements.txt Dockerfile docker-compose.proxmox.yml \
  nix@192.168.1.100:~/phonellama-orchestrator/
ssh nix@192.168.1.100 'cd ~/phonellama-orchestrator && docker compose -f docker-compose.proxmox.yml up -d --build'
ssh nix@192.168.1.100 'docker exec phonellama-ollama ollama pull qwen3:30b-a3b'
```

Verify:

```bash
curl http://192.168.1.100:8081/health
curl -X POST http://192.168.1.100:8081/v1/route \
  -H "Content-Type: application/json" \
  -d '{"prompt":"write a Python REST API with authentication and tests"}'
```

Set the orchestrator URL in the PhoneLlama app to `http://192.168.1.100:8081`
(or `http://100.69.62.49:8081` when on Tailscale).

## Ramble mode (`/v1/ramble`)

Long-form think-aloud analysis. The phone streams transcript segments as you
talk (offline Vosk, no time limit), then requests analysis of the full
transcript against a mode prompt:

- `challenge` — find holes, fallacies, unstated assumptions, counterarguments
- `solve` — restate the problem and propose ranked solutions
- `summarize` — thesis, key points, open questions

```bash
curl -X POST :8081/v1/ramble -d '{"session_id":"abc12345","mode":"challenge","chunk":"first segment..."}'
curl -X POST :8081/v1/ramble -d '{"session_id":"abc12345","chunk":"more..."}'
curl -X POST :8081/v1/ramble -d '{"session_id":"abc12345","mode":"challenge","final":true}'
```

Sessions are in-memory; transcripts over `RAMBLE_MAX_WORDS` (default 6000)
are truncated to the most recent words.

## OpenClaw wake-word routing

Prompts starting with a wake word (`use claw`, `ask claw`, `hey claw` — override
via `OPENCLAW_TRIGGERS`) are routed to the OpenClaw gateway on the same host
(`http://host.docker.internal:18789`) instead of plain Ollama. OpenClaw runs a
full agent turn with web search (searxng), browser, and skills, using the local
`qwen3:30b-a3b` as its model.

Setup on the NixOS VM (already done, for reference):

- `~/.openclaw/openclaw.json`: `gateway.http.endpoints.chatCompletions.enabled: true`,
  `agents.defaults.thinkingDefault: "off"`, model `ollama-local/qwen3:30b-a3b`
  (provider `ollama-local` → `http://127.0.0.1:11434`).
- `~/phonellama-orchestrator/.env` holds `OPENCLAW_TOKEN` (the gateway auth token,
  chmod 600, not in git).

Latency: a cold agent turn prefills ~8.5k tokens at ~45 tok/s (~3 min); warm
turns reuse the Ollama slot cache and are much faster. `OLLAMA_NUM_PARALLEL=2`
keeps phone queries from evicting OpenClaw's cached prefix.

| Variable | Default | Description |
|---|---|---|
| `OPENCLAW_URL` | *(unset — routing disabled)* | OpenClaw gateway base URL |
| `OPENCLAW_TOKEN` | *(unset)* | Gateway auth token (`gateway.auth.token`) |
| `OPENCLAW_TRIGGERS` | `use claw,ask claw,hey claw` | Comma-separated wake phrases |
| `OPENCLAW_TIMEOUT` | `340` | Seconds to wait for an agent turn |

## Deploy on r740 LXC (original two-endpoint setup)

```bash
cd orchestrator
docker compose up -d --build
```

Verify:

```bash
curl http://localhost:8080/health
curl -X POST http://localhost:8080/v1/route \
  -H "Content-Type: application/json" \
  -d '{"prompt":"write a Python REST API with authentication and tests"}'
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_R740` | `http://host.docker.internal:11434` | Ollama on Proxmox host (medium-long prompts) |
| `OLLAMA_DESKTOP` | `http://192.168.2.38:11434` | GPU desktop Ollama (200+ token prompts) |
| `OLLAMA_R740_MODEL` | `qwen2.5:7b` | Model for r740 |
| `OLLAMA_DESKTOP_MODEL` | `qwen2.5:32b` | Model for desktop GPU |

Set the orchestrator URL in the PhoneLlama app Voice Assistant screen (default `http://192.168.2.10:8080`).

## Routing logic

- Prompts under 200 tokens → r740 Ollama
- Prompts 200+ tokens → desktop GPU Ollama

The phone's `ComplexityScorer` only forwards **complex** tier requests to this service when orchestrator mode is Phone-first or Remote-only.
