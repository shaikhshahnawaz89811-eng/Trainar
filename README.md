# Multi-Phone AI Trainer

Kotlin + Jetpack Compose Android app for the Multi-Phone AI Trainer plan.

## Included

- Dark neon AI Training Center UI based on the supplied reference
- Skill/module checkboxes persisted with DataStore
- Basic / Intermediate / Advanced training level selector
- Real Android document picker for readable text, JSON and CSV training data
- Local bounded training queue with observable progress, steps and loss
- Real on-device next-character SGD trainer for selected readable text, with measured loss/speed and private weight persistence
- Export trained binary module action, disabled until a model is actually saved
- Safe ZIP extraction with entry listing and path-traversal protection
- Same-WiFi LAN TCP handshake on port 8765, pairing-code validation, and exchanged CPU/RAM/battery capability reports
- Real QR generation on the master and CameraX/ZXing QR scanning on workers, including runtime camera permission handling
- Groq via its real OpenAI-compatible API, plus any OpenAI-compatible custom endpoint
- API keys encrypted with an Android Keystore AES-GCM key; keys are not hardcoded or shown after saving
- Battery percentage and Android thermal status from device APIs
- GitHub Actions workflow producing `app-debug.apk`

## Build from Termux

```bash
git add .
git commit -m "Start Training Phase 1"
git push origin main
```

The APK appears in the GitHub Actions run as the `training-debug-apk` artifact.

The LAN connection is real TCP on the same Wi-Fi network; both phones must be able to reach port 8765. The master generates a QR containing the LAN host, port, pairing code and device limit; worker phones scan it after granting camera permission.

The evaluation screen makes a real OpenAI-compatible HTTP call. It does not fabricate a score, build result, test result, or code output. A custom app must expose `/chat/completions` and accept Bearer authentication, or the request will fail visibly.

ZIP extraction and path validation are real on-device operations. An Android app cannot safely promise to install arbitrary project dependencies or run every desktop toolchain, so those results are not simulated.

The local trainer is deliberately small: it learns next-character probabilities from selected text. It is not a claim that a full coding assistant or large language model is trained on-device.
## Important load/training truth

The LAN capability screen is intentionally honest: the saved percentage is a **workload target**, not a promise that Android will force an exact CPU percentage on a remote phone. The current release does not yet execute distributed training on workers. It is therefore safe to use for pairing/capability planning without pretending that remote compute has happened.

The local trainer is real next-character SGD and now checkpoints every 100 steps. Severe/critical thermal state or very-low battery pauses work and leaves a resumable checkpoint. A process death can therefore resume from the latest checkpoint rather than silently starting from zero.

A trained module can be exported and later uploaded back into the app; the binary is validated before replacement. Uploading a module asks whether worker phones should be connected before continuing.

## Distributed training flow (current build)

1. Select training data in Phase 1. The corpus is persisted locally for the distributed trainer.
2. Import a trained module if continuing from an existing model. The module is validated before replacement.
3. Open Phase 2 on the master phone and start the LAN master. Share the generated QR only with intended workers.
4. On each worker phone, open Phase 2 → Worker, scan the QR and connect.
5. The master receives real CPU/RAM/battery capabilities over the authenticated TCP connection.
6. Set each worker scheduling cap. The cap controls actual training-step allocation; it is not an OS-level CPU percentage guarantee.
7. Start distributed training. Every round sends real model/corpus/job data to workers, workers compute their assigned steps, and the master merges returned weights.
8. The master saves the merged model after every completed round. A failed worker round is not silently counted as complete.

### Recovery

- Worker disconnect during a round: the current round fails; the previous completed master model remains saved.
- Master process loss: the last completed round remains in the saved model.
- Thermal/battery protection: training stops before additional work and the saved state remains available.
- The existing Phase 1 local checkpoint/resume flow remains available.

### Scope boundary

This source change intentionally implements the requested Phase 1–4 distributed-training path first. Physical multi-phone testing, final Android crash/device testing, and later Phase 5/6 work are deferred to the next pass.

## Phase 5–6

Phase 5 persists verified evaluation history, computes weak areas from real category scores, maintains a training queue, and stores a continuous-improvement state machine. Phase 6 provides the connected feature summary and final source-level safety status. Physical Android-device validation remains explicitly separate and is not falsely marked complete.
