# EchoSpace voice backend

The experimental voice service uses Firebase Authentication, a self-hosted LiveKit server, and Gemini Live on Vertex AI. `voice_gateway.py` verifies Firebase ID tokens and creates short-lived, isolated room tokens. `realtime_agent.py` handles conversations and forwards supported actions to the Android app over LiveKit RPC. An OpenAI realtime model is an optional fallback when `OPENAI_API_KEY` is configured.

`deploy/` contains the single-VM Docker Compose setup. Configure a trusted HTTPS hostname, Firebase project, a restricted Firebase UID allowlist, LiveKit credentials, a Vertex-enabled Google Cloud project and the VM service account before deploying. Keep `.env`, LiveKit config and service-account keys out of Git. The Android app and backend must use the same Firebase project. A VM service account supplies application-default credentials for Firebase and Vertex; do not ship a service-account key in the APK.

Prateek's original local prototypes (`agent.py`, `api.py`, and `configuration_*.py`) are retained for reference and fallback experiments. Their ADB and JSON configuration tools are not the phone integration used by the realtime service. Do not expose the original prototype API as the production gateway.

The live agent and app remain experimental. The Android app recognizes the wake phrase locally before starting a voice session, and guest mode disables the voice assistant. The backend closes inactive sessions and applies a maximum session duration as a cost safeguard. Camera, audio routing, wake phrase reliability, cue customization and scene accuracy need physical-device testing. See [the testing checklist](../docs/testing.md).
