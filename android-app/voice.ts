import {AudioSession, AndroidAudioTypePresets} from '@livekit/react-native';
import {Room, RoomEvent, Track, RemoteAudioTrack} from 'livekit-client';
import {NativeModules, PermissionsAndroid} from 'react-native';

type Handlers = {
  readSettings: () => unknown;
  changeSetting: (name: string, value: string) => Promise<unknown>;
  startScan: () => Promise<unknown>;
  onDisconnected: () => void;
  voiceVolume: () => number;
};

export function setVoiceVolume(room: Room, volume: number): void {
  for (const participant of room.remoteParticipants.values()) {
    if (!participant.isAgent) continue;
    for (const publication of participant.audioTrackPublications.values()) {
      if (publication.track instanceof RemoteAudioTrack) publication.track.setVolume(volume);
    }
  }
}

/** A session is created only after Firebase sign-in and explicit mic permission. */
export async function connectVoice(baseUrl: string, handlers: Handlers): Promise<Room> {
  if (!baseUrl.startsWith('https://')) throw new Error('Voice service requires HTTPS');
  const mic = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
  if (mic !== PermissionsAndroid.RESULTS.GRANTED) throw new Error('Microphone permission is required');
  const idToken: string = await NativeModules.VoiceAuth.idToken();
  const response = await fetch(`${baseUrl}/v1/voice/session`, {
    method: 'POST', headers: {Authorization: `Bearer ${idToken}`},
  });
  if (!response.ok) throw new Error('Unable to start voice session');
  const session: {url: string; token: string} = await response.json();
  if (!session.url.startsWith('wss://')) throw new Error('Voice connection requires WSS');
  const room = new Room();
  room.on(RoomEvent.TrackSubscribed, (track, _publication, participant) => {
    if (participant.isAgent && track.kind === Track.Kind.Audio && track instanceof RemoteAudioTrack) track.setVolume(handlers.voiceVolume());
  });
  room.on(RoomEvent.Disconnected, handlers.onDisconnected);
  room.on(RoomEvent.ParticipantDisconnected, participant => { if (participant.isAgent) handlers.onDisconnected(); });
  room.registerRpcMethod('echo.startup_context', async () => NativeModules.VoiceAuth.startupContext());
  room.registerRpcMethod('echo.capture_image', async () => NativeModules.ARScanner.captureVoiceImage());
  room.registerRpcMethod('echo.read_scene', async () => NativeModules.ARScanner.readVoiceScene());
  room.registerRpcMethod('echo.start_scan', async () => JSON.stringify(await handlers.startScan()));
  room.registerRpcMethod('echo.stop_scan', async () => {
    const stopped: boolean = await NativeModules.ARScanner.stopScan();
    return JSON.stringify(stopped ? {ok: true, message: 'Live scan stopped.'} : {ok: false, message: 'No live scan is currently active.'});
  });
  room.registerRpcMethod('echo.read_settings', async () => JSON.stringify(handlers.readSettings()));
  room.registerRpcMethod('echo.change_setting', async ({payload}) => {
    const args = JSON.parse(payload) as {name: string; value: string};
    return JSON.stringify(await handlers.changeSetting(args.name, args.value));
  });
  room.registerRpcMethod('echo.preview_sound', async ({payload}) => {
    const args = JSON.parse(payload) as {category: string; sound: string};
    return NativeModules.ARScanner.previewVoiceSound(args.category, args.sound);
  });
  room.registerRpcMethod('echo.confirm_sound', async ({payload}) => {
    const args = JSON.parse(payload) as {preview_id: string};
    return NativeModules.ARScanner.confirmVoiceSound(args.preview_id);
  });
  await AudioSession.configureAudio({android: {audioTypeOptions: {...AndroidAudioTypePresets.media, manageAudioFocus: false}}});
  await AudioSession.startAudioSession();
  try {
    await room.connect(session.url, session.token);
    setVoiceVolume(room, handlers.voiceVolume());
    await room.localParticipant.setMicrophoneEnabled(true);
    return room;
  } catch (error) {
    await room.disconnect();
    await AudioSession.stopAudioSession();
    throw error;
  }
}

export async function disconnectVoice(room: Room): Promise<void> {
  await room.disconnect();
  await AudioSession.stopAudioSession();
}
