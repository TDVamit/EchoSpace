import unittest
from unittest.mock import AsyncMock, patch

from realtime_agent import Deep


class ScanVoiceToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_stop_scan_forwards_phone_result(self):
        with patch('realtime_agent.phone_call', new_callable=AsyncMock, return_value='{"ok":true}') as call:
            result = await Deep.stop_scan.__wrapped__(None)
        self.assertEqual(result, '{"ok":true}')
        call.assert_awaited_once_with('echo.stop_scan', {})

    async def test_stop_scan_preserves_inactive_result(self):
        with patch('realtime_agent.phone_call', new_callable=AsyncMock, return_value='{"ok":false}'):
            self.assertEqual(await Deep.stop_scan.__wrapped__(None), '{"ok":false}')
