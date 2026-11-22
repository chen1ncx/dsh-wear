package io.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tiny LAN page so the phone can fill in the standalone config: the watch shows
 * a QR code with its own address, the phone opens this page, types Base URL /
 * API key / model, and the watch picks the values up immediately.
 */
final class SoloConfig {

    interface Listener {
        void onSaved(String base, String key, String model);
    }

    private static final String TAG = "DSHWatch";
    private static final int PORT = 8788;
    private static ServerSocket socket;
    private static Thread thread;
    private static Listener listener;

    private SoloConfig() {
    }

    static void setListener(Listener value) {
        listener = value;
    }

    /** Start serving, and return the URL to encode in the QR code. */
    static String start(final Context context) {
        if (socket == null || socket.isClosed()) {
            try {
                socket = new ServerSocket(PORT);
                Log.i(TAG, "solo server bound on " + PORT);
                thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(context);
                    }
                });
                thread.setDaemon(true);
                thread.start();
            } catch (Throwable err) {
                Log.w(TAG, "solo server bind failed", err);
                socket = null;
                return null;
            }
        }
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/";
    }

    static void stop() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        thread = null;
        listener = null;
    }

    /** First non-loopback IPv4 address: the one the phone can reach. */
    static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (host != null && !host.startsWith("127.")) return host;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void serve(Context context) {
        while (socket != null && !socket.isClosed()) {
            Socket client = null;
            try {
                client = socket.accept();
                Log.i(TAG, "solo server accepted");
                final Socket opened = client;
                client.setSoTimeout(8000);
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(context, opened);
                            Log.i(TAG, "solo server handled");
                        } catch (Throwable boom) {
                            Log.w(TAG, "solo handle failed", boom);
                        } finally {
                            try {
                                opened.close();
                            } catch (Exception no) {
                            }
                        }
                    }
                });
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable no) {
                try {
                    if (client != null) client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void handle(Context context, Socket client) throws Exception {
        Log.i(TAG, "solo handle start");
        InputStream in = client.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        // Read until the blank line that ends the headers. Checking CRLF and
        // bare LF matters: byte-by-byte matching missed "\r\n\r\n" and made
        // every page load wait for the socket timeout.
        while (head.size() < 16384) {
            int n = in.read(chunk);
            if (n <= 0) break;
            head.write(chunk, 0, n);
            String sofar = new String(head.toByteArray(), "ISO-8859-1");
            if (sofar.contains("\r\n\r\n") || sofar.endsWith("\n\n")) break;
        }
        byte[] raw = head.toByteArray();
        String all = new String(raw, "ISO-8859-1");
        int split = all.indexOf("\r\n\r\n");
        int splitLen = 4;
        if (split < 0) {
            split = all.indexOf("\n\n");
            splitLen = 2;
        }
        String request = split >= 0 ? all.substring(0, split) : all;
        byte[] leftover = split >= 0
                ? java.util.Arrays.copyOfRange(raw, split + splitLen, raw.length)
                : new byte[0];
        String[] lines = request.split("\r?\n");
        String[] first = lines.length > 0 ? lines[0].split(" ") : new String[0];
        String method = first.length > 0 ? first[0] : "GET";
        String path = first.length > 1 ? first[1] : "/";

        int length = 0;
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).trim());
                } catch (Exception ignored) {
                }
            }
        }
        byte[] bodyBytes = new byte[Math.max(0, Math.min(length, 8192))];
        int filled = Math.min(leftover.length, bodyBytes.length);
        System.arraycopy(leftover, 0, bodyBytes, 0, filled);
        while (filled < bodyBytes.length) {
            int n = in.read(bodyBytes, filled, bodyBytes.length - filled);
            if (n <= 0) break;
            filled += n;
        }
        String body = new String(bodyBytes, 0, filled, "UTF-8");

        Log.i(TAG, "solo " + method + " " + path + " len=" + length);
        if ("POST".equals(method) && path.startsWith("/save")) {
            SharedPreferences prefs = context.getSharedPreferences("dshwatch", Context.MODE_PRIVATE);
            String base = "";
            String key = prefs.getString("soloKey", "");
            String model = "";
            boolean fetch = false;
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq);
                String val = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                if ("base".equals(name)) base = val;
                else if ("key".equals(name) && !val.isEmpty() && !val.contains("****")) key = val;
                else if ("model".equals(name)) model = val;
                else if ("fetch".equals(name)) fetch = true;
            }
            if (fetch && !base.isEmpty() && !key.isEmpty()) {
                String[] result = fetchModels(base, key);
                if (result.length > 0 && result[0] != null) {
                    respond(client, 200, formPage(base, maskKey(key), model, result, null, ""));
                } else {
                    respond(client, 200, formPage(base, maskKey(key), model, null,
                            result.length > 1 ? result[1] : "获取失败", ""));
                }
                return;
            }
            if (!base.isEmpty() && !key.isEmpty() && model.isEmpty()) {
                respond(client, 200, formPage(base, maskKey(key), "",
                        null, "模型要从云端获取后再选，不能留空。", ""));
                return;
            }
            if (!base.isEmpty() && !key.isEmpty()) {
                prefs.edit()
                        .putString("soloBase", base)
                        .putString("soloKey", key)
                        .putString("soloModel", model)
                        .putString("mode", "solo")
                        .apply();
                Listener target = listener;
                if (target != null) target.onSaved(base, key, model);
                respond(client, 200, page("已保存", "<p class=ok>已经写进手表，可以关掉这个页面了。</p>"));
                return;
            }
            respond(client, 200, page("没保存", "<p class=bad>Base URL 和 API Key 都要填。</p>"));
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("dshwatch", Context.MODE_PRIVATE);
        String base = prefs.getString("soloBase", "");
        String model = prefs.getString("soloModel", "");
        boolean hasKey = prefs.getString("soloKey", "").length() > 0;
        String masked = prefs.getString("soloKey", "");
        respond(client, 200, formPage(base, masked.isEmpty() ? "" : maskKey(masked),
                model, null, null, ""));
    }

    /** Current OpenAI-compatible endpoints, newest vendor domains. */
    private static final String[][] PROVIDERS = {
            {"DeepSeek", "https://api.deepseek.com/v1"},
            {"阿里云百炼（通义）", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
            {"火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"},
            {"月之暗面 Kimi", "https://api.moonshot.cn/v1"},
            {"MiniMax", "https://api.minimax.cn/v1"},
            {"硅基流动", "https://api.siliconflow.cn/v1"},
            {"百度千帆", "https://qianfan.baidubce.com/v2"},
            {"腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"},
            {"阶跃星辰", "https://api.stepfun.com/v1"},
            {"OpenAI", "https://api.openai.com/v1"},
            {"OpenRouter", "https://openrouter.ai/api/v1"},
            {"xAI Grok", "https://api.x.ai/v1"},
            {"Gemini（OpenAI 兼容）", "https://generativelanguage.googleapis.com/v1beta/openai"},
    };

    /** The whole config page: base, key, then either a text field or a model list. */
    private static String formPage(String base, String key, String model,
                                   String[] models, String error, String keyHint) {
        StringBuilder form = new StringBuilder();
        form.append("<form method=post action=/save>");
        form.append("<label>供应商</label>")
                .append("<select id=pick onchange=\"document.getElementById('base').value=this.value\">");
        for (String[] provider : PROVIDERS) {
            form.append("<option value=\"").append(escape(provider[1])).append("\">")
                    .append(escape(provider[0])).append("</option>");
        }
        form.append("</select>");
        form.append("<label>Base URL</label>")
                .append("<input id=base name=base value=\"").append(escape(base)).append("\" placeholder=\"https://api.deepseek.com/v1\">");
        form.append("<label>API Key</label>")
                .append("<input name=key value=\"").append(escape(key)).append("\" placeholder=\"")
                .append(escape(keyHint == null || keyHint.isEmpty() ? "sk-…" : keyHint)).append("\">");
        form.append("<p class=hint>当前保存的 Key：")
                .append(escape(key.isEmpty() ? "（还没有）" : key))
                .append("　要更换就直接改上面的输入框。</p>");
        if (models != null && models.length > 0) {
            form.append("<label>模型（已从云端取到 ").append(models.length).append(" 个）</label>");
            form.append("<select name=model>");
            for (String id : models) {
                if (id == null) continue;
                form.append("<option value=\"").append(escape(id)).append("\"")
                        .append(id.equals(model) ? " selected" : "").append(">")
                        .append(escape(id)).append("</option>");
            }
            form.append("</select>");
        } else {
            form.append("<label>模型</label>")
                    .append("<div class=pick>还没选。先点下面的「从云端获取模型」，")
                    .append("列表来自这把 Key 能用的模型。</div>");
            form.append("<input type=hidden name=model value=\"\">");
        }
        form.append("<button name=fetch value=1 class=ghost>从云端获取模型</button>");
        form.append("<button>保存到手表</button>");
        form.append("</form>");
        if (error != null) {
            form.append("<p class=bad>").append(escape(error)).append("</p>");
        }
        form.append("<p class=hint>填完点保存，手表会自动填入并开始用。</p>");
        return page("配置 DSH on wear", form.toString());
    }

    /** Ask the API which models this key can use. [list-of-ids..., error] shape: see caller. */
    private static String[] fetchModels(String base, String key) {
        HttpURLConnection conn = null;
        try {
            String url = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            conn = (HttpURLConnection) new URL(url + "/models").openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            while ((n = stream.read(chunk)) > 0) buffer.write(chunk, 0, n);
            String raw = new String(buffer.toByteArray(), "UTF-8");
            if (code >= 400) return new String[]{null, "HTTP " + code + " " + raw};
            JSONArray data = new JSONObject(raw).optJSONArray("data");
            if (data == null) return new String[]{null, "返回里没有 data"};
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < data.length(); i += 1) {
                JSONObject item = data.optJSONObject(i);
                String id = item == null ? null : item.optString("id", "");
                if (id != null && !id.isEmpty()) ids.add(id);
            }
            if (ids.isEmpty()) return new String[]{null, "这个 Key 没有可用模型"};
            return ids.toArray(new String[0]);
        } catch (Throwable err) {
            return new String[]{null, String.valueOf(err.getMessage())};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** sk-abcdef…wxyz: recognisable, not reusable. */
    private static String maskKey(String value) {
        if (value == null || value.length() <= 8) return "sk-****";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private static String page(String title, String body) {
        return "<!doctype html><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title>"
                + "<style>"
                + "body{font:15px/1.5 -apple-system,system-ui,sans-serif;margin:24px;background:#0e1116;color:#e8edf5}"
                + "h3{margin:0 0 16px;font-size:17px}"
                + "label{display:block;margin:14px 0 6px;color:#8b98ab;font-size:13px}"
                + "select{width:100%;box-sizing:border-box;padding:11px;border-radius:10px;border:1px solid #2a3444;background:#161c25;color:#e8edf5;font-size:15px}"
                + "input{width:100%;box-sizing:border-box;padding:11px;border-radius:10px;border:1px solid #2a3444;background:#161c25;color:#e8edf5;font-size:15px}"
                + "button{margin-top:18px;width:100%;padding:13px;border:0;border-radius:11px;background:#7ac4ff;color:#0b1220;font-size:16px;font-weight:600}"
                + "button.ghost{background:#243044;color:#cfe0f5;margin-top:10px}"
                + ".pick{padding:11px;border-radius:10px;background:#161c25;border:1px dashed #2a3444;color:#8b98ab;font-size:14px}"
                + ".ok{color:#7fe0a8}.bad{color:#ff8f8f}.hint{color:#7b8798;font-size:13px}"
                + "</style><h3>" + title + "</h3>" + body;
    }

    private static void respond(Socket client, int code, String html) throws Exception {
        byte[] bytes = html.getBytes("UTF-8");
        Log.i(TAG, "solo respond " + code + " bytes=" + bytes.length);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }
}
