# Main App Integration Contract

The existing AI app should add a Compute Manager rather than moving its UI into this worker app.

1. Discover `_sa-compute._tcp` workers on the current local network.
2. Allow QR scan as the deterministic pairing path.
3. Store worker ID, host, port and access token in secure app storage.
4. Call `/v1/worker` to verify the worker and inspect available models.
5. When local resources are insufficient and a suitable worker is available, send the same chat request to `/v1/chat/completions`.
6. Consume SSE chunks and append the real token content to the existing chat UI.
7. On HTTP/network failure, return to the local engine rather than fabricating a response.

No RAM is physically combined. The remote worker executes the model on its own CPU/RAM and returns the result over the local network.
