# Compute Bridge Protocol v1

## Discovery

Worker advertises an NSD service of type `_sa-compute._tcp` on the local network. The service name contains the worker ID.

## QR payload

The Worker app displays JSON in a QR code:

```json
{
  "protocol": "sa-compute-v1",
  "worker_id": "WK-...",
  "host": "192.168.x.x",
  "port": 8765,
  "pairing_token": "CB-..."
}
```

The main app should scan this payload and call `POST /v1/pair` with the pairing token. The response returns the access token used as `Authorization: Bearer <token>` for subsequent calls.

## Endpoints

- `GET /v1/health` — unauthenticated liveness check.
- `POST /v1/pair` — verifies the pairing token.
- `GET /v1/worker` — authenticated worker metadata, network addresses, engine state and models.
- `GET /v1/models` — authenticated GGUF model list.
- `POST /v1/chat/completions` — authenticated OpenAI-compatible chat inference. `stream=true` returns SSE token chunks.

## Failure behavior

If no model is loaded, inference returns HTTP 503 with `model_not_loaded`. The client must not treat that as a successful response.

## Resource limit

The worker exposes a persistent compute-usage policy from 10% to 100%.

- `GET /v1/settings/resource` returns the current policy and effective CPU/token/context limits.
- `POST /v1/settings/resource` with `{ "percent": 50 }` updates the policy for the paired client.
- Lowering the limit immediately affects task admission and token/context caps. The CPU-thread limit is applied when the GGUF model is next loaded.
- The worker rejects generation when its measured app/native memory usage is outside the configured budget.

This is intentionally not described as an exact CPU-percent throttle because Android does not provide a portable hard CPU-percentage cap for a normal application process.


## Safety and disconnect handling

- Only one native generation may run at a time.
- The Worker rejects generation when the configured resource budget is exceeded.
- Prompt size is bounded before llama.cpp tokenization to reduce out-of-memory risk.
- Pairing attempts are rate-limited after repeated failures.
- If an SSE client disconnects or the stream writer fails, the Worker requests cancellation of the native generation instead of treating the disconnect as an app crash.
- Stopping the Worker service stops the server/advertisement first, requests generation cancellation, and unloads the native model asynchronously.
- Model replacement is rejected while generation is active.
- The UI provides a Stop Current AI Task action.
- QR display is inside a scrollable container so it remains usable on small screens.
- LAN address selection prefers private Wi-Fi/hotspot addresses instead of blindly choosing the first network interface.
