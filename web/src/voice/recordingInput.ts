import { DENOISE_RATE, makeDenoiser, releaseDenoiser } from "./denoise";
import { getMic } from "./media";
import type { VoiceSettings } from "@/stores/settings";

/** The call's denoiser, without capture AGC boosting pauses or the call's input-volume boost. */
export async function recordingInput(settings: VoiceSettings) {
  const ctx = new AudioContext({ sampleRate: DENOISE_RATE });
  let raw: MediaStream | null = null;
  let denoiser: AudioNode | null = null;
  try {
    await ctx.resume();
    if (settings.noiseMode === "enhanced") denoiser = await makeDenoiser(ctx);
    raw = await getMic({ ...settings, autoGain: false, voiceChannels: "mono" }, { browserNoise: settings.noiseMode === "standard" || (settings.noiseMode === "enhanced" && !denoiser) });
    const source = ctx.createMediaStreamSource(raw);
    const highpass = ctx.createBiquadFilter();
    highpass.type = "highpass"; highpass.frequency.value = 75; highpass.Q.value = 0.707;
    const compressor = ctx.createDynamicsCompressor();
    compressor.threshold.value = -12; compressor.knee.value = 6; compressor.ratio.value = 4;
    compressor.attack.value = 0.003; compressor.release.value = 0.15;
    const headroom = ctx.createGain(); headroom.gain.value = 0.79;
    const destination = ctx.createMediaStreamDestination();
    source.connect(highpass);
    (denoiser ? highpass.connect(denoiser) : highpass).connect(compressor).connect(headroom).connect(destination);
    return { stream: destination.stream, ctx, release() {
      raw?.getTracks().forEach((track) => track.stop());
      destination.stream.getTracks().forEach((track) => track.stop());
      releaseDenoiser(denoiser); source.disconnect();
      void ctx.close().catch(() => undefined);
    } };
  } catch (error) {
    raw?.getTracks().forEach((track) => track.stop()); releaseDenoiser(denoiser);
    await ctx.close(); throw error;
  }
}
