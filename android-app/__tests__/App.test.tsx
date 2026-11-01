import React from 'react';
import {DeviceEventEmitter, NativeModules, PermissionsAndroid, Switch, Text, TouchableOpacity} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import App from '../App';
import {connectVoice} from '../voice';
import {disconnectVoice} from '../voice';

jest.mock('../voice', () => ({connectVoice: jest.fn(), disconnectVoice: jest.fn().mockResolvedValue(undefined), setVoiceVolume: jest.fn()}));

beforeEach(() => {
  jest.clearAllMocks();
  jest.spyOn(PermissionsAndroid, 'request').mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);
  NativeModules.WakeWord = {start: jest.fn().mockResolvedValue(null), stop: jest.fn().mockResolvedValue(null), addListener: jest.fn(), removeListeners: jest.fn()};
});
afterEach(() => jest.restoreAllMocks());

test('starts a real scan and keeps advanced settings accessible without a fake map', async () => {
  const startScan = jest.fn().mockResolvedValue(null);
  NativeModules.ARScanner = {startScan};
  NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue(null)};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  const skip = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Continue without signing in');
  expect(skip).toBeDefined();
  await act(async () => { skip!.props.onPress(); });
  expect(connectVoice).not.toHaveBeenCalled();
  const texts = () => renderer!.root.findAllByType(Text).flatMap(node => node.props.children).filter(value => typeof value === 'string');
  expect(texts()).toContain('Start live scan');
  expect(texts()).not.toContain('Your point cloud appears here');
  const profile = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Profile');
  expect(renderer!.root.findAllByType(TouchableOpacity).some(node => node.props.accessibilityLabel === 'Sign in')).toBe(false);
  await act(async () => { profile!.props.onPress(); });
  expect(renderer!.root.findAllByType(TouchableOpacity).some(node => node.props.accessibilityLabel === 'Sign in')).toBe(true);
  const more = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'More settings');
  expect(more).toBeDefined();
  await act(async () => { more!.props.onPress(); });
  expect(texts()).toContain('Listen below phone');
  const start = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Start live scan');
  await act(async () => { await start!.props.onPress(); });
  expect(startScan).toHaveBeenCalledWith(expect.objectContaining({range: 1, heightBelow: 1.5, heightAbove: 0.6}));
});

test('signed-in profile exposes sign out and returns to the login screen', async () => {
  const signOut = jest.fn().mockResolvedValue(null);
  NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue('demo@example.com'), signOut};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  expect(renderer!.root.findAllByType(TouchableOpacity).some(node => node.props.accessibilityLabel === 'Sign out')).toBe(false);
  const profile = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Profile');
  await act(async () => { profile!.props.onPress(); });
  expect(renderer!.root.findAllByType(Text).some(node => node.props.children === 'demo@example.com')).toBe(true);
  const button = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Sign out');
  await act(async () => { await button!.props.onPress(); });
  expect(signOut).toHaveBeenCalledTimes(1);
  expect(renderer!.root.findAllByType(TouchableOpacity).some(node => node.props.accessibilityLabel === 'Continue without signing in')).toBe(true);
  await act(async () => { renderer!.unmount(); });
});

test('restored sign-in listens locally and connects only after the wake phrase', async () => {
  const room = {activeSpeakers: []};
  (connectVoice as jest.Mock).mockResolvedValue(room);
  NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue('demo@example.com')};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  expect(NativeModules.WakeWord.start).toHaveBeenCalledTimes(1);
  expect(connectVoice).not.toHaveBeenCalled();
  await act(async () => { DeviceEventEmitter.emit('EchoWakeDetected'); });
  expect(connectVoice).toHaveBeenCalledTimes(1);
  expect(renderer!.root.findAllByType(Text).some(node => node.props.children === 'Deep is listening')).toBe(true);
  await act(async () => { renderer!.unmount(); });
});

test('signing in arms the offline wake phrase and opens the home screen', async () => {
  (connectVoice as jest.Mock).mockClear().mockResolvedValue({});
  NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue(null), signIn: jest.fn().mockResolvedValue('demo@example.com')};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  const button = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Sign in');
  expect(button).toBeDefined();
  await act(async () => { await button!.props.onPress(); });
  expect(connectVoice).not.toHaveBeenCalled();
  expect(NativeModules.WakeWord.start).toHaveBeenCalledTimes(1);
  expect(renderer!.root.findAllByType(TouchableOpacity).some(node => node.props.accessibilityLabel === 'Start live scan')).toBe(true);
  await act(async () => { renderer!.unmount(); });
});

test('an idle session disconnects and resumes offline listening', async () => {
  jest.useFakeTimers();
  try {
    (connectVoice as jest.Mock).mockResolvedValue({activeSpeakers: []});
    NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue('demo@example.com')};
    let renderer: ReactTestRenderer.ReactTestRenderer;
    await act(async () => { renderer = ReactTestRenderer.create(<App />); });
    await act(async () => { DeviceEventEmitter.emit('EchoWakeDetected'); });
    await act(async () => { jest.advanceTimersByTime(31_000); });
    expect(disconnectVoice).toHaveBeenCalledTimes(1);
    expect(NativeModules.WakeWord.start).toHaveBeenCalledTimes(2);
    await act(async () => { renderer!.unmount(); });
  } finally { jest.useRealTimers(); }
});

test('turning off Deep disconnects voice and stops wake listening', async () => {
  (connectVoice as jest.Mock).mockResolvedValue({activeSpeakers: []});
  NativeModules.VoiceAuth = {currentUser: jest.fn().mockResolvedValue('demo@example.com')};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  await act(async () => { DeviceEventEmitter.emit('EchoWakeDetected'); });
  const toggle = renderer!.root.findAllByType(Switch).find(node => node.props.accessibilityLabel === 'Deep');
  await act(async () => { toggle!.props.onValueChange(false); });
  expect(disconnectVoice).toHaveBeenCalledTimes(1);
  expect(NativeModules.WakeWord.stop).toHaveBeenCalled();
  expect(renderer!.root.findAllByType(Text).some(node => node.props.children === 'Deep is off')).toBe(true);
  await act(async () => { renderer!.unmount(); });
});
