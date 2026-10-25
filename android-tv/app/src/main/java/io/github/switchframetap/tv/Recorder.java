/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** v0.7.16: the stream to an MP4, as the PC viewer records it: the console's
 *  H.264 as it arrives (no re-encoding, so the full picture quality) and its
 *  48 kHz PCM as AAC. It starts at the next keyframe (the engine asks the
 *  console for one). The receiver only hands packets over; the muxing runs on
 *  its own thread, so a slow card never holds the stream up - 2 s behind, the
 *  recording stops instead. */
final class Recorder {
    interface Done { void finished(Recorder recorder, boolean saved, String message); }

    private static final String TAG = "SFT-REC";
    private static final int RATE = 48000, CHANNELS = 2;
    private final Capture.Target target;
    private final Done done;
    private final ArrayBlockingQueue<SftProtocol.Packet> queue = new ArrayBlockingQueue<>(240);
    private final Thread thread;
    private volatile boolean stopping;
    private volatile boolean split;   // v0.7.18: the picture size changed - a new file goes on (#44)
    private volatile String failure;
    final long startedNs = System.nanoTime();

    private MediaMuxer muxer;
    private MediaCodec aac;
    private MediaFormat videoFormat, audioFormat;
    private int videoTrack = -1, audioTrack = -1;
    private boolean started, audioGivenUp;
    private long baseNs, keyNs, lastVideoUs = -1, lastAudioUs = -1;
    private long audioStartUs = -1, audioFrames;
    private long videoFrames, audioBytesIn;
    private final ArrayList<SftProtocol.Packet> pendingVideo = new ArrayList<>();
    private final ArrayList<byte[]> pendingAudio = new ArrayList<>();
    private final ArrayList<Long> pendingAudioUs = new ArrayList<>();
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(), videoInfo = new MediaCodec.BufferInfo();
    private int width, height;

    Recorder(Capture.Target target, Done done) {
        this.target = target; this.done = done;
        thread = new Thread(this::run, "sft-recorder");
    }
    void start() { thread.start(); }
    String displayName() { return target.shown; }

    /** From the receiver thread: never blocks. */
    void offer(SftProtocol.Packet packet) {
        if (stopping) return;
        if (!queue.offer(packet)) { failure = "this device could not keep up with the recording"; stopping = true; }
    }
    void stop() { stopping = true; }
    /** The recording ended only because the picture size changed: the app starts the next file. */
    boolean continues() { return split; }

    private void run() {
        boolean saved = false;
        String message;
        try {
            if (Build.VERSION.SDK_INT >= 26 && target.fd != null) muxer = new MediaMuxer(target.fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            else muxer = new MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            openAudio();
            while (!stopping) {
                SftProtocol.Packet p = queue.poll(100, TimeUnit.MILLISECONDS);
                if (p != null) handle(p);
                drainAudio(false);
                // no sound at all (muted on the console): video only after 1.5 s
                if (!started && videoFormat != null && audioFormat == null && System.nanoTime() - keyNs > 1_500_000_000L) {
                    giveUpAudio(); startMuxer();
                }
            }
            SftProtocol.Packet p;
            while ((p = queue.poll()) != null) handle(p);
            if (aac != null && started) endAudio();
            if (started && videoFrames > 0) { muxer.stop(); saved = true; }
            message = failure != null ? "Recording stopped: " + failure
                    : saved && split ? "Saved " + target.shown + " - the picture size changed, recording goes on in a new file"
                    : saved ? "Recording saved to " + target.shown
                    : "Nothing was recorded (no picture arrived)";
        } catch (IOException | RuntimeException | InterruptedException e) {
            Log.w(TAG, "recording failed", e);
            message = "Recording failed: " + e.getMessage();
            saved = false;
        } finally {
            if (aac != null) { try { aac.stop(); } catch (RuntimeException ignored) { } aac.release(); aac = null; }
            if (muxer != null) { try { muxer.release(); } catch (RuntimeException ignored) { } muxer = null; }
        }
        // a stopped muxer may still have written a playable file before the failure
        Capture.finish(target, saved);
        done.finished(this, saved, message);
    }

    private void openAudio() {
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no AAC encoder - recording the picture only", e);
            if (aac != null) aac.release();
            aac = null; audioGivenUp = true;
        }
    }
    private void giveUpAudio() {
        if (aac != null) { try { aac.stop(); } catch (RuntimeException ignored) { } aac.release(); aac = null; }
        audioGivenUp = true; pendingAudio.clear(); pendingAudioUs.clear();
    }

    private void handle(SftProtocol.Packet p) throws IOException {
        if (p.audio()) { audio(p); return; }
        if (p.status()) return;
        if (videoFormat != null && p.key() && (p.width != width || p.height != height)) {
            // v0.7.19: in the first half second, before anything is written, this is
            // the console switching to the size the recording asked for (one
            // started on the HOME menu): start over at this keyframe, no 0 s file
            if (!started && p.receivedNs - keyNs < 500_000_000L) {
                videoFormat = null; pendingVideo.clear();
            } else {
                // docked <-> handheld, the HOME menu at 720p: an MP4 track keeps one
                // picture size, so this file ends and the next one goes on (as the PC viewer does)
                split = true; stopping = true; return;
            }
        }
        if (videoFormat == null) {
            if (!p.key()) return;   // the file starts at a keyframe
            width = p.width; height = p.height;
            byte[][] csd = SftProtocol.parameterSets(p.data);
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0]));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1]));
            videoFormat = f; baseNs = keyNs = p.receivedNs;
            if (audioGivenUp) startMuxer();
        }
        if (!started) {
            if (pendingVideo.size() < 90) pendingVideo.add(p);
            if (audioFormat != null || audioGivenUp) startMuxer();
            return;
        }
        writeVideo(p);
    }
    private void writeVideo(SftProtocol.Packet p) {
        byte[] sample = SftProtocol.pictureNals(p.data);
        if (sample.length == 0) return;
        long us = Math.max(0, (p.receivedNs - baseNs) / 1000);
        if (us <= lastVideoUs) us = lastVideoUs + 1;
        lastVideoUs = us;
        videoInfo.set(0, sample.length, us, p.key() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(sample), videoInfo);
        videoFrames++;
    }

    /** PCM into the encoder, timed by its sample count from the first packet after the keyframe. */
    private void audio(SftProtocol.Packet p) {
        if (aac == null || videoFormat == null) return;
        long arrivedUs = (p.receivedNs - baseNs) / 1000;
        if (arrivedUs < 0) return;
        if (audioStartUs < 0) { audioStartUs = arrivedUs; audioFrames = 0; }
        long clockUs = audioStartUs + audioFrames * 1_000_000L / RATE;
        // the console sent nothing for a while (a loading screen): jump the clock, do not drift
        if (arrivedUs - clockUs > 150_000) { audioStartUs = arrivedUs; audioFrames = 0; clockUs = arrivedUs; }
        byte[] pcm = p.data;
        int off = 0, waits = 0;
        while (off < pcm.length) {
            int index = aac.dequeueInputBuffer(20_000);
            if (index < 0) {
                drainAudio(false);
                if (++waits > 25) return;   // the encoder is stuck: this packet's sound is lost, not the stream
                continue;
            }
            ByteBuffer in = aac.getInputBuffer(index);
            if (in == null) return;
            in.clear();
            int n = Math.min(in.remaining(), pcm.length - off) & ~3;
            if (n <= 0) { aac.queueInputBuffer(index, 0, 0, clockUs, 0); return; }
            in.put(pcm, off, n);
            aac.queueInputBuffer(index, 0, n, audioStartUs + audioFrames * 1_000_000L / RATE, 0);
            audioFrames += n / 4; off += n; audioBytesIn += n;
            drainAudio(false);
        }
    }
    private void drainAudio(boolean untilEnd) {
        if (aac == null) return;
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (true) {
            int index = aac.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = aac.getOutputFormat();
                if (!started && videoFormat != null) startMuxer();
                continue;
            }
            if (index < 0) {
                if (!untilEnd || System.nanoTime() > deadline) return;
                continue;
            }
            ByteBuffer out = aac.getOutputBuffer(index);
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(info.offset); out.limit(info.offset + info.size);
                if (started && audioTrack >= 0) writeAudio(out, info.presentationTimeUs);
                else if (pendingAudio.size() < 200) {
                    byte[] copy = new byte[info.size]; out.get(copy);
                    pendingAudio.add(copy); pendingAudioUs.add(info.presentationTimeUs);
                }
            }
            aac.releaseOutputBuffer(index, false);
            if (end) return;
        }
    }
    private void writeAudio(ByteBuffer data, long us) {
        if (us <= lastAudioUs) us = lastAudioUs + 1;
        lastAudioUs = us;
        MediaCodec.BufferInfo a = new MediaCodec.BufferInfo();
        a.set(data.position(), data.remaining(), us, MediaCodec.BUFFER_FLAG_KEY_FRAME);
        muxer.writeSampleData(audioTrack, data, a);
    }
    private void endAudio() {
        int index = aac.dequeueInputBuffer(50_000);
        if (index >= 0) aac.queueInputBuffer(index, 0, 0, audioStartUs < 0 ? 0 : audioStartUs + audioFrames * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drainAudio(true);
    }

    private void startMuxer() {
        if (started || videoFormat == null) return;
        videoTrack = muxer.addTrack(videoFormat);
        if (!audioGivenUp && audioFormat != null) audioTrack = muxer.addTrack(audioFormat);
        muxer.start();
        started = true;
        for (SftProtocol.Packet v : pendingVideo) writeVideo(v);
        pendingVideo.clear();
        if (audioTrack >= 0) for (int i = 0; i < pendingAudio.size(); i++) writeAudio(ByteBuffer.wrap(pendingAudio.get(i)), pendingAudioUs.get(i));
        pendingAudio.clear(); pendingAudioUs.clear();
    }
}
