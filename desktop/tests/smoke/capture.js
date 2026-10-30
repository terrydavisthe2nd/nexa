/* Actual native X11 video and a Pulse producer, received by the webview peer.
 * CI uses a null sink: none of these tones reaches a physical speaker. */
async function qualifyNativeCapture(bridge, report, post) {
  navigator.mediaDevices.getDisplayMedia = () => { throw new Error("Browser capture must never run"); };
  const pattern = document.createElement("div");
  pattern.style.cssText = "position:fixed;inset:0;background:linear-gradient(90deg,#e02020 50%,#2040e0 50%);z-index:9999";
  document.body.append(pattern);
  const context = new AudioContext({ sampleRate: 48000 });
  // A real runner click, not an autoplay-policy override, starts Nexa playback.
  await new Promise((resolve, reject) => {
    document.addEventListener("click", () => context.resume().then(resolve, reject), { once: true });
    post("native-activate").catch(reject);
  });
  const own = context.createOscillator();
  const gain = context.createGain();
  gain.gain.value = 0.12;
  own.frequency.value = 1709;
  own.connect(gain).connect(context.destination);
  own.start();
  await new Promise((resolve) => setTimeout(resolve, 700));

  async function take(application, withAudio) {
    const unpick = bridge.onDisplaySources((request) => {
      report.captureSources = request;
      const source = request.sources.find((s) => s.kind === "screen");
      const sound = request.sounds.find((s) => s.name.includes(application));
      if (!source || (withAudio && !sound)) {
        bridge.chooseDisplaySource(request.requestId, null, false);
        return;
      }
      bridge.chooseDisplaySource(request.requestId, source.id, withAudio, sound?.id);
    });
    try { return await bridge.captureScreen({ width: 1280, height: 720, fps: 30, audio: withAudio }); }
    finally { unpick(); }
  }
  function magnitude(samples, frequency) {
    let sine = 0, cosine = 0;
    for (let i = 0; i < samples.length; i++) {
      const angle = 2 * Math.PI * frequency * i / context.sampleRate;
      sine += samples[i] * Math.sin(angle);
      cosine += samples[i] * Math.cos(angle);
    }
    return 2 * Math.hypot(sine, cosine) / samples.length;
  }
  async function measure(stream) {
    const source = context.createMediaStreamSource(new MediaStream(stream.getAudioTracks()));
    const split = context.createChannelSplitter(2);
    const analysers = [context.createAnalyser(), context.createAnalyser()];
    analysers.forEach((a, i) => { a.fftSize = 32768; split.connect(a, i); });
    source.connect(split);
    const silent = context.createGain();
    silent.gain.value = 0;
    analysers.forEach((a) => a.connect(silent));
    silent.connect(context.destination);
    await new Promise((resolve) => setTimeout(resolve, 2200));
    const result = analysers.map((analyser) => {
      const samples = new Float32Array(analyser.fftSize);
      analyser.getFloatTimeDomainData(samples);
      return Object.fromEntries([431, 863, 1291, 1709].map((hz) => [hz, magnitude(samples, hz)]));
    });
    source.disconnect(); split.disconnect();
    analysers.forEach((a) => a.disconnect()); silent.disconnect();
    return result;
  }
  const active = [];
  try {
    await post("native-capture");
    const stream = await take("Nexa Tone Selected", true);
    active.push(stream);
    const video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.srcObject = stream;
    await video.play();
    const canvas = document.createElement("canvas");
    canvas.width = 160; canvas.height = 90;
    const draw = canvas.getContext("2d");
    let red = 0, blue = 0;
    for (let attempt = 0; attempt < 30 && (red < 500 || blue < 500); attempt++) {
      await new Promise((r) => setTimeout(r, 100));
      draw.drawImage(video, 0, 0, 160, 90);
      const pixels = draw.getImageData(0, 0, 160, 90).data;
      red = 0; blue = 0;
      for (let i = 0; i < pixels.length; i += 4) {
        if (pixels[i] > 140 && pixels[i + 2] < 90) red++;
        if (pixels[i + 2] > 140 && pixels[i] < 90) blue++;
      }
    }
    report.nativeCapture = { red, blue, width: video.videoWidth, height: video.videoHeight,
      selected: await measure(stream), tracks: stream.getTracks().map((t) => t.kind) };
    bridge.stopScreen(stream);
    report.nativeCapture.stopped = stream.getTracks().every((t) => t.readyState === "ended");
    video.srcObject = null;
    await new Promise((r) => setTimeout(r, 500));
    // Inclusion positive control: the supposedly excluded external tone must
    // really be playing, and capturing it deliberately must make it audible.
    const control = await take("Nexa Tone Unrelated", true);
    active.push(control);
    report.nativeCapture.unrelatedControl = await measure(control);
    bridge.stopScreen(control);
    await new Promise((r) => setTimeout(r, 500));
    const silent = await take("", false);
    active.push(silent);
    report.nativeCapture.audioOff = silent.getAudioTracks().length === 0;
    bridge.stopScreen(silent);
  } finally {
    active.forEach((stream) => bridge.stopScreen(stream));
    own.stop(); gain.disconnect(); await context.close(); pattern.remove();
  }
}
