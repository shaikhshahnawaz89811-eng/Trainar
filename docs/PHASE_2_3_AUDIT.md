# Phase 2-3 Audit — 2026-08-20

## Phase 2 status

Implemented as a real same-Wi-Fi LAN distributed-training path:

- QR/manual pairing with an 8-character-or-longer shared pairing code.
- Persistent TCP connection after handshake; the socket stays open for training jobs.
- Worker capability exchange: CPU cores, RAM and battery.
- Master computes scheduling weights from real capabilities plus the user's worker cap.
- Each training round allocates an exact integer number of training steps to each participant.
- Workers receive the current model, corpus, offset, step count and learning rate.
- Worker computes the assigned steps locally and returns updated weights plus loss.
- Master waits for all assigned jobs, then performs a weighted model merge.
- Every completed round is atomically persisted as the current master model.
- A lost worker fails the current round instead of being silently counted as completed.
- Thermal/battery pause is checked on both master and worker before/while training.

## Important correctness boundary

The workload control is **training-work allocation**, not an OS-level promise such as "exactly 30% CPU". Android does not provide a portable API that can force another phone to consume an exact CPU utilization percentage. The app therefore enforces the user setting by assigning the corresponding share of real training steps.

## Module upload flow

Phase 1 module import validates the real binary module before replacing the saved model. Training data is retained separately so a master can load an existing module and continue distributed training with selected training data.

## Recovery behaviour

- Master model is checkpointed after every completed distributed round.
- If a worker disappears during a round, that incomplete round is not merged.
- If the master process disappears after a completed round, the last atomic model remains available.
- A restarted run begins from the last saved master model.
- Phase 1 local training retains its existing atomic training checkpoint path.

## Phase 3 boundary

The existing Phase 3 AI evaluation path was not expanded in this change. Phase 2 distributed training is now real; the later evaluation/toolchain stages remain subject to their existing project-toolchain limitations.

## Not included in this pass

Physical two-phone testing, APK build verification and final crash/device audit are intentionally deferred to the requested later testing pass.
