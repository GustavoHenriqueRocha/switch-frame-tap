/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.Locale;

import static io.github.switchframetap.tv.Ui.dp;

/** The viewer's two screens, as on the PC: a home screen while there is no
 *  picture (the state in a card, USB / Network, Connect), and a Settings
 *  drawer over the home screen or the game. OK, Menu or Back opens it over the
 *  game; Back closes it. */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root, home, drawer;
    private View scrim;
    private SurfaceView picture;
    private TextView hud, title, detail, note, ipLabel;
    private Ui.Ring ring;
    private EditText ip;
    private Button action, settingsButton, homeUsb, homeNet, drawerUsb, drawerNet;
    private Ui.Toggle muteToggle, stableToggle, statsToggle;
    private Ui.Toggle recordToggle;               // v0.7.16: screenshots and recordings (Capture, Recorder)
    private TextView recBadge;
    private Recorder recorder;
    private boolean recordStopping;
    private Runnable afterPermission;
    private static final int STORAGE_REQUEST = 7;
    private Updater updater;                       // v0.7.14: updates from GitHub
    private Button updateButton, updateAction;
    private TextView updateStatus;
    private LinearLayout ipRow;
    private boolean usbMode, resumed, surfaceReady, requested, tv, showingVideo;
    private boolean mute, stable, stats;
    private StreamEngine engine;
    private String message = "";
    private int consoleReason;
    private String consoleHead = "", consoleDetail = "";
    private long consoleAt;
    private long sampledNs, sampledFrames, sampledBytes, sampledReceived, sampledDecoded, sampledAudio;
    private AudioManager audioManager;
    private boolean focusGranted;
    private AudioManager.OnAudioFocusChangeListener audioFocus;
    private AudioFocusRequest audioFocusRequest;
    private int focusSerial;
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!usbAction().equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    || (device != null && ((UsbManager)getSystemService(USB_SERVICE)).hasPermission(device));
            if (!granted) {
                requested = false; stopEngine();
                setMessage("USB access was not allowed. Select Connect to ask again.");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("viewer", MODE_PRIVATE);
        usbMode = prefs.getBoolean("usb", false);
        mute = prefs.getBoolean("mute", false);
        stable = prefs.getBoolean("stable_audio", false);
        stats = prefs.getBoolean("stats", false);
        requested = prefs.getBoolean("auto", false);   // connect again if it was connected last time
        UiModeManager mode = (UiModeManager)getSystemService(UI_MODE_SERVICE);
        tv = mode != null && mode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        root = new FrameLayout(this); root.setBackgroundColor(Ui.BG); setContentView(root);
        picture = new SurfaceView(this);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        picture.getHolder().addCallback(this);
        picture.setOnClickListener(v -> openDrawer());   // a tap on the game: Settings
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) fitPicture();
        });
        hud = Ui.text(this, "", 13, Ui.INK, false);
        hud.setPadding(dp(14), dp(10), dp(14), dp(10)); hud.setLineSpacing(dp(3), 1);
        hud.setBackground(Ui.round(Ui.HUD, 8)); hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(dp(10), dp(10), 0, 0);
        root.addView(hud, hl);
        recBadge = Ui.text(this, "", 13, Ui.INK, true);
        recBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        recBadge.setBackground(Ui.round(0xCCB91C1C, 14)); recBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rl.setMargins(0, dp(10), dp(10), 0);
        root.addView(recBadge, rl);
        updater = new Updater(this, this::updateUpdater);
        buildHome();
        buildDrawer();
        if (prefs.getBoolean("update_check", true)) updater.check(prefs.getBoolean("update_betas", false));

        IntentFilter filter = new IntentFilter(usbAction());
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter);
        updateMode(); updateHome(); handler.post(tick);
    }

    /* ---- the home screen ---------------------------------------------------- */

    private void buildHome() {
        home = new FrameLayout(this); home.setBackgroundColor(Ui.BG);
        home.setClickable(true);   // taps here are not for the picture behind
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        // a phone held sideways is ~360 dp tall: the card scrolls there, and sits centred on a TV
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams scl = new FrameLayout.LayoutParams(-1, -1); scl.topMargin = dp(64);
        home.addView(scroll, scl);
        FrameLayout centre = new FrameLayout(this);
        scroll.addView(centre, new ScrollView.LayoutParams(-1, -1));

        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(new Ui.Dot(this, Ui.ACC), new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView name = Ui.text(this, "Switch Frame Tap", 21, Ui.INK, true); name.setPadding(dp(11), 0, 0, 0);
        brand.addView(name);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bl.setMargins(dp(32), dp(24), 0, 0);
        home.addView(brand, bl);

        settingsButton = new Button(this);
        settingsButton.setContentDescription("Settings");
        settingsButton.setStateListAnimator(null);
        android.graphics.drawable.LayerDrawable gear = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ Ui.states(Ui.SURF2, Ui.SURF3, 20), new Ui.Gear(Ui.INK) });
        gear.setLayerInset(1, dp(10), dp(10), dp(10), dp(10));
        settingsButton.setBackground(gear);
        settingsButton.setOnClickListener(v -> openDrawer());
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        gl.setMargins(0, dp(18), dp(32), 0);
        home.addView(settingsButton, gl);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cl.bottomMargin = dp(24);
        centre.addView(column, cl);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(Ui.SURF, 18));
        card.setPadding(dp(32), dp(26), dp(32), dp(22));
        int width = Math.min(dp(600), getResources().getDisplayMetrics().widthPixels - dp(48));
        column.addView(card, new LinearLayout.LayoutParams(width, -2));

        ring = new Ui.Ring(this);
        card.addView(ring, new LinearLayout.LayoutParams(dp(36), dp(36)));
        title = Ui.text(this, "", 23, Ui.INK, true); title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(8));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        detail = Ui.text(this, "", 15, Ui.DIM, false); detail.setGravity(Gravity.CENTER); detail.setLineSpacing(dp(3), 1);
        card.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        note = Ui.text(this, "", 13, Ui.FAINT, false); note.setGravity(Gravity.CENTER); note.setPadding(0, dp(6), 0, 0);
        card.addView(note, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout segments = segmented(); homeUsb = (Button)segments.getChildAt(0); homeNet = (Button)segments.getChildAt(1);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(18);
        card.addView(segments, sl);

        action = Ui.pill(this, "Connect", true, v -> {
            if (requested) disconnect(); else connect();
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, dp(42)); al.topMargin = dp(14);
        card.addView(action, al);

        updateButton = Ui.pill(this, "", true, v -> updateClicked());   // v0.7.14: a newer app on GitHub
        updateButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, dp(42)); ul.topMargin = dp(10);
        card.addView(updateButton, ul);

        if (tv) {
            LinearLayout hints = new LinearLayout(this); hints.setGravity(Gravity.CENTER);
            hints.addView(Ui.keyHint(this, "OK", "Select"));
            hints.addView(Ui.keyHint(this, "Menu", "Settings"));
            hints.addView(Ui.keyHint(this, "Back", "Close"));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2); hl.topMargin = dp(24);
            column.addView(hints, hl);
        }
        action.requestFocus();
    }

    /** USB | Network on a SURF2 track. */
    private LinearLayout segmented() {
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.round(Ui.SURF2, 10)); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button usb = Ui.pill(this, "USB", false, v -> chooseMode(true));
        Button net = Ui.pill(this, "Network", false, v -> chooseMode(false));
        for (Button b : new Button[]{usb, net}) {
            b.setPadding(dp(18), 0, dp(18), 0);
            track.addView(b, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        return track;
    }

    /* ---- the settings drawer ------------------------------------------------ */

    private void buildDrawer() {
        scrim = new View(this); scrim.setBackgroundColor(0x99000000); scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        drawer = new FrameLayout(this); drawer.setBackgroundColor(Ui.SURF); drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        int w = Math.min(dp(420), (int)(getResources().getDisplayMetrics().widthPixels * 0.9f));
        root.addView(drawer, new FrameLayout.LayoutParams(w, -1, Gravity.END));

        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        drawer.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(26), dp(20), dp(20), dp(14));
        head.addView(Ui.text(this, "Settings", 25, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.pill(this, "Close", false, v -> closeDrawer()), new LinearLayout.LayoutParams(-2, dp(40)));
        column.addView(head);
        View line = new View(this); line.setBackgroundColor(0x14FFFFFF);
        column.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(this);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(6), dp(14), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        section(body, "CAPTURE");
        actionRow(body, "Screenshot", "A PNG in Pictures/" + Capture.FOLDER + (tv ? " · P key" : ""), v -> { closeDrawer(); handler.postDelayed(this::screenshot, 250); });
        recordToggle = switchRow(body, "Record", "An MP4 with sound in Movies/" + Capture.FOLDER + (tv ? " · R or Record key" : ""), v -> toggleRecording());
        // v0.7.19: the v0.7.18 size lock, now a choice (off: never scaled, a new file when the size changes)
        toggleRow(body, "One file per recording", "Keeps the size the recording started at, scaling other sizes. Off: a new file when the size changes",
                prefs.getBoolean("one_file_recording", false), value -> {
            prefs.edit().putBoolean("one_file_recording", value).apply();
            StreamEngine e = engine; if (e != null) e.oneFileRecording(value);
        });

        section(body, "CONNECTION");
        LinearLayout conn = new LinearLayout(this); conn.setGravity(Gravity.CENTER_VERTICAL);
        conn.setPadding(dp(12), dp(8), dp(12), dp(8));
        conn.addView(Ui.text(this, "Connection", 16, Ui.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout seg = segmented(); drawerUsb = (Button)seg.getChildAt(0); drawerNet = (Button)seg.getChildAt(1);
        conn.addView(seg);
        body.addView(conn);
        ipRow = new LinearLayout(this); ipRow.setOrientation(LinearLayout.VERTICAL);
        ipRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        ipRow.addView(Ui.text(this, "Switch IP address", 16, Ui.INK, false));
        ip = new EditText(this);
        ip.setTextColor(Ui.INK); ip.setHintTextColor(Ui.FAINT); ip.setTypeface(Ui.regular); ip.setTextSize(15);
        ip.setSingleLine(true);
        ip.setHint("Blank: find it on the network");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(prefs.getString("ip", "")); ip.setSelectAllOnFocus(true);
        ip.setBackground(Ui.states(Ui.SURF2, Ui.SURF2, 10)); ip.setPadding(dp(14), dp(9), dp(14), dp(9));
        ip.setOnFocusChangeListener((v, focused) -> {
            if (!focused) prefs.edit().putString("ip", ip.getText().toString().trim()).apply();
        });
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2); il.topMargin = dp(8);
        ipRow.addView(ip, il);
        body.addView(ipRow);
        ipLabel = Ui.text(this, "", 13, Ui.DIM, false); ipLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        ipLabel.setLineSpacing(dp(3), 1);
        body.addView(ipLabel);

        section(body, "SOUND");
        muteToggle = toggleRow(body, "Mute", "This device only - the Switch keeps its own sound", mute, value -> {
            mute = value; prefs.edit().putBoolean("mute", value).apply();
            if (engine != null) engine.mute(value || !focusGranted);
        });
        stableToggle = toggleRow(body, "Stable audio", "A larger buffer (60 ms) if the sound drops out", stable, value -> {
            stable = value; prefs.edit().putBoolean("stable_audio", value).apply();
            if (engine != null) { stopEngine(); startEngine(); }
        });

        section(body, "DISPLAY");
        statsToggle = toggleRow(body, "Stats overlay", "Frame rates, bitrate, decoder and sound", stats, value -> {
            stats = value; prefs.edit().putBoolean("stats", value).apply();
            hud.setVisibility(value && engine != null ? View.VISIBLE : View.GONE);
        });

        section(body, "UPDATES");
        LinearLayout upd = new LinearLayout(this); upd.setGravity(Gravity.CENTER_VERTICAL);
        upd.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout ut = new LinearLayout(this); ut.setOrientation(LinearLayout.VERTICAL);
        ut.addView(Ui.text(this, "Switch Frame Tap " + Updater.version(this), 16, Ui.INK, false));
        updateStatus = Ui.text(this, "", 13, Ui.DIM, false); updateStatus.setPadding(0, dp(5), 0, 0);
        ut.addView(updateStatus);
        upd.addView(ut, new LinearLayout.LayoutParams(0, -2, 1));
        updateAction = Ui.pill(this, "Check", false, v -> updateClicked());
        LinearLayout.LayoutParams ual = new LinearLayout.LayoutParams(-2, dp(38)); ual.leftMargin = dp(12);
        upd.addView(updateAction, ual);
        body.addView(upd);
        toggleRow(body, "Include betas", "Offer test versions too, not only full releases", prefs.getBoolean("update_betas", false), value -> {
            prefs.edit().putBoolean("update_betas", value).apply();
            if (updater.state == Updater.State.LATEST || updater.state == Updater.State.FOUND) updater.check(value);
        });
        toggleRow(body, "Check at start-up", "Look for a new version when the app opens", prefs.getBoolean("update_check", true), value ->
            prefs.edit().putBoolean("update_check", value).apply());

        section(body, "ABOUT");
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { }
        TextView about = Ui.text(this, "Switch Frame Tap for Android " + version + "\n"
                + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + "\n"
                + "GPL-2.0 · github.com/TomasUribe/switch-frame-tap\n\n"
                + "Your controller stays connected to the Switch. Quality and resolution are chosen on the console.", 13, Ui.DIM, false);
        about.setLineSpacing(dp(4), 1); about.setPadding(dp(12), dp(4), dp(12), dp(8));
        body.addView(about);

        if (tv) {
            View line2 = new View(this); line2.setBackgroundColor(0x14FFFFFF);
            column.addView(line2, new LinearLayout.LayoutParams(-1, 1));
            LinearLayout hints = new LinearLayout(this); hints.setGravity(Gravity.CENTER_VERTICAL);
            hints.setPadding(dp(24), dp(12), dp(12), dp(14));
            hints.addView(Ui.keyHint(this, "Back", "close"));
            hints.addView(Ui.keyHint(this, "Up/Down", "move"));
            hints.addView(Ui.keyHint(this, "OK", "change"));
            column.addView(hints);
        }
    }
    private void section(LinearLayout body, String name) {
        TextView t = Ui.text(this, name, 12, Ui.FAINT, true);
        t.setLetterSpacing(0.06f); t.setPadding(dp(12), dp(18), dp(12), dp(8));
        body.addView(t);
    }
    private interface OnToggle { void changed(boolean value); }
    private Ui.Toggle toggleRow(LinearLayout body, String label, String sub, boolean on, OnToggle changed) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true); row.setClickable(true); row.setBackground(Ui.rowBackground());
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, label, 16, Ui.INK, false));
        TextView s = Ui.text(this, sub, 13, Ui.DIM, false); s.setPadding(0, dp(5), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle toggle = new Ui.Toggle(this); toggle.set(on);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(44), dp(24)); tl.leftMargin = dp(16);
        row.addView(toggle, tl);
        final boolean[] state = {on};
        row.setOnClickListener(v -> { state[0] = !state[0]; toggle.set(state[0]); changed.changed(state[0]); });
        body.addView(row);
        return toggle;
    }
    /** A row that does something when chosen. */
    private void actionRow(LinearLayout body, String label, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true); row.setClickable(true); row.setBackground(Ui.rowBackground());
        row.addView(Ui.text(this, label, 16, Ui.INK, false));
        TextView s = Ui.text(this, sub, 13, Ui.DIM, false); s.setPadding(0, dp(5), 0, 0);
        row.addView(s);
        row.setOnClickListener(click);
        body.addView(row);
    }
    /** A switch whose state the app sets (recording can also stop by itself). */
    private Ui.Toggle switchRow(LinearLayout body, String label, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true); row.setClickable(true); row.setBackground(Ui.rowBackground());
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, label, 16, Ui.INK, false));
        TextView s = Ui.text(this, sub, 13, Ui.DIM, false); s.setPadding(0, dp(5), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle toggle = new Ui.Toggle(this);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(44), dp(24)); tl.leftMargin = dp(16);
        row.addView(toggle, tl);
        row.setOnClickListener(click);
        body.addView(row);
        return toggle;
    }

    /* ---- v0.7.16: screenshots and recordings -------------------------------- */

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    /** Android 9 and older: the storage permission first, then the action. */
    private boolean storageReady(Runnable action) {
        if (!Capture.needsPermission()
                || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true;
        afterPermission = action;
        requestPermissions(new String[]{ android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
        return false;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != STORAGE_REQUEST) return;
        Runnable action = afterPermission; afterPermission = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { if (action != null) action.run(); }
        else toast("Allow storage access to save screenshots and recordings.");
    }
    private void screenshot() {
        if (engine == null || !showingVideo) { toast("No picture to save yet - start a game on the Switch."); return; }
        if (Build.VERSION.SDK_INT < 24) { toast("Screenshots need Android 7 or newer."); return; }
        if (!storageReady(this::screenshot)) return;
        copyPicture();
    }
    @android.annotation.TargetApi(24)   // PixelCopy; screenshot() checks the version
    private void copyPicture() {
        if (Build.VERSION.SDK_INT < 24 || engine == null) return;
        int w = engine.stats.width, h = engine.stats.height;
        if (w < 16 || h < 16) return;
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(picture, bitmap, result -> {
                if (result != PixelCopy.SUCCESS) { bitmap.recycle(); toast("The screenshot failed (the picture could not be copied)."); return; }
                new Thread(() -> {
                    String text;
                    try { text = "Screenshot saved to " + Capture.savePng(getApplicationContext(), bitmap); }
                    catch (IOException | RuntimeException e) { text = "The screenshot failed: " + e.getMessage(); }
                    bitmap.recycle();
                    final String shown = text;
                    handler.post(() -> toast(shown));
                }, "sft-screenshot").start();
            }, handler);
        } catch (IllegalArgumentException e) { bitmap.recycle(); toast("The screenshot failed: " + e.getMessage()); }
    }
    private void toggleRecording() {
        if (recorder != null) {
            if (engine != null) engine.stopRecording(); else recorder.stop();
            recordStopping = true; updateRecording();
            return;
        }
        if (engine == null || !showingVideo) { toast("Nothing to record yet - start a game on the Switch."); updateRecording(); return; }
        if (!storageReady(this::toggleRecording)) return;
        final Recorder r;
        try {
            r = new Recorder(Capture.newVideo(getApplicationContext()), (done, saved, text) -> handler.post(() -> {
                boolean next = recorder == done && done.continues() && !recordStopping;
                if (recorder == done) { recorder = null; recordStopping = false; }
                updateRecording();
                toast(text);
                if (next) toggleRecording();   // v0.7.18: the next file, at the new size (#44)
            }));
        } catch (IOException | RuntimeException e) { toast("Could not start recording: " + e.getMessage()); return; }
        recorder = r; r.start(); engine.record(r);
        updateRecording();
    }
    private void updateRecording() {
        boolean on = recorder != null;
        if (recordToggle != null) recordToggle.set(on);
        recBadge.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && recordStopping) recBadge.setText("Saving…");
        else if (on) {
            long s = (System.nanoTime() - recorder.startedNs) / 1_000_000_000L;
            recBadge.setText(String.format(Locale.ROOT, "● REC %d:%02d", s / 60, s % 60));
        }
    }

    /* ---- v0.7.14: updates (Updater.java) ---- */
    private void updateClicked() {
        switch (updater.state) {
            case FOUND: updater.install(); break;
            case FAILED: if (!updater.url.isEmpty()) updater.install(); else updater.check(prefs.getBoolean("update_betas", false)); break;
            case CHECKING: case GETTING: case INSTALLING: break;
            default: updater.check(prefs.getBoolean("update_betas", false)); break;
        }
    }
    private void updateUpdater() {
        Updater.State st = updater.state;
        if (updateStatus != null) updateStatus.setText(updater.status(Updater.version(this)));
        if (updateAction != null) {
            updateAction.setText(st == Updater.State.FOUND ? "Update" : st == Updater.State.FAILED && !updater.url.isEmpty() ? "Try again" : "Check");
            updateAction.setEnabled(st != Updater.State.CHECKING && st != Updater.State.GETTING && st != Updater.State.INSTALLING);
            Ui.stylePill(updateAction, st == Updater.State.FOUND);
        }
        if (updateButton != null) {
            boolean show = st == Updater.State.FOUND || st == Updater.State.GETTING || st == Updater.State.INSTALLING
                    || (st == Updater.State.FAILED && !updater.url.isEmpty());
            updateButton.setVisibility(show ? View.VISIBLE : View.GONE);
            updateButton.setText(st == Updater.State.FOUND ? "Update to " + updater.tag
                    : st == Updater.State.GETTING ? updater.status("")
                    : st == Updater.State.INSTALLING ? "Confirm the install on screen"
                    : "Update failed - try again");
            updateButton.setEnabled(st == Updater.State.FOUND || st == Updater.State.FAILED);
        }
    }

    private void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        scrim.setVisibility(View.VISIBLE); drawer.setVisibility(View.VISIBLE);
        (usbMode ? drawerUsb : drawerNet).requestFocus();
    }
    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        scrim.setVisibility(View.GONE); drawer.setVisibility(View.GONE);
        prefs.edit().putString("ip", ip.getText().toString().trim()).apply();
        InputMethodManager keyboard = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(ip.getWindowToken(), 0);
        updateHome();
        if (home.getVisibility() == View.VISIBLE) action.requestFocus();
    }
    private boolean drawerOpen() { return drawer.getVisibility() == View.VISIBLE; }

    /* ---- connecting --------------------------------------------------------- */

    private void chooseMode(boolean usb) {
        if (usb == usbMode) return;
        boolean was = requested;
        stopEngine(); usbMode = usb;
        prefs.edit().putBoolean("usb", usb).apply();
        updateMode();
        setMessage("");
        if (was) startEngine();   // keep connecting, now the other way
    }
    private void updateMode() {
        for (Button b : new Button[]{homeUsb, drawerUsb}) Ui.styleSegment(b, usbMode);
        for (Button b : new Button[]{homeNet, drawerNet}) Ui.styleSegment(b, !usbMode);
        ipRow.setVisibility(usbMode ? View.GONE : View.VISIBLE);
        ipLabel.setText(usbMode ? "Set Connection to \"USB - PC viewer\" in the Switch Frame Tap app on the Switch too."
                : "Set Connection to Network in the Switch Frame Tap app on the Switch too; it shows the Switch's IP address.");
        updateHome();
    }
    private void connect() {
        String address = ip.getText().toString().trim();
        if (!usbMode && !address.isEmpty() && !SftProtocol.ipv4(address)) {
            setMessage("That is not an IPv4 address (such as 192.168.1.50). Leave it blank to find the Switch.");
            openDrawer(); ip.requestFocus(); return;
        }
        InputMethodManager keyboard = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(ip.getWindowToken(), 0);
        prefs.edit().putString("ip", address).putBoolean("auto", true).apply();
        requested = true; stopEngine(); startEngine();
    }
    private void disconnect() {
        requested = false; prefs.edit().putBoolean("auto", false).apply();
        stopEngine(); setMessage(""); updateHome();
    }
    private void startEngine() {
        if (!requested || !resumed || !surfaceReady || engine != null) return;
        final int serial = ++focusSerial;
        audioFocus = value -> {
            if (serial != focusSerial) return; // ignore delayed callbacks from a previous connection
            focusGranted = value == AudioManager.AUDIOFOCUS_GAIN;
            if (engine != null) engine.mute(mute || !focusGranted);
        };
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(audioFocus, handler).setWillPauseWhenDucked(true).build();
            focusGranted = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focusGranted = audioManager.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        final StreamEngine[] identity = new StreamEngine[1];
        StreamEngine.Listener listener = new StreamEngine.Listener() {
            @Override public void status(String value) { handler.post(() -> { if (engine == identity[0]) setMessage(value); }); }
            @Override public void consoleStatus(int reason, String head, String text) {
                handler.post(() -> {
                    if (engine != identity[0]) return;
                    consoleReason = reason; consoleHead = head; consoleDetail = text; consoleAt = System.nanoTime();
                    updateHome();
                });
            }
            @Override public void connectedAddress(String value) {
                handler.post(() -> { if (engine == identity[0]) prefs.edit().putString("last_ip", value).apply(); });
            }
            @Override public void usbPermission(UsbDevice device) {
                handler.post(() -> {
                    if (engine != identity[0] || !resumed) return;
                    PendingIntent result = PendingIntent.getBroadcast(MainActivity.this, 0,
                            new Intent(usbAction()).setPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    try { ((UsbManager)getSystemService(USB_SERVICE)).requestPermission(device, result); }
                    catch (RuntimeException e) { setMessage("Could not ask for USB access: " + e.getMessage()); }
                });
            }
        };
        engine = new StreamEngine(this, picture.getHolder().getSurface(), usbMode, prefs.getString("ip", ""),
                prefs.getString("last_ip", ""), stable, mute || !focusGranted, listener);
        engine.oneFileRecording(prefs.getBoolean("one_file_recording", false));
        identity[0] = engine; sampledNs = System.nanoTime();
        sampledBytes = sampledFrames = sampledReceived = sampledDecoded = sampledAudio = 0;
        consoleReason = 0; consoleAt = 0;
        setMessage(""); engine.start(); updateHome();
    }
    private void stopEngine() {
        StreamEngine old = engine; engine = null;
        focusSerial++;
        if (old != null) old.stop();
        hud.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        else if (audioFocus != null) audioManager.abandonAudioFocus(audioFocus);
        audioFocusRequest = null; audioFocus = null; focusGranted = false;
        showVideo(false);
    }

    /* ---- what the home screen says ------------------------------------------ */

    private void setMessage(String value) { message = value == null ? "" : value; updateHome(); }
    private void updateHome() {
        if (title == null) return;
        boolean fresh = consoleAt != 0 && System.nanoTime() - consoleAt < 4_000_000_000L && consoleReason != 0;
        String t, d, n = "";
        int color = Ui.ACC;
        if (!requested) {
            t = "Ready to connect";
            d = usbMode ? "Connect the Switch to this device with a USB-C data cable, in handheld mode."
                        : "The Switch and this device on the same network. Playing docked works.";
            String addr = ip.getText().toString().trim();
            n = usbMode ? "Playing docked? Choose Network."
                        : addr.isEmpty() ? "It finds the Switch by itself, or set its address in Settings." : "Connecting to " + addr + " (Settings to change it)";
        } else if (fresh) {
            t = consoleHead.isEmpty() ? "Connected" : consoleHead;
            d = consoleDetail;
            color = Ui.GOOD;
        } else if (engine != null && engine.stats.transport.length() > 0 && message.startsWith("Connected")) {
            t = "Connected"; d = "Open a game on the Switch and the picture appears here."; color = Ui.GOOD;
        } else {
            t = usbMode ? "Waiting for the Switch" : "Looking for the Switch";
            d = message.isEmpty() ? (usbMode ? "Connect it with a USB-C data cable, in handheld mode."
                                             : "Searching the network. Enter its IP address if it is not found.") : message;
            if (message.endsWith("Reconnecting…")) color = Ui.WARN;
        }
        title.setText(t); detail.setText(d); note.setText(n);
        note.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        ring.setColor(color);
        action.setText(requested ? "Disconnect" : "Connect");
        Ui.stylePill(action, !requested);
    }
    private void showVideo(boolean value) {
        if (value == showingVideo) return;
        showingVideo = value;
        home.setVisibility(value ? View.GONE : View.VISIBLE);
        if (!value && !drawerOpen()) action.requestFocus();
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (engine != null) {
                StreamStats s = engine.stats;
                long now = System.nanoTime();
                boolean active = s.lastRenderedNs > 0 && now - s.lastRenderedNs < 1_500_000_000L;
                showVideo(active);
                fitPicture();
                double seconds = (now - sampledNs) / 1_000_000_000.0;
                if (seconds >= 1) {
                    double fps = (s.rendered - sampledFrames) / seconds;
                    double mbps = (s.bytes - sampledBytes) * 8 / seconds / 1_000_000;
                    double rx = (s.received - sampledReceived) / seconds;
                    double decoded = (s.decoded - sampledDecoded) / seconds;
                    double audioKb = (s.audioBytes - sampledAudio) / seconds / 1000;
                    hud.setText(String.format(Locale.ROOT,
                            "%s · %d×%d · %.1f Mbps\nVideo RX %.1f · decoded %.1f · shown %.1f FPS\nQueue + decode %.1f ms · console age %.1f ms\nAudio RX %.0f kB/s · played %.0f ms · queued %.0f ms\nAudio %s · TV buffer %.0f ms · %s\nErrors %d · dropped %d/%d · adjusted PTS %d\n%s",
                            s.transport, s.width, s.height, mbps, rx, decoded, fps, s.decoderMs, s.consoleMs,
                            audioKb, s.audioPlayedFrames / 48.0, s.audioQueuedMs,
                            s.audioMuted ? "muted" : "active", s.audioBufferMs,
                            focusGranted ? "focus granted" : "no audio focus", s.decoderErrors, s.skipped,
                            s.videoDropped, s.unmatchedPts, s.decoder + " · " + s.audio));
                    sampledNs = now; sampledFrames = s.rendered; sampledBytes = s.bytes;
                    sampledReceived = s.received; sampledDecoded = s.decoded; sampledAudio = s.audioBytes;
                }
                hud.setVisibility(stats ? View.VISIBLE : View.GONE);
                if (recorder != null) updateRecording();
                if (!active) updateHome();   // a console status ages out after 4 s
            }
            handler.postDelayed(this, 250);
        }
    };
    private void fitPicture() {
        if (root.getWidth() == 0 || root.getHeight() == 0) return;
        int w = engine == null ? 16 : Math.max(16, engine.stats.width);
        int h = engine == null ? 9 : Math.max(9, engine.stats.height);
        double ratio = (double)w/h;
        int fitW = root.getWidth(), fitH = (int)(fitW / ratio);
        if (fitH > root.getHeight()) { fitH = root.getHeight(); fitW = (int)(fitH * ratio); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)picture.getLayoutParams();
        if (lp.width != fitW || lp.height != fitH) { lp.width = fitW; lp.height = fitH; picture.setLayoutParams(lp); }
    }

    /* ---- keys and lifecycle ------------------------------------------------- */

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (event.getRepeatCount() == 0 && !drawerOpen() && showingVideo) {
            // v0.7.16: a keyboard or a remote with a record key
            if (code == KeyEvent.KEYCODE_MEDIA_RECORD || code == KeyEvent.KEYCODE_R) { toggleRecording(); return true; }
            if (code == KeyEvent.KEYCODE_P || code == KeyEvent.KEYCODE_SYSRQ) { screenshot(); return true; }
        }
        if (event.getRepeatCount() == 0 && !drawerOpen()) {
            boolean open = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || (showingVideo && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                                         || code == KeyEvent.KEYCODE_BUTTON_A));
            if (open) { openDrawer(); return true; }
        }
        return super.onKeyDown(code, event);
    }
    @Override public void onBackPressed() {
        if (drawerOpen()) closeDrawer();
        else if (showingVideo) openDrawer();
        else { stopEngine(); super.onBackPressed(); }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; immersive(); startEngine(); }
    @Override protected void onPause() { resumed = false; stopEngine(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(permissionReceiver); updater.release(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; startEngine(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopEngine(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private String usbAction() { return getPackageName() + ".USB_PERMISSION"; }
}
