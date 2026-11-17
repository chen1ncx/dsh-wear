package io.dsh.watch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    static final int C_BG = 0xFF151A21;
    static final int C_CARD = 0xFF161C24;
    static final int C_TEXT = 0xFFE6E9EF;
    static final int C_MUTED = 0xFF9AA4B2;
    static final int C_ACCENT = 0xFFFFD166;
    static final int C_GREEN = 0xFF57CC99;
    static final int C_RED = 0xFFE5484D;
    static final int C_BLUE = 0xFF7CC4FF;
    static final int C_LINE = 0xFF26303C;

    static final String PREFS = "dshwatch";
    static final String TAG = "DSHWatch";
    static final int POLL_MS = 4000;

    // ------------------------------------------------------------- state
    SharedPreferences prefs;
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
    boolean firstPaint = true;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newSingleThreadExecutor();

    FrameLayout root;
    LinearLayout screenPair;
    LinearLayout screenList;
    LinearLayout screenDetail;

    ListView listView;
    SessionAdapter adapter;
    final List<Session> rows = new ArrayList<>();

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
            baseUrl = prefs.getString("url", null);
            token = prefs.getString("token", null);

            root = new FrameLayout(this);
            root.setBackgroundColor(C_BG);
            setContentView(root);

            applyLaunchConfig(getIntent());
            if (TextUtils.isEmpty(baseUrl)) baseUrl = readConfigUrl();
            Log.i(TAG, "onCreate token=" + (token != null) + " url=" + baseUrl);
            if (TextUtils.isEmpty(token)) showPair(null); else showList();
        } catch (Throwable err) {
            showFallback(err);
        }
    }

    /** Never leave the watch on a black screen: show the failure instead. */
    void showFallback(Throwable err) {
        try {
            StringWriter buffer = new StringWriter();
            err.printStackTrace(new PrintWriter(buffer));
            String text = "DSH wear 启动失败\n\n" + buffer.toString();
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
                        if (item != null) pending += item.optInt("pending", 0);
                    }
                    if (pending > lastPending) vibrate();
                    lastPending = pending;
                } catch (Exception ignored) {
                }
                if (root.getChildCount() == 0) {
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
                    token = null;
                    prefs.edit().remove("token").apply();
                    showPair("配对失效了，重新配一次");
                    return;
                }
                if (token != null) {
                    if (selectedId == null) showList(); else showDetail();
                }
            }
        });
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
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("name", "oppo-watch2");
        } catch (Exception ignored) {
        }
        api("POST", "/api/pair", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                pairing = false;
                token = response.optString("token", null);
                prefs.edit().putString("url", baseUrl).putString("token", token).apply();
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
                    pairStatus.setText(message);
                }
            }
        });
    }

    // ============================================================== screens

    void clearRoot() {
        root.removeAllViews();
    }

    boolean showing(View view) {
        return root.getChildCount() == 1 && root.getChildAt(0) == view;
    }

    /** Show one screen without rebuilding it when it is already the visible one. */
    void showScreen(View view) {
        if (root.getChildCount() == 1 && root.getChildAt(0) == view) return;
        Log.i(TAG, "screen " + view.getClass().getSimpleName() + " children=" + root.getChildCount());
        root.removeAllViews();
        frameAdd(view);
    }

    LinearLayout page() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        return box;
    }

    void showPair(String message) {
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
        showScreen(screenPair);
        pairContent.removeAllViews();

        LinearLayout head = row();
        head.setPadding(dp(10), dp(8), dp(10), dp(2));
        head.addView(text("配对 DSH", 13, C_ACCENT, true), weight(1));
        pairContent.addView(head, fullWidth());

        pairUrl = edit(TextUtils.isEmpty(typedUrl) ? (baseUrl == null ? "http://" : baseUrl) : typedUrl);
        pairUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pairUrl.setSingleLine(true);
        pairContent.addView(pairUrl, lp(-1, dp(30), 8, 2, 8, 0));

        pairCode = edit("");
        pairCode.setHint("6 位配对码");
        pairCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pairCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pairCode.setGravity(Gravity.CENTER);
        pairContent.addView(pairCode, lp(-1, dp(36), 8, 4, 8, 0));

        pairStatus = text(message == null ? "在 DSH 的 /watch 页面看配对码" : message, 10,
                message == null ? C_MUTED : C_RED, false);
        pairStatus.setPadding(dp(10), 0, dp(10), dp(2));
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
                keyRow.addView(button, lp(0, dp(30), 1, 0, 1, 0));
                ((LinearLayout.LayoutParams) button.getLayoutParams()).weight = 1;
            }
            pairContent.addView(keyRow, fullWidth());
        }

        TextView hint = text("也可以用 adb 直接配对:\n"
                + "am start -n io.dsh.watch/.MainActivity \\\n"
                + "  --es url http://IP:8787 --es code 123456", 8, C_MUTED, false);
        hint.setPadding(dp(8), dp(4), dp(8), dp(4));
        pairContent.addView(hint);
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
            if (TextUtils.isEmpty(url) || code.length() < 4) {
                pairStatus.setText("地址和配对码都要填");
                return;
            }
            pairStatus.setText("连接中…");
            pairWith(url, code, false);
            return;
        }
        if (current.length() < 6) pairCode.setText(current + key);
    }

    void showList() {
        if (token == null) {
            showPair(null);
            return;
        }
        selectedId = null;
        if (screenList == null) {
            clearRoot();
            screenList = page();
            frameAdd(screenList);

            LinearLayout head = row();
            head.setPadding(dp(10), dp(6), dp(8), dp(2));
            TextView title = text("DSH wear", 12, C_ACCENT, true);
            head.addView(title, weight(1));
            listStatus = text("", 9, C_MUTED, false);
            head.addView(listStatus);
            TextView gear = text("⚙", 13, C_MUTED, false);
            gear.setPadding(dp(8), 0, dp(2), 0);
            gear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPair("重新配对会换设备");
                }
            });
            head.addView(gear);
            screenList.addView(head, fullWidth());

            listView = new ListView(this);
            listView.setDivider(null);
            listView.setDividerHeight(dp(3));
            listView.setPadding(dp(6), dp(2), dp(6), 0);
            listView.setClipToPadding(false);
            adapter = new SessionAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position < rows.size()) {
                        selectedId = rows.get(position).id;
                        lastPending = -1;
                        state = null;
                        showDetail();
                        refreshNow(true);
                    }
                }
            });
            screenList.addView(listView, weightV());
        }
        showScreen(screenList);
        bindSessions();
    }

    void bindSessions() {
        rows.clear();
        for (int i = 0; i < sessions.length(); i += 1) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) continue;
            Session s = new Session();
            s.id = item.optString("id", "");
            s.title = item.optString("title", s.id);
            s.running = item.optBoolean("running", false);
            s.live = item.optBoolean("live", false);
            s.pending = item.optInt("pending", 0);
            rows.add(s);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (listStatus != null) {
            if (rows.isEmpty()) {
                listStatus.setText(lastError.isEmpty() ? "没有会话" : "连不上");
            } else {
                int waiting = 0;
                for (Session s : rows) waiting += s.pending;
                listStatus.setText(waiting > 0 ? waiting + " 条待确认" : (lastError.isEmpty() ? "在线" : "离线"));
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
            clearRoot();
            screenDetail = page();
            frameAdd(screenDetail);

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
            policyChip.setBackground(bg(C_CARD, 6));
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
            detailActions.setPadding(dp(6), dp(2), dp(6), dp(6));
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
        String policy = session.isNull("policy") ? null : session.optString("policy", null);
        if (policyChip != null) {
            if (policy == null) {
                policyChip.setVisibility(View.GONE);
            } else {
                policyChip.setVisibility(View.VISIBLE);
                boolean ask = "ask".equals(policy);
                policyChip.setText(ask ? "审批 开" : "审批 关");
                policyChip.setTextColor(ask ? C_GREEN : C_RED);
            }
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
        if (state == null) return;
        JSONObject session = state.optJSONObject("session");
        if (session == null) return;
        String policy = session.optString("policy", "ask");
        final String target = "ask".equals(policy) ? "never" : "ask";
        JSONObject body = new JSONObject();
        try {
            body.put("session", selectedId);
            body.put("policy", target);
        } catch (Exception ignored) {
        }
        api("POST", "/api/policy", body, new Callback() {
            @Override
            public void onOk(JSONObject response) {
                toast("审批已" + ("ask".equals(target) ? "打开" : "关闭"));
                refreshNow();
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
        new AlertDialog.Builder(this)
                .setMessage("停止当前任务？")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("session", selectedId);
                        } catch (Exception ignored) {
                        }
                        api("POST", "/api/stop", body, simpleCallback("已停止"));
                        toast("已停止");
                    }
                })
                .show();
    }

    void showSayDialog() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(2));
        final AlertDialog[] holder = new AlertDialog[1];
        String[] quick = {"继续", "先停一下", "汇报一下进展", "重试一次"};
        for (final String quickText : quick) {
            Button b = smallButton(quickText, C_CARD);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    say(quickText);
                    if (holder[0] != null) holder[0].dismiss();
                }
            });
            box.addView(b, lp(-1, dp(32), 0, 1, 0, 1));
        }
        final EditText input = edit("");
        input.setHint("自己写一句");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(input, lp(-1, dp(32), 0, 2, 0, 2));
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("发给 DSH")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String text = input.getText().toString().trim();
                        if (!text.isEmpty()) say(text);
                    }
                })
                .create();
        holder[0].show();
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
        if (screenExit == null) {
            screenExit = page();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(16), dp(28), dp(16), dp(12));

            TextView title = text("DSH wear", 16, C_ACCENT, true);
            title.setGravity(Gravity.CENTER);
            box.addView(title, fullWidth());
            TextView sub = text("要退出吗？", 10, C_MUTED, false);
            sub.setGravity(Gravity.CENTER);
            box.addView(sub, fullWidth());

            Button stay = smallButton("继续使用", C_BLUE);
            stay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showList();
                }
            });
            box.addView(stay, lp(-1, dp(38), 0, 18, 0, 0));

            Button quit = smallButton("退出应用", C_RED);
            quit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.removeCallbacks(ticker);
                    finish();
                }
            });
            box.addView(quit, lp(-1, dp(38), 0, 8, 0, 0));

            screenExit.addView(box, weightV());
        }
        showScreen(screenExit);
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    /** One path for the back key and the right-swipe gesture. */
    void goBack() {
        // Back / right-swipe never closes the app by accident: it walks back to
        // the list, and from the list it opens the exit screen.
        if (root.getChildCount() == 1) {
            View current = root.getChildAt(0);
            if (current == screenExit) {
                showList();
                return;
            }
            if (current == screenDetail) {
                selectedId = null;
                showList();
                refreshNow(true);
                return;
            }
            if (current == screenPair) {
                if (token != null) showList(); else showExit();
                return;
            }
        }
        showExit();
    }

    // -------------------------------------------------- 右滑返回（自己做）

    private float downX;
    private float downY;
    private long downAt;
    private boolean swipeFired;

    /**
     * ColorOS Watch does not turn a right swipe into a BACK key, so the gesture
     * is recognised here instead: horizontal, rightward, short, and long enough
     * to be intentional. Vertical scrolling is untouched.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                downAt = SystemClock.uptimeMillis();
                swipeFired = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                // Fire as soon as the gesture is unambiguous: the system's own
                // gesture detector may steal the rest of the stream on CANCEL.
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!swipeFired && dx > dp(60) && Math.abs(dy) < dp(55)
                        && SystemClock.uptimeMillis() - downAt < 600) {
                    swipeFired = true;
                    goBack();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!swipeFired && dx > dp(60) && Math.abs(dy) < dp(55)
                        && SystemClock.uptimeMillis() - downAt < 600) {
                    goBack();
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    // ============================================================== widgets

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
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setBackground(bg(C_CARD, 10));

            Session s = rows.get(position);
            View dot = new View(MainActivity.this);
            int color = s.pending > 0 ? C_RED : s.running ? C_GREEN : C_LINE;
            dot.setBackground(round(color, 4));
            row.addView(dot, lp(dp(7), dp(7), 0, 0, 8, 0));

            TextView title = text(s.title, 11, C_TEXT, s.pending > 0);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, weight(1));

            if (s.pending > 0) {
                TextView badge = text(String.valueOf(s.pending), 10, 0xFF131313, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(round(C_RED, 8));
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
        box.setBackground(bg(user ? 0xFF1D2733 : C_CARD, 9));
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
        GradientDrawable bg = bg(0xFF1A1418, 10);
        bg.setStroke(dp(1), accent);
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
        edit.setBackground(bg(C_CARD, 8));
        edit.setPadding(dp(8), 0, dp(8), 0);
        return edit;
    }

    Button smallButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        button.setTextColor(label.equals("允许") ? 0xFF131313 : C_TEXT);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(keyBg(color));
        return button;
    }

    GradientDrawable keyBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
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
        int pending;
    }

    static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    static String normalize(String url) {
        String out = url.trim();
        if (!out.startsWith("http://") && !out.startsWith("https://")) out = "http://" + out;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
