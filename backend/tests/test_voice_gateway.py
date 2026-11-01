import os
from unittest.mock import patch

import jwt
from fastapi.testclient import TestClient

import voice_gateway
from api import require_user


def test_session_requires_firebase_identity():
    client = TestClient(voice_gateway.app)
    assert client.post('/v1/voice/session').status_code == 401


def test_sessions_are_private_and_short_lived():
    voice_gateway.app.dependency_overrides[require_user] = lambda: 'firebase-uid'
    try:
        with patch.dict(os.environ, {'LIVEKIT_PUBLIC_URL': 'wss://example.test',
                                    'LIVEKIT_API_KEY': 'test-key',
                                    'LIVEKIT_API_SECRET': 's' * 32}):
            client = TestClient(voice_gateway.app)
            first = client.post('/v1/voice/session')
            second = client.post('/v1/voice/session')
        assert first.status_code == 200
        assert first.headers['cache-control'] == 'no-store'
        body = first.json()
        assert body['room'] != second.json()['room']
        claims = jwt.decode(body['token'], 's' * 32, algorithms=['HS256'], issuer='test-key')
        assert claims['video']['room'] == body['room']
        assert claims['video']['roomJoin'] is True
        assert claims['exp'] - claims['nbf'] <= 300
        assert 'firebase-uid' not in body['room']
    finally:
        voice_gateway.app.dependency_overrides.clear()


def test_refuses_insecure_url():
    voice_gateway.app.dependency_overrides[require_user] = lambda: 'user'
    try:
        with patch.dict(os.environ, {'LIVEKIT_PUBLIC_URL': 'ws://example.test',
                                    'LIVEKIT_API_KEY': 'key', 'LIVEKIT_API_SECRET': 'secret'}):
            assert TestClient(voice_gateway.app).post('/v1/voice/session').status_code == 503
    finally:
        voice_gateway.app.dependency_overrides.clear()
