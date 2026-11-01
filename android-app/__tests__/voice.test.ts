import {NativeModules, PermissionsAndroid} from 'react-native';
import {connectVoice} from '../voice';
import {AudioSession} from '@livekit/react-native';

const mockMethods: Record<string, (arg: {payload: string}) => Promise<string>> = {};
jest.mock('@livekit/react-native', () => ({AndroidAudioTypePresets: {media: {audioMode: 'normal'}}, AudioSession: {configureAudio: jest.fn().mockResolvedValue(undefined), startAudioSession: jest.fn().mockResolvedValue(undefined), stopAudioSession: jest.fn().mockResolvedValue(undefined)}}));
jest.mock('livekit-client', () => ({
  RoomEvent: {Disconnected: 'disconnected', ParticipantDisconnected: 'participantDisconnected'},
  Room: jest.fn().mockImplementation(() => ({
    on: jest.fn(), remoteParticipants: new Map(), registerRpcMethod: (name: string, method: (arg: {payload: string}) => Promise<string>) => { mockMethods[name] = method; },
    connect: jest.fn().mockResolvedValue(undefined), disconnect: jest.fn().mockResolvedValue(undefined),
    localParticipant: {setMicrophoneEnabled: jest.fn().mockResolvedValue(undefined)},
  })),
}));

test('stop scan RPC returns the phone result and handles an inactive scan', async () => {
  jest.spyOn(PermissionsAndroid, 'request').mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);
  NativeModules.VoiceAuth = {idToken: jest.fn().mockResolvedValue('token')};
  NativeModules.ARScanner = {stopScan: jest.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false)};
  const previousFetch = globalThis.fetch;
  globalThis.fetch = jest.fn().mockResolvedValue({ok: true, json: async () => ({url: 'wss://example.test', token: 'token'})}) as unknown as typeof fetch;
  try {
    await connectVoice('https://example.test', {readSettings: () => ({}), changeSetting: async () => ({}), startScan: async () => ({}), onDisconnected: () => {}, voiceVolume: () => 0.45});
    expect(AudioSession.configureAudio).toHaveBeenCalledWith({android: {audioTypeOptions: expect.objectContaining({audioMode: 'normal', manageAudioFocus: false})}});
    expect(JSON.parse(await mockMethods['echo.stop_scan']({payload: '{}'}))).toEqual({ok: true, message: 'Live scan stopped.'});
    expect(JSON.parse(await mockMethods['echo.stop_scan']({payload: '{}'}))).toEqual({ok: false, message: 'No live scan is currently active.'});
    expect(NativeModules.ARScanner.stopScan).toHaveBeenCalledTimes(2);
  } finally { globalThis.fetch = previousFetch; jest.restoreAllMocks(); }
});
