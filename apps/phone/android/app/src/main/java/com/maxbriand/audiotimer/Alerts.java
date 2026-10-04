package com.maxbriand.audiotimer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Vibration and tone, natively. Both used to run in the WebView — the buzz
 * through a plugin call, the beep through WebAudio — so both went quiet
 * whenever the hidden page stopped being scheduled. Here they are driven
 * straight from the engine thread and answer to nothing else.
 *
 * The tone plays on the alarm stream on purpose: this is a warning you asked
 * for mid-run, so a silenced ringer should not swallow it.
 */
class Alerts {

    private static final int SAMPLE_RATE = 22050;
    private static final int PULSE_MS = 200, GAP_MS = 60;
    private static final int RAMP_MS = 4;               // clip the square-wave click

    // Same patterns and pitches the web version uses, so the app sounds the
    // same whichever shell it runs in.
    private static final long[] VIB_HIGH = { 0, 300, 120, 300, 120, 400 };
    private static final long[] VIB_LOW  = { 0, 600, 250, 600 };
    private static final long[] VIB_PART = { 0, 90, 70, 90 };

    private final Context ctx;
    private volatile AudioTrack track;

    Alerts(Context ctx) { this.ctx = ctx; }

    void buzz(String dir, boolean vib, boolean snd) {
        boolean high = "high".equals(dir);
        if (vib) vibrate(high ? VIB_HIGH : VIB_LOW);
        if (snd) tone(high ? 880 : 294, 3);
    }

    void partDone(boolean vib, boolean snd) {
        if (vib) vibrate(VIB_PART);
        if (snd) tone(660, 2);
    }

    void cancel() {
        Vibrator v = vibrator();
        if (v != null) { try { v.cancel(); } catch (Exception e) { /* nothing playing */ } }
        stopTone();
    }

    private Vibrator vibrator() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm == null ? null : vm.getDefaultVibrator();
            }
            return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) { return null; }
    }

    /** Pattern is [delay, on, off, on, …] — Android's waveform shape. */
    void vibrate(long[] pattern) {
        Vibrator v = vibrator();
        if (v == null || pattern.length == 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception e) { /* vibrator busy or absent */ }
    }

    private void tone(final int freq, final int pulses) {
        stopTone();
        new Thread(() -> {
            AudioTrack t = null;
            try {
                short[] buf = square(freq, pulses);
                t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buf.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
                t.write(buf, 0, buf.length);
                track = t;
                t.play();
                Thread.sleep(buf.length * 1000L / SAMPLE_RATE + 120);
            } catch (Exception e) {
                // A device that refuses the track must not take the vibration
                // (or the session) down with it.
            } finally {
                if (t != null) { try { t.release(); } catch (Exception e) { /* already released */ } }
                if (track == t) track = null;
            }
        }, "zone-tone").start();
    }

    private void stopTone() {
        AudioTrack t = track;
        track = null;
        if (t != null) { try { t.stop(); t.release(); } catch (Exception e) { /* already stopped */ } }
    }

    private short[] square(int freq, int pulses) {
        int slot = PULSE_MS + GAP_MS;
        int n = SAMPLE_RATE * pulses * slot / 1000;
        short[] buf = new short[n];
        int halfPeriod = Math.max(1, SAMPLE_RATE / (freq * 2));
        int ramp = SAMPLE_RATE * RAMP_MS / 1000;
        int pulseSamples = SAMPLE_RATE * PULSE_MS / 1000;
        int slotSamples = SAMPLE_RATE * slot / 1000;
        for (int i = 0; i < n; i++) {
            int at = i % slotSamples;
            if (at >= pulseSamples) continue;
            double env = 1.0;
            if (at < ramp) env = at / (double) ramp;
            else if (at > pulseSamples - ramp) env = (pulseSamples - at) / (double) ramp;
            buf[i] = (short) (((i / halfPeriod) % 2 == 0 ? 9000 : -9000) * env);
        }
        return buf;
    }
}
