import React, {useEffect, useRef, useState} from 'react';
import {NativeEventEmitter, NativeModules, PermissionsAndroid, SafeAreaView, ScrollView, StatusBar, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View} from 'react-native';
import type {Room} from 'livekit-client';
import {connectVoice, disconnectVoice, setVoiceVolume} from './voice';

const green = '#275744';

type StepProps = {label: string; hint?: string; value: number; min: number; max: number; step: number; unit: string; onChange: (value: number) => void};
function Stepper({label, hint, value, min, max, step, unit, onChange}: StepProps) {
  const formatted = Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0$/, '');
  return <View style={styles.setting}>
    <View style={styles.settingCopy}><Text style={styles.settingLabel}>{label}</Text>{hint ? <Text style={styles.hint}>{hint}</Text> : null}</View>
    <View style={styles.stepper}>
      <TouchableOpacity accessibilityRole="button" accessibilityLabel={`Decrease ${label}`} onPress={() => onChange(Math.max(min, Math.round((value - step) * 100) / 100))} style={styles.step}><Text style={styles.stepText}>−</Text></TouchableOpacity>
      <Text accessibilityLabel={`${label}, ${formatted} ${unit}`} style={styles.stepValue}>{formatted}{unit}</Text>
      <TouchableOpacity accessibilityRole="button" accessibilityLabel={`Increase ${label}`} onPress={() => onChange(Math.min(max, Math.round((value + step) * 100) / 100))} style={styles.step}><Text style={styles.stepText}>+</Text></TouchableOpacity>
    </View>
  </View>;
}
function Toggle({label, hint, value, onChange}: {label: string; hint: string; value: boolean; onChange: (value: boolean) => void}) {
  return <View style={styles.setting}><View style={styles.settingCopy}><Text style={styles.settingLabel}>{label}</Text><Text style={styles.hint}>{hint}</Text></View><Switch accessibilityLabel={label} value={value} onValueChange={onChange} trackColor={{false: '#c8d2ca', true: '#91bb9c'}} thumbColor={value ? green : '#ffffff'} /></View>;
}

function App() {
  const [density, setDensity] = useState(55);
  const [range, setRange] = useState(1);
  const [blindMode, setBlindMode] = useState(false);
  const [heightBelow, setHeightBelow] = useState(1.5);
  const [heightAbove, setHeightAbove] = useState(0.6);
  const [reduceFloor, setReduceFloor] = useState(false);
  const [audioEnabled, setAudioEnabled] = useState(true);
  const [audioVoices, setAudioVoices] = useState(128);
  const [audioVolume, setAudioVolume] = useState(60);
  const [expanded, setExpanded] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [signedIn, setSignedIn] = useState<string | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [guest, setGuest] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const [voiceAttempt, setVoiceAttempt] = useState(0);
  const [authMessage, setAuthMessage] = useState('');
  const [voiceConnected, setVoiceConnected] = useState(false);
  const [deepEnabled, setDeepEnabled] = useState(true);
  const [deepVolume, setDeepVolume] = useState(45);
  const [wakeStatus, setWakeStatus] = useState('Preparing offline wake phrase…');
  const [voiceMessage, setVoiceMessage] = useState('');
  const voiceRoom = useRef<Room | null>(null);
  const deepVolumeRef = useRef(deepVolume);
  deepVolumeRef.current = deepVolume;
  const settingsRef = useRef({hearing_distance: range, sound_level: audioVolume});
  settingsRef.current = {hearing_distance: range, sound_level: audioVolume};
  useEffect(() => {
    let active = true;
    const restore = async () => {
      try { const user = await NativeModules.VoiceAuth?.currentUser(); if (active && user) setSignedIn(user); }
      catch { /* Show sign-in when a session cannot be restored. */ }
      finally { if (active) setAuthReady(true); }
    };
    restore();
    return () => { active = false; };
  }, []);
  useEffect(() => () => { if (voiceRoom.current) { disconnectVoice(voiceRoom.current).catch(() => {}); voiceRoom.current = null; } }, []);
  const signIn = async () => {
    try {
      setAuthMessage('');
      setSigningIn(true);
      const user = await NativeModules.VoiceAuth.signIn(email, password);
      setPassword('');
      setGuest(false);
      setSignedIn(user);
    } catch { setAuthMessage('Unable to sign in. Check your details and connection.'); }
    finally { setSigningIn(false); }
  };
  const startScan = async () => {
    try {
      setMessage('');
      setProfileOpen(false);
      await NativeModules.ARScanner.startScan({density, range, blindMode, audioEnabled, audioVoices, audioVolume: audioVolume / 100, heightBelow, heightAbove, reduceFloor});
      return {ok: true};
    } catch (error) { setMessage(String(error)); return {ok: false, error: 'Could not open the scanner.'}; }
  };
  const startScanRef = useRef(startScan);
  startScanRef.current = startScan;
  const accountAction = async () => {
    try {
      if (signedIn) await NativeModules.VoiceAuth.signOut();
      setSignedIn(null);
      setGuest(false);
      setProfileOpen(false);
    } catch {
      setMessage('Could not sign out. Please try again.');
    }
  };
  useEffect(() => {
    if (!signedIn || guest || !deepEnabled) return;
    let active = true;
    let connecting = false;
    let ending = false;
    let locationAsked = false;
    let idleTimer: ReturnType<typeof setInterval> | undefined;
    let lastActivity = Date.now();
    const emitter = new NativeEventEmitter(NativeModules.WakeWord);
    const armWake = async () => {
      try {
        if (!active) return;
        setWakeStatus('Preparing offline wake phrase…');
        const permission = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
        if (!active) return;
        if (permission !== PermissionsAndroid.RESULTS.GRANTED) throw new Error('Microphone permission is required.');
        if (!locationAsked) {
          locationAsked = true;
          await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION);
          if (!active) return;
        }
        await NativeModules.WakeWord.start();
        if (active) { setVoiceMessage(''); setWakeStatus('Say “Hey Deep”'); }
        else await NativeModules.WakeWord.stop();
      } catch { if (active) setVoiceMessage('Offline wake listening could not start. Check microphone permission and try again.'); }
    };
    const endVoice = async () => {
      if (ending) return;
      ending = true;
      if (idleTimer) clearInterval(idleTimer);
      idleTimer = undefined;
      const room = voiceRoom.current;
      voiceRoom.current = null;
      if (active) setVoiceConnected(false);
      if (room) await disconnectVoice(room).catch(() => {});
      ending = false;
      if (active) await armWake();
    };
    const startVoice = async () => {
      if (!active || connecting || voiceRoom.current) return;
      connecting = true;
      let agentLeft = false;
      setWakeStatus('Connecting Deep…');
      try {
      setVoiceMessage('');
      const room = await connectVoice('https://34-100-173-107.sslip.io', {
        readSettings: () => settingsRef.current,
        startScan: () => startScanRef.current(),
        onDisconnected: () => {
          if (connecting) { agentLeft = true; return; }
          if (active && !ending) endVoice().catch(() => {});
        },
        voiceVolume: () => deepVolumeRef.current / 100,
        changeSetting: async (name, raw) => {
          const value = Number(raw);
          const valid = Number.isFinite(value) && ((name === 'hearing_distance' && value >= 1 && value <= 20) || (name === 'sound_level' && value >= 0 && value <= 100));
          if (!valid) return {ok: false, error: 'Unsupported setting or value.'};
          if (await NativeModules.ARScanner.isLiveScanActive() && !await NativeModules.ARScanner.setLiveVoiceSetting(name, value)) return {ok: false, error: 'Could not update the live scan.'};
          if (name === 'hearing_distance') setRange(value); else setAudioVolume(value);
          return {ok: true, name, value};
        },
      });
      if (!active) { await disconnectVoice(room); return; }
      if (agentLeft) { await disconnectVoice(room); await armWake(); return; }
      voiceRoom.current = room;
      setVoiceConnected(true);
      lastActivity = Date.now();
      idleTimer = setInterval(() => {
        if (room.activeSpeakers.length) lastActivity = Date.now();
        else if (Date.now() - lastActivity >= 30_000) endVoice().catch(() => {});
      }, 1000);
      } catch {
        if (active) {
          setVoiceMessage('Deep could not connect. Say “Hey Deep” to try again.');
          await armWake();
        }
      } finally { connecting = false; }
    };
    const wakeListener = emitter.addListener('EchoWakeDetected', () => { startVoice(); });
    const errorListener = emitter.addListener('EchoWakeError', () => { if (active) setVoiceMessage('Wake listening stopped. Please retry.'); });
    armWake();
    return () => {
      active = false;
      wakeListener.remove(); errorListener.remove();
      if (idleTimer) clearInterval(idleTimer);
      NativeModules.WakeWord.stop().catch(() => {});
      const room = voiceRoom.current;
      voiceRoom.current = null;
      setVoiceConnected(false);
      if (room) disconnectVoice(room).catch(() => {});
    };
  }, [signedIn, guest, voiceAttempt, deepEnabled]);
  useEffect(() => {
    NativeModules.WakeWord?.setDeepEnabled?.(!!signedIn && !guest && deepEnabled, !!signedIn && !guest).catch(() => {});
  }, [signedIn, guest, deepEnabled]);
  useEffect(() => {
    NativeModules.WakeWord?.setVoiceVolume?.(deepVolume).catch(() => {});
    if (voiceRoom.current) setVoiceVolume(voiceRoom.current, deepVolume / 100);
  }, [deepVolume]);
  useEffect(() => {
    const emitter = new NativeEventEmitter(NativeModules.WakeWord);
    const listener = emitter.addListener('EchoDeepEnabled', (...args: readonly Object[]) => setDeepEnabled(Boolean(args[0])));
    return () => listener.remove();
  }, []);
  if (!authReady) return <SafeAreaView style={styles.safe}><View style={styles.splash}><Text style={styles.brand}>EchoSpace</Text><Text style={styles.description}>Getting things ready…</Text></View></SafeAreaView>;
  if (!signedIn && !guest) return <SafeAreaView style={styles.safe}><ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.splash}>
    <View style={styles.brandMark}><View style={styles.brandBarShort}/><View style={styles.brandBar}/><View style={styles.brandBarTall}/><View style={styles.brandBar}/><View style={styles.brandBarShort}/></View>
    <Text style={styles.loginBrand}>EchoSpace</Text>
    <Text style={styles.loginTitle}>Hear the space around you.</Text>
    <Text style={styles.loginDescription}>Sign in to use Deep, your voice assistant, alongside the live scan.</Text>
    <Text style={styles.inputLabel}>Email</Text>
    <TextInput accessibilityLabel="Email" placeholder="you@example.com" placeholderTextColor="#687c70" autoCapitalize="none" autoCorrect={false} keyboardType="email-address" value={email} onChangeText={setEmail} style={styles.input}/>
    <Text style={styles.inputLabel}>Password</Text>
    <TextInput accessibilityLabel="Password" placeholder="Enter your password" placeholderTextColor="#687c70" secureTextEntry value={password} onChangeText={setPassword} style={styles.input}/>
    {!!authMessage && <Text accessibilityRole="alert" style={styles.error}>{authMessage}</Text>}
    <TouchableOpacity accessibilityRole="button" accessibilityLabel="Sign in" disabled={signingIn} onPress={signIn} style={styles.loginButton}><Text style={styles.startTitle}>{signingIn ? 'Signing in…' : 'Sign in'}</Text></TouchableOpacity>
    <TouchableOpacity accessibilityRole="button" accessibilityLabel="Continue without signing in" onPress={() => { setGuest(true); setPassword(''); setAuthMessage(''); }} style={styles.skipButton}><Text style={styles.moreText}>Continue without signing in</Text></TouchableOpacity>
    <Text style={styles.hint}>Live scan works without an account. Deep is available when you sign in.</Text>
  </ScrollView></SafeAreaView>;
  return <SafeAreaView style={styles.safe}>
    <StatusBar barStyle="dark-content" />
    <ScrollView contentContainerStyle={styles.page}>
      <View style={styles.brandRow}><View style={styles.brandMark}><View style={styles.brandBarShort}/><View style={styles.brandBar}/><View style={styles.brandBarTall}/><View style={styles.brandBar}/><View style={styles.brandBarShort}/></View><Text style={styles.brand}>EchoSpace</Text><View style={styles.headerSpacer}/><TouchableOpacity accessibilityRole="button" accessibilityLabel="Profile" accessibilityHint="Open account options" accessibilityState={{expanded: profileOpen}} onPress={() => setProfileOpen(!profileOpen)} style={styles.profileButton}><View style={styles.profileIcon}><View style={styles.profileHead}/><View style={styles.profileShoulders}/></View><Text style={styles.profileButtonText}>Profile</Text></TouchableOpacity></View>
      {profileOpen && <View style={styles.profilePanel}>
        <Text style={styles.profileTitle}>Your profile</Text>
        <Text style={styles.profileIdentity} numberOfLines={2}>{guest ? 'Guest mode' : signedIn}</Text>
        <Text style={styles.hint}>{guest ? 'Sign in to use Deep.' : 'Your account is connected.'}</Text>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel={guest ? 'Sign in' : 'Sign out'} onPress={accountAction} style={styles.profileAction}><Text style={styles.profileActionText}>{guest ? 'Sign in' : 'Sign out'}</Text></TouchableOpacity>
      </View>}
      <View style={styles.heading}><Text style={styles.title}>Ready to explore?</Text><Text style={styles.description}>Put on your headphones, then start a live scan.</Text></View>
      <View style={styles.accountRow}><Text style={styles.hint}>{guest ? 'Deep is off · Guest mode' : !deepEnabled ? 'Deep is off' : voiceConnected ? 'Deep is listening' : voiceMessage ? 'Deep needs attention' : wakeStatus}</Text>
      </View>
      {!!voiceMessage && <View><Text accessibilityRole="alert" style={styles.error}>{voiceMessage}</Text><TouchableOpacity accessibilityRole="button" onPress={() => setVoiceAttempt(v => v + 1)}><Text style={styles.moreText}>Retry Deep</Text></TouchableOpacity></View>}
      <TouchableOpacity accessibilityRole="button" accessibilityLabel="Start live scan" onPress={startScan} style={styles.start} activeOpacity={0.85}><View style={styles.startText}><Text style={styles.startTitle}>Start live scan</Text><Text style={styles.startSubtitle}>Open camera and sound</Text></View><Text style={styles.startArrow}>→</Text></TouchableOpacity>
      {message ? <Text accessibilityRole="alert" style={styles.error}>{message}</Text> : null}
      <View style={styles.sectionTitleRow}><Text style={styles.sectionTitle}>Your settings</Text><Text style={styles.sectionNote}>Before you begin</Text></View>
      <View style={styles.group}>
        <Stepper label="Hearing distance" hint="Horizontal distance from your phone" value={range} min={1} max={20} step={1} unit=" m" onChange={setRange} />
        <View style={styles.divider} />
        <Stepper label="Sound level" value={audioVolume} min={0} max={100} step={5} unit="%" onChange={setAudioVolume} />
        <View style={styles.divider} />
        <Toggle label="Sound" hint="Hear nearby scanned points" value={audioEnabled} onChange={setAudioEnabled} />
        <View style={styles.divider} />
        <Toggle label="Blind mode" hint="Hide the scan view while listening" value={blindMode} onChange={setBlindMode} />
        {!!signedIn && <><View style={styles.divider}/><Toggle label="Deep" hint="Turn the voice assistant on or off" value={deepEnabled} onChange={setDeepEnabled}/><View style={styles.divider}/><Stepper label="Deep voice level" hint="Independent of spatial sound" value={deepVolume} min={0} max={100} step={5} unit="%" onChange={setDeepVolume}/></>}
      </View>
      <TouchableOpacity accessibilityRole="button" accessibilityLabel="More settings" accessibilityState={{expanded}} onPress={() => setExpanded(!expanded)} style={styles.more}><Text style={styles.moreText}>More settings</Text><Text style={styles.moreArrow}>{expanded ? '−' : '+'}</Text></TouchableOpacity>
      {expanded ? <View style={styles.group}>
        <Stepper label="Listen below phone" hint="Include lower objects" value={heightBelow} min={0.25} max={2.5} step={0.25} unit=" m" onChange={setHeightBelow}/><View style={styles.divider}/>
        <Stepper label="Listen above phone" hint="Include overhead obstacles" value={heightAbove} min={0.1} max={2} step={0.25} unit=" m" onChange={setHeightAbove}/><View style={styles.divider}/>
        <Toggle label="Reduce floor sound" hint="Experimental; may also quiet low obstacles" value={reduceFloor} onChange={setReduceFloor}/><View style={styles.divider}/>
        <Stepper label="Scan density" value={density} min={10} max={100} step={5} unit="%" onChange={setDensity}/><View style={styles.divider}/>
        <Stepper label="Point sounds" hint="Maximum simultaneous contributions" value={audioVoices} min={8} max={256} step={16} unit="" onChange={setAudioVoices}/>
      </View> : null}
      <Text style={styles.footer}>Move your phone slowly to scan your surroundings.</Text>
    </ScrollView>
  </SafeAreaView>;
}
const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: '#f5f7f2'}, page: {padding: 24, paddingBottom: 40},
  brandRow: {flexDirection: 'row', alignItems: 'center', marginTop: 8}, brand: {color: '#18332a', fontSize: 20, fontWeight: '800', marginLeft: 10}, brandMark: {width: 38, height: 38, borderRadius: 11, backgroundColor: '#264b3d', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 2}, brandBar: {width: 2, height: 13, borderRadius: 2, backgroundColor: '#cae9b6'}, brandBarTall: {width: 2, height: 20, borderRadius: 2, backgroundColor: '#cae9b6'}, brandBarShort: {width: 2, height: 7, borderRadius: 2, backgroundColor: '#cae9b6'},
  headerSpacer: {flex: 1}, profileButton: {minHeight: 48, paddingHorizontal: 12, flexDirection: 'row', alignItems: 'center', gap: 8, borderRadius: 12, backgroundColor: '#e8efe6'}, profileButtonText: {color: '#275744', fontSize: 13, fontWeight: '700'}, profileIcon: {width: 19, height: 19, alignItems: 'center', justifyContent: 'center'}, profileHead: {width: 7, height: 7, borderRadius: 4, borderWidth: 1.5, borderColor: '#275744'}, profileShoulders: {width: 13, height: 7, borderTopLeftRadius: 8, borderTopRightRadius: 8, borderWidth: 1.5, borderBottomWidth: 0, borderColor: '#275744', marginTop: 2}, profilePanel: {backgroundColor: '#fff', borderRadius: 14, padding: 16, marginTop: 12}, profileTitle: {color: '#203b30', fontWeight: '700', fontSize: 15}, profileIdentity: {color: '#203b30', fontSize: 14, marginTop: 10}, profileAction: {minHeight: 48, marginTop: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: '#eef3ed', borderRadius: 10}, profileActionText: {color: '#275744', fontWeight: '700', fontSize: 14},
  heading: {marginTop: 46, marginBottom: 22}, title: {color: '#162e25', fontSize: 30, lineHeight: 36, fontWeight: '700', letterSpacing: -0.7}, description: {fontSize: 15, lineHeight: 22, color: '#52675c', marginTop: 9, maxWidth: 310},
  start: {backgroundColor: '#285c46', borderRadius: 16, minHeight: 78, paddingHorizontal: 21, flexDirection: 'row', alignItems: 'center'}, startText: {flex: 1}, startTitle: {fontSize: 17, color: '#ffffff', fontWeight: '700'}, startSubtitle: {fontSize: 12, color: '#d3e9d5', marginTop: 4}, startArrow: {color: '#d2edc2', fontSize: 27}, error: {color: '#a73535', marginTop: 10},
  splash: {paddingHorizontal: 28, paddingTop: 54, paddingBottom: 40, flexGrow: 1}, loginBrand: {fontSize: 18, fontWeight: '700', color: '#315e47', marginTop: 16}, loginTitle: {fontSize: 32, lineHeight: 39, fontWeight: '700', color: '#162e25', marginTop: 35}, loginDescription: {fontSize: 16, lineHeight: 24, color: '#52675c', marginTop: 12, marginBottom: 25}, inputLabel: {fontSize: 14, fontWeight: '700', color: '#203b30', marginTop: 18}, input: {borderColor: '#bccdc0', backgroundColor: '#fff', borderWidth: 1, borderRadius: 10, minHeight: 52, paddingHorizontal: 14, color: '#203b30', marginTop: 8}, loginButton: {backgroundColor: '#285c46', borderRadius: 12, minHeight: 54, alignItems: 'center', justifyContent: 'center', marginTop: 28}, skipButton: {minHeight: 54, alignItems: 'center', justifyContent: 'center', marginTop: 8}, accountRow: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16},
  sectionTitleRow: {flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 38, marginBottom: 13}, sectionTitle: {fontSize: 19, color: '#18332a', fontWeight: '700'}, sectionNote: {fontSize: 12, color: '#687c70'}, group: {backgroundColor: '#ffffff', borderRadius: 16, paddingHorizontal: 16}, setting: {minHeight: 68, flexDirection: 'row', alignItems: 'center', paddingVertical: 12}, settingCopy: {flex: 1, paddingRight: 12}, settingLabel: {fontSize: 14, fontWeight: '700', color: '#203b30'}, hint: {fontSize: 12, lineHeight: 17, color: '#61776b', marginTop: 3}, divider: {height: StyleSheet.hairlineWidth, backgroundColor: '#dfe8df'}, stepper: {flexDirection: 'row', alignItems: 'center'}, step: {width: 38, height: 38, borderRadius: 10, backgroundColor: '#eef3ed', justifyContent: 'center', alignItems: 'center'}, stepText: {fontSize: 21, color: '#275744'}, stepValue: {minWidth: 54, textAlign: 'center', color: '#203b30', fontSize: 13, fontWeight: '700'}, more: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 20}, moreText: {fontSize: 14, fontWeight: '700', color: '#315e47'}, moreArrow: {fontSize: 22, color: '#315e47'}, footer: {fontSize: 12, lineHeight: 18, color: '#667b6e', marginTop: 12},
});
export default App;
