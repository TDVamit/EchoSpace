"""LiveKit/Vertex voice worker. Phone RPC handlers are required for actions."""
import json
import os
import asyncio
import time
import logging

from livekit import agents
from livekit.agents import Agent, AgentServer, AgentSession, function_tool, get_job_context
from livekit.agents.llm import ImageContent, RealtimeModelFallbackAdapter, ToolError
from livekit.plugins import google, openai

logger = logging.getLogger('echospace.voice')

INSTRUCTIONS = """You are Deep, the EchoSpace voice assistant. Speak briefly and naturally.
You are an experimental aid, not a substitute for a cane. Never claim to see or change
anything unless the phone reports it. Scene data can be stale or incomplete; say when
uncertain. Settings changes and sounds apply only when the phone confirms success.
Use the camera image as the primary source for describing what is in front. Detections
are only hints, filtered at confidence 0.35, and can be incomplete or incorrect. Do not
present a detection as certain when the image disagrees. Only assign a persistent sound
when the phone confirms it recently recognized the requested object category.
When a user asks to change a recognized object's sound, call preview_sound; use
random if they have not specified a preset. Do not claim you cannot change sound before
trying the tool. Preview a sound and ask the user for explicit confirmation before saving it. If an
object is ambiguous, ask the user to clarify. Don't invent location or object distance.
When asked what's in front or for a surroundings briefing, call inspect_camera and
read_scene; if the image is unavailable, say so and use only the reported data.
When the user asks to stop or close the live scan, live session, or scanning session, call stop_scan.
Only say that the scan stopped when the phone reports success.
"""


async def phone_call(method: str, payload: dict) -> str:
    room = get_job_context().room
    participants = [p for p in room.remote_participants.values() if p.identity.startswith('user-')]
    if len(participants) != 1:
        raise ToolError('The phone is not connected to this session.')
    try:
        return await room.local_participant.perform_rpc(
            destination_identity=participants[0].identity,
            method=method,
            payload=json.dumps(payload),
            response_timeout=10.0,
        )
    except Exception as exc:
        raise ToolError('The phone could not complete that request.') from exc


class Deep(Agent):
    def __init__(self):
        super().__init__(instructions=INSTRUCTIONS)

    @function_tool()
    async def startup_context(self) -> str:
        """Get optional approximate location and current phone orientation. Values may be unavailable."""
        return await phone_call('echo.startup_context', {})

    @function_tool()
    async def read_scene(self) -> str:
        """Read supporting detections above 0.35 confidence and active sound sources; these hints may be wrong or incomplete."""
        return await phone_call('echo.read_scene', {})

    @function_tool()
    async def inspect_camera(self) -> str:
        """Capture a current camera image only when asked what is in view or for a surroundings briefing."""
        encoded = await phone_call('echo.capture_image', {})
        if len(encoded) > 15000:
            raise ToolError('The camera image was too large.')
        chat = self.chat_ctx.copy()
        chat.add_message(role='user', content=['Current phone camera image:', ImageContent(image='data:image/jpeg;base64,' + encoded)])
        await self.update_chat_ctx(chat)
        return 'A current camera image was added to context. Describe the image first; use read_scene only as fallible supporting data and for sound assignments.'

    @function_tool()
    async def start_scan(self) -> str:
        """Start scanning only after the user has explicitly requested or agreed to it."""
        return await phone_call('echo.start_scan', {})

    @function_tool()
    async def stop_scan(self) -> str:
        """Stop the active live scan and return to the home screen when the user asks to stop scanning."""
        return await phone_call('echo.stop_scan', {})

    @function_tool()
    async def read_settings(self) -> str:
        """Read available app settings and current values."""
        return await phone_call('echo.read_settings', {})

    @function_tool()
    async def change_setting(self, name: str, value: str) -> str:
        """Set a supported setting. The phone validates the name and value."""
        return await phone_call('echo.change_setting', {'name': name, 'value': value})

    @function_tool()
    async def preview_sound(self, category: str, sound: str) -> str:
        """Preview a sound for a recognized object category without saving it. Pass a singular detector category, such as chair or table. Use random when no preset is specified. Supported sounds: chime, bell, soft, pulse, beacon, random. Retain the returned preview_id and ask for approval."""
        result = await phone_call('echo.preview_sound', {'category': category, 'sound': sound})
        try:
            logger.info('Sound preview completed: %s', 'ok' if json.loads(result).get('ok') else 'failed')
        except (ValueError, AttributeError):
            logger.warning('Sound preview returned an invalid result')
        return result

    @function_tool()
    async def confirm_sound(self, preview_id: str) -> str:
        """Save a previously previewed sound only after the user explicitly agrees."""
        result = await phone_call('echo.confirm_sound', {'preview_id': preview_id})
        try:
            logger.info('Sound confirmation completed: %s', 'ok' if json.loads(result).get('ok') else 'failed')
        except (ValueError, AttributeError):
            logger.warning('Sound confirmation returned an invalid result')
        return result


server = AgentServer()


@server.rtc_session()
async def voice_session(ctx: agents.JobContext):
    model = google.realtime.RealtimeModel(
        model=os.getenv('VERTEX_LIVE_MODEL', 'gemini-3.8-live'),
        vertexai=True,
        project=os.environ['GOOGLE_CLOUD_PROJECT'],
        location=os.getenv('GOOGLE_CLOUD_LOCATION', 'us-central1'),
    )
    if os.getenv('OPENAI_API_KEY'):
        model = RealtimeModelFallbackAdapter([model, openai.realtime.RealtimeModel(api_key=os.environ['OPENAI_API_KEY'])])
    session = AgentSession(llm=model, user_away_timeout=40.0)
    activity = {'last': time.monotonic(), 'user': 'listening', 'agent': 'initializing'}
    @session.on('user_state_changed')
    def on_user_state_changed(event):
        activity['user'] = event.new_state
        activity['last'] = time.monotonic()
        if event.new_state == 'away':
            session.shutdown(drain=False)
            ctx.shutdown('inactive')

    @session.on('agent_state_changed')
    def on_agent_state_changed(event):
        activity['agent'] = event.new_state
        activity['last'] = time.monotonic()

    async def limit_session():
        started = time.monotonic()
        while True:
            await asyncio.sleep(2)
            now = time.monotonic()
            if activity['user'] == 'speaking' or activity['agent'] in ('speaking', 'thinking', 'initializing'):
                activity['last'] = now
            if now - activity['last'] >= 40 or now - started >= 300:
                session.shutdown(drain=False)
                ctx.shutdown('inactive or session time limit')
                return

    await session.start(room=ctx.room, agent=Deep())
    timeout_task = asyncio.create_task(limit_session())
    ctx.add_shutdown_callback(lambda: cancel_timeout(timeout_task))
    await session.generate_reply(instructions='Call startup_context first, greet the user, mention their approximate location only if provided, ask them to hold the phone upright if the tool says not_upright, then ask whether they want to start a live scan. Never invent unavailable information.')


async def cancel_timeout(task):
    task.cancel()


if __name__ == '__main__':
    agents.cli.run_app(server)
