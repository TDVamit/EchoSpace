import React, {useState} from 'react';
import {NativeModules, SafeAreaView, ScrollView, StatusBar, StyleSheet, Switch, Text, TouchableOpacity, View} from 'react-native';

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
  const [message, setMessage] = useState('');
  const startScan = async () => {
    try {
      setMessage('');
      await NativeModules.ARScanner.startScan({density, range, blindMode, audioEnabled, audioVoices, audioVolume: audioVolume / 100, heightBelow, heightAbove, reduceFloor});
    } catch (error) { setMessage(String(error)); }
  };
  return <SafeAreaView style={styles.safe}>
    <StatusBar barStyle="dark-content" />
    <ScrollView contentContainerStyle={styles.page}>
      <View style={styles.brandRow}><View style={styles.brandMark}><View style={styles.brandBarShort}/><View style={styles.brandBar}/><View style={styles.brandBarTall}/><View style={styles.brandBar}/><View style={styles.brandBarShort}/></View><Text style={styles.brand}>EchoSpace</Text></View>
      <View style={styles.heading}><Text style={styles.title}>Ready to explore?</Text><Text style={styles.description}>Put on your headphones, then start a live scan.</Text></View>
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
      </View>
      <TouchableOpacity accessibilityRole="button" accessibilityState={{expanded}} onPress={() => setExpanded(!expanded)} style={styles.more}><Text style={styles.moreText}>More settings</Text><Text style={styles.moreArrow}>{expanded ? '−' : '+'}</Text></TouchableOpacity>
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
  heading: {marginTop: 46, marginBottom: 22}, title: {color: '#162e25', fontSize: 30, lineHeight: 36, fontWeight: '700', letterSpacing: -0.7}, description: {fontSize: 15, lineHeight: 22, color: '#52675c', marginTop: 9, maxWidth: 310},
  start: {backgroundColor: '#285c46', borderRadius: 16, minHeight: 78, paddingHorizontal: 21, flexDirection: 'row', alignItems: 'center'}, startText: {flex: 1}, startTitle: {fontSize: 17, color: '#ffffff', fontWeight: '700'}, startSubtitle: {fontSize: 12, color: '#d3e9d5', marginTop: 4}, startArrow: {color: '#d2edc2', fontSize: 27}, error: {color: '#a73535', marginTop: 10},
  sectionTitleRow: {flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 38, marginBottom: 13}, sectionTitle: {fontSize: 19, color: '#18332a', fontWeight: '700'}, sectionNote: {fontSize: 12, color: '#687c70'}, group: {backgroundColor: '#ffffff', borderRadius: 16, paddingHorizontal: 16}, setting: {minHeight: 68, flexDirection: 'row', alignItems: 'center', paddingVertical: 12}, settingCopy: {flex: 1, paddingRight: 12}, settingLabel: {fontSize: 14, fontWeight: '700', color: '#203b30'}, hint: {fontSize: 12, lineHeight: 17, color: '#61776b', marginTop: 3}, divider: {height: StyleSheet.hairlineWidth, backgroundColor: '#dfe8df'}, stepper: {flexDirection: 'row', alignItems: 'center'}, step: {width: 38, height: 38, borderRadius: 10, backgroundColor: '#eef3ed', justifyContent: 'center', alignItems: 'center'}, stepText: {fontSize: 21, color: '#275744'}, stepValue: {minWidth: 54, textAlign: 'center', color: '#203b30', fontSize: 13, fontWeight: '700'}, more: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 20}, moreText: {fontSize: 14, fontWeight: '700', color: '#315e47'}, moreArrow: {fontSize: 22, color: '#315e47'}, footer: {fontSize: 12, lineHeight: 18, color: '#667b6e', marginTop: 12},
});
export default App;
