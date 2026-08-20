# Compute Bridge — Worker APK

Compute Bridge is the worker-side Android application for a local distributed AI system.

## What is real in this project

- Android foreground worker service.
- Local HTTP API bound to `0.0.0.0` for LAN/hotspot access.
- QR pairing payload containing worker identity, address, port and pairing token.
- NSD/DNS-SD advertisement using `_sa-compute._tcp` for local discovery.
- Authenticated `/v1/pair`, `/v1/health`, `/v1/worker`, `/v1/models`, and OpenAI-compatible `/v1/chat/completions` endpoints.
- Real GGUF model import through Android Storage Access Framework.
- Real llama.cpp native inference through the JNI bridge.
- Real token streaming for chat completions.
- Disconnect-safe cancellation, prompt-size guards, pairing rate limiting and safe service shutdown.
- Scroll-safe QR display and private-LAN address selection for Wi-Fi/hotspot pairing.
- No bundled fake model and no scripted AI responses.

## Network modes

1. Same Wi-Fi: both phones are on the same local network.
2. Hotspot: one phone provides the hotspot and the other joins it.

Internet access is not required for worker-to-client communication.

## Build

GitHub Actions builds the debug APK using Gradle 8.7, Android SDK 35, NDK 26.3.11579264 and CMake 3.22.1. llama.cpp is fetched from its pinned upstream tag during the native build.

## Worker setup

1. Install the APK on the worker phone.
2. Connect the worker phone to Wi-Fi or the main phone's hotspot.
3. Import a compatible `.gguf` model.
4. Load the model.
5. Start Worker.
6. Show the QR code.
7. The main app scans the QR payload and uses the returned local API address and pairing token.

## Worker usage limit

The Worker app includes a real resource policy from 10% to 100%. The setting controls the llama.cpp CPU-thread count used at the next model load, maximum generation tokens, maximum context size, and a memory-budget admission check. The same setting is exposed through the authenticated `/v1/settings/resource` endpoint so a paired controller can read or update it.

The percentage is a compute policy, not a claim that Android can hard-throttle an app to an exact CPU percentage. Lowering the limit immediately affects new tasks; reload the model to apply a changed CPU-thread count.

### Limit control behaviour

The Worker UI exposes a 10%-100% slider in 10% steps. Saving the setting persists it in app storage. The effective policy is visible immediately in the UI and through the authenticated API. New inference requests are capped by the policy; model loading is blocked when the configured memory budget is exceeded. A changed CPU-thread allowance is applied on the next model load because llama.cpp creates the context with its thread count at load time.
