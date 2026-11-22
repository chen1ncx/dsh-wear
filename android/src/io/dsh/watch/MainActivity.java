package io.dsh.watch;

import android.app.Activity;
import android.Manifest;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.VelocityTracker;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DSH on the wrist: a session list, the tail of one conversation, and the
 * approval / question cards that need a human — all over the tiny HTTP bridge
 * the dsh-watch-bridge plugin exposes on the DSH host.
 *
 * Everything is built in code with framework widgets only: no AndroidX, no
 * resources beyond the icon, because this has to build with aapt2 + javac and
 * run on an old watch with a 402x476 screen.
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------ palette
    // Material 3 dark tokens; the accent set is switchable from the theme page.
    int C_BG;
    int C_CARD;
    int C_CARD_HI;
    int C_TEXT;
    int C_MUTED;
    int C_ACCENT;
    int C_ON_ACCENT;
    int C_GREEN;
    int C_RED;
    int C_ERR_BG;
    int C_BLUE;
    int C_LINE;
    /** Frame colour of the conversation cards, a step lighter than the fill. */
    int C_EDGE;

    /** bg, card, cardHi, accent, onAccent, tertiary, error, errorBg */
    static final int[][] THEMES = {
            {0xFF141218, 0xFF211F26, 0xFF2B2930, 0xFFD0BCFF, 0xFF381E72, 0xFFEFB8C8, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF101418, 0xFF1A2027, 0xFF232B34, 0xFFA8C7FA, 0xFF00325B, 0xFFB8E0FF, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF0F1512, 0xFF1A211C, 0xFF232B25, 0xFFA8D5BA, 0xFF06331F, 0xFFB7E4D0, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF16130E, 0xFF211C15, 0xFF2B251C, 0xFFF5C26B, 0xFF3B2E00, 0xFFDCC7A0, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF171114, 0xFF241C21, 0xFF2E2429, 0xFFF2B8C6, 0xFF4A0F26, 0xFFF6D2DC, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF0D1416, 0xFF162023, 0xFF1F2A2E, 0xFF9BD6E8, 0xFF00363F, 0xFFBFE6F0, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF171210, 0xFF231B17, 0xFF2D231E, 0xFFF4B08A, 0xFF4A2200, 0xFFF0D2BE, 0xFFF2B8B5, 0xFF8C1D18},
            {0xFF121316, 0xFF1C1D22, 0xFF26272D, 0xFFC7CBE0, 0xFF1F2333, 0xFFDDE1F5, 0xFFF2B8B5, 0xFF8C1D18},
    };

    /** Lighten `from` toward `toward` by `amount`, keeping it opaque. */
    static int blend(int from, int toward, float amount) {
        int r = (int) (((from >> 16) & 0xFF) + (((toward >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int g = (int) (((from >> 8) & 0xFF) + (((toward >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int b = (int) ((from & 0xFF) + ((toward & 0xFF) - (from & 0xFF)) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    void applyTheme(int index) {
        Log.i(TAG, "applyTheme " + index);
        int[] t = THEMES[Math.floorMod(index, THEMES.length)];
        C_BG = t[0];
        C_CARD = t[1];
        C_CARD_HI = t[2];
        C_ACCENT = t[3];
        C_ON_ACCENT = t[4];
        C_BLUE = t[5];
        C_RED = t[6];
        C_ERR_BG = t[7];
        C_GREEN = C_ACCENT;
        C_TEXT = 0xFFE6E0E9;
        C_MUTED = 0xFFCAC4D0;
        C_LINE = 0xFF49454F;
        C_EDGE = (C_CARD_HI & 0x00FFFFFF) | 0xFF000000;
        C_EDGE = blend(C_CARD, 0xFFFFFFFF, 0.16f);
        Log.i(TAG, "  bg=" + Integer.toHexString(C_BG) + " accent=" + Integer.toHexString(C_ACCENT));
    }

    static final String PREFS = "dshwatch";
    static final int DEFAULT_PORT = 8787;
    static final String PREFILL_URL = "http://x.x.x.x:8787";
    static final String CHANNEL_DONE = "done";
    static final int NOTE_ID = 1001;
    /** Sessions that finished a turn and were not opened since. */
    final Set<String> finishedUnseen = new HashSet<>();
    /** Decoded previews, so a 4s poll does not refetch the same picture. */
    final Map<String, Bitmap> imageCache = new HashMap<>();
    /** Process rows the user opened, keyed by timeline index. */
    final Set<Integer> expandedRows = new HashSet<>();
    /** Model shown on the composer chip. */
    String currentModelName = "";
    /** True once this host has answered at least once; before that an empty list means "loading". */
    boolean gotState;
    /** True while the conversation itself fills the middle page (not the list). */
    boolean readingDetail;
    static final int REQ_STORAGE = 7;
    /** 1 = image picker, 2 = file picker; resumed after the permission answer. */
    int pendingPicker = 0;
    final Map<String, Boolean> runningBefore = new HashMap<>();
    static final String TAG = "DSHWatch";
    static final int POLL_MS = 4000;

    // ------------------------------------------------------------- state
    SharedPreferences prefs;
    String deviceId;
    String baseUrl;
    String token;

    JSONObject state;
    JSONArray sessions = new JSONArray();
    String selectedId;
    int lastPending;
    String lastError = "";
    long lastOkAt;
    boolean refreshing;
    int seqCounter;
    boolean pairing;
    int pairSeq;
    boolean firstPaint = true;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newSingleThreadExecutor();

    FrameLayout root;
    LinearLayout screenPair;
    /** "host" = talk to a DSH host; "solo" = the watch talks to the API itself. */
    String mode;
    String soloBase;
    String soloKey;
    String soloModel;
    JSONArray soloMessages;
    boolean soloBusy;
    EditText baseField;
    EditText keyField;
    Thread soloThread;
    LinearLayout soloLog;
    ScrollView soloScroll;
    TextView soloStatus;

    LinearLayout screenList;
    LinearLayout screenDetail;

    FrameLayout conversation;


    ListView listView;
    SessionAdapter adapter;
    final List<Session> rows = new ArrayList<>();
    static final String ARCHIVE_TOGGLE = "#archive";

    ScrollView detailScroll;
    LinearLayout detailBox;
    TextView detailTitle;
    TextView policyChip;
    int shownPending;
    LinearLayout detailActions;

    ScrollView pairScroll;
    LinearLayout pairContent;
    EditText pairUrl;
    EditText pairCode;
    TextView pairStatus;
    TextView listStatus;

    // ============================================================ lifecycle

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));
            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            deviceId = prefs.getString("deviceId", null);
            if (deviceId == null) {
                deviceId = java.util.UUID.randomUUID().toString().substring(0, 8);
                prefs.edit().putString("deviceId", deviceId).apply();
            }
            baseUrl = prefs.getString("url", null);
            token = prefs.getString("token", null);
            applyTheme(prefs.getInt("theme", 0));
            try {
                JSONArray unseen = new JSONArray(prefs.getString("finishedUnseen", "[]"));
                for (int i = 0; i < unseen.length(); i += 1) {
                    String id = unseen.optString(i, "");
                    if (!id.isEmpty()) finishedUnseen.add(id);
                }
            } catch (Exception ignored) {
            }

            root = new FrameLayout(this);
            root.setBackgroundColor(C_BG);
            setContentView(root);
            loadSolo();
        buildPager();

            applyLaunchConfig(getIntent());
            if (TextUtils.isEmpty(baseUrl)) baseUrl = readConfigUrl();
            Log.i(TAG, "onCreate token=" + (token != null) + " url=" + baseUrl);
            if (!TextUtils.isEmpty(token) && !TextUtils.isEmpty(baseUrl)) {
                // Heal the switcher list on every launch.
                rememberServer(baseUrl, token, "");
            }
            if (TextUtils.isEmpty(token)) {
                showPair(null);
            } else {
                showList();
            }
        } catch (Throwable err) {
            showFallback(err);
        }
    }

    /** Never leave the watch on a black screen: show the failure instead. */
    void showFallback(Throwable err) {
        try {
            StringWriter buffer = new StringWriter();
            err.printStackTrace(new PrintWriter(buffer));
            String text = "DSH on wear 启动失败\n\n" + buffer.toString();
            Log.e(TAG, text);
            TextView view = new TextView(this);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            view.setTextColor(0xFFFF6B6B);
            view.setBackgroundColor(C_BG);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            view.setVerticalScrollBarEnabled(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(view);
            setContentView(scroll);
        } catch (Throwable ignored) {
            // Nothing left to do.
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyLaunchConfig(intent);
        if (TextUtils.isEmpty(token)) showPair(null); else refreshNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleTick(300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    /** url/code passed by `adb shell am start ... --es url ... --es code ...`. */
    void applyLaunchConfig(Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra("url");
        String code = intent.getStringExtra("code");
        if (!TextUtils.isEmpty(url)) baseUrl = normalize(url);
        if (!TextUtils.isEmpty(url) && !TextUtils.isEmpty(code)) {
            pairWith(baseUrl, code, true);
            return;
        }
        if (!TextUtils.isEmpty(baseUrl)) prefs.edit().putString("url", baseUrl).apply();
    }

    String readConfigUrl() {
        String[] paths = {"/sdcard/dshwatch.json", "/storage/emulated/0/dshwatch.json"};
        for (String path : paths) {
            try {
                File file = new File(path);
                if (!file.exists()) continue;
                JSONObject json = new JSONObject(readAll(new FileInputStream(file)));
                String url = json.optString("url", "");
                String code = json.optString("code", "");
                if (TextUtils.isEmpty(url)) continue;
                baseUrl = normalize(url);
                if (!TextUtils.isEmpty(code)) pairWith(baseUrl, code, true);
                return baseUrl;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    // ============================================================== polling

    final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (token == null) return;
            refreshNow();
            scheduleTick(POLL_MS);
        }
    };

    void scheduleTick(int delay) {
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, delay);
    }

    void refreshNow() {
        refreshNow(false);
    }

    void refreshNow(boolean force) {
        // A poll already in flight must not stomp the session the user just
        // tapped: every response is stamped and only the newest one renders.
        if (refreshing && !force) return;
        refreshing = true;
        final int mySeq = ++seqCounter;
        final String usedToken = token;
        final String wanted = selectedId;
        final String sessionParam = wanted == null ? "" : "&session=" + enc(wanted);
        api("GET", "/api/state?limit=8" + sessionParam, null, new Callback() {
            @Override
            public void onOk(JSONObject body) {
                refreshing = false;
                if (mySeq != seqCounter) return;
                state = body;
                gotState = true;
                lastOkAt = System.currentTimeMillis();
                lastError = "";
                try {
                    sessions = body.optJSONArray("sessions");
                    if (sessions == null) sessions = new JSONArray();
                    JSONObject session = body.optJSONObject("session");
                    // Never adopt the server's auto-pick as the user's choice.
                    if (session != null && selectedId != null
                            && !selectedId.equals(session.optString("id", ""))) {
                        state = null;
                    }
                    int pending = 0;
                    for (int i = 0; i < sessions.length(); i += 1) {
                        JSONObject item = sessions.optJSONObject(i);
                        if (item == null) continue;
                        pending += item.optInt("pending", 0);
                        String sid = item.optString("id", "");
                        boolean running = item.optBoolean("running", false);
                        Boolean before = runningBefore.put(sid, running);
                        if (before != null && before && !running) {
                            // The turn ended while we were polling: badge it.
                            finishedUnseen.add(sid);
                            saveFinished();
                            notifyDone(item.optString("title", sid));
                        }
                    }
                    if (pending > lastPending) vibrate();
                    lastPending = pending;
                } catch (Exception ignored) {
                }
                if (conversation != null && conversation.getChildCount() == 0) {
                    if (selectedId == null) showList(); else showDetail();
                } else if (showing(screenDetail)) {
                    showDetail();
                } else if (showing(screenList)) {
                    showList();
                }
                // pairing / exit screens keep themselves; a poll never replaces them.
                firstPaint = false;
            }

            @Override
            public void onErr(String message, int status) {
                refreshing = false;
                if (mySeq != seqCounter) return;
                lastError = message;
                if (status == 401) {
                    // A pairing attempt is in flight: do not tear the page down
                    // and clear the code the user is typing.
                    if (pairing) return;
                    // Keep the token: a 401 usually means this host revoked the
                    // device, and wiping it would force a new pairing just to
                    // switch back later.
                    if (usedToken == null || usedToken.equals(token)) {
                        needsAuth = true;
                        token = null;
                        showPair("这台 DSH 不认这个 token 了，重新配对");
                        pairUrl.setText(baseUrl == null ? PREFILL_URL : baseUrl);
                    }
                    return;
                }
                if (token != null) {
                    if (selectedId == null) showList(); else showDetail();
                }
            }
        });
    }

    void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_DONE) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_DONE, "任务完成", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        } catch (Exception ignored) {
        }
    }

    /** A normal notification, so the launcher can show its unread dot. */
    void notifyDone(String title) {
        try {
            ensureChannel();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_DONE)
                    : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("任务完成")
                    .setContentText(title)
                    .setAutoCancel(true)
                    .setContentIntent(pending);
            nm.notify(NOTE_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    void cancelDoneNote() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {
        }
    }

    void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(220);
        } catch (Exception ignored) {
        }
    }

    // ================================================================= HTTP

    interface Callback {
        void onOk(JSONObject body);

        void onErr(String message, int status);
    }

    void api(final String method, final String path, final JSONObject payload, final Callback cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                int status = 0;
                String text = null;
                try {
                    URL url = new URL(baseUrl + path);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(8000);
                    conn.setRequestMethod(method);
                    conn.setRequestProperty("Accept", "application/json");
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
                    if (payload != null) {
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");
                        byte[] bytes = payload.toString().getBytes("UTF-8");
                        conn.setFixedLengthStreamingMode(bytes.length);
                        OutputStream out = conn.getOutputStream();
                        out.write(bytes);
                        out.close();
                    }
                    status = conn.getResponseCode();
                    InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
                    text = in == null ? "" : readAll(in);
                    conn.disconnect();
                } catch (Exception err) {
                    final String message = err.getClass().getSimpleName() + ": " + err.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onErr(message, 0);
                        }
                    });
                    return;
                }
                JSONObject body = null;
                try {
                    body = new JSONObject(text);
                } catch (Exception ignored) {
                }
                final JSONObject parsed = body;
                final int code = status;
                final String raw = text;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (parsed == null) {
                            cb.onErr("服务器回了看不懂的内容 (" + code + ")", code);
                            return;
                        }
                        if (parsed.optBoolean("ok", false)) cb.onOk(parsed);
                        else cb.onErr(parsed.optString("error", raw), code);
                    }
                });
            }
        });
    }

    /** Binary fetch (images): no JSON parsing, just bytes. */
    interface RawCallback {
        void done(byte[] data, String error);
    }

    void apiRaw(final String path, final RawCallback cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = null;
                String error = null;
                try {
                    URL url = new URL(baseUrl + path);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "image/*");
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
                    int status = conn.getResponseCode();
                    if (status >= 200 && status < 300) bytes = readAllBytes(conn.getInputStream());
                    else error = "HTTP " + status;
                    conn.disconnect();
                } catch (Exception err) {
                    error = err.getClass().getSimpleName();
                }
                final byte[] data = bytes;
                final String message = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(data, message);
                    }
                });
            }
        });
    }

    static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ============================================================ pairing

    void pairWith(String url, String code, boolean silent) {
        baseUrl = normalize(url);
        pairing = true;
        final int attempt = ++pairSeq;
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("name", "oppo-watch2");
            if (deviceId != null) body.put("id", deviceId);
        } catch (Exception ignored) {
        }
        api("POST", "/api/pair", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                pairing = false;
                token = response.optString("token", null);
                needsAuth = false;
                prefs.edit().putString("url", baseUrl).putString("token", token).apply();
                // Record it before the naming dialog, so dismissing the dialog
                // still leaves a usable entry in the switcher.
                rememberServer(baseUrl, token, "");
                askServerName(baseUrl, token);
                toast("配对成功");
                showList();
                scheduleTick(200);
            }

            @Override
            public void onErr(String message, int status) {
                pairing = false;
                if (silent) {
                    showPair(message);
                } else {
                    pairStatus.setVisibility(View.VISIBLE);
                    pairStatus.setText(message);
                }
            }
        });
        // The socket can hang without ever calling back; never leave the page
        // sitting on 连接中… forever.
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pairing || attempt != pairSeq) return;
                pairing = false;
                if (pairStatus != null && !silent) {
                    pairStatus.setVisibility(View.VISIBLE);
                    pairStatus.setText("没连上，检查地址和端口");
                }
            }
        }, 12000);
    }

    // ============================================================== screens

    void clearRoot() {
        root.removeAllViews();
    }

    boolean showing(View view) {
        return conversation != null && conversation.getChildCount() == 1
                && conversation.getChildAt(0) == view;
    }

    /** Pages you came through, so the back arrow returns one step, not home. */
    final java.util.ArrayDeque<View> history = new java.util.ArrayDeque<>();

    View currentScreen() {
        return conversation != null && conversation.getChildCount() > 0
                ? conversation.getChildAt(0) : null;
    }

    /** Navigate to a page, remembering the one it replaces. */
    void go(View page) {
        View now = currentScreen();
        if (now != null && now != page && !history.contains(page)) history.push(now);
        showScreen(page);
    }

    /** One step back: the previous page, or the list at the bottom of the stack. */
    void goBack() {
        View previous = history.poll();
        if (previous != null && previous.getParent() == null) {
            showScreen(previous);
            return;
        }
        history.clear();
        showList();
    }

    /** Put the conversation content (list or detail) into the middle page. */
    void showScreen(View view) {
        if (showing(view)) return;
        conversation.removeAllViews();
        conversation.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void buildPager() {
        // One screen. 配对 / 主题 / 更多 / 关于 are pages swapped in by showScreen().
        conversation = new FrameLayout(this);
        conversation.setBackgroundColor(C_BG);
        conversation.setPadding(0, 0, 0, dp(2));
        root.addView(conversation, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** One-pixel rule used to split the tool capsule into three hit areas. */
    View hairline(int height) {
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        return line;
    }

    /** Tiny canvas-drawn glyphs: no icon files, no font coverage worries. */
    class Glyph extends View {
        static final int MENU = 0;
        static final int GEAR = 1;
        static final int PLUS = 2;
        static final int CLOUD = 3;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;

        Glyph(Context context, int kind) {
            super(context);
            this.kind = kind;
            setClickable(true);
            setFocusable(true);
        }

        /**
         * Handle the tap here instead of leaning on View's click machinery: on
         * this watch the stock path never fired performClick for a canvas view.
         */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    setPressed(false);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public void setPressed(boolean value) {
            pressed = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float unit = Math.min(w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(kind == CLOUD ? C_ON_ACCENT : (pressed ? C_ACCENT : C_TEXT));
            if (kind == CLOUD) {
                // cloud body: two lobes over a rounded base
                paint.setStyle(Paint.Style.FILL);
                float r = unit * 0.17f;
                canvas.drawCircle(cx - unit * 0.15f, cy - unit * 0.03f, r, paint);
                canvas.drawCircle(cx + unit * 0.13f, cy - unit * 0.07f, r * 1.08f, paint);
                canvas.drawCircle(cx - unit * 0.01f, cy - unit * 0.16f, r * 0.95f, paint);
                canvas.drawRoundRect(new RectF(cx - unit * 0.30f, cy - unit * 0.08f,
                        cx + unit * 0.30f, cy + unit * 0.12f), unit * 0.10f, unit * 0.10f, paint);

                // arrow cut out of the cloud
                paint.setColor(C_ACCENT);
                float shaft = Math.max(dp(1.2f), unit * 0.075f);
                canvas.drawRoundRect(new RectF(cx - shaft / 2f, cy - unit * 0.10f,
                        cx + shaft / 2f, cy + unit * 0.03f), shaft / 2f, shaft / 2f, paint);
                android.graphics.Path head = new android.graphics.Path();
                head.moveTo(cx - unit * 0.11f, cy + unit * 0.01f);
                head.lineTo(cx + unit * 0.11f, cy + unit * 0.01f);
                head.lineTo(cx, cy + unit * 0.16f);
                head.close();
                canvas.drawPath(head, paint);
                return;
            }
            if (kind == PLUS) {
                float half = unit * 0.28f;
                float stroke = Math.max(dp(1.4f), unit * 0.10f);
                canvas.drawRoundRect(new RectF(cx - half, cy - stroke / 2f, cx + half, cy + stroke / 2f),
                        stroke / 2f, stroke / 2f, paint);
                canvas.drawRoundRect(new RectF(cx - stroke / 2f, cy - half, cx + stroke / 2f, cy + half),
                        stroke / 2f, stroke / 2f, paint);
                return;
            }
            if (kind == MENU) {
                // three round-capped bars
                float half = unit * 0.30f;
                float stroke = Math.max(dp(1.4f), unit * 0.085f);
                float gap = unit * 0.20f;
                for (int i = -1; i <= 1; i += 1) {
                    float y = cy + i * gap;
                    canvas.drawRoundRect(new RectF(cx - half, y - stroke / 2f, cx + half, y + stroke / 2f),
                            stroke / 2f, stroke / 2f, paint);
                }
                return;
            }
            // gear: a disc with eight stubby rounded teeth and a punched hole
            float body = unit * 0.30f;
            float toothLong = unit * 0.13f;
            float toothWide = unit * 0.15f;
            float hole = unit * 0.115f;
            for (int i = 0; i < 8; i += 1) {
                canvas.save();
                canvas.rotate(i * 45f, cx, cy);
                canvas.drawRoundRect(new RectF(cx - toothWide / 2f, cy - body - toothLong * 0.62f,
                        cx + toothWide / 2f, cy - body + toothLong * 0.38f), toothWide * 0.28f, toothWide * 0.28f, paint);
                canvas.restore();
            }
            canvas.drawCircle(cx, cy, body, paint);
            paint.setColor(C_BG);
            canvas.drawCircle(cx, cy, hole, paint);
        }
    }

    LinearLayout page() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        return box;
    }

    void showPair(String message) {
        if (!prefs.getBoolean("guideDone", false)) {
            showGuidePage();
            return;
        }
        showPairPage(message);
    }

    /** 配对：整屏二级页面，左上角返回。 */
    void showPairPage(String message) {
        buildPairPage(message);
        if (screenPair != null) go(screenPair);
        readingDetail = false;
    }

    /** 教程：整屏页面，滑到底点确认。 */
    void showGuidePage() {
        guideActive = true;
        buildGuidePage();
        if (screenPair != null) showScreen(screenPair);
        readingDetail = false;
    }

    /** A secondary page: back arrow, title, then the body. */
    LinearLayout secondary(String title) {
        LinearLayout page = page();
        page.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 20, C_BLUE, true);
        back.setPadding(dp(8), 0, dp(10), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });
        head.addView(back, lp(-2, -2, 0, 0, 0, 0));
        head.addView(text(title, 12, C_ACCENT, true), weight(1));
        page.addView(head, fullWidth());
        return page;
    }

    /** A tappable line: label, optional hint, and what it opens. */
    View pageRow(String label, String hint, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(7));
        box.setBackground(bg(C_CARD, 14));
        box.addView(text(label, 11, C_TEXT, false), fullWidth());
        if (hint != null && !hint.isEmpty()) {
            TextView sub = text(hint, 8, C_MUTED, false);
            sub.setPadding(0, dp(1), 0, 0);
            box.addView(sub, fullWidth());
        }
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(1), 0, dp(3));
        wrap.addView(box, fullWidth());
        return wrap;
    }

    void showColumnPage() {
        LinearLayout page = secondary("更多");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(2), 0, dp(6));
        body.addView(pageRow("配对新设备", "换一台 DSH，或重新配一次", new Runnable() {
            public void run() { showPairPage(null); }
        }), fullWidth());
        body.addView(pageRow("主题色", "八套配色，点了立刻生效", new Runnable() {
            public void run() { showThemePage(); }
        }), fullWidth());
        body.addView(pageRow("退出应用", "手表上的 DSH 会关掉", new Runnable() {
            public void run() { confirmQuit(); }
        }), fullWidth());
        body.addView(pageRow("切换模式", "连接 DSH / 独立使用", new Runnable() {
            public void run() { showModePage(); }
        }), fullWidth());
        body.addView(pageRow("关于", "开发者与版本", new Runnable() {
            public void run() { showAboutPage(); }
        }), fullWidth());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, weightV());
        readingDetail = false;
        go(page);
    }

    void confirmQuit() {
        destructiveConfirm("退出应用？", "手表上的 DSH 会关掉，随时可以再打开", "退出", new Runnable() {
            @Override
            public void run() {
                quitApp();
            }
        });
    }

    void showThemePage() {
        LinearLayout page = secondary("主题色");
        page.addView(themeGrid(), fullWidth());
        readingDetail = false;
        go(page);
    }

    /** Eight palette circles; tapping one applies it and returns to the list. */
    View themeGrid() {
        int current = prefs.getInt("theme", 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int line = 0; line < 2; line += 1) {
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER);
            for (int col = 0; col < 4; col += 1) {
                final int index = line * 4 + col;
                View swatch = new View(this);
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(THEMES[index][3]);
                if (index == current) shape.setStroke(dp(3), C_TEXT);
                swatch.setBackground(shape);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
                params.setMargins(dp(5), dp(8), dp(5), dp(8));
                swatch.setLayoutParams(params);
                swatch.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectTheme(index);
                        showList();
                    }
                });
                row.addView(swatch);
            }
            box.addView(row, fullWidth());
        }
        return box;
    }

    void showAboutPage() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception no) {
            version = "?";
        }
        LinearLayout page = secondary("关于");
        TextView name = text("DSH on wear", 14, C_ACCENT, true);
        name.setPadding(dp(8), dp(6), dp(8), 0);
        page.addView(name, fullWidth());
        TextView ver = text("版本 " + version, 9, C_MUTED, false);
        ver.setPadding(dp(8), dp(1), dp(8), 0);
        page.addView(ver, fullWidth());
        TextView dev = text("开发者：", 9, C_MUTED, false);
        dev.setPadding(dp(8), dp(10), dp(8), 0);
        page.addView(dev, fullWidth());
        TextView devName = text("YukioZola@0o0社区 · 0o0.site", 9, C_TEXT, false);
        devName.setPadding(dp(8), dp(1), dp(8), 0);
        page.addView(devName, fullWidth());
        TextView thanks = text("@DeepSeek Harness", 8, C_MUTED, false);
        thanks.setPadding(dp(8), dp(3), dp(8), 0);
        page.addView(thanks, fullWidth());
        TextView note = text("在手表上看 DSH 在做什么、回话、审批、换模型。", 9, C_TEXT, false);
        note.setPadding(dp(8), dp(9), dp(8), 0);
        page.addView(note, fullWidth());
        TextView ai = text("界面与代码由 AI 生成", 8, C_MUTED, false);
        ai.setPadding(dp(8), dp(6), dp(8), 0);
        page.addView(ai, fullWidth());
        TextView repo = text("github.com/chen1ncx/dsh-wear", 8, C_BLUE, false);
        repo.setPadding(dp(8), dp(10), dp(8), 0);
        page.addView(repo, fullWidth());
        readingDetail = false;
        go(page);
    }

    /** First run: what to install on the host, with a copyable command block. */
    void buildGuidePage() {
        guideActive = true;
        if (screenPair == null) {
            screenPair = page();
            pairScroll = new ScrollView(this);
            pairContent = new LinearLayout(this);
            pairContent.setOrientation(LinearLayout.VERTICAL);
            pairScroll.setFillViewport(true);
            pairScroll.addView(pairContent, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            screenPair.addView(pairScroll, weightV());
        }
        pairContent.removeAllViews();

        LinearLayout head = row();
        head.setPadding(dp(10), dp(8), dp(10), dp(2));
        head.addView(text("第一次使用", 13, C_ACCENT, true), weight(1));
        pairContent.addView(head, fullWidth());

        pairContent.addView(guideLine("1", "在要连的那台电脑或手机上装插件（命令整段复制）"));
        pairContent.addView(guideCode("cd /tmp && rm -rf dsh-wear && git clone --depth 1 https://github.com/chen1ncx/dsh-wear.git dsh-wear\n"
                + "P=$(readlink -f ~/.dsh/profiles/web/node_modules/dsh-watch-bridge)\n"
                + "[ -d \"$P\" ] || P=~/dsh-watch-bridge\n"
                + "cp -f dsh-wear/lib/index.js \"$P/lib/index.js\"\n"
                + "cp -f dsh-wear/package.json \"$P/package.json\""));

        pairContent.addView(guideLine("2", "重启那台机器上的 DSH，插件就生效了"));
        pairContent.addView(guideCode("curl -s http://127.0.0.1:8787/api/hello"));
        pairContent.addView(guideLine("3", "在 DSH 里让我给你一个配对码（10 分钟内有效）"));
        pairContent.addView(guideLine("4", "把地址和配对码填到下一页，点「连接」"));

        TextView tip = text("地址形如 http://192.168.1.5:8787，手机和手表要在同一个 Wi-Fi。",
                9, C_MUTED, false);
        tip.setPadding(dp(10), dp(4), dp(10), dp(8));
        pairContent.addView(tip, fullWidth());

        Button ok = roundKey("确认", C_ACCENT);
        ok.setTextColor(C_ON_ACCENT);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("guideDone", true).apply();
                guideActive = false;
                showPairPage(null);
            }
        });
        LinearLayout okRow = row();
        okRow.setPadding(dp(10), dp(4), dp(10), dp(10));
        okRow.addView(ok, weightH(30));
        pairContent.addView(okRow, fullWidth());
    }

    /** "1" style step caption. */
    View guideLine(String number, String body) {
        LinearLayout line = row();
        line.setPadding(dp(10), dp(6), dp(10), 0);
        TextView badge = text(number, 9, C_ON_ACCENT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(bg(C_ACCENT, 8));
        line.addView(badge, lp(dp(16), dp(16), 0, 0, 7, 0));
        TextView label = text(body, 9, C_TEXT, false);
        line.addView(label, weight(1));
        return line;
    }

    /** Copyable command block: a button beats long-press on a 1-inch screen. */
    View guideCode(final String command) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        box.setBackground(bg(C_CARD, 12));

        TextView code = text(command, 8, C_MUTED, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        box.addView(code, fullWidth());

        Button copy = outlinePill("复制命令");
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh", command));
                toast("复制好了");
            }
        });
        LinearLayout copyRow = row();
        copyRow.setGravity(Gravity.RIGHT);
        copyRow.addView(copy, lp(-2, dp(24), 0, 4, 0, 0));
        box.addView(copyRow, fullWidth());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(10), dp(4), dp(10), dp(2));
        wrap.addView(box, fullWidth());
        return wrap;
    }

    void buildPairPage(String message) {
        Log.i(TAG, "buildPairPage bg=" + Integer.toHexString(C_BG));
        if (screenPair == null) {
            screenPair = page();
            pairScroll = new ScrollView(this);
            pairContent = new LinearLayout(this);
            pairContent.setOrientation(LinearLayout.VERTICAL);
            pairScroll.setFillViewport(true);
            pairScroll.addView(pairContent, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            screenPair.addView(pairScroll, weightV());
        }
        String typedUrl = pairUrl == null ? null : pairUrl.getText().toString().trim();
        pairContent.removeAllViews();

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(6), dp(10), dp(2));
        TextView pairBack = text("‹", 20, C_BLUE, true);
        pairBack.setPadding(dp(6), 0, dp(8), 0);
        pairBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });
        head.addView(pairBack, lp(-2, -2, 0, 0, 0, 0));
        head.addView(text("配对 DSH", 13, C_ACCENT, true), weight(1));
        pairContent.addView(head, fullWidth());

        // Empty on purpose: the last-used address is only a hint. Typing wins.
        // Boilerplate is prefilled (typing "http://" on a watch is painful) and
        // selected, so the first keystroke replaces it. The saved address is
        // never echoed: this page ends up in screenshots.
        // Both ends of the address are prefilled; the cursor sits right after
        // "http://" so only the host has to be typed.
        pairUrl = edit(TextUtils.isEmpty(typedUrl)
                ? (needsAuth && baseUrl != null ? baseUrl : PREFILL_URL)
                : typedUrl);
        pairUrl.setHint("http://服务器:8787");
        pairUrl.setSelectAllOnFocus(false);
        pairUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pairUrl.setSingleLine(true);
        // The address must stay typeable: normal focus, normal keyboard.
        pairUrl.setCursorVisible(true);
        pairUrl.setFocusableInTouchMode(true);
        pairUrl.setShowSoftInputOnFocus(true);
        pairUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pairUrl.requestFocus();
                // Select the host placeholder so the first keystroke replaces it.
                if (PREFILL_URL.equals(pairUrl.getText().toString())) pairUrl.setSelection(7, 14);
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(pairUrl, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        pairContent.addView(pairUrl, lp(-1, dp(26), 8, 2, 8, 0));

        pairCode = edit("");
        pairCode.setHint("6 位配对码");
        pairCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pairCode.setGravity(Gravity.CENTER);
        // Digits come from the on-screen keypad only: no IME, no blinking caret.
        pairCode.setInputType(android.text.InputType.TYPE_NULL);
        pairCode.setKeyListener(null);
        pairCode.setFocusable(false);
        pairCode.setFocusableInTouchMode(false);
        pairCode.setCursorVisible(false);
        pairCode.setLongClickable(false);
        pairCode.setShowSoftInputOnFocus(false);
        pairContent.addView(pairCode, lp(-1, dp(30), 8, 4, 8, 0));

        pairStatus = text(message == null ? "在 DSH 对话里说「给我手表配对码」" : message, 10,
                message == null ? C_MUTED : C_RED, false);
        pairStatus.setPadding(dp(10), 0, dp(10), dp(2));
        pairStatus.setVisibility(message == null ? View.GONE : View.VISIBLE);
        pairContent.addView(pairStatus);

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "连接"}};
        for (String[] line : keys) {
            LinearLayout keyRow = row();
            keyRow.setPadding(dp(4), dp(1), dp(4), dp(1));
            for (final String key : line) {
                Button button = new Button(this);
                button.setText(key);
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                button.setTextColor("连接".equals(key) ? 0xFF131313 : C_TEXT);
                button.setAllCaps(false);
                button.setPadding(0, 0, 0, 0);
                button.setMinHeight(0);
                button.setMinimumHeight(0);
                button.setBackground(keyBg("连接".equals(key) ? C_ACCENT : C_CARD));
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKey(key);
                    }
                });
                keyRow.addView(button, lp(0, dp(26), 1, 0, 1, 0));
                ((LinearLayout.LayoutParams) button.getLayoutParams()).weight = 1;
            }
            pairContent.addView(keyRow, fullWidth());
        }


    }

    void onKey(String key) {
        String current = pairCode.getText().toString();
        if ("⌫".equals(key)) {
            if (current.length() > 0) pairCode.setText(current.substring(0, current.length() - 1));
            return;
        }
        if ("连接".equals(key)) {
            String url = pairUrl.getText().toString().trim();
            String code = pairCode.getText().toString().trim();
            if (TextUtils.isEmpty(url) || hostMissing(url)) {
                // Only the boilerplate is left: reuse the address this watch last used.
                url = baseUrl == null ? "" : baseUrl;
            }
            if (TextUtils.isEmpty(url) || code.length() < 4) {
                pairStatus.setVisibility(View.VISIBLE);
                pairStatus.setText(TextUtils.isEmpty(url) ? "先填服务器地址" : "配对码没填完");
                return;
            }
            pairStatus.setVisibility(View.VISIBLE);
            pairStatus.setText("连接中…");
            pairWith(url, code, false);
            return;
        }
        if (current.length() < 6) pairCode.setText(current + key);
    }

    boolean showArchived;
    TextView listEmpty;
    TextView statusDot;
    SwipeRow openSwipe;
    /** First-run guide owns the screen: no paging, no pill, only 确认 moves on. */
    boolean guideActive;
    /** Signature of what the list currently shows; a poll rebuilds only on change. */
    int listSig;
    String pendingDelete;
    boolean needsAuth;

    void showList() {
        if ("solo".equals(mode)) {
            showSoloChat();
            return;
        }
        selectedId = null;
        if (screenList == null) {
            Log.i(TAG, "buildList bg=" + Integer.toHexString(C_BG));
            screenList = page();

            // Two components only: one capsule for the host, one for the tools.
            // Everything is the same height and corner radius, so the row reads
            // as one bar instead of five floating bits.
            LinearLayout head = row();
            // Fixed height and centre alignment: a weighted spacer in a
            // wrap-content row measured unpredictably and ate the list.
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(8), 0, dp(8), 0);

            LinearLayout host = row();
            host.setGravity(Gravity.CENTER_VERTICAL);
            host.setPadding(dp(9), 0, dp(9), 0);
            host.setBackground(bg(C_CARD, 11));
            host.setClickable(true);
            host.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDevicesDialog();
                }
            });
            host.addView(text("设备", 9, C_TEXT, false));
            statusDot = text("●", 6, C_GREEN, false);
            statusDot.setPadding(dp(5), 0, dp(3), 0);
            host.addView(statusDot);
            listStatus = text("", 8, C_MUTED, false);
            host.addView(listStatus);
            head.addView(host, lp(-2, dp(22), 0, 0, 0, 0));

            // ＋ belongs beside the host capsule: it makes a new conversation
            // on that host, so it reads as part of the same choice.
            Glyph plus = new Glyph(this, Glyph.PLUS);
            plus.setBackground(bg(C_CARD, 11));
            plus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    createSession();
                }
            });
            head.addView(plus, lp(dp(26), dp(22), 5, 0, 0, 0));

            View spacer = new View(this);
            head.addView(spacer, weight(1));

            LinearLayout tools = row();
            tools.setGravity(Gravity.CENTER_VERTICAL);
            tools.setBackground(bg(C_CARD, 11));
            Glyph menu = new Glyph(this, Glyph.MENU);
            menu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showColumnPage();
                }
            });
            // One button for everything: 设置 lives inside 更多 now.
            tools.addView(menu, lp(dp(30), dp(22), 0, 0, 0, 0));
            head.addView(tools, lp(-2, dp(22), 0, 0, 0, 0));
            screenList.addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

            listView = new ListView(this);
            listView.setDivider(null);
            // The platform selector is a flat rectangle: it peeks out behind the
            // rounded row cards as four coloured corners. Rows draw their own
            // pressed state, so the selector is turned off entirely.
            listView.setSelector(new ColorDrawable(0x00000000));
            listView.setDrawSelectorOnTop(false);
            listView.setDividerHeight(dp(2));
            listView.setPadding(dp(6), dp(2), dp(6), dp(16));
            listView.setClipToPadding(false);
            adapter = new SessionAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (openSwipe != null) {
                        // The finger that just opened the row also lifts here; do
                        // not treat that release as "tap outside to close".
                        if (System.currentTimeMillis() - openSwipe.openedAt < 400) return;
                        openSwipe.close();
                        return;
                    }
                    if (position < rows.size()) {
                        if (ARCHIVE_TOGGLE.equals(rows.get(position).id)) {
                            showArchived = !showArchived;
                            bindSessions();
                            return;
                        }
                        openConversation(rows.get(position));
                    }
                }
            });
            screenList.addView(listView, weightV());
            listEmpty = text("", 11, C_MUTED, false);
            listEmpty.setGravity(Gravity.CENTER);
            listEmpty.setPadding(dp(16), 0, dp(16), dp(24));
            listEmpty.setVisibility(View.GONE);
            screenList.addView(listEmpty, weightV());
        }
        readingDetail = false;
        history.clear();
        showScreen(screenList);
        // Only the fields we actually draw decide a rebuild: the payload carries
        // timestamps that change on every poll and used to rebuild the list
        // (and close an open row) every four seconds.
        int sig = listSignature();
        if (openSwipe != null) return;
        if (sig != listSig || adapter == null) {
            listSig = sig;
            bindSessions();
        }
    }

    /** Hash of the visible list state: stable while nothing on screen changed. */
    int listSignature() {
        int sig = 31 + (showArchived ? 7 : 0) + finishedUnseen.hashCode() + rows.size();
        JSONArray list = sessions;
        for (int i = 0; list != null && i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            sig = sig * 31 + item.optString("id", "").hashCode();
            sig = sig * 31 + item.optString("title", "").hashCode();
            sig = sig * 31 + (item.optBoolean("running", false) ? 1 : 0);
            sig = sig * 31 + (item.optBoolean("archived", false) ? 1 : 0);
            sig = sig * 31 + item.optInt("pending", 0);
        }
        return sig;
    }

    void bindSessions() {
        rows.clear();
        List<Session> archived = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i += 1) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) continue;
            Session s = new Session();
            s.id = item.optString("id", "");
            s.title = item.optString("title", s.id);
            s.running = item.optBoolean("running", false);
            s.live = item.optBoolean("live", false);
            s.archived = item.optBoolean("archived", false);
            s.pending = item.optInt("pending", 0);
            if (s.archived) archived.add(s); else rows.add(s);
        }
        // Newest result first: need-you-now, then just-finished, then running.
        // Collections.sort is stable, so each bucket keeps the server's order.
        Collections.sort(rows, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return listBucket(a) - listBucket(b);
            }
        });
        // The header row stays above its group, open or closed.
        if (!archived.isEmpty()) {
            Session toggle = new Session();
            toggle.id = ARCHIVE_TOGGLE;
            toggle.title = "已归档 · " + archived.size();
            rows.add(toggle);
            if (showArchived) rows.addAll(archived);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (listEmpty != null) {
            boolean empty = rows.isEmpty();
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
            listEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) {
                String host = shortHost(baseUrl == null ? "" : baseUrl);
                if (!lastError.isEmpty()) {
                    listEmpty.setText("连不上这台\n" + host + "\n在「设备」里换一台\n或在配对页里重新配对");
                } else if (!gotState) {
                    listEmpty.setText("读取中…");
                } else {
                    listEmpty.setText("这台还没有对话\n点 ＋ 新建一个");
                }
            }
        }
        if (listStatus != null) {
            if (rows.isEmpty()) {
                listStatus.setText(!lastError.isEmpty() ? "连不上" : (gotState ? "没有会话" : "读取中…"));
            } else {
                int waiting = 0;
                for (Session s : rows) waiting += s.pending;
                listStatus.setText(waiting > 0 ? "待确认 " + waiting : (lastError.isEmpty() ? "在线" : "离线"));
                listStatus.setTextColor(waiting > 0 ? C_RED : (lastError.isEmpty() ? C_MUTED : C_RED));
            }
        }
    }

    void showDetail() {
        if (selectedId == null) {
            showList();
            return;
        }
        if (screenDetail == null) {
            screenDetail = page();

            LinearLayout head = row();
            head.setPadding(dp(6), dp(6), dp(8), dp(2));
            TextView back = text("‹", 20, C_BLUE, true);
            back.setPadding(dp(6), 0, dp(8), 0);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedId = null;
                    showList();
                    refreshNow(true);
                }
            });
            head.addView(back);
            detailTitle = text("", 12, C_TEXT, true);
            detailTitle.setSingleLine(true);
            detailTitle.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(detailTitle, weight(1));
            policyChip = text("", 9, C_MUTED, false);
            policyChip.setPadding(dp(6), dp(2), dp(6), dp(2));
            policyChip.setBackground(bg(C_CARD_HI, 16));
            policyChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePolicy();
                }
            });
            head.addView(policyChip);
            screenDetail.addView(head, fullWidth());

            detailScroll = new ScrollView(this);
            detailBox = new LinearLayout(this);
            detailBox.setOrientation(LinearLayout.VERTICAL);
            detailBox.setPadding(dp(6), dp(2), dp(6), dp(4));
            detailScroll.setFillViewport(true);
            detailScroll.addView(detailBox, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            screenDetail.addView(detailScroll, weightV());

            detailActions = row();
            detailActions.setPadding(dp(6), dp(2), dp(6), dp(18));
            screenDetail.addView(detailActions, fullWidth());
        }
        readingDetail = true;
        showScreen(screenDetail);
        renderDetail();
    }

    void renderDetail() {
        if (detailBox == null) return;
        JSONObject session = state == null ? null : state.optJSONObject("session");
        if (session == null) {
            detailBox.removeAllViews();
            detailTitle.setText("…");
            detailBox.addView(note(lastError.isEmpty() ? "读取中…" : lastError));
            return;
        }
        detailTitle.setText(session.optString("title", "会话"));
        boolean auto = session.optBoolean("auto", false);
        if (policyChip != null) {
            policyChip.setVisibility(View.VISIBLE);
            policyChip.setText(auto ? "自动通过" : "审批 问");
            policyChip.setTextColor(auto ? C_RED : C_GREEN);
        }
        int scrollY = detailScroll.getScrollY();
        int oldHeight = detailBox.getHeight();
        detailBox.removeAllViews();

        JSONArray pending = session.optJSONArray("pending");
        if (pending != null) {
            for (int i = 0; i < pending.length(); i += 1) {
                JSONObject item = pending.optJSONObject(i);
                if (item == null) continue;
                if ("question".equals(item.optString("kind"))) detailBox.addView(questionCard(item));
                else detailBox.addView(approvalCard(item));
            }
        }

        renderTimeline(session);
        String activity = session.optString("activity", "");
        if (session.optBoolean("running", false)) {
            detailBox.addView(note("正在运行" + (activity.isEmpty() ? "" : " · " + activity)));
        }
        int pendingCount = pending == null ? 0 : pending.length();
        final boolean pendingAppeared = pendingCount > 0 && shownPending == 0;
        shownPending = pendingCount;
        final boolean atBottom = scrollY + detailScroll.getHeight() + dp(24) >= oldHeight;
        detailScroll.post(new Runnable() {
            @Override
            public void run() {
                // A new card must never appear off-screen: jump to the top for it.
                if (pendingAppeared) detailScroll.scrollTo(0, 0);
                else if (atBottom) detailScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
        renderDetailActions(session);
    }

    /** What the agent is doing right now: thinking, tool calls, files, images. */
    void renderActivity(JSONObject session) {
        if (detailBox == null) return;
        String thinking = session.optString("thinking", "");
        JSONArray tools = session.optJSONArray("tools");
        JSONArray files = session.optJSONArray("files");
        JSONArray images = session.optJSONArray("images");
        boolean hasTools = tools != null && tools.length() > 0;
        boolean hasFiles = files != null && files.length() > 0;
        boolean hasImages = images != null && images.length() > 0;
        if (thinking.isEmpty() && !hasTools && !hasFiles && !hasImages) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(5), dp(8), dp(6));
        GradientDrawable shape = bg(C_CARD, 14);
        shape.setStroke(dp(1), C_LINE);
        box.setBackground(shape);
        box.addView(text("过程", 8, C_MUTED, false));

        if (!thinking.isEmpty()) {
            TextView think = text(thinking, 9, C_MUTED, false);
            think.setMaxLines(3);
            think.setEllipsize(TextUtils.TruncateAt.END);
            think.setPadding(0, dp(2), 0, 0);
            box.addView(think);
        }
        if (hasTools) {
            for (int i = 0; i < tools.length(); i += 1) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null) continue;
                String name = tool.optString("name", "tool");
                String arg = tool.optString("arg", "");
                boolean known = !tool.isNull("ok");
                boolean ok = tool.optBoolean("ok", true);
                LinearLayout line = row();
                line.setPadding(0, dp(1), 0, 0);
                TextView mark = text(known ? (ok ? "✓" : "✗") : "·", 9, known ? (ok ? C_GREEN : C_RED) : C_MUTED, true);
                line.addView(mark, lp(dp(10), -2, 0, 0, 2, 0));
                TextView label = text(arg.isEmpty() ? name : name + " · " + arg, 9, C_TEXT, false);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                line.addView(label, weight(1));
                box.addView(line);
            }
        }
        if (hasFiles) {
            TextView produced = text("产出 " + files.length() + " 个文件 · 到 DSH 上看", 9, C_BLUE, false);
            produced.setPadding(0, dp(3), 0, 0);
            produced.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toast("打开手机或电脑上的 DSH 查看");
                }
            });
            box.addView(produced);
        }
        if (hasImages) {
            LinearLayout strip = row();
            strip.setPadding(0, dp(4), 0, 0);
            final String sessionId = session.optString("id", "");
            for (int i = 0; i < images.length(); i += 1) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                final String imageId = image.optString("id", "");
                final String name = image.optString("name", "");
                if (imageId.isEmpty()) continue;
                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(bg(C_CARD_HI, 10));
                thumb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showImage(sessionId, imageId, name);
                    }
                });
                strip.addView(thumb, lp(dp(74), dp(74), 0, 0, 6, 0));
                loadThumb(sessionId, imageId, thumb);
            }
            box.addView(strip);
        }
        detailBox.addView(box, lp(-1, -2, 0, 0, 0, 5));
    }

    void loadThumb(final String sessionId, final String imageId, final ImageView view) {
        Bitmap cached = imageCache.get(imageId);
        if (cached != null) {
            view.setImageBitmap(scaleTo(cached, dp(150)));
            return;
        }
        apiRaw("/api/image?session=" + enc(sessionId) + "&id=" + enc(imageId), new RawCallback() {
            @Override
            public void done(byte[] data, String error) {
                if (data == null) return;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                imageCache.put(imageId, bitmap);
                view.setImageBitmap(scaleTo(bitmap, dp(150)));
            }
        });
    }

    void showImage(final String sessionId, final String imageId, String name) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable card = bg(C_CARD_HI, 20);
        content.setBackground(card);

        LinearLayout head = row();
        TextView title = text(name.isEmpty() ? "图片" : name, 9, C_MUTED, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(title, weight(1));
        Button close = roundKey("✕", C_CARD);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        head.addView(close, lp(dp(24), dp(24), 4, 0, 0, 0));
        content.addView(head, fullWidth());

        final ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Tapping the picture also closes the preview: on a watch the outside
        // strip is only a few pixels wide.
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        content.addView(view, lp(-1, -2, 0, 4, 0, 0));

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();

        apiRaw("/api/image?session=" + enc(sessionId) + "&id=" + enc(imageId), new RawCallback() {
            @Override
            public void done(byte[] data, String error) {
                if (data == null) {
                    toast(error == null ? "图片读不到" : error);
                    return;
                }
                Bitmap bitmap = imageCache.get(imageId);
                if (bitmap == null) bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                imageCache.put(imageId, bitmap);
                view.setImageBitmap(scaleTo(bitmap, dp(250)));
            }
        });
    }

    static Bitmap scaleTo(Bitmap source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide || longest == 0) return source;
        float scale = maxSide / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, (int) (width * scale)),
                Math.max(1, (int) (height * scale)), true);
    }

    /** The conversation plus its process, the way the phone shows it. */
    void renderTimeline(JSONObject session) {
        JSONArray blocks = session.optJSONArray("blocks");
        if (blocks == null) {
            JSONArray legacy = session.optJSONArray("timeline");
            if (legacy != null) {
                renderFlatTimeline(legacy);
                return;
            }
            JSONArray messages = session.optJSONArray("messages");
            for (int i = 0; messages != null && i < messages.length(); i += 1) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                detailBox.addView(bubble(message.optString("role", "a"), message.optString("text", "")));
            }
            return;
        }
        for (int i = 0; i < blocks.length(); i += 1) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String kind = block.optString("kind", "");
            if ("msg".equals(kind)) {
                detailBox.addView(bubble(block.optString("role", "a"), block.optString("text", "")));
            } else if ("process".equals(kind)) {
                detailBox.addView(processBlock(i, block));
            } else if ("files".equals(kind)) {
                JSONArray files = block.optJSONArray("files");
                final int count = files == null ? 0 : files.length();
                if (count == 0) continue;
                TextView row = text("产出 " + count + " 个文件 · 在 DSH 上查看", 9, C_BLUE, false);
                row.setPadding(dp(8), dp(3), dp(8), dp(3));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toast("打开手机或电脑上的 DSH 查看");
                    }
                });
                detailBox.addView(row);
            }
        }
    }

    /** Bridge 0.3.0 layout: a flat list of thinking / tool / message entries. */
    void renderFlatTimeline(JSONArray timeline) {
        for (int i = 0; i < timeline.length(); i += 1) {
            JSONObject item = timeline.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("kind", "");
            if ("msg".equals(kind)) {
                detailBox.addView(bubble(item.optString("role", "a"), item.optString("text", "")));
            } else if ("think".equals(kind)) {
                detailBox.addView(processRow(i, "思考 · " + firstLine(item.optString("text", "")), item.optString("text", ""), C_MUTED));
            } else if ("tool".equals(kind)) {
                String name = item.optString("name", "tool");
                String arg = item.optString("arg", "");
                boolean known = !item.isNull("ok");
                boolean ok = item.optBoolean("ok", true);
                String mark = known ? (ok ? "✓ " : "✗ ") : "· ";
                int color = known ? (ok ? C_GREEN : C_RED) : C_MUTED;
                detailBox.addView(processRow(i, mark + name + (arg.isEmpty() ? "" : " · " + arg), item.optString("result", ""), color));
            }
        }
    }

    /** One turn's tool work: a summary line while collapsed, the rows when open. */
    View processBlock(final int index, JSONObject block) {
        boolean running = block.optBoolean("running", false);
        int toolCount = block.optInt("toolCount", 0);
        boolean open = running || expandedRows.contains(index);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(5), dp(8), dp(5));
        box.setBackground(bg(C_CARD, 12));

        LinearLayout head = row();
        String label = running
                ? "进行中 · " + toolCount + " 次工具调用"
                : "已完成 · " + toolCount + " 次工具调用";
        head.addView(text(label, 9, running ? C_ACCENT : C_MUTED, false), weight(1));
        head.addView(text(open ? "⌃" : "⌄", 9, C_MUTED, false), lp(dp(10), -2, 2, 0, 0, 0));
        box.addView(head);

        if (open) {
            JSONArray items = block.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i += 1) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String kind = item.optString("kind", "");
                if ("think".equals(kind)) {
                    box.addView(processRow(index * 1000 + i, "思考 · " + firstLine(item.optString("text", "")), item.optString("text", ""), C_MUTED));
                } else if ("image".equals(kind)) {
                    String picturePath = item.optString("path", item.optString("name", ""));
                    if (picturePath.isEmpty()) picturePath = item.optString("name", "图片");
                    final String imageId = item.optString("id", "");
                    final String imageName = item.optString("name", "");
                    LinearLayout line = row();
                    line.setPadding(dp(8), dp(3), dp(8), dp(3));
                    TextView pic = text("查看图片 · " + shortName(picturePath), 9, C_BLUE, false);
                    pic.setSingleLine(true);
                    pic.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    line.addView(pic, weight(1));
                    line.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (imageId.isEmpty()) {
                                toast("这张图读不到");
                                return;
                            }
                            showImage(selectedId, imageId, imageName);
                        }
                    });
                    box.addView(line);
                } else if ("tool".equals(kind)) {
                    String name = item.optString("name", "tool");
                    String arg = item.optString("arg", "");
                    boolean known = !item.isNull("ok");
                    boolean ok = item.optBoolean("ok", true);
                    String mark = known ? (ok ? "✓ " : "✗ ") : "· ";
                    int color = known ? (ok ? C_GREEN : C_RED) : C_MUTED;
                    String body = item.optString("result", "");
                    box.addView(processRow(index * 1000 + i, mark + name + (arg.isEmpty() ? "" : " · " + arg), body, color));
                }
            }
        }
        if (!running) {
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (expandedRows.contains(index)) expandedRows.remove(index);
                    else expandedRows.add(index);
                    renderDetail();
                }
            });
        }
        LinearLayout.LayoutParams params = lp(-1, -2, 0, 0, 0, 3);
        box.setLayoutParams(params);
        return box;
    }

    static String shortName(String path) {
        if (path == null || path.isEmpty()) return "";
        int cut = path.lastIndexOf('/');
        return cut >= 0 && cut + 1 < path.length() ? path.substring(cut + 1) : path;
    }

    static String firstLine(String text) {
        if (text == null) return "";
        String line = text.trim();
        int cut = line.indexOf('\n');
        if (cut > 0) line = line.substring(0, cut);
        return line.length() > 28 ? line.substring(0, 28) + "…" : line;
    }

    /** One collapsible process line: head always visible, body on tap. */
    View processRow(final int index, String head, final String body, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.setBackground(bg(C_CARD, 12));

        LinearLayout line = row();
        TextView label = text(head, 9, color, false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(label, weight(1));
        boolean open = expandedRows.contains(index);
        line.addView(text(open ? "⌃" : "⌄", 9, C_MUTED, false), lp(dp(10), -2, 2, 0, 0, 0));
        box.addView(line);

        if (open && !body.isEmpty()) {
            TextView detail = text(body, 9, C_MUTED, false);
            detail.setPadding(0, dp(2), 0, 0);
            box.addView(detail);
        }
        if (!body.isEmpty()) {
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (expandedRows.contains(index)) expandedRows.remove(index);
                    else expandedRows.add(index);
                    renderDetail();
                }
            });
        }
        LinearLayout.LayoutParams params = lp(-1, -2, 0, 0, 0, 2);
        box.setLayoutParams(params);
        return box;
    }

    /** Pictures produced or read in this session, as thumbnails. */
    void renderImages(JSONObject session) {
        JSONArray images = session.optJSONArray("images");
        if (images == null || images.length() == 0) return;
        final String sessionId = session.optString("id", "");
        LinearLayout strip = row();
        strip.setPadding(dp(6), dp(3), dp(6), dp(4));
        for (int i = 0; i < images.length(); i += 1) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            final String imageId = image.optString("id", "");
            final String name = image.optString("name", "");
            if (imageId.isEmpty()) continue;
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(bg(C_CARD_HI, 10));
            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showImage(sessionId, imageId, name);
                }
            });
            strip.addView(thumb, lp(dp(74), dp(74), 0, 0, 6, 0));
            loadThumb(sessionId, imageId, thumb);
        }
        detailBox.addView(strip);
    }

    void renderDetailActions(JSONObject session) {
        detailActions.removeAllViews();
        final JSONArray pending = session.optJSONArray("pending");
        boolean hasPending = pending != null && pending.length() > 0;

        Button stop = smallButton("停止", C_RED);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmStop();
            }
        });
        detailActions.addView(stop, weightH(32));

        Button say = smallButton("说一句", C_BLUE);
        say.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSayDialog();
            }
        });
        detailActions.addView(say, weightH(32));

        if (hasPending) {
            Button jump = smallButton("待确认 " + pending.length(), C_ACCENT);
            jump.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    detailScroll.scrollTo(0, 0);
                }
            });
            detailActions.addView(jump, weightH(32));
        }
    }

    View approvalCard(final JSONObject item) {
        LinearLayout card = card(C_RED);
        TextView head = text("需要确认 · " + item.optString("tool", "工具"), 11, C_RED, true);
        card.addView(head);
        String reason = item.optString("reason", "");
        if (!reason.isEmpty()) card.addView(text(reason, 10, C_TEXT, false));
        String command = item.optString("command", "");
        if (!command.isEmpty()) {
            TextView code = text(command, 10, C_MUTED, false);
            code.setTypeface(Typeface.MONOSPACE);
            code.setMaxLines(5);
            code.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(code);
        }

        LinearLayout buttons = row();
        Button deny = smallButton("拒绝", C_CARD);
        deny.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decide(item.optString("id"), false);
            }
        });
        buttons.addView(deny, weightH(32));
        Button allow = smallButton("允许", C_GREEN);
        allow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decide(item.optString("id"), true);
            }
        });
        buttons.addView(allow, weightH(32));
        card.addView(buttons);
        return card;
    }

    View questionCard(final JSONObject item) {
        LinearLayout card = card(C_BLUE);
        card.addView(text("DSH 在问你", 11, C_BLUE, true));
        JSONArray questions = item.optJSONArray("questions");
        final JSONArray answers = new JSONArray();
        if (questions == null) {
            card.addView(note("没有内容"));
            return card;
        }
        for (int i = 0; i < questions.length(); i += 1) {
            JSONObject q = questions.optJSONObject(i);
            if (q == null) continue;
            card.addView(text(q.optString("question", ""), 11, C_TEXT, true));
            String detail = q.optString("detail", "");
            if (!detail.isEmpty()) card.addView(text(detail, 9, C_MUTED, false));
            final String qid = q.optString("id", "");
            JSONArray options = q.optJSONArray("options");
            if (options == null || options.length() == 0) {
                final EditText input = edit("");
                input.setHint("输入回答");
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                card.addView(input, lp(-1, dp(32), 0, 2, 0, 2));
                Button submit = smallButton("回答", C_BLUE);
                submit.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            JSONObject answer = new JSONObject();
                            answer.put("id", qid);
                            answer.put("selected", new JSONArray());
                            answer.put("custom", input.getText().toString());
                            answers.put(answer);
                            sendAnswers(item.optString("id"), answers);
                        } catch (Exception ignored) {
                        }
                    }
                });
                card.addView(submit, lp(-1, dp(30), 0, 2, 0, 2));
            } else {
                for (int k = 0; k < options.length() && k < 6; k += 1) {
                    JSONObject option = options.optJSONObject(k);
                    if (option == null) continue;
                    final String label = option.optString("label", "");
                    Button choice = smallButton(label.length() > 22 ? label.substring(0, 22) : label, C_CARD);
                    choice.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                    choice.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                JSONObject answer = new JSONObject();
                                answer.put("id", qid);
                                JSONArray selected = new JSONArray();
                                selected.put(label);
                                answer.put("selected", selected);
                                answers.put(answer);
                                sendAnswers(item.optString("id"), answers);
                            } catch (Exception ignored) {
                            }
                        }
                    });
                    card.addView(choice, lp(-1, dp(30), 0, 1, 0, 1));
                }
            }
        }
        return card;
    }

    void togglePolicy() {
        if (state == null || selectedId == null) return;
        JSONObject session = state.optJSONObject("session");
        if (session == null) return;
        if (session.optBoolean("auto", false)) {
            setAuto(false);
            return;
        }
        destructiveConfirm("自动通过审批？", "删文件、跑命令都不再逐条问你", "打开", new Runnable() {
            @Override
            public void run() {
                setAuto(true);
            }
        });
    }

    /** The watch answers this session's approvals with 允许 until it is turned off. */
    void setAuto(final boolean on) {
        JSONObject body = new JSONObject();
        try {
            body.put("session", selectedId);
            body.put("on", on);
        } catch (Exception ignored) {
        }
        api("POST", "/api/auto", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                toast(on ? "审批：自动通过" : "审批：逐条问");
                refreshNow(true);
            }

            @Override
            public void onErr(String message, int status) {
                toast("改不了: " + message);
            }
        });
    }

    void decide(String id, boolean allow) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("allow", allow);
        } catch (Exception ignored) {
        }
        api("POST", "/api/approve", body, simpleCallback(allow ? "已允许" : "已拒绝"));
        toast(allow ? "已允许" : "已拒绝");
    }

    void sendAnswers(String id, JSONArray answers) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("answers", answers);
        } catch (Exception ignored) {
        }
        api("POST", "/api/answer", body, simpleCallback("已回复"));
        toast("已回复");
    }

    void confirmStop() {
        destructiveConfirm("停止当前任务？", "这一轮会立刻中断，已经写下的内容还在", "停止", new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("session", selectedId);
                } catch (Exception ignored) {
                }
                api("POST", "/api/stop", body, simpleCallback("已停止"));
                toast("已停止");
            }
        });
    }

    /** Delete is permanent, so it always goes through this card first. */
    void confirmDelete(final Session target) {
        String hint = target.title == null || target.title.isEmpty() ? "从这个列表里删掉" : target.title;
        destructiveConfirm("删除这个对话？", hint + " · 删了不能恢复", "删除", new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("session", target.id);
                } catch (Exception ignored) {
                }
                api("POST", "/api/delete", body, new Callback() {
                    @Override
                    public void onOk(JSONObject response) {
                        finishedUnseen.remove(target.id);
                        saveFinished();
                        if (target.id.equals(selectedId)) selectedId = null;
                        toast("已删除");
                        showList();
                        refreshNow(true);
                    }

                    @Override
                    public void onErr(String message, int status) {
                        toast(message);
                    }
                });
            }
        });
    }

    /** A new empty conversation in the same directory as the one being looked at. */
    void createSession() {
        JSONObject body = new JSONObject();
        try {
            String cwd = "";
            for (int i = 0; i < sessions.length() && cwd.isEmpty(); i += 1) {
                JSONObject item = sessions.optJSONObject(i);
                if (item == null) continue;
                if (selectedId != null && selectedId.equals(item.optString("id", ""))) {
                    cwd = item.optString("cwd", "");
                }
            }
            for (int i = 0; i < sessions.length() && cwd.isEmpty(); i += 1) {
                JSONObject item = sessions.optJSONObject(i);
                if (item != null && !item.optString("cwd", "").isEmpty()) cwd = item.optString("cwd", "");
            }
            if (!cwd.isEmpty()) body.put("cwd", cwd);
        } catch (Exception ignored) {
        }
        toast("新建中…");
        api("POST", "/api/new", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                String id = response.optString("session", "");
                if (id.isEmpty()) {
                    toast("没建起来");
                    return;
                }
                selectedId = id;
                state = null;
                showDetail();
                refreshNow(true);
                toast("新对话");
            }

            @Override
            public void onErr(String message, int status) {
                toast("建不了: " + message);
            }
        });
    }

    void destructiveConfirm(String titleText, String hintText, String okLabel, final Runnable action) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_CARD_HI);
        card.setCornerRadius(dp(26));
        content.setBackground(card);

        TextView title = text(titleText, 13, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, fullWidth());

        TextView hint = text(hintText, 9, C_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(2), 0, dp(8));
        content.addView(hint, fullWidth());

        LinearLayout actions = row();
        Button cancel = smallButton("取消", C_CARD);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actions.addView(cancel, weightH(30));
        Button ok = smallButton(okLabel, C_RED);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                action.run();
            }
        });
        actions.addView(ok, weightH(30));
        content.addView(actions, lp(-1, -2, 0, 8, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    /** The composer: a floating capsule with +, the model, the text and send. */
    void showSayDialog() {
        if (selectedId == null) return;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout island = row();
        island.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable pill = bg(C_CARD_HI, 30);
        pill.setStroke(dp(1), C_LINE);
        island.setBackground(pill);

        Button plus = roundKey("＋", C_CARD);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPlusMenu(dialog);
            }
        });
        island.addView(plus, lp(dp(30), dp(30), 0, 0, 5, 0));

        final Button model = roundKey(modelLabel(), C_CARD);
        model.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        model.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelPicker(dialog, model);
            }
        });
        island.addView(model, lp(dp(58), dp(30), 0, 0, 5, 0));

        final EditText input = edit("");
        input.setHint("给 DSH 发消息…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        input.setSingleLine(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        island.addView(input, weight(1));

        Button send = roundKey("↑", C_ACCENT);
        send.setTextColor(C_ON_ACCENT);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) return;
                say(text);
                dialog.dismiss();
            }
        });
        island.addView(send, lp(dp(30), dp(30), 5, 0, 0, 0));

        if (currentModelName.isEmpty()) refreshModelLabel(model);
        dialog.setContentView(island);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.96f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM;
            params.y = dp(8);
            window.setAttributes(params);
        }
        dialog.show();
    }

    /** Header row with a ✕ that closes the dialog: the watch has no easy outside tap. */
    /** Show a dialog without the platform zoom, which reads as a stutter on a watch. */
    void showPlain(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) window.setWindowAnimations(0);
        dialog.show();
    }

    LinearLayout dialogHead(final Dialog dialog, String label) {
        LinearLayout head = row();
        TextView title = text(label, 9, C_MUTED, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(title, weight(1));
        Button close = roundKey("✕", C_CARD);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        head.addView(close, lp(dp(22), dp(22), 4, 0, 0, 0));
        return head;
    }

    /** Round button used inside the composer capsule. */
    Button roundKey(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTextColor(C_TEXT);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(bg(color, 18));
        button.setStateListAnimator(null);
        return button;
    }

    String modelLabel() {
        if (currentModelName.isEmpty()) return "模型";
        String label = currentModelName;
        if (label.startsWith("DeepSeek-")) label = label.substring(9);
        return label.length() > 9 ? label.substring(0, 9) : label;
    }

    void showPlusMenu(final Dialog composer) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(bg(C_CARD_HI, 28));

        Button photo = roundKey("图片", C_CARD);
        photo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (composer != null) composer.dismiss();
                ensureStorage(1);
            }
        });
        box.addView(dialogHead(dialog, "发送"), fullWidth());
        box.addView(photo, lp(-1, dp(32), 0, 4, 0, 6));

        Button file = roundKey("文件", C_CARD);
        file.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        file.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (composer != null) composer.dismiss();
                ensureStorage(2);
            }
        });
        box.addView(file, lp(-1, dp(32), 0, 0, 0, 0));

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.6f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask for storage once, then open whichever picker was requested. */
    void ensureStorage(int which) {
        if (hasStorage()) {
            if (which == 1) showImagePicker(); else showFilePicker(new File("/sdcard/Download"));
            return;
        }
        pendingPicker = which;
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        toast("先允许读取存储");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_STORAGE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        int which = pendingPicker;
        pendingPicker = 0;
        if (!granted) {
            toast("没有存储权限，读不到图片和文件");
            return;
        }
        if (which == 1) showImagePicker();
        else if (which == 2) showFilePicker(new File("/sdcard/Download"));
    }

    /** Newest pictures anywhere on the watch, as a small grid. */
    void showImagePicker() {
        toast("找图片…");
        net.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<File> found = new ArrayList<>();
                collectImages(new File("/sdcard"), found, 0);
                if (found.isEmpty()) collectImages(new File("/storage/emulated/0"), found, 0);
                Collections.sort(found, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showImageGrid(found);
                    }
                });
            }
        });
    }

    void showImageGrid(final ArrayList<File> found) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Dialog picker = dialog;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(bg(C_CARD_HI, 24));

        box.addView(dialogHead(picker, found.isEmpty() ? "没有找到图片" : "最近的图片 · " + found.size()), fullWidth());
        if (found.isEmpty()) {
            TextView tip = text("用电脑 adb push 一张到 /sdcard/Download 就行", 9, C_MUTED, false);
            tip.setPadding(0, dp(4), 0, 0);
            box.addView(tip);
        }
        int perRow = 3;
        LinearLayout gridRow = null;
        for (int i = 0; i < Math.min(found.size(), 18); i += 1) {
            if (i % perRow == 0) {
                gridRow = row();
                box.addView(gridRow, fullWidth());
            }
            final File file = found.get(i);
            ImageView cell = new ImageView(this);
            cell.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.setBackground(bg(C_CARD, 8));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picker.dismiss();
                    uploadFile(file, "image");
                }
            });
            gridRow.addView(cell, lp(dp(50), dp(50), 0, 0, 5, 5));
            thumbLocal(file, cell);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    void collectImages(File dir, ArrayList<File> out, int depth) {
        if (dir == null || depth > 6 || out.size() >= 40) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (out.size() >= 40) return;
            String name = entry.getName();
            if (name.startsWith(".")) continue;
            if (entry.isDirectory()) {
                // App-private trees hold thousands of thumbnails and nothing useful.
                if (name.equals("Android") || name.equals("LOST.DIR") || name.equals("DCIM-cover")) continue;
                collectImages(entry, out, depth + 1);
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
                if (entry.length() > 64) out.add(entry);
            }
        }
    }

    void thumbLocal(final File file, final ImageView view) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                int sample = 1;
                while (options.outWidth / sample > 300) sample *= 2;
                BitmapFactory.Options real = new BitmapFactory.Options();
                real.inSampleSize = sample;
                final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), real);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bitmap != null) view.setImageBitmap(scaleTo(bitmap, dp(110)));
                    }
                });
            }
        });
    }

    /** Walk the watch's own storage without a system file picker. */
    void showFilePicker(final File dir) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Dialog picker = dialog;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(bg(C_CARD_HI, 24));

        box.addView(dialogHead(picker, shortPath(dir)), fullWidth());

        File parent = dir.getParentFile();
        if (parent != null) {
            Button up = roundKey("‹ 上一级", C_CARD);
            up.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picker.dismiss();
                    showFilePicker(dir.getParentFile());
                }
            });
            box.addView(up, lp(-1, dp(28), 0, dp(4), 0, dp(4)));
        }

        File[] entries = dir.listFiles();
        if (entries == null) entries = new File[0];
        ArrayList<File> dirs = new ArrayList<>();
        ArrayList<File> files = new ArrayList<>();
        for (File entry : entries) {
            if (entry.getName().startsWith(".")) continue;
            if (entry.isDirectory()) dirs.add(entry); else files.add(entry);
        }
        Collections.sort(dirs);
        Collections.sort(files);
        for (final File entry : dirs) {
            Button row = roundKey("▸ " + entry.getName(), C_CARD);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picker.dismiss();
                    showFilePicker(entry);
                }
            });
            box.addView(row, lp(-1, dp(26), 0, 0, 0, 3));
        }
        int shown = 0;
        for (final File entry : files) {
            if (shown >= 40) break;
            shown += 1;
            Button row = roundKey(entry.getName() + "  " + (entry.length() / 1024) + "K", C_CARD);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picker.dismiss();
                    uploadFile(entry, "file");
                }
            });
            box.addView(row, lp(-1, dp(26), 0, 0, 0, 3));
        }
        if (dirs.isEmpty() && files.isEmpty()) box.addView(text("这个文件夹是空的", 9, C_MUTED, false));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    String shortPath(File dir) {
        String path = dir.getAbsolutePath();
        return path.startsWith("/sdcard") ? "手表" + path.substring(7) : path;
    }

    /** Read a file and hand it to the DSH as a prompt with its path. */
    void uploadFile(final File file, final String kind) {
        if (selectedId == null) return;
        if (file.length() > 8L * 1024 * 1024) {
            toast("文件太大（上限 8MB）");
            return;
        }
        toast("上传中…");
        final String sessionId = selectedId;
        net.execute(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = null;
                String error = null;
                try {
                    bytes = readAllBytes(new FileInputStream(file));
                } catch (Exception err) {
                    error = "读不了这个文件";
                }
                final byte[] data = bytes;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (data == null) {
                            toast(failure);
                            return;
                        }
                        JSONObject body = new JSONObject();
                        try {
                            body.put("session", sessionId);
                            body.put("name", file.getName());
                            body.put("kind", kind);
                            body.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                        } catch (Exception ignored) {
                        }
                        toast("传 " + (data.length / 1024) + "K…");
                        api("POST", "/api/upload", body, new Callback() {
                            @Override
                            public void onOk(JSONObject response) {
                                toast("已发给 DSH");
                                refreshNow(true);
                            }

                            @Override
                            public void onErr(String message, int status) {
                                toast("传不上去: " + message);
                            }
                        });
                    }
                });
            }
        });
    }

    /** Quietly fill the chip with the session's current model. */
    void refreshModelLabel(final Button chip) {
        if (selectedId == null) return;
        api("GET", "/api/models?session=" + enc(selectedId), null, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                JSONObject current = response.optJSONObject("current");
                if (current == null) return;
                currentModelName = nameOf(response.optJSONArray("groups"),
                        current.optString("provider", ""), current.optString("model", ""));
                if (chip != null) chip.setText(modelLabel());
            }

            @Override
            public void onErr(String message, int status) {
                /* old bridge: leave the chip as 模型 */
            }
        });
    }

    /** Provider tabs -> models -> reasoning effort, like the phone's sheet. */
    JSONArray pickerGroups;
    String pickerProvider = "";
    String pickerModel = "";
    String pickerEffort = "";
    /** Scroll the sheet to the effort row right after a model tap. */
    boolean sheetToEnd;

    void showModelPicker(final Dialog composer, final Button chip) {
        if (selectedId == null) return;
        api("GET", "/api/models?session=" + enc(selectedId), null, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                pickerGroups = response.optJSONArray("groups");
                JSONObject current = response.optJSONObject("current");
                if (current != null) {
                    pickerProvider = current.optString("provider", "");
                    pickerModel = current.optString("model", "");
                    pickerEffort = current.optString("reasoningEffort", "");
                    currentModelName = nameOf(pickerGroups, pickerProvider, pickerModel);
                    chip.setText(modelLabel());
                }
                if (pickerGroups == null) {
                    toast("没有可选模型");
                    return;
                }
                buildModelDialog(chip);
            }

            @Override
            public void onErr(String message, int status) {
                toast(status == 404 ? "服务器插件太旧，升到 0.3.4 才有模型列表" : "列不出模型: " + message);
            }
        });
    }

    JSONObject groupOf(JSONArray groups, String provider) {
        if (groups == null) return null;
        for (int i = 0; i < groups.length(); i += 1) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null && provider.equals(group.optString("id", ""))) return group;
        }
        return groups.optJSONObject(0);
    }

    JSONObject modelOf(JSONObject group, String modelId) {
        if (group == null) return null;
        JSONArray models = group.optJSONArray("models");
        for (int i = 0; models != null && i < models.length(); i += 1) {
            JSONObject item = models.optJSONObject(i);
            if (item != null && modelId.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    static String effortLabel(String id, String name) {
        if ("none".equals(id) || "minimal".equals(id)) return "不思考";
        if ("low".equals(id)) return "低";
        if ("medium".equals(id)) return "中";
        if ("high".equals(id)) return "高";
        if ("max".equals(id) || "maximum".equals(id)) return "最高";
        return name == null || name.isEmpty() ? id : name;
    }

    void buildModelDialog(final Button chip) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(6), dp(10), dp(8));
        LinearLayout box = body;

        // Providers: two per row, so the models stay on screen without scrolling.
        JSONArray groups = pickerGroups;
        LinearLayout providerRow = null;
        int placed = 0;
        for (int i = 0; groups != null && i < groups.length(); i += 1) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            if (placed % 2 == 0) {
                providerRow = row();
                box.addView(providerRow, fullWidth());
            }
            placed += 1;
            final String providerId = group.optString("id", "");
            boolean on = providerId.equals(pickerProvider);
            Button tab = roundKey(shortProvider(group.optString("name", providerId)), on ? C_ACCENT : C_CARD);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tab.setTextColor(on ? C_ON_ACCENT : C_TEXT);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickerProvider = providerId;
                    pickerModel = "";
                    pickerEffort = "";
                    dialog.dismiss();
                    buildModelDialog(chip);
                }
            });
            LinearLayout.LayoutParams tabParams = lp(0, dp(24), 0, 2, 3, 2);
            tabParams.weight = 1;
            providerRow.addView(tab, tabParams);
        }

        // Hairline between "which provider" and "which model".
        View divider = new View(this);
        divider.setBackgroundColor(C_LINE);
        box.addView(divider, lp(-1, 1, 0, 4, 0, 5));

        // Models of the chosen provider
        JSONObject current = groupOf(pickerGroups, pickerProvider);
        if (current == null) {
            box.addView(text("这个供应商没有可用模型", 9, C_MUTED, false));
        } else {
            JSONArray models = current.optJSONArray("models");
            for (int i = 0; models != null && i < models.length(); i += 1) {
                JSONObject model = models.optJSONObject(i);
                if (model == null) continue;
                final String modelId = model.optString("id", "");
                final String providerId = current.optString("id", "");
                boolean on = modelId.equals(pickerModel);
                String label = model.optString("name", modelId);
                String badge = model.optString("badge", "");
                if (!badge.isEmpty()) label += " · " + badge;
                LinearLayout line = row();
                line.setPadding(dp(8), dp(4), dp(8), dp(4));
                line.setBackground(bg(on ? C_CARD : C_CARD_HI, 12));
                TextView name = text(label, 10, on ? C_ACCENT : C_TEXT, on);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(name, weight(1));
                line.addView(text(on ? "✓" : "", 10, C_ACCENT, true), lp(dp(12), -2, 2, 0, 0, 0));
                line.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Picking a model is only half the choice: keep the sheet
                        // open so the effort row below applies the decision.
                        pickerProvider = providerId;
                        pickerModel = modelId;
                        pickerEffort = "";
                        sheetToEnd = true;
                        dialog.dismiss();
                        buildModelDialog(chip);
                    }
                });
                box.addView(line, lp(-1, -2, 0, 3, 0, 0));
            }
        }

        // Reasoning effort for the selected model
        JSONObject selected = modelOf(current, pickerModel);
        JSONArray efforts = selected == null ? null : selected.optJSONArray("efforts");
        if ((efforts == null || efforts.length() == 0) && selected != null) {
            // The catalog does not advertise levels for every model; offer the
            // standard ladder and let the host reject one it cannot serve.
            efforts = new JSONArray();
            for (String[] pair : new String[][]{{"none", "不思考"}, {"low", "低"}, {"high", "高"}, {"max", "最高"}}) {
                JSONObject effort = new JSONObject();
                try {
                    effort.put("id", pair[0]);
                    effort.put("name", pair[1]);
                } catch (Exception ignored) {
                }
                efforts.put(effort);
            }
        }
        if (selected == null && pickerModel.isEmpty()) {
            TextView hint = text("先选一个模型，再选思考程度", 9, C_MUTED, false);
            hint.setPadding(0, dp(4), 0, 0);
            box.addView(hint);
        }
        if (efforts != null && efforts.length() > 0) {
            box.addView(text("思考深度", 9, C_MUTED, false));
            LinearLayout row = row();
            for (int i = 0; i < efforts.length(); i += 1) {
                JSONObject effort = efforts.optJSONObject(i);
                if (effort == null) continue;
                final String effortId = effort.optString("id", "");
                boolean on = !pickerEffort.isEmpty()
                        ? effortId.equals(pickerEffort)
                        : effortId.equals(selected.optString("defaultEffort", ""));
                Button chipBtn = roundKey(effortLabel(effortId, effort.optString("name", effortId)), on ? C_ACCENT : C_CARD);
                chipBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                if (on) chipBtn.setTextColor(C_ON_ACCENT);
                chipBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        selectModel(pickerProvider, pickerModel, effortId, chip);
                    }
                });
                row.addView(chipBtn, lp(0, dp(26), 0, 0, 3, 0));
                ((LinearLayout.LayoutParams) chipBtn.getLayoutParams()).weight = 1;
            }
            box.addView(row, fullWidth());
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(6));
        root.setBackground(bg(C_CARD_HI, 24));
        // The header stays put; only the list below it scrolls, so ✕ never leaves.
        root.addView(dialogHead(dialog, "选择模型"), fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, weightV());
        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            params.width = (int) (screenWidth * 0.94f);
            body.measure(View.MeasureSpec.makeMeasureSpec((int) (params.width * 0.92f), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int wanted = body.getMeasuredHeight() + dp(42);
            params.height = Math.min(wanted, (int) (screenHeight * 0.86f));
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    /** "open.bigmodel.cn" reads better as "bigmodel" on a 186dp screen. */
    static String shortProvider(String name) {
        if (name == null || name.isEmpty()) return "?";
        String label = name;
        if (label.contains(".")) {
            String[] parts = label.split("\\.");
            label = parts[0];
            if (("api".equals(label) || "open".equals(label)) && parts.length > 1) label = parts[1];
        }
        return label.length() > 12 ? label.substring(0, 12) : label;
    }

    String nameOf(JSONArray groups, String provider, String model) {
        if (groups == null) return model;
        for (int i = 0; i < groups.length(); i += 1) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null || !provider.equals(group.optString("id", ""))) continue;
            JSONArray models = group.optJSONArray("models");
            if (models == null) continue;
            for (int m = 0; m < models.length(); m += 1) {
                JSONObject item = models.optJSONObject(m);
                if (item != null && model.equals(item.optString("id", ""))) {
                    return item.optString("name", model);
                }
            }
        }
        return model;
    }

    void selectModel(final String provider, final String model, final String effort, final Button chip) {
        JSONObject body = new JSONObject();
        try {
            body.put("session", selectedId);
            body.put("provider", provider);
            body.put("model", model);
            if (effort != null && !effort.isEmpty()) body.put("reasoningEffort", effort);
        } catch (Exception ignored) {
        }
        api("POST", "/api/model", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                JSONObject selected = response.optJSONObject("selected");
                currentModelName = selected == null ? model : selected.optString("model", model);
                if (chip != null) chip.setText(modelLabel());
                toast("换好了");
                refreshNow(true);
            }

            @Override
            public void onErr(String message, int status) {
                toast("换不了: " + message);
            }
        });
    }

    /** Tonal pill with a hairline outline: reads as a button on the dialog surface. */
    Button outlinePill(String label) {
        Button button = smallButton(label, C_CARD_HI);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(C_CARD);
        shape.setCornerRadius(dp(20));
        shape.setStroke(dp(1), C_LINE);
        button.setBackground(shape);
        return button;
    }

    /** Ask for a label right after pairing; an empty name falls back to the address. */
    void askServerName(final String url, final String token) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_CARD_HI);
        card.setCornerRadius(dp(26));
        box.setBackground(card);

        TextView title = text("给这台 DSH 起个名字", 13, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, fullWidth());

        TextView hint = text(shortHost(url), 9, C_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(2), 0, dp(6));
        box.addView(hint, fullWidth());

        final EditText input = edit("");
        input.setHint("如：家里的电脑 / 云服务器");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setSingleLine(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        GradientDrawable field = new GradientDrawable();
        field.setColor(C_CARD);
        field.setCornerRadius(dp(18));
        field.setStroke(dp(1), C_LINE);
        input.setBackground(field);
        box.addView(input, lp(-1, dp(32), 0, 4, 0, 0));

        LinearLayout actions = row();
        Button skip = smallButton("跳过", C_CARD);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rememberServer(url, token, "");
                dialog.dismiss();
            }
        });
        actions.addView(skip, weightH(30));
        Button save = smallButton("保存", C_ACCENT);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rememberServer(url, token, input.getText().toString().trim());
                dialog.dismiss();
            }
        });
        actions.addView(save, weightH(30));
        box.addView(actions, lp(-1, -2, 0, 8, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    /** Known DSH hosts: [{url, token, name}], the current one included. */
    String serverList() {
        return prefs.getString("servers", "[]");
    }

    void rememberServer(String url, String token, String name) {
        // Never store a blank token: that would wipe the working one for this host.
        if (url == null || url.isEmpty() || token == null || token.isEmpty()) return;
        try {
            JSONArray list = new JSONArray(serverList());
            JSONArray next = new JSONArray();
            String keptName = "";
            for (int i = 0; i < list.length(); i += 1) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                if (url.equals(item.optString("url", ""))) {
                    keptName = item.optString("name", "");
                    continue;
                }
                next.put(item);
            }
            // Loads and switches pass an empty name; they must not wipe the label
            // the user typed when naming this host.
            String wanted = name == null ? "" : name.trim();
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("token", token);
            entry.put("name", wanted.isEmpty() ? keptName : wanted);
            next.put(entry);
            prefs.edit().putString("servers", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void confirmForget(final String url, final Dialog parent) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_CARD_HI);
        card.setCornerRadius(dp(26));
        box.setBackground(card);

        TextView title = text("删除这台 DSH？", 13, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, fullWidth());
        TextView who = text(shortHost(url), 10, C_MUTED, false);
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, dp(4), 0, dp(10));
        box.addView(who, fullWidth());

        LinearLayout actions = row();
        Button cancel = smallButton("取消", C_CARD);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actions.addView(cancel, weightH(32));
        Button remove = smallButton("删除", C_RED);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgetServer(url);
                dialog.dismiss();
                parent.dismiss();
                showDevicesDialog();
            }
        });
        actions.addView(remove, weightH(32));
        box.addView(actions, lp(-1, -2, 0, 6, 0, 0));

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
    }

    void forgetServer(String url) {
        Log.i(TAG, "forget server " + url);
        try {
            JSONArray list = new JSONArray(serverList());
            JSONArray next = new JSONArray();
            for (int i = 0; i < list.length(); i += 1) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                if (url.equals(item.optString("url", ""))) continue;
                next.put(item);
            }
            prefs.edit().putString("servers", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void switchServer(String url, String newToken) {
        baseUrl = url;
        // `token = token` used to shadow the field and keep the previous host's
        // token, which made every switch a 401.
        token = newToken;
        prefs.edit().putString("url", url).putString("token", newToken).apply();
        selectedId = null;
        state = null;
        // The previous host's conversations must not linger under the new one.
        sessions = new JSONArray();
        lastError = "";
        gotState = false;
        showList();
        refreshNow(true);
    }

    void showDevicesDialog() {
        if (token == null) {
            toast("先配对才能管理设备");
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_CARD_HI);
        card.setCornerRadius(dp(26));
        list.setBackground(card);

        TextView title = text("已配对设备", 13, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        list.addView(title, fullWidth());
        final TextView hint = text("读取中…", 10, C_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        list.addView(hint, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();

        // Fill in synchronously: the list lives in SharedPreferences.
        list.removeAllViews();
        list.addView(title, fullWidth());
        JSONArray servers;
        try {
            servers = new JSONArray(serverList());
        } catch (Exception err) {
            servers = new JSONArray();
        }
        boolean hasCurrent = false;
        for (int i = 0; i < servers.length(); i += 1) {
            JSONObject item = servers.optJSONObject(i);
            if (item != null && baseUrl != null && baseUrl.equals(item.optString("url", ""))) hasCurrent = true;
        }
        if (!hasCurrent && baseUrl != null) {
            // Persist it too, so switching away and back keeps both hosts.
            rememberServer(baseUrl, token == null ? "" : token, "");
            try {
                servers = new JSONArray(serverList());
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i < servers.length(); i += 1) {
            final JSONObject item = servers.optJSONObject(i);
            if (item == null) continue;
            final String url = item.optString("url", "");
            final String entryToken = item.optString("token", "");
            boolean current = url.equals(baseUrl);
            final LinearLayout row = row();
            String label = item.optString("name", "");
            if (label.isEmpty()) label = shortHost(url);
            TextView name = text(label + (current ? "  ●" : ""), 10, current ? C_ACCENT : C_TEXT, current);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name, weight(1));
            if (url.equals(pendingDelete)) {
                Button drop = smallButton("删除", C_RED);
                drop.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        forgetServer(url);
                        pendingDelete = null;
                        dialog.dismiss();
                        showDevicesDialog();
                    }
                });
                row.addView(drop, lp(dp(52), dp(26), 6, 0, 0, 0));
            } else if (!current) {
                Button use = outlinePill("切换");
                use.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchServer(url, entryToken);
                        dialog.dismiss();
                    }
                });
                row.addView(use, lp(dp(52), dp(26), 6, 0, 0, 0));
            }
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (current) {
                        toast("当前连接不能删，先切到别的");
                        return true;
                    }
                    Log.i(TAG, "long-press server " + url);
                    pendingDelete = url;
                    dialog.dismiss();
                    showDevicesDialog();
                    return true;
                }
            });
            list.addView(row, lp(-1, -2, 0, 5, 0, 0));
        }
        TextView tip = text("长按可删除", 9, C_MUTED, false);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(6), 0, 0);
        list.addView(tip, fullWidth());
        Button close = smallButton(pendingDelete == null ? "关闭" : "取消", C_CARD);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pendingDelete != null) {
                    pendingDelete = null;
                    dialog.dismiss();
                    showDevicesDialog();
                    return;
                }
                dialog.dismiss();
            }
        });
        list.addView(close, lp(-1, dp(30), 0, 10, 0, 0));
    }

    static String shortHost(String url) {
        String out = url == null ? "" : url;
        if (out.startsWith("http://")) out = out.substring(7);
        if (out.startsWith("https://")) out = out.substring(8);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    void revokeDevice(final String id, final Dialog dialog) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
        } catch (Exception ignored) {
        }
        api("POST", "/api/devices/revoke", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                toast("已撤销");
                dialog.dismiss();
                showDevicesDialog();
            }

            @Override
            public void onErr(String message, int status) {
                toast("撤销失败: " + message);
            }
        });
    }

    void say(String text) {
        JSONObject body = new JSONObject();
        try {
            body.put("session", selectedId);
            body.put("text", text);
        } catch (Exception ignored) {
        }
        api("POST", "/api/say", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                toast("已发送");
                refreshNow();
            }

            @Override
            public void onErr(String message, int status) {
                toast("没发出去: " + message);
            }
        });
    }

    Callback simpleCallback(final String okText) {
        return new Callback() {
            @Override
            public void onOk(JSONObject body) {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshNow();
                    }
                }, 400);
            }

            @Override
            public void onErr(String message, int status) {
                toast("失败: " + message);
            }
        };
    }

    void selectTheme(int index) {
        Log.i(TAG, "selectTheme " + index);
        prefs.edit().putInt("theme", index).apply();
        applyTheme(index);
        root.setBackgroundColor(C_BG);
        if (conversation != null) conversation.setBackgroundColor(C_BG);

        // Everything was drawn with the old palette: drop the cached views and
        // redraw the list. No pager, no exit page - they no longer exist.
        screenList = null;
        screenDetail = null;
        screenPair = null;
        listStatus = null;
        detailTitle = null;
        policyChip = null;
        pairUrl = null;
        pairCode = null;
        pairStatus = null;
        history.clear();
        if (conversation != null) conversation.removeAllViews();
        showList();
        toast("换好了");
    }

    /** Leave without the platform's activity zoom/fade. */
    void quitApp() {
        ui.removeCallbacks(ticker);
        finish();
        overridePendingTransition(0, 0);
    }

    /** System back: one step out of the current screen. */
    @Override
    public void onBackPressed() {
        if (showing(screenDetail)) {
            selectedId = null;
            showList();
            refreshNow(true);
            return;
        }
        if (currentScreen() != null && !showing(screenList)) {
            goBack();
            return;
        }
        super.onBackPressed();
    }

    // ============================================================== 独立模式

    String soloBase() {
        return soloBase != null ? soloBase : "https://api.deepseek.com";
    }

    String soloModel() {
        return soloModel == null || soloModel.isEmpty() ? "deepseek-chat" : soloModel;
    }

    void loadSolo() {
        mode = prefs.getString("mode", "host");
        soloBase = prefs.getString("soloBase", "");
        soloKey = prefs.getString("soloKey", "");
        soloModel = prefs.getString("soloModel", "deepseek-chat");
        soloMessages = null;
        try {
            soloMessages = new JSONArray(prefs.getString("soloMessages", "[]"));
        } catch (Exception bad) {
            soloMessages = new JSONArray();
        }
    }

    void saveSoloConfig() {
        prefs.edit()
                .putString("mode", mode)
                .putString("soloBase", soloBase == null ? "" : soloBase)
                .putString("soloKey", soloKey == null ? "" : soloKey)
                .putString("soloModel", soloModel())
                .apply();
    }

    void saveSoloMessages() {
        prefs.edit().putString("soloMessages",
                soloMessages == null ? "[]" : soloMessages.toString()).apply();
    }

    void showModePage() {
        LinearLayout page = secondary("切换模式");
        addModeRow(page, "host", "连接 DSH", "看电脑上 DSH 在做什么，并回话");
        addModeRow(page, "solo", "独立使用", "不连 DSH，手表直接调模型");
        readingDetail = false;
        go(page);
    }

    void addModeRow(LinearLayout page, final String value, String label, String hint) {
        boolean active = value.equals(mode);
        View row = pageRow((active ? "● " : "○ ") + label, hint, new Runnable() {
            public void run() {
                if ("solo".equals(value) && (soloKey == null || soloKey.isEmpty())) {
                    showSoloConfig(null);
                    return;
                }
                mode = value;
                saveSoloConfig();
                history.clear();
                if ("solo".equals(mode)) showSoloChat(); else showList();
            }
        });
        page.addView(row, fullWidth());
    }

    /** Base URL / API key / model, typed on the watch or filled from the QR page. */
    void showSoloConfig(String message) {
        LinearLayout page = secondary("独立使用");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(2), 0, dp(8));

        TextView hint = text(message != null ? message
                : "手表直接调模型，不经过 DSH。填一次就记住。", 9, C_MUTED, false);
        hint.setPadding(dp(8), 0, dp(8), dp(5));
        body.addView(hint, fullWidth());

        final EditText base = edit(soloBase());
        baseField = base;
        base.setHint("https://api.deepseek.com");
        body.addView(field("Base URL", base), fullWidth());

        final EditText key = edit(soloKey == null ? "" : soloKey);
        keyField = key;
        key.setHint("sk-…");
        key.setSingleLine(true);
        body.addView(field("API Key", key), fullWidth());

        final TextView model = text(soloModel(), 10, C_TEXT, false);
        model.setSingleLine(true);
        model.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        model.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout modelBox = new LinearLayout(this);
        modelBox.setOrientation(LinearLayout.VERTICAL);
        modelBox.setPadding(dp(10), dp(4), dp(10), dp(5));
        modelBox.setBackground(bg(C_CARD, 12));
        modelBox.addView(text("模型", 8, C_MUTED, false), fullWidth());
        LinearLayout modelRow = row();
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.addView(model, weight(1));
        LinearLayout loadModels = new LinearLayout(this);
        loadModels.setOrientation(LinearLayout.HORIZONTAL);
        loadModels.setGravity(Gravity.CENTER);
        loadModels.setBackground(bg(C_ACCENT, 10));
        Glyph cloud = new Glyph(this, Glyph.CLOUD);
        loadModels.addView(cloud, lp(dp(14), dp(14), 0, 0, 3, 0));
        TextView loadLabel = text("获取", 9, C_ON_ACCENT, true);
        loadModels.addView(loadLabel);
        loadModels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String base = baseField.getText().toString().trim();
                String key = keyField.getText().toString().trim();
                if (key.isEmpty()) {
                    toast("先填 API Key");
                    return;
                }
                loadModels(base, key, model);
            }
        });
        modelRow.addView(loadModels, lp(dp(74), dp(26), 4, 0, 0, 0));
        modelBox.addView(modelRow, fullWidth());
        LinearLayout modelWrap = new LinearLayout(this);
        modelWrap.setOrientation(LinearLayout.VERTICAL);
        modelWrap.setPadding(0, dp(1), 0, dp(3));
        modelWrap.addView(modelBox, fullWidth());
        body.addView(modelWrap, fullWidth());

        Button save = smallButton("保存并开始", C_ACCENT);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soloBase = base.getText().toString().trim();
                soloKey = key.getText().toString().trim();
                soloModel = model.getText().toString().trim();
                if (soloModel.isEmpty()) soloModel = "deepseek-chat";
                if (soloKey.isEmpty()) {
                    toast("API Key 不能为空");
                    return;
                }
                mode = "solo";
                saveSoloConfig();
                history.clear();
                showSoloChat();
            }
        });
        body.addView(save, lp(-1, dp(34), 0, dp(8), 0, 0));

        TextView tip = text("懒得按键？扫这个二维码，在手机上填，手表自动填入。", 8, C_MUTED, false);
        tip.setPadding(dp(8), dp(12), dp(8), dp(4));
        body.addView(tip, fullWidth());
        body.addView(soloQrView(), lp(-1, dp(150), 0, 0, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, weightV());
        readingDetail = false;
        go(page);
    }

    /** Ask the API which models the key can use, then pick one. */
    void loadModels(final String base, final String key, final TextView target) {
        toast("正在获取模型…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String> ids = new ArrayList<>();
                String error = null;
                try {
                    String url = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                    HttpURLConnection conn = (HttpURLConnection) new URL(url + "/models").openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                    int code = conn.getResponseCode();
                    InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String raw = soloReadAll(stream);
                    conn.disconnect();
                    if (code >= 400) {
                        error = "HTTP " + code + " " + shorten(raw, 90);
                    } else {
                        JSONObject root = new JSONObject(raw);
                        JSONArray data = root.optJSONArray("data");
                        for (int i = 0; data != null && i < data.length(); i += 1) {
                            JSONObject item = data.optJSONObject(i);
                            String id = item == null ? null : item.optString("id", "");
                            if (id != null && !id.isEmpty()) ids.add(id);
                        }
                    }
                } catch (Throwable err) {
                    error = String.valueOf(err.getMessage());
                }
                final String failed = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (failed != null) {
                            toast("获取失败 " + failed);
                            return;
                        }
                        if (ids.isEmpty()) {
                            toast("这个 Key 没有可用模型");
                            return;
                        }
                        final Dialog dialog = new Dialog(MainActivity.this);
                        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                        dialog.setCanceledOnTouchOutside(true);
                        LinearLayout box = new LinearLayout(MainActivity.this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setPadding(dp(8), dp(6), dp(8), dp(6));
                        box.setBackground(bg(C_CARD_HI, 20));
                        box.addView(dialogHead(dialog, "选一个模型"), fullWidth());
                        ScrollView scroll = new ScrollView(MainActivity.this);
                        scroll.setVerticalScrollBarEnabled(false);
                        LinearLayout list = new LinearLayout(MainActivity.this);
                        list.setOrientation(LinearLayout.VERTICAL);
                        for (String id : ids) {
                            final String choice = id;
                            TextView row = text(id, 10, C_TEXT, false);
                            row.setPadding(dp(10), dp(8), dp(10), dp(8));
                            row.setBackground(bg(C_CARD, 10));
                            row.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    target.setText(choice);
                                    dialog.dismiss();
                                }
                            });
                            LinearLayout wrap = new LinearLayout(MainActivity.this);
                            wrap.setOrientation(LinearLayout.VERTICAL);
                            wrap.setPadding(0, dp(1), 0, dp(2));
                            wrap.addView(row, fullWidth());
                            list.addView(wrap, fullWidth());
                        }
                        scroll.addView(list, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        box.addView(scroll, lp(-1, dp(210), 0, 0, 0, 0));
                        dialog.setContentView(box);
                        Window window = dialog.getWindow();
                        if (window != null) {
                            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
                            WindowManager.LayoutParams params = window.getAttributes();
                            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
                            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                            params.gravity = Gravity.CENTER;
                            window.setAttributes(params);
                        }
                        showPlain(dialog);
                    }
                });
            }
        }).start();
    }

    /** One labelled input row. */
    View field(String label, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(4), dp(10), dp(4));
        box.setBackground(bg(C_CARD, 12));
        TextView name = text(label, 8, C_MUTED, false);
        box.addView(name, fullWidth());
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        input.setBackground(null);
        input.setPadding(0, dp(1), 0, 0);
        box.addView(input, fullWidth());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(1), 0, dp(3));
        wrap.addView(box, fullWidth());
        return wrap;
    }

    /** QR into the LAN page the phone opens to fill Base URL / key / model. */
    View soloQrView() {
        SoloConfig.setListener(new SoloConfig.Listener() {
            @Override
            public void onSaved(String base, String key, String model) {
                soloBase = base;
                soloKey = key;
                soloModel = model;
                mode = "solo";
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("手机填好了，已保存");
                        history.clear();
                        showSoloChat();
                    }
                });
            }
        });
        String url = SoloConfig.start(this);
        final String text = url == null ? "http://" + (SoloConfig.localIp() == null ? "?" : SoloConfig.localIp()) + ":8788/" : url;
        final boolean[][] grid = Qr.encode(text, Qr.M);
        View view = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                canvas.drawColor(0x00000000);
                int w = getWidth();
                int h = getHeight();
                int side = Math.min(w, h);
                int left = (w - side) / 2;
                int top = (h - side) / 2;
                Paint back = new Paint();
                back.setColor(0xFFFFFFFF);
                canvas.drawRoundRect(new RectF(left, top, left + side, top + side), dp(8), dp(8), back);
                if (grid == null) return;
                Paint ink = new Paint();
                ink.setColor(0xFF0E1116);
                ink.setAntiAlias(false);
                int count = grid.length + 8;
                float unit = (float) side / count;
                for (int y = 0; y < grid.length; y += 1) {
                    for (int x = 0; x < grid.length; x += 1) {
                        if (!grid[y][x]) continue;
                        float px = left + (x + 4) * unit;
                        float py = top + (y + 4) * unit;
                        canvas.drawRect(px, py, px + unit + 0.5f, py + unit + 0.5f, ink);
                    }
                }
            }
        };
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView caption = text(text, 7, C_MUTED, false);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(2), 0, 0);
        box.addView(caption, fullWidth());
        return box;
    }

    void showSoloChat() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(6), dp(8), dp(2));
        head.addView(text("独立使用", 12, C_ACCENT, true), weight(1));
        Button config = smallButton("设置", C_CARD);
        config.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSoloConfig(null);
            }
        });
        head.addView(config, lp(dp(52), dp(24), 0, 0, 0, 0));
        page.addView(head, fullWidth());

        soloStatus = text("", 8, C_MUTED, false);
        soloStatus.setPadding(dp(10), 0, dp(10), dp(2));
        page.addView(soloStatus, fullWidth());

        soloLog = new LinearLayout(this);
        soloLog.setOrientation(LinearLayout.VERTICAL);
        soloLog.setPadding(dp(8), dp(2), dp(8), dp(4));
        soloScroll = new ScrollView(this);
        soloScroll.setVerticalScrollBarEnabled(false);
        soloScroll.addView(soloLog, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(soloScroll, weightV());

        LinearLayout actions = row();
        actions.setPadding(dp(6), dp(2), dp(6), dp(6));
        Button clear = smallButton("新对话", C_CARD);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                destructiveConfirm("开新对话？", "上面的内容会清掉", "新对话", new Runnable() {
                    @Override
                    public void run() {
                        if (soloBusy) return;
                        soloMessages = new JSONArray();
                        saveSoloMessages();
                        renderSolo();
                    }
                });
            }
        });
        actions.addView(clear, weightH(32));
        Button say = smallButton("说一句", C_BLUE);
        say.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSoloSay();
            }
        });
        actions.addView(say, weightH(32));
        page.addView(actions, fullWidth());

        readingDetail = false;
        showScreen(page);
        renderSolo();
    }

    void renderSolo() {
        if (soloLog == null) return;
        soloLog.removeAllViews();
        if (soloStatus != null) {
            soloStatus.setText(soloModel() + " · " + soloHostLabel(soloBase()));
        }
        int count = soloMessages == null ? 0 : soloMessages.length();
        if (count == 0) {
            TextView empty = text("问点什么吧。这条链路不经过 DSH，手表直接调模型。", 9, C_MUTED, false);
            empty.setPadding(dp(4), dp(8), dp(4), 0);
            soloLog.addView(empty, fullWidth());
            return;
        }
        for (int i = 0; i < count; i += 1) {
            JSONObject item = soloMessages.optJSONObject(i);
            if (item == null) continue;
            boolean mine = "user".equals(item.optString("role"));
            LinearLayout bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(9), dp(7), dp(9), dp(7));
            bubble.setBackground(bg(mine ? C_CARD_HI : C_CARD, 12));
            TextView who = text(mine ? "我" : "模型", 8, mine ? C_BLUE : C_ACCENT, true);
            bubble.addView(who, fullWidth());
            TextView body = text(item.optString("content", ""), 10, C_TEXT, false);
            body.setTextIsSelectable(true);
            bubble.addView(body, fullWidth());
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(1), 0, dp(3));
            wrap.addView(bubble, fullWidth());
            soloLog.addView(wrap, fullWidth());
        }
        final ScrollView scroll = soloScroll;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    String soloHostLabel(String url) {
        if (url == null) return "";
        String clean = url.replace("https://", "").replace("http://", "");
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    void showSoloSay() {
        if (soloBusy) {
            toast("上一条还没回完");
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout island = new LinearLayout(this);
        island.setOrientation(LinearLayout.HORIZONTAL);
        island.setGravity(Gravity.CENTER_VERTICAL);
        island.setPadding(dp(8), dp(5), dp(6), dp(5));
        island.setBackground(bg(C_CARD_HI, 18));

        final EditText input = edit("");
        input.setHint("说点什么…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        input.setSingleLine(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        input.setBackground(null);
        island.addView(input, weight(1));

        Button send = roundKey("↑", C_ACCENT);
        send.setTextColor(C_ON_ACCENT);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String typed = input.getText().toString().trim();
                if (typed.isEmpty()) return;
                dialog.dismiss();
                sendSolo(typed);
            }
        });
        island.addView(send, lp(dp(30), dp(30), 5, 0, 0, 0));

        dialog.setContentView(island);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.96f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM;
            params.y = dp(8);
            window.setAttributes(params);
        }
        dialog.show();
    }

    void sendSolo(final String typed) {
        try {
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", typed);
            soloMessages.put(user);
            JSONObject empty = new JSONObject();
            empty.put("role", "assistant");
            empty.put("content", "");
            soloMessages.put(empty);
            saveSoloMessages();
            renderSolo();
            soloBusy = true;
            soloThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    callSoloApi();
                }
            });
            soloThread.start();
        } catch (Exception err) {
            toast("发送失败 " + err.getMessage());
            soloBusy = false;
        }
    }

    /** One blocking call to /chat/completions; the reply replaces the empty bubble. */
    void callSoloApi() {
        String error = null;
        String answer = "";
        HttpURLConnection conn = null;
        try {
            JSONArray sent = new JSONArray();
            for (int i = 0; i < soloMessages.length(); i += 1) {
                JSONObject item = soloMessages.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role");
                String content = item.optString("content");
                if ("assistant".equals(role) && content.isEmpty()) continue;
                JSONObject out = new JSONObject();
                out.put("role", role);
                out.put("content", content);
                sent.put(out);
            }
            JSONObject body = new JSONObject();
            body.put("model", soloModel());
            body.put("messages", sent);
            body.put("stream", false);

            String base = soloBase();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            URL url = new URL(base + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + soloKey);
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            out.write(payload);
            out.close();

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = soloReadAll(stream);
            if (code >= 400) {
                error = "HTTP " + code + " " + shorten(raw, 120);
            } else {
                JSONObject root = new JSONObject(raw);
                JSONArray choices = root.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject first = choices.optJSONObject(0);
                    JSONObject message = first == null ? null : first.optJSONObject("message");
                    answer = message == null ? "" : message.optString("content", "");
                }
                if (answer.isEmpty()) error = "没有内容返回";
            }
        } catch (Throwable err) {
            error = String.valueOf(err.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        final String reply = answer;
        final String failed = error;
        ui.post(new Runnable() {
            @Override
            public void run() {
                soloBusy = false;
                int last = soloMessages.length() - 1;
                JSONObject tail = last >= 0 ? soloMessages.optJSONObject(last) : null;
                try {
                    if (failed != null) {
                        if (tail != null) tail.put("content", "出错了：" + failed);
                    } else if (tail != null) {
                        tail.put("content", reply);
                    }
                    saveSoloMessages();
                } catch (Exception ignored) {
                }
                renderSolo();
            }
        });
    }

    String soloReadAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = stream.read(chunk)) > 0) buffer.write(chunk, 0, n);
        stream.close();
        return new String(buffer.toByteArray(), "UTF-8");
    }

    String shorten(String value, int max) {
        if (value == null) return "";
        String flat = value.replaceAll("[ \\t\\r\\n]+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    // ============================================================== widgets

    /** 0 = waiting on you, 1 = just finished and unread, then running / live / rest. */
    int listBucket(Session s) {
        if (s.pending > 0) return 0;
        if (finishedUnseen.contains(s.id)) return 1;
        if (s.running) return 2;
        if (s.live) return 3;
        return 4;
    }

    /** The unread dots survive a relaunch, like message badges. */
    void saveFinished() {
        try {
            JSONArray arr = new JSONArray();
            for (String id : finishedUnseen) arr.put(id);
            prefs.edit().putString("finishedUnseen", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * A row that slides aside when it is held, to expose 归档 and 删除.
     * A plain sideways drag is left alone: that gesture turns the page.
     */
    class SwipeRow extends FrameLayout {
        final Session session;
        final View content;
        final LinearLayout actions;
        final int actionWidth;
        final Runnable hold;
        float downX;
        float downY;
        /** A drag is not a tap: it must not open the conversation. */
        boolean moved;
        boolean opened;
        long openedAt;

        SwipeRow(Session session, View content) {
            super(MainActivity.this);
            this.session = session;
            this.content = content;
            actionWidth = dp(56) * 2;

            actions = row();
            actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            TextView archive = actionButton(session.archived ? "取消归档" : "归档", C_CARD_HI, C_TEXT);
            archive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    close();
                    toggleArchive(session);
                }
            });
            actions.addView(archive, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT));
            TextView delete = actionButton("删除", C_ERR_BG, 0xFF131313);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    close();
                    confirmDelete(session);
                }
            });
            actions.addView(delete, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT));

            FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                    actionWidth, ViewGroup.LayoutParams.MATCH_PARENT);
            actionParams.gravity = Gravity.RIGHT;
            addView(actions, actionParams);
            addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            actions.setVisibility(View.INVISIBLE);

            content.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (moved) return;
                    if (opened) {
                        // The lift that opened the row also arrives as a click;
                        // ignore that one, then a real tap closes it.
                        if (System.currentTimeMillis() - openedAt < 500) return;
                        close();
                        return;
                    }
                    openConversation(session);
                }
            });

            // 200ms of stillness opens the actions; anything that moves is a
            // scroll or a page swipe and cancels the timer.
            hold = new Runnable() {
                @Override
                public void run() {
                    // Pressing an already-open row puts it back: that is how a
                    // user expects to dismiss it.
                    if (opened) close();
                    else openRow(SwipeRow.this);
                }
            };
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    moved = false;
                    removeCallbacks(hold);
                    postDelayed(hold, 400);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - downX) > dp(8) || Math.abs(ev.getY() - downY) > dp(8)) {
                        moved = true;
                        removeCallbacks(hold);
                    }
                    break;
                default:
                    removeCallbacks(hold);
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }

        void openRow(SwipeRow row) {
            if (openSwipe != null && openSwipe != row) openSwipe.closeQuiet();
            openSwipe = row;
            row.opened = true;
            row.openedAt = System.currentTimeMillis();
            row.actions.setVisibility(View.VISIBLE);
            row.content.animate().translationX(-row.actionWidth).setDuration(140).start();
            row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }

        void closeQuiet() {
            opened = false;
            content.animate().translationX(0).setDuration(110).start();
            actions.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!opened) actions.setVisibility(View.INVISIBLE);
                }
            }, 130);
        }

        void close() {
            opened = false;
            if (openSwipe == this) openSwipe = null;
            content.animate().translationX(0).setDuration(140).start();
            actions.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!opened) actions.setVisibility(View.INVISIBLE);
                }
            }, 150);
        }
    }

    class SessionAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(10), dp(9), dp(10));
            row.setMinimumHeight(dp(38));
            row.setBackground(rowState());

            Session s = rows.get(position);
            if (ARCHIVE_TOGGLE.equals(s.id)) {
                TextView label = text(s.title + (showArchived ? "  ▾" : "  ▸"), 11, C_MUTED, false);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(4), 0, dp(4));
                row.addView(label, weight(1));
                return row;
            }
            View dot = new View(MainActivity.this);
            int color = s.pending > 0 ? C_RED : s.running ? C_GREEN : C_LINE;
            dot.setBackground(round(color, 4));
            row.addView(dot, lp(dp(7), dp(7), 0, 0, 8, 0));

            TextView title = text(s.title, 11, C_TEXT, s.pending > 0);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, weight(1));

            if (finishedUnseen.contains(s.id)) {
                View dot2 = new View(MainActivity.this);
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(0xFFE5484D);  // unread-style red, not the soft error tone
                dot2.setBackground(shape);
                row.addView(dot2, lp(dp(8), dp(8), 4, 0, 3, 0));
            }

            if (s.pending > 0) {
                TextView badge = text(String.valueOf(s.pending), 10, 0xFF131313, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(round(C_ERR_BG, 12));
                badge.setPadding(dp(5), 0, dp(5), 0);
                row.addView(badge, lp(-2, dp(16), 4, 0, 0, 0));
            }
            // The card slides aside to expose 归档 / 删除.
            return new SwipeRow(s, row);
        }
    }

    View bubble(String role, String text) {
        boolean user = "u".equals(role);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(5), dp(8), dp(5));
        box.setBackground(bg(user ? C_CARD_HI : C_CARD, 16));
        TextView who = text(user ? "我" : "DSH", 9, user ? C_ACCENT : C_BLUE, true);
        box.addView(who);
        TextView body = text(text, 11, C_TEXT, false);
        box.addView(body);
        LinearLayout.LayoutParams params = lp(-1, -2, 0, 3, 0, 0);
        box.setLayoutParams(params);
        return box;
    }

    LinearLayout card(int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable bg = bg(C_CARD_HI, 20);
        bg.setStroke(dp(1), C_LINE);
        box.setBackground(bg);
        box.setLayoutParams(lp(-1, -2, 0, 4, 0, 4));
        return box;
    }

    TextView note(String text) {
        TextView view = text(text, 9, C_MUTED, false);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(0, 1.1f);
        return view;
    }

    EditText edit(String value) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setTextColor(C_TEXT);
        edit.setHintTextColor(C_MUTED);
        edit.setBackground(bg(C_CARD_HI, 20));
        edit.setPadding(dp(8), 0, dp(8), 0);
        return edit;
    }

    Button smallButton(String label, int color) {
        boolean filledPrimary = color == C_ACCENT || color == C_GREEN;
        boolean danger = color == C_RED;
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setTextColor(filledPrimary ? C_ON_ACCENT : danger ? 0xFFFFDAD6 : C_TEXT);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        int bgColor = filledPrimary ? C_ACCENT : danger ? C_ERR_BG : C_CARD_HI;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bgColor);
        shape.setCornerRadius(dp(24));
        button.setBackground(shape);
        button.setStateListAnimator(null);
        button.setElevation(0);
        return button;
    }

    GradientDrawable keyBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    /** Enter one conversation; used by both the list row and its inner card. */
    void openConversation(Session session) {
        if (openSwipe != null) {
            openSwipe.close();
            return;
        }
        selectedId = session.id;
        finishedUnseen.remove(selectedId);
        saveFinished();
        cancelDoneNote();
        lastPending = -1;
        state = null;
        showDetail();
        refreshNow(true);
    }

    TextView actionButton(String label, int background, int foreground) {
        TextView view = text(label, 10, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(bg(background, 18));
        return view;
    }

    /** Archive (or bring back) one conversation. */
    void toggleArchive(final Session session) {
        JSONObject body = new JSONObject();
        try {
            body.put("session", session.id);
        } catch (Exception ignored) {
        }
        String path = session.archived ? "/api/unarchive" : "/api/archive";
        api("POST", path, body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                toast(session.archived ? "已取消归档" : "已归档");
                refreshNow(true);
            }

            @Override
            public void onErr(String message, int status) {
                toast(status == 501 ? "服务器插件要升级到 0.3.6" : "归档失败: " + message);
            }
        });
    }

    /** Conversation card: a visible frame, not just a fill. */
    GradientDrawable card(int color, float radius) {
        GradientDrawable drawable = bg(color, radius);
        drawable.setStroke(dp(1.5f), C_EDGE);
        return drawable;
    }

    GradientDrawable bg(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    GradientDrawable round(int color, float radius) {
        return bg(color, radius);
    }

    /** Card that lightens while a finger is on it; no platform highlight. */
    StateListDrawable rowState() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, bg(C_CARD_HI, 24));
        states.addState(new int[]{android.R.attr.state_activated}, bg(C_CARD_HI, 24));
        states.addState(new int[]{}, bg(C_CARD, 24));
        return states;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** Horizontal cell: zero width + weight, natural height. */
    LinearLayout.LayoutParams weight(float value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.weight = value;
        return params;
    }

    /** Vertical cell: full width + weight, zero height. */
    LinearLayout.LayoutParams weightV() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        params.weight = 1;
        return params;
    }

    /** A block that fills the parent width. */
    LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Weighted cell with an explicit height: safer than MATCH_PARENT inside a wrap_content row. */
    LinearLayout.LayoutParams weightH(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp));
        params.weight = 1;
        return params;
    }

    LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    float density() {
        return getResources().getDisplayMetrics().density;
    }

    int dp(float value) {
        return Math.round(value * density());
    }

    void frameAdd(View view) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(view, params);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** One row of the session list. */
    static class Session {
        String id;
        String title;
        boolean running;
        boolean live;
        boolean archived;
        int pending;
    }

    static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    /** True when only the boilerplate is there: the host part is empty. */
    static boolean hostMissing(String url) {
        String out = url.trim();
        int at = out.indexOf("://");
        String rest = at >= 0 ? out.substring(at + 3) : out;
        if (rest.startsWith(":")) rest = rest.substring(1);
        int colon = rest.indexOf(":");
        String host = colon >= 0 ? rest.substring(0, colon) : rest;
        return host.isEmpty() || host.equals("x.x.x.x");
    }

    static String normalize(String url) {
        String out = url.trim();
        if (!out.startsWith("http://") && !out.startsWith("https://")) out = "http://" + out;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        // Typing just "192.168.1.5" should be enough: fill in the default port.
        String rest = out.substring(out.indexOf("://") + 3);
        if (out.startsWith("http://") && !rest.contains(":") && !rest.contains("/")) {
            out = out + ":" + DEFAULT_PORT;
        }
        return out;
    }
}
