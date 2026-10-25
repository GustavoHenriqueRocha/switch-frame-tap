/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Surface;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class StreamEngine {
    interface Listener {
        void status(String message);
        /** The console says why there is no picture (reason 0 clears it). */
        void consoleStatus(int reason, String head, String detail);
        void usbPermission(UsbDevice device);
        void connectedAddress(String ip);
    }
    final StreamStats stats = new StreamStats();
    private final Context context;
    private final Surface surface;
    private final boolean usb, stableAudio;
    private final String address;
    private String rememberedAddress;
    private final Listener listener;
    private volatile boolean stopped, muted;
    private volatile SftProtocol.Source source;
    private volatile DatagramSocket discovery;
    private volatile AudioPump audio;
    private volatile VideoDecoder decoder;
    private volatile Recorder recorder;      // v0.7.16: recording to an MP4
    private volatile boolean keyWanted;       // ... which starts at a keyframe asked for now
    private volatile boolean unlockWanted;    // v0.7.18: recording ended - the console may change size again
    private volatile boolean oneFile;         // v0.7.19: "One file per recording" (Settings) - the size lock is a choice
    private final Thread thread;
    StreamEngine(Context context, Surface surface, boolean usb, String address, String lastAddress,
                 boolean stableAudio, boolean muted, Listener listener) {
        this.context = context.getApplicationContext(); this.surface = surface;
        this.usb = usb; this.address = address;
        this.rememberedAddress = lastAddress;
        this.stableAudio = stableAudio; this.muted = muted; this.listener = listener;
        thread = new Thread(this::run, "sft-receiver");
    }
    void start() { thread.start(); }
    void mute(boolean value) { muted = value; AudioPump a = audio; if (a != null) a.mute(value); }
    /** v0.7.16: every packet from now on also goes to this recording. */
    void record(Recorder r) { recorder = r; keyWanted = true; }
    /** v0.7.19: while recording, ask the console to keep one picture size (off: a new file when it changes). */
    void oneFileRecording(boolean value) { oneFile = value; if (recorder != null) keyWanted = true; }
    /** Ends the recording (it finishes the file on its own thread). */
    void stopRecording() {
        Recorder r = recorder; recorder = null;
        if (r != null) { r.stop(); unlockWanted = true; keyWanted = true; }
    }
    /** v0.7.18: what to ask the console for - while recording, a keyframe and one
     *  stream size (v0.7.19: with "One file per recording" on); after it, a
     *  keyframe and the size free again. */
    private byte[] requestNow() {
        if (recorder != null) return oneFile ? SftProtocol.RECORD_LOCK : SftProtocol.RECORD_UNLOCK;
        return unlockWanted ? SftProtocol.RECORD_UNLOCK : SftProtocol.KEYFRAME_REQUEST;
    }
    void stop() {
        stopRecording();
        stopped = true; thread.interrupt();
        VideoDecoder v = decoder; if (v != null) v.requestStop();
        AudioPump a = audio; if (a != null) a.requestStop();
        DatagramSocket d = discovery; if (d != null) d.close();
        closeSource();
    }
    private void closeSource() {
        SftProtocol.Source s = source;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
    private void status(String message) { if (!stopped) listener.status(message); }
    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            while (!stopped) {
                VideoDecoder video = null;
                AudioPump a = null;
                try {
                    SftProtocol.Source connected = usb ? usbConnect() : networkConnect();
                    if (connected == null || stopped) break;
                    source = connected;
                    VideoQueue queue = new VideoQueue();
                    a = new AudioPump(stats, stableAudio); audio = a; a.mute(muted); a.start();
                    video = new VideoDecoder(surface, queue, stats, this::status); decoder = video; video.start();
                    if (stopped) break;
                    status("Connected. Open a game on the Switch…");
                    long keyAskedNs = 0, retryAtNs = 0;
                    int tries = 0;
                    while (!stopped) {
                        if (keyWanted && System.nanoTime() >= retryAtNs) {
                            keyWanted = false;
                            byte[] ask = requestNow();
                            if (connected.request(ask)) { tries = 0; retryAtNs = 0; if (ask == SftProtocol.RECORD_UNLOCK) unlockWanted = false; }
                            // USB: the console was not listening - again in 150 ms, for ~2 s
                            else if (++tries < 14) { keyWanted = true; retryAtNs = System.nanoTime() + 150_000_000L; }
                            else { tries = 0; retryAtNs = 0; }
                            keyAskedNs = System.nanoTime();
                        }
                        SftProtocol.Packet packet = SftProtocol.readPacket(connected, () -> stopped);
                        stats.bytes += packet.data.length + 32;
                        Recorder r = recorder;
                        if (r != null && !packet.status()) r.offer(packet);
                        if (packet.status()) {
                            if (!stopped) listener.consoleStatus(packet.statusReason(), packet.statusHead(), packet.statusDetail());
                        }
                        else if (packet.audio()) {
                            stats.audioPackets++; stats.audioBytes += packet.data.length;
                            a.offer(packet);
                        }
                        else {
                            stats.received++; stats.lastVideoNs = packet.receivedNs;
                            queue.offer(packet);
                            if (queue.takeKeyRequest() && packet.receivedNs - keyAskedNs > 300_000_000L) {
                                keyWanted = true; keyAskedNs = packet.receivedNs;   // v0.7.18: with the recording's size request, retried
                            }
                            stats.videoResets = queue.resets();
                            stats.videoDropped = queue.dropped();
                            // Video IDR recovery must not repeatedly flush the audio sink.
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    if (!stopped) {
                        Log.w("SFT-TV", "Transport", e);
                        status(e.getMessage() + ". Reconnecting…");
                    }
                } finally {
                    stopRecording();   // a new connection is a new stream: the file ends here
                    closeSource(); source = null;
                    // Cleanup must finish even when stop() interrupted the receiver.
                    Thread.interrupted();
                    if (video != null) video.close();
                    if (a != null) a.close();
                    audio = null;
                    decoder = null;
                    stats.lastRenderedNs = 0; stats.audioQueuedMs = 0;
                }
                pause(800);
            }
        } finally { closeSource(); }
    }
    private SftProtocol.Source usbConnect() throws IOException {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            status("This device does not support USB host mode. Use Network instead.");
            while (!stopped) pause(500);
            return null;
        }
        UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
        Set<String> requested = new HashSet<>();
        while (!stopped) {
            UsbDevice device = Transports.find(manager);
            if (device == null) {
                requested.clear();
                status("Waiting for the Switch over USB. Use USB viewer mode and a data cable.");
            } else if (!manager.hasPermission(device)) {
                status("Allow USB access. If permission was denied, select Connect to try again.");
                if (requested.add(device.getDeviceName())) listener.usbPermission(device);
            } else {
                source = new Transports.Usb(manager, device);
                if (stopped) { closeSource(); return null; }
                stats.transport = "USB";
                return source;
            }
            pause(500);
        }
        return null;
    }
    /** The address answered, but nothing listens there: the Switch is set to USB. */
    static final class RefusedException extends IOException {
        RefusedException(String message, Throwable cause) { super(message, cause); }
    }
    private Transports.Tcp tcp(String host, int port) throws IOException {
        Transports.Tcp tcp = new Transports.Tcp(); source = tcp;
        try {
            if (stopped) throw new IOException("Connection stopped");
            tcp.connect(host, port);
            if (stopped) throw new IOException("Connection stopped");
            stats.transport = "Network · " + host;
            rememberedAddress = host;
            listener.connectedAddress(host);
            return tcp;
        } catch (IOException e) {
            tcp.close(); source = null;
            // refused: the Switch is there, but set to USB (#24)
            if (String.valueOf(e.getMessage()).contains("ECONNREFUSED"))
                throw new RefusedException("The Switch at " + host + " answered but is not streaming over the network: set Connection to Network on the Switch and restart it", e);
            throw e;
        }
    }
    private SftProtocol.Source networkConnect() throws IOException {
        if (!address.isEmpty()) {
            status("Connecting to " + address + ":9950…");
            return tcp(address, Transports.PORT);
        }
        String recent = rememberedAddress;
        DatagramSocket beacon = new DatagramSocket(null);
        discovery = beacon;
        try {
            beacon.setReuseAddress(true);
            beacon.bind(new InetSocketAddress(Transports.BEACON));
            beacon.setSoTimeout(400);
            long nextTry = 0;
            String hint = null;
            while (!stopped) {
                if (SftProtocol.ipv4(recent) && System.nanoTime() >= nextTry) {
                    try { return tcp(recent, Transports.PORT); }
                    catch (IOException e) {
                        nextTry = System.nanoTime() + 3_000_000_000L;
                        hint = e instanceof RefusedException ? e.getMessage() : null;
                    }
                }
                status(hint != null ? hint : "Searching for the Switch on the network. Enter its IP address if it is not found.");
                byte[] bytes = new byte[256];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try {
                    beacon.receive(packet);
                    int port = SftProtocol.beaconPort(new String(bytes, 0, packet.getLength(), StandardCharsets.US_ASCII));
                    if (port != Transports.PORT || !(packet.getAddress() instanceof Inet4Address)) continue;
                    recent = packet.getAddress().getHostAddress(); // trust the sender address, not the beacon's IP
                    try { return tcp(recent, port); } catch (IOException ignored) { }
                } catch (SocketTimeoutException ignored) { }
            }
        } finally { beacon.close(); discovery = null; }
        return null;
    }
    private void pause(int ms) {
        if (stopped) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
