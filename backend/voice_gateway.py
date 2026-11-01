"""Firebase-authenticated LiveKit session gateway for EchoSpace."""
import hashlib
import os
from datetime import timedelta
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, Response
from livekit import api as livekit_api

from api import require_user

app = FastAPI(title="EchoSpace voice gateway", docs_url=None, redoc_url=None)


@app.post("/v1/voice/session")
async def create_session(response: Response, uid: str = Depends(require_user)):
    """Issue a short-lived token scoped to a fresh room for this user."""
    url = os.environ.get("LIVEKIT_PUBLIC_URL", "")
    key = os.environ.get("LIVEKIT_API_KEY", "")
    secret = os.environ.get("LIVEKIT_API_SECRET", "")
    if not url.startswith("wss://") or not key or not secret:
        raise HTTPException(status_code=503, detail="Voice service is not configured")
    user_hash = hashlib.sha256(uid.encode()).hexdigest()[:16]
    session_id = uuid4().hex
    room = f"echo-{user_hash}-{session_id}"
    identity = f"user-{user_hash}-{session_id[:12]}"
    token = (livekit_api.AccessToken(key, secret)
             .with_identity(identity)
             .with_ttl(timedelta(minutes=5))
             .with_grants(livekit_api.VideoGrants(room_join=True, room=room,
                                                  can_publish=True, can_subscribe=True))
             .to_jwt())
    response.headers["Cache-Control"] = "no-store"
    return {"url": url, "token": token, "room": room}
