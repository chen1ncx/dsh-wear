package io.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;

/**
 * Tiny LAN page so the phone can fill in the standalone config: the watch shows
 * a QR code with its own address, the phone opens this page, types Base URL /
 * API key / model, and the watch picks the values up immediately.
 */
final class SoloConfig {

    interface Listener {
        void onSaved(String base, String key, String model);
    }

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
        if (socket == null) {
            try {
                socket = new ServerSocket(PORT);
                thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(context);
                    }
                });
                thread.setDaemon(true);
                thread.start();
            } catch (Throwable err) {
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
                client.setSoTimeout(8000);
                handle(context, client);
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (client != null) client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void handle(Context context, Socket client) throws Exception {
        InputStream in = client.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int previous = -1;
        int value;
        while ((value = in.read()) >= 0) {
            head.write(value);
            if (previous == '\n' && value == '\n') break;
            if (previous == '\r' && value == '\r') break;
            previous = value;
            if (head.size() > 16384) break;
        }
        String request = new String(head.toByteArray(), "UTF-8");
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
        String body = "";
        if (length > 0) {
            byte[] raw = new byte[Math.min(length, 8192)];
            int read = 0;
            while (read < raw.length) {
                int n = in.read(raw, read, raw.length - read);
                if (n <= 0) break;
                read += n;
            }
            body = new String(raw, 0, read, "UTF-8");
        }

        if ("POST".equals(method) && path.startsWith("/save")) {
            SharedPreferences prefs = context.getSharedPreferences("dshwatch", Context.MODE_PRIVATE);
            String base = "";
            String key = "";
            String model = "";
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq);
                String val = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                if ("base".equals(name)) base = val;
                else if ("key".equals(name)) key = val;
                else if ("model".equals(name)) model = val;
            }
            if (!base.isEmpty() && !key.isEmpty()) {
                prefs.edit()
                        .putString("soloBase", base)
                        .putString("soloKey", key)
                        .putString("soloModel", model.isEmpty() ? "deepseek-chat" : model)
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
        String base = prefs.getString("soloBase", "https://api.deepseek.com");
        String model = prefs.getString("soloModel", "deepseek-chat");
        boolean hasKey = prefs.getString("soloKey", "").length() > 0;
        String form = "<form method=post action=/save>"
                + "<label>Base URL</label>"
                + "<input name=base value=\"" + escape(base) + "\" placeholder=\"https://api.deepseek.com\">"
                + "<label>API Key</label>"
                + "<input name=key value=\"\" placeholder=\"" + (hasKey ? "已保存，重新填会覆盖" : "sk-…") + "\">"
                + "<label>模型</label>"
                + "<input name=model value=\"" + escape(model) + "\" placeholder=\"deepseek-chat\">"
                + "<button>保存到手表</button></form>"
                + "<p class=hint>填完点保存，手表会自动填入并开始用。</p>";
        respond(client, 200, page("配置 DSH on wear", form));
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
                + "input{width:100%;box-sizing:border-box;padding:11px;border-radius:10px;border:1px solid #2a3444;background:#161c25;color:#e8edf5;font-size:15px}"
                + "button{margin-top:18px;width:100%;padding:13px;border:0;border-radius:11px;background:#7ac4ff;color:#0b1220;font-size:16px;font-weight:600}"
                + ".ok{color:#7fe0a8}.bad{color:#ff8f8f}.hint{color:#7b8798;font-size:13px}"
                + "</style><h3>" + title + "</h3>" + body;
    }

    private static void respond(Socket client, int code, String html) throws Exception {
        byte[] bytes = html.getBytes("UTF-8");
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
