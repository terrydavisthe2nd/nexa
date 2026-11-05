// Enhanced noise suppression (voice.md 8.2): xiph's RNNoise on the
// microphone after echo cancellation and before the encoder, run from the
// audio processing module's capture post-processing hook. RNNoise works on
// 10 ms of 48 kHz mono (480 samples) in the 16-bit range, which is exactly
// one block of the processing module at 48 kHz; at a lower internal rate the
// block is stretched up to 48 kHz and back.
//
// The same pass applies the input volume (0 to 200 %) and measures the level
// voice activity and the speaking ring go by (RMS x 4, as LevelMeter).

#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>

#include <algorithm>

extern "C" {
#include "rnnoise.h"
}

namespace {

constexpr int kFrame = 480;  // 10 ms at 48 kHz

struct Denoiser {
  DenoiseState* state = nullptr;
  float up[kFrame];
  float out[kFrame];
  float history[3][kFrame] = {};
  bool audible[3] = {true, true, true};
  int history_pos = 0;
  int hold = 0;
  float gate_gain = 1.f;
  float prev = 0.f;  // the last input sample of the block before, so stretching joins up
  long tone_at = 0;  // samples of test signal made so far
  uint32_t noise = 2463534242u;  // xorshift state of the test noise
};

// Linear stretch of `n` samples to `m`, continuing from `prev`.
void Stretch(const float* in, int n, float prev, float* out, int m) {
  for (int i = 0; i < m; i++) {
    const float pos = (float(i) + 1.f) * float(n) / float(m) - 1.f;
    const int a = int(floorf(pos));
    const float t = pos - float(a);
    const float x0 = a < 0 ? prev : in[std::min(a, n - 1)];
    const float x1 = in[std::min(a + 1, n - 1)];
    out[i] = x0 + (x1 - x0) * t;
  }
}

// A test signal in place of the microphone (debug builds): 1 is a steady 440 Hz
// tone, 2 the same in 1.5 s phrases with 1.5 s pauses, as the web's delay
// harness speaks, and 3 steady white noise (what noise suppression removes).
void Tone(Denoiser* d, float* data, int frames, int rate, int mode) {
  for (int i = 0; i < frames; i++) {
    const long n = d->tone_at++;
    if (mode == 3) {
      d->noise ^= d->noise << 13;
      d->noise ^= d->noise >> 17;
      d->noise ^= d->noise << 5;
      data[i] = (float(d->noise % 20001u) - 10000.f) * 0.4f;
      continue;
    }
    const double t = double(n) / double(rate);
    bool on = true;
    if (mode == 2) on = fmod(t, 3.0) < 1.5;
    data[i] = on ? float(sin(2.0 * M_PI * 440.0 * t) * 8000.0) : 0.f;
  }
}

void SpeechGate(Denoiser* d, float probability) {
  const bool speech = probability >= (d->hold ? .2f : .5f);
  if (speech) { d->hold = 20; std::fill_n(d->audible, 3, true); }
  else d->hold = std::max(0, d->hold - 1);
  memcpy(d->history[d->history_pos], d->out, sizeof(d->out));
  d->audible[d->history_pos] = d->hold > 0;
  d->history_pos = (d->history_pos + 1) % 3;
  const float target = d->audible[d->history_pos] ? 1.f : .02f;
  for (int i = 0; i < kFrame; ++i) {
    d->gate_gain += (target - d->gate_gain) * (target > d->gate_gain ? .02f : .002f);
    d->out[i] = d->history[d->history_pos][i] * d->gate_gain;
  }
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_fish_nexa_voice_Denoise_nativeCreate(JNIEnv*, jobject) {
  auto* d = new Denoiser();
  d->state = rnnoise_create(nullptr);
  if (d->state == nullptr) {
    delete d;
    return 0;
  }
  return reinterpret_cast<jlong>(d);
}

JNIEXPORT void JNICALL Java_fish_nexa_voice_Denoise_nativeDestroy(JNIEnv*, jobject, jlong ptr) {
  auto* d = reinterpret_cast<Denoiser*>(ptr);
  if (d == nullptr) return;
  rnnoise_destroy(d->state);
  delete d;
}

// Processes one block in place. Returns its level (0..1), or -1 when the
// buffer could not be read. With `pass` off the block is measured and then
// silenced: voice activity still hears it, the encoder gets silence (and
// Opus's DTX sends next to nothing), and the stream's timestamps keep moving.
JNIEXPORT jfloat JNICALL Java_fish_nexa_voice_Denoise_nativeProcess(
    JNIEnv* env, jobject, jlong ptr, jobject buffer, jint frames, jint rate, jboolean denoise, jfloat gain, jint tone,
    jboolean pass) {
  auto* d = reinterpret_cast<Denoiser*>(ptr);
  auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (d == nullptr || data == nullptr || frames <= 0 || capacity < jlong(frames) * jlong(sizeof(float))) return -1.f;

  if (tone != 0) Tone(d, data, frames, rate > 0 ? rate : frames * 100, tone);

  if (denoise == JNI_TRUE && frames <= kFrame) {
    if (frames == kFrame) {
      SpeechGate(d, rnnoise_process_frame(d->state, d->out, data));
      memcpy(data, d->out, sizeof(float) * kFrame);
    } else {
      const float last = data[frames - 1];
      Stretch(data, frames, d->prev, d->up, kFrame);
      SpeechGate(d, rnnoise_process_frame(d->state, d->out, d->up));
      // and back down, sample for sample in time
      for (int i = 0; i < frames; i++) {
        const float pos = (float(i) + 1.f) * float(kFrame) / float(frames) - 1.f;
        const int a = std::max(0, std::min(kFrame - 1, int(lroundf(pos))));
        data[i] = d->out[a];
      }
      d->prev = last;
    }
  }

  double sum = 0;
  for (int i = 0; i < frames; i++) {
    float x = data[i];
    if (gain != 1.f) {
      x = std::max(-32768.f, std::min(32767.f, x * gain));
      data[i] = x;
    }
    const double s = double(x) / 32768.0;
    sum += s * s;
  }
  if (pass != JNI_TRUE) memset(data, 0, sizeof(float) * size_t(frames));
  return float(std::min(1.0, sqrt(sum / frames) * 4.0));
}

// One block of the microphone as AudioRecord read it, interleaved 16-bit, in
// place, before WebRTC sees it. The capture hook above only ever gets the first
// channel, so with two-channel capture everything it does for a call happens
// here instead, to both, or a shut gate would leave the right channel open.
// Each channel has its own RNNoise and they share a gate, so a voice stays where
// it is. `apart` false mixes them into one voice first. With `pass` off the block
// is measured and silenced, however the rest of it went.
JNIEXPORT jfloat JNICALL Java_fish_nexa_voice_Denoise_nativeProcessTwoChannels(
    JNIEnv* env, jobject, jlong left, jlong right, jobject buffer, jint frames, jboolean denoise, jfloat gain, jboolean apart,
    jboolean pass) {
  auto* pcm = static_cast<int16_t*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (pcm == nullptr || frames <= 0 || capacity < jlong(frames) * 4) return -1.f;
  auto* d0 = reinterpret_cast<Denoiser*>(left);
  auto* d1 = reinterpret_cast<Denoiser*>(right);
  float in[2][kFrame];
  const bool clean = denoise == JNI_TRUE && d0 != nullptr && d1 != nullptr && frames <= kFrame;
  if (clean) {
    for (int i = 0; i < frames; i++) {
      const float l = float(pcm[i * 2]), r = float(pcm[i * 2 + 1]);
      in[0][i] = apart == JNI_TRUE ? l : (l + r) * .5f;
      in[1][i] = apart == JNI_TRUE ? r : in[0][i];
    }
    Denoiser* both[2] = {d0, d1};
    float probability = 0.f;
    for (int c = 0; c < 2; c++) {
      Denoiser* d = both[c];
      if (frames == kFrame) {
        probability = std::max(probability, rnnoise_process_frame(d->state, d->out, in[c]));
      } else {
        const float last = in[c][frames - 1];
        Stretch(in[c], frames, d->prev, d->up, kFrame);
        probability = std::max(probability, rnnoise_process_frame(d->state, d->out, d->up));
        d->prev = last;
      }
    }
    for (int c = 0; c < 2; c++) {
      SpeechGate(both[c], probability);
      for (int i = 0; i < frames; i++) {
        const int a = frames == kFrame ? i : std::max(0, std::min(kFrame - 1, int(lroundf((float(i) + 1.f) * float(kFrame) / float(frames) - 1.f))));
        in[c][i] = both[c]->out[a];
      }
    }
  }
  double sum = 0;
  for (int i = 0; i < frames; i++) {
    float l = clean ? in[0][i] : float(pcm[i * 2]);
    float r = clean ? in[1][i] : float(pcm[i * 2 + 1]);
    if (!clean && apart != JNI_TRUE) l = r = (l + r) * .5f;
    l = std::max(-32768.f, std::min(32767.f, l * gain));
    r = std::max(-32768.f, std::min(32767.f, r * gain));
    sum += (double(l) * l + double(r) * r) / (2.0 * 32768.0 * 32768.0);
    pcm[i * 2] = pass == JNI_TRUE ? int16_t(lroundf(l)) : 0;
    pcm[i * 2 + 1] = pass == JNI_TRUE ? int16_t(lroundf(r)) : 0;
  }
  return float(std::min(1.0, sqrt(sum / frames) * 4.0));
}

}  // extern "C"

// Voice recordings own a separate filter state from a running call.
extern "C" JNIEXPORT jlong JNICALL Java_fish_nexa_data_RecordingDsp_create(JNIEnv* e, jobject o) {
  return Java_fish_nexa_voice_Denoise_nativeCreate(e, o);
}
extern "C" JNIEXPORT void JNICALL Java_fish_nexa_data_RecordingDsp_destroy(JNIEnv* e, jobject o, jlong p) {
  Java_fish_nexa_voice_Denoise_nativeDestroy(e, o, p);
}
extern "C" JNIEXPORT void JNICALL Java_fish_nexa_data_RecordingDsp_process(JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jboolean enhanced) {
  auto* d = reinterpret_cast<Denoiser*>(ptr);
  if (!d || env->GetArrayLength(pcm) != kFrame) return;
  jshort data[kFrame];
  env->GetShortArrayRegion(pcm, 0, kFrame, data);
  for (int i = 0; i < kFrame; ++i) d->up[i] = float(data[i]);
  if (enhanced) SpeechGate(d, rnnoise_process_frame(d->state, d->out, d->up));
  else memcpy(d->out, d->up, sizeof(d->up));
  // A soft limiter only turns peaks down; never amplify the microphone or its noise.
  for (int i = 0; i < kFrame; ++i) {
    const float x = d->out[i] / 32768.f;
    const float magnitude = fabsf(x);
    const float limited = magnitude > .25f ? .25f + (magnitude - .25f) * .25f : magnitude;
    data[i] = jshort(std::clamp(copysignf(limited * .79f, x), -.79f, .79f) * 32767.f);
  }
  env->SetShortArrayRegion(pcm, 0, kFrame, data);
}
