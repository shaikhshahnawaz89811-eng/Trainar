# Workflow Audit — 2026-08-20

## End-to-end training flow now implemented

1. User selects training data in Phase 1.
2. Training corpus is persisted atomically for the distributed trainer.
3. User can import an existing trained module; the binary is validated before replacement.
4. Import/data selection can route the user to the device-connect phase.
5. Master starts a real LAN listener and displays a QR pairing payload.
6. Worker scans the QR or enters the LAN address and pairing code.
7. Master authenticates the worker and exchanges capability information over the real TCP socket.
8. The connection remains open for jobs.
9. Master calculates scheduling weight from CPU capability and user worker cap.
10. Master assigns exact training-step counts for each round.
11. Local master and remote workers compute real next-character SGD updates.
12. Worker results are verified using the corpus SHA-256 checksum and merged only after all assigned jobs return.
13. Completed rounds are atomically saved.
14. The final merged model is saved as the normal training module.

## Failure rules

- Invalid module: reject without replacing the existing model.
- Invalid pairing code: reject the connection.
- Invalid protocol frame: reject/close the affected connection.
- Corpus checksum mismatch: reject the affected job.
- Worker timeout/disconnect: fail the current round; do not fabricate completion.
- Thermal/battery protection: stop before starting additional work and preserve the last completed state.
- Master process loss: recover the last completed distributed round from the saved model.
