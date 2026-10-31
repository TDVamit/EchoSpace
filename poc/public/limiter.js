class PointLimiter extends AudioWorkletProcessor {
  constructor() {
    super();
    this.frames = 0;
    this.peak = 0;
  }
  process(inputs, outputs) {
    const input = inputs[0],
      output = outputs[0];
    if (!input?.length) return true;
    for (let c = 0; c < output.length; c++) {
      const source = input[Math.min(c, input.length - 1)],
        dest = output[c];
      for (let i = 0; i < dest.length; i++) {
        const x = source[i] || 0;
        this.peak = Math.max(this.peak, Math.abs(x));
        dest[i] = Math.tanh(x);
      }
    }
    this.frames += output[0]?.length || 128;
    if (this.frames >= sampleRate / 8) {
      this.port.postMessage({ peak: this.peak });
      this.frames = 0;
      this.peak = 0;
    }
    return true;
  }
}
registerProcessor("point-limiter", PointLimiter);
