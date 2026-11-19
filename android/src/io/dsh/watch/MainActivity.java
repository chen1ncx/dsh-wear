package io.dsh.watch;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.SharedPreferences;
import android.content.res.Configuration;
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
        Log.i(TAG, "  bg=" + Integer.toHexString(C_BG) + " accent=" + Integer.toHexString(C_ACCENT));
    }

    static final String PREFS = "dshwatch";
    static final int DEFAULT_PORT = 8787;
    static final String PREFILL_URL = "http://x.x.x.x:8787";
    static final String CHANNEL_DONE = "done";
    static final int NOTE_ID = 1001;
    /** Sessions that finished a turn and were not opened since. */
    final Set<String> finishedUnseen = new HashSet<>();
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
    boolean screenExitReady;
    LinearLayout screenExit;
    boolean pairing;
    int pairSeq;
    boolean firstPaint = true;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newSingleThreadExecutor();

    FrameLayout root;
    LinearLayout screenPair;
    LinearLayout screenList;
    LinearLayout screenDetail;
    Pager pager;
    LinearLayout pagesBox;
    FrameLayout pageExit;
    FrameLayout conversation;
    FrameLayout pagePair;
    FrameLayout pageTheme;
    LinearLayout indicator;
    int pageWidth;
    int pageIndex = PAGE_TALK;

    static final int PAGE_EXIT = 0;
    static final int PAGE_TALK = 1;
    static final int PAGE_PAIR = 2;
    static final int PAGE_THEME = 3;

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
                pager.post(new Runnable() {
                    @Override
                    public void run() {
                        pager.go(PAGE_TALK);
                    }
                });
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
                if (pager != null) pager.go(PAGE_TALK);
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

    /** Put the conversation content (list or detail) into the middle page. */
    void showScreen(View view) {
        if (showing(view)) return;
        conversation.removeAllViews();
        conversation.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    FrameLayout pagerPage() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(C_BG);
        frame.setPadding(0, 0, 0, dp(2));
        frame.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    void buildPager() {
        pageWidth = getResources().getDisplayMetrics().widthPixels;
        pagesBox = new LinearLayout(this);
        pagesBox.setOrientation(LinearLayout.HORIZONTAL);
        pageExit = pagerPage();
        conversation = pagerPage();
        pagePair = pagerPage();
        pageTheme = pagerPage();
        pagesBox.addView(pageExit);
        pagesBox.addView(conversation);
        pagesBox.addView(pagePair);
        pagesBox.addView(pageTheme);

        pager = new Pager(this);
        pager.addView(pagesBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildThemePage();
        buildIndicator();
    }

    /** The little pill at the bottom: one dot per page, the current one stretched. */
    void buildIndicator() {
        if (indicator != null) root.removeView(indicator);
        indicator = new LinearLayout(this);
        indicator.setOrientation(LinearLayout.HORIZONTAL);
        indicator.setGravity(Gravity.CENTER);
        indicator.setPadding(dp(5), dp(2), dp(5), dp(2));
        indicator.setClickable(false);
        indicator.setFocusable(false);

        // Transparent glass: the capsule is only a hint, the page shows through.
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x1AFFFFFF);
        pill.setCornerRadius(dp(10));
        pill.setStroke(dp(1), 0x2EFFFFFF);
        indicator.setBackground(pill);

        for (int i = 0; i < 4; i += 1) {
            View dot = new View(this);
            dot.setClickable(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(4), dp(4));
            params.setMargins(dp(2), 0, dp(2), 0);
            dot.setLayoutParams(params);
            indicator.addView(dot);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(4);
        root.addView(indicator, params);
        updateIndicator(pageIndex);
    }

    void updateIndicator(int page) {
        if (indicator == null) return;
        for (int i = 0; i < indicator.getChildCount(); i += 1) {
            View dot = indicator.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) dot.getLayoutParams();
            boolean active = i == page;
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(3));
            shape.setColor(active ? C_ACCENT : 0x80FFFFFF);
            dot.setBackground(shape);
            params.width = dp(active ? 14 : 5);
            params.height = dp(5);
            dot.setLayoutParams(params);
        }
    }

    /** Horizontal pager with page snapping; vertical scrolling stays with the children. */
    class Pager extends HorizontalScrollView {
        private float downX;
        private float downY;
        private VelocityTracker tracker;

        Pager(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
        }

        /**
         * A vertical list scroll must never turn into a page swipe. The stock
         * HorizontalScrollView intercepts on any horizontal slop, so the axis is
         * decided here first: mostly-vertical gestures stay with the child.
         */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                super.onInterceptTouchEvent(ev);
                return false;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                if (dx > dp(5) && dx > dy * 1.5f) {
                    return super.onInterceptTouchEvent(ev);
                }
                return false;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (tracker == null) tracker = VelocityTracker.obtain();
            tracker.addMovement(ev);
            boolean handled = super.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracker.computeCurrentVelocity(1000);
                float vx = tracker.getXVelocity();
                tracker.recycle();
                tracker = null;
                settle(vx);
            }
            return handled;
        }

        /**
         * Decide the page from the gesture, not just from the resting position:
         * a flick turns the page even when the content moved only a little, and a
         * 30% drag is enough without velocity.
         */
        void settle(float vx) {
            int width = getWidth();
            if (width <= 0) return;
            float pos = getScrollX() / (float) width;
            int base = (int) Math.floor(pos);
            int page = pos - base > 0.3f ? base + 1 : base;
            if (vx < -dp(180)) page = (int) Math.ceil(pos);
            else if (vx > dp(180)) page = (int) Math.floor(pos);
            page = Math.max(0, Math.min(3, page));
            smoothScrollTo(page * width, 0);
            pageIndex = page;
            updateIndicator(page);
            onPageChanged(page);
        }

        @Override
        protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
            super.onScrollChanged(left, top, oldLeft, oldTop);
            int width = getWidth();
            if (width > 0) updateIndicator(Math.max(0, Math.min(3, Math.round(left / (float) width))));
        }

        void snap() {
            Log.i(TAG, "snap scrollX=" + getScrollX());
            int width = getWidth();
            if (width <= 0) return;
            int page = Math.round(getScrollX() / (float) width);
            page = Math.max(0, Math.min(3, page));
            smoothScrollTo(page * width, 0);
            pageIndex = page;
            updateIndicator(page);
            onPageChanged(page);
        }

        void go(int page) {
            pageIndex = page;
            updateIndicator(page);
            onPageChanged(page);
            int width = getWidth();
            if (width <= 0) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        smoothScrollTo(page * getWidth(), 0);
                    }
                });
                return;
            }
            smoothScrollTo(page * width, 0);
        }
    }

    LinearLayout page() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        return box;
    }

    void showPair(String message) {
        buildPairPage(message);
        if (pager != null) pager.go(PAGE_PAIR);
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
        if (pagePair.getChildCount() == 0) {
            pagePair.addView(screenPair, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pairContent.removeAllViews();

        LinearLayout head = row();
        head.setPadding(dp(10), dp(8), dp(10), dp(2));
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
    String pendingDelete;
    boolean needsAuth;

    void showList() {
        if (token == null) {
            showPair(null);
            return;
        }
        selectedId = null;
        if (screenList == null) {
            Log.i(TAG, "buildList bg=" + Integer.toHexString(C_BG));
            screenList = page();

            LinearLayout head = row();
            head.setPadding(dp(10), dp(6), dp(8), dp(2));
            TextView title = text("DSH on wear", 10, C_ACCENT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, 0, dp(4), 0);
            head.addView(title, weight(1));
            listStatus = text("", 8, C_MUTED, false);
            head.addView(listStatus);
            Button plus = outlinePill("＋");
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            plus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    createSession();
                }
            });
            head.addView(plus, lp(dp(26), dp(20), 6, 0, 0, 0));
            Button devices = outlinePill("设备");
            devices.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDevicesDialog();
                }
            });
            head.addView(devices, lp(dp(36), dp(20), 6, 0, 2, 0));
            screenList.addView(head, fullWidth());

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
                    if (position < rows.size()) {
                        if (ARCHIVE_TOGGLE.equals(rows.get(position).id)) {
                            showArchived = !showArchived;
                            bindSessions();
                            return;
                        }
                        selectedId = rows.get(position).id;
                        finishedUnseen.remove(selectedId);
                        saveFinished();
                        cancelDoneNote();
                        lastPending = -1;
                        state = null;
                        showDetail();
                        refreshNow(true);
                    }
                }
            });
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= rows.size()) return false;
                    Session s = rows.get(position);
                    if (ARCHIVE_TOGGLE.equals(s.id)) return false;
                    confirmDelete(s);
                    return true;
                }
            });
            screenList.addView(listView, weightV());
            listEmpty = text("", 11, C_MUTED, false);
            listEmpty.setGravity(Gravity.CENTER);
            listEmpty.setPadding(dp(16), 0, dp(16), dp(24));
            listEmpty.setVisibility(View.GONE);
            screenList.addView(listEmpty, weightV());
        }
        showScreen(screenList);
        bindSessions();
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
                listEmpty.setText(lastError.isEmpty()
                        ? "这台还没有对话\n点 ＋ 新建一个"
                        : "连不上这台\n" + host + "\n在「设备」里换一台\n或在配对页里重新配对");
            }
        }
        if (listStatus != null) {
            if (rows.isEmpty()) {
                listStatus.setText(lastError.isEmpty() ? "没有会话" : "连不上");
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

        JSONArray messages = session.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i += 1) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                detailBox.addView(bubble(m.optString("role", "a"), m.optString("text", "")));
            }
        }
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

    void showSayDialog() {
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

        TextView title = text("发给 DSH", 13, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, fullWidth());

        String[] quick = {"继续", "先停一下", "汇报进展"};
        for (final String q : quick) {
            Button b = outlinePill(q);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    say(q);
                    dialog.dismiss();
                }
            });
            content.addView(b, lp(-1, dp(28), 0, 5, 0, 0));
        }

        final EditText input = edit("");
        input.setHint("自己写一句");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setSingleLine(true);
        input.setCursorVisible(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        GradientDrawable field = new GradientDrawable();
        field.setColor(C_CARD);
        field.setCornerRadius(dp(18));
        field.setStroke(dp(1), C_LINE);
        input.setBackground(field);
        content.addView(input, lp(-1, dp(32), 0, 8, 0, 0));

        LinearLayout actions = row();
        Button cancel = smallButton("取消", C_RED);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actions.addView(cancel, weightH(30));

        Button send = smallButton("发送", C_ACCENT);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) say(text);
                dialog.dismiss();
            }
        });
        actions.addView(send, weightH(30));
        content.addView(actions, lp(-1, -2, 0, 8, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
        dialog.show();
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

    // ============================================================== 退出

    /** The screen the system back gesture lands on instead of killing the app. */
    void showExit() {
        buildExitPage();
        if (pager != null) pager.go(PAGE_EXIT);
    }

    void buildExitPage() {
        if (screenExit == null) {
            screenExit = page();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(16), dp(22), dp(16), dp(10));

            TextView title = text("DSH on wear", 16, C_ACCENT, true);
            title.setGravity(Gravity.CENTER);
            box.addView(title, fullWidth());
            TextView tagline = text("在手表上查看您的电脑端的 DSH", 10, C_TEXT, false);
            tagline.setGravity(Gravity.CENTER);
            tagline.setPadding(0, dp(3), 0, 0);
            box.addView(tagline, fullWidth());
            TextView compat = text("兼容 DSH Go", 9, C_MUTED, false);
            compat.setGravity(Gravity.CENTER);
            compat.setPadding(0, dp(1), 0, 0);
            box.addView(compat, fullWidth());
            TextView sub = text("要退出吗？", 10, C_MUTED, false);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(6), 0, 0);
            box.addView(sub, fullWidth());

            Button stay = smallButton("继续使用", C_BLUE);
            stay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showList();
                    if (pager != null) pager.go(PAGE_TALK);
                }
            });
            box.addView(stay, lp(-1, dp(34), 0, 14, 0, 0));

            Button quit = smallButton("退出应用", C_RED);
            quit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    quitApp();
                }
            });
            box.addView(quit, lp(-1, dp(34), 0, 6, 0, 0));

            screenExit.addView(box, weightV());
        }
        if (pageExit.getChildCount() == 0) {
            pageExit.addView(screenExit, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Side pages are built the first time they are reached. */
    void onPageChanged(int page) {
        Log.i(TAG, "page -> " + page);
        if (page == PAGE_PAIR && pagePair.getChildCount() == 0) buildPairPage(null);
        else if (page == PAGE_EXIT && pageExit.getChildCount() == 0) buildExitPage();
    }

    void buildThemePage() {
        pageTheme.removeAllViews();
        LinearLayout box = page();
        box.setPadding(dp(16), dp(16), dp(16), dp(10));

        box.addView(text("主题色", 18, C_TEXT, true), fullWidth());
        TextView sub = text("点了立刻生效", 10, C_MUTED, false);
        sub.setPadding(0, 0, 0, dp(6));
        box.addView(sub, fullWidth());

        int current = prefs.getInt("theme", 0);
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
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
                params.setMargins(dp(4), dp(8), dp(4), dp(8));
                swatch.setLayoutParams(params);
                swatch.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectTheme(index);
                    }
                });
                row.addView(swatch);
            }
            box.addView(row, fullWidth());
        }
        TextView hint = text("右滑退出 · 左滑配对 / 主题", 9, C_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageTheme.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void selectTheme(int index) {
        Log.i(TAG, "selectTheme " + index);
        prefs.edit().putInt("theme", index).apply();
        applyTheme(index);
        root.setBackgroundColor(C_BG);
        pageExit.setBackgroundColor(C_BG);
        conversation.setBackgroundColor(C_BG);
        pagePair.setBackgroundColor(C_BG);
        pageTheme.setBackgroundColor(C_BG);

        // Everything is rebuilt from the palette, so drop the cached views.
        screenList = null;
        screenDetail = null;
        screenPair = null;
        screenExit = null;
        listStatus = null;
        detailTitle = null;
        policyChip = null;
        pairUrl = null;
        pairCode = null;
        pairStatus = null;
        pageExit.removeAllViews();
        pagePair.removeAllViews();
        conversation.removeAllViews();
        buildThemePage();
        buildIndicator();
        pager.go(PAGE_THEME);
        if (token == null) buildPairPage(null); else showList();
        toast("换好了");
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    /** Leave without the platform's activity zoom/fade. */
    void quitApp() {
        ui.removeCallbacks(ticker);
        finish();
        overridePendingTransition(0, 0);
    }

    /** Back: leave any side page, then leave the detail, then open the exit page. */
    void goBack() {
        Log.i(TAG, "goBack page=" + pageIndex);
        if (pageIndex != PAGE_TALK) {
            pager.go(PAGE_TALK);
            if (token == null) showPair(null); else showList();
            return;
        }
        if (showing(screenDetail)) {
            selectedId = null;
            showList();
            refreshNow(true);
            return;
        }
        pager.go(PAGE_EXIT);
        showExit();
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
            row.setPadding(dp(8), dp(5), dp(8), dp(5));
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
            return row;
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
