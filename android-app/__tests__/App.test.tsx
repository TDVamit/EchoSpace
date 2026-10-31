import React from 'react';
import {NativeModules, Text, TouchableOpacity} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import App from '../App';

test('starts a real scan and keeps advanced settings accessible without a fake map', async () => {
  const startScan = jest.fn().mockResolvedValue(null);
  NativeModules.ARScanner = {startScan};
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  const texts = () => renderer!.root.findAllByType(Text).flatMap(node => node.props.children).filter(value => typeof value === 'string');
  expect(texts()).toContain('Start live scan');
  expect(texts()).not.toContain('Your point cloud appears here');
  const more = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityState?.expanded === false);
  expect(more).toBeDefined();
  await act(async () => { more!.props.onPress(); });
  expect(texts()).toContain('Listen below phone');
  const start = renderer!.root.findAllByType(TouchableOpacity).find(node => node.props.accessibilityLabel === 'Start live scan');
  await act(async () => { await start!.props.onPress(); });
  expect(startScan).toHaveBeenCalledWith(expect.objectContaining({range: 1, heightBelow: 1.5, heightAbove: 0.6}));
});
