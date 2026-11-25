package io.dsh.watch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The small set of tools the watch can run by itself. Every call is plain Java:
 * no shell, no network, nothing that can leave the watch.
 */
final class SoloAgent {

    /** Where artifacts and files live; both are inside the app or the DSH folder. */
    static final String ARTIFACTS = "artifacts";

    private SoloAgent() {
    }

    /** The JSON array sent as "tools" in a /chat/completions request. */
    static JSONArray tools() {
        JSONArray list = new JSONArray();
        try {
            list.put(tool("ui_screen", "读当前屏幕上的文字和可点位置（需要无障碍开启）",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("ui_tap", "按文字点屏幕上的控件，或按坐标点（需要无障碍）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("text", new JSONObject().put("type", "string")
                                            .put("description", "控件上的文字，优先用这个"))
                                    .put("x", new JSONObject().put("type", "integer"))
                                    .put("y", new JSONObject().put("type", "integer")))));
            list.put(tool("ui_key", "按系统键：back / home / recents / notifications（需要无障碍）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("key", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("key"))));
            list.put(tool("open_app", "打开手表上装的应用（按包名或名称）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("app", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("app"))));
            list.put(tool("clip_get", "读剪贴板里的文字",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("clip_set", "把文字写进剪贴板",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("text", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("text"))));
            list.put(tool("notify", "给手表发一条通知",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("title", new JSONObject().put("type", "string"))
                                    .put("text", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("title").put("text"))));
            list.put(tool("file_edit", "把文件里的一段文字替换成另一段",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", new JSONObject().put("type", "string"))
                                    .put("find", new JSONObject().put("type", "string"))
                                    .put("replace", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("path").put("find")
                                    .put("replace"))));
            list.put(tool("move_file", "移动或重命名文件（范围同读写）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("from", new JSONObject().put("type", "string"))
                                    .put("to", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("from").put("to"))));
            list.put(tool("vibrate", "让手表震动一下",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("ms", new JSONObject().put("type", "integer")
                                            .put("description", "毫秒，默认 400")))));
            list.put(tool("web_search", "上网搜一下，返回前几条标题和链接",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("query", new JSONObject().put("type", "string"))
                                    .put("count", new JSONObject().put("type", "integer")
                                            .put("description", "默认 5 条")))
                            .put("required", new JSONArray().put("query"))));
            list.put(tool("screen_timeout", "读或设置息屏时间",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("seconds", new JSONObject().put("type", "integer")
                                            .put("description", "不填就是读；填 15/30/60 等")))
                            .put("required", new JSONArray().put("seconds"))));
            list.put(tool("now", "当前日期和时间",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("calc", "算一个算式，只支持数字和 + - * / ( )",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("expr", new JSONObject().put("type", "string")
                                            .put("description", "如 (3+4)*2")))
                            .put("required", new JSONArray().put("expr"))));
            list.put(tool("note_add", "往手表的记事本追加一行",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("text", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("text"))));
            list.put(tool("note_list", "读出手表记事本的全部内容",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("list_files", "列出手表应用目录或 Download/DSH-Go 里的文件",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("dir", new JSONObject().put("type", "string")
                                            .put("description", "留空看全部")))));
            list.put(tool("delete_file", "删一个文件（只在手表应用目录或 Download/DSH-Go 里）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("path"))));
            list.put(tool("append_file", "往文件末尾追加内容",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", new JSONObject().put("type", "string"))
                                    .put("content", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("path").put("content"))));
            list.put(tool("set_alarm", "在手表上设一个闹钟",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("hour", new JSONObject().put("type", "integer"))
                                    .put("minute", new JSONObject().put("type", "integer"))
                                    .put("label", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("hour").put("minute"))));
            list.put(tool("set_timer", "在手表上开一个倒计时",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("seconds", new JSONObject().put("type", "integer"))
                                    .put("label", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("seconds"))));
            list.put(tool("list_alarms", "看手表上下一个闹钟",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("cancel_alarm", "关掉手表上正在响/下一个闹钟",
                    new JSONObject().put("type", "object").put("properties", new JSONObject())));
            list.put(tool("http_fetch", "抓一个网址的正文（纯文本，最多 4000 字）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("url", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("url"))));
            list.put(tool("file_read", "读一个文件（只能在手表应用目录或 Download/DSH-Go 里）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", new JSONObject().put("type", "string")
                                            .put("description", "相对路径，如 artifacts/a.md")))
                            .put("required", new JSONArray().put("path"))));
            list.put(tool("file_write", "写一个文件（只能在手表应用目录或 Download/DSH-Go 里）",
                    new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", new JSONObject().put("type", "string"))
                                    .put("content", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("path").put("content"))));
        } catch (Exception ignored) {
        }
        return list;
    }

    private static JSONObject tool(String name, String description, JSONObject parameters)
            throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "function");
        item.put("function", new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters));
        return item;
    }

    /** Tools that only read; everything else asks first. */
    static boolean needsApproval(String name) {
        return "set_alarm".equals(name) || "set_timer".equals(name) || "cancel_alarm".equals(name)
                || "file_write".equals(name) || "file_read".equals(name)
                || "delete_file".equals(name) || "append_file".equals(name)
                || "http_fetch".equals(name) || "list_files".equals(name);
    }

    static String describe(String name, JSONObject args) {
        if (args == null) args = new JSONObject();
        if ("calc".equals(name)) return args.optString("expr");
        if ("note_add".equals(name)) return args.optString("text");
        if ("file_read".equals(name) || "file_write".equals(name)
                || "delete_file".equals(name) || "append_file".equals(name)) {
            return args.optString("path");
        }
        if ("list_files".equals(name)) return args.optString("dir");
        if ("set_alarm".equals(name)) {
            return args.optInt("hour", 0) + ":" + args.optInt("minute", 0)
                    + " " + args.optString("label");
        }
        if ("set_timer".equals(name)) return args.optInt("seconds", 0) + " 秒 " + args.optString("label");
        if ("cancel_alarm".equals(name)) return "关掉闹钟";
        if ("list_alarms".equals(name)) return "看下一个闹钟";
        if ("http_fetch".equals(name)) return args.optString("url");
        if ("now".equals(name)) return "现在几点";
        if ("note_list".equals(name)) return "看看记事本";
        return name;
    }

    static String run(Context context, String name, JSONObject args) {
        try {
            if ("clip_get".equals(name)) {
                return clipGet(context);
            }
            if ("clip_set".equals(name)) {
                return clipSet(context, args.optString("text"));
            }
            if ("notify".equals(name)) {
                return pushNote(context, args.optString("title"), args.optString("text"));
            }
            if ("file_edit".equals(name)) {
                File file = resolve(context, args.optString("path"));
                if (file == null) return "路径不允许";
                if (!file.exists()) return "没有这个文件";
                String all = readFileText(file);
                String find = args.optString("find");
                if (!all.contains(find)) return "文件里没有这段文字";
                writeFileText(file, all.replace(find, args.optString("replace")));
                return "改好了";
            }
            if ("move_file".equals(name)) {
                File from = resolve(context, args.optString("from"));
                File to = resolve(context, args.optString("to"));
                if (from == null || to == null) return "路径不允许";
                if (!from.exists()) return "源文件不存在";
                File parent = to.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                return from.renameTo(to) ? "移好了" : "移不动";
            }
            if ("vibrate".equals(name)) {
                return buzz(context, args.optInt("ms", 400));
            }
            if ("web_search".equals(name)) {
                return search(args.optString("query"), args.optInt("count", 5));
            }
            if ("screen_timeout".equals(name)) {
                return timeout(context, args.has("seconds") ? args.optInt("seconds", 0) : -1);
            }
            if ("ui_screen".equals(name)) {
                return WatchAcc.dump();
            }
            if ("ui_tap".equals(name)) {
                String text = args.optString("text", "");
                if (!text.isEmpty()) return WatchAcc.tapText(text);
                int x = args.optInt("x", -1);
                int y = args.optInt("y", -1);
                if (x < 0 || y < 0) return "要给我 text 或者 x、y";
                return WatchAcc.tap(x, y) ? "点了 " + x + "," + y : "点不到（无障碍没开？）";
            }
            if ("ui_key".equals(name)) {
                String key = args.optString("key", "back").toLowerCase(Locale.US);
                int code = "home".equals(key) ? 2 : "recents".equals(key) ? 3
                        : "notifications".equals(key) ? 4 : 1;
                return WatchAcc.key(code) ? "按了 " + key : "按不了（无障碍没开？）";
            }
            if ("open_app".equals(name)) {
                return openApp(context, args.optString("app", ""));
            }
            if ("now".equals(name)) {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm EEEE",
                        Locale.CHINA);
                return format.format(new Date());
            }
            if ("calc".equals(name)) {
                double value = new Parser(args.optString("expr")).parse();
                if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                    return String.valueOf((long) value);
                }
                return String.valueOf(value);
            }
            if ("note_add".equals(name)) {
                File notes = new File(context.getFilesDir(), "notes.txt");
                FileOutputStream out = new FileOutputStream(notes, true);
                out.write((args.optString("text") + "\n").getBytes("UTF-8"));
                out.close();
                return "已记下：" + args.optString("text");
            }
            if ("note_list".equals(name)) {
                File notes = new File(context.getFilesDir(), "notes.txt");
                if (!notes.exists()) return "记事本是空的";
                return readFile(notes, 4000);
            }
            if ("list_files".equals(name)) {
                File dir = resolveDir(context, args.optString("dir"));
                if (dir == null || !dir.isDirectory()) return "没有这个目录";
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) return "空目录";
                StringBuilder out = new StringBuilder();
                for (File f : files) {
                    out.append(f.getName()).append(f.isDirectory() ? "/" : "")
                            .append(" (").append(f.length()).append("B)\n");
                }
                return out.toString();
            }
            if ("delete_file".equals(name)) {
                File file = resolve(context, args.optString("path"));
                if (file == null) return "路径不允许";
                return file.delete() ? "删掉了 " + args.optString("path") : "没有这个文件";
            }
            if ("append_file".equals(name)) {
                File file = resolve(context, args.optString("path"));
                if (file == null) return "路径不允许";
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream out = new FileOutputStream(file, true);
                out.write(args.optString("content").getBytes("UTF-8"));
                out.close();
                return "追加好了，现在 " + file.length() + " 字节";
            }
            if ("set_alarm".equals(name)) {
                int hour = Math.max(0, Math.min(23, args.optInt("hour", 0)));
                int minute = Math.max(0, Math.min(59, args.optInt("minute", 0)));
                android.content.Intent intent = new android.content.Intent(
                        "android.intent.action.SET_ALARM");
                intent.putExtra("android.intent.extra.alarm.HOUR", hour);
                intent.putExtra("android.intent.extra.alarm.MINUTES", minute);
                String label = args.optString("label", "");
                if (!label.isEmpty()) {
                    intent.putExtra("android.intent.extra.alarm.MESSAGE", label);
                }
                intent.putExtra("android.intent.extra.alarm.SKIP_UI", true);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return "已设闹钟 " + String.format(Locale.CHINA, "%02d:%02d", hour, minute)
                        + (label.isEmpty() ? "" : "（" + label + "）");
            }
            if ("set_timer".equals(name)) {
                int seconds = Math.max(1, Math.min(86400, args.optInt("seconds", 60)));
                android.content.Intent intent = new android.content.Intent(
                        "android.intent.action.SET_TIMER");
                intent.putExtra("android.intent.extra.alarm.LENGTH", seconds);
                String label = args.optString("label", "");
                if (!label.isEmpty()) {
                    intent.putExtra("android.intent.extra.alarm.MESSAGE", label);
                }
                intent.putExtra("android.intent.extra.alarm.SKIP_UI", true);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return "开始计时 " + seconds + " 秒";
            }
            if ("list_alarms".equals(name)) {
                String next = android.provider.Settings.System.getString(
                        context.getContentResolver(),
                        android.provider.Settings.System.NEXT_ALARM_FORMATTED);
                return next == null || next.isEmpty() ? "系统里没有待响的闹钟" : "下一个闹钟：" + next;
            }
            if ("cancel_alarm".equals(name)) {
                android.content.Intent intent = new android.content.Intent(
                        "android.intent.action.DISMISS_ALARM");
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(intent);
                    return "已经关掉正在响的闹钟";
                } catch (Throwable err) {
                    return "这台手表不支持直接关：" + err.getMessage();
                }
            }
            if ("http_fetch".equals(name)) {
                String url = args.optString("url");
                if (!url.startsWith("http")) return "只支持 http/https";
                java.net.URLConnection raw = new java.net.URL(url).openConnection();
                raw.setConnectTimeout(12000);
                raw.setReadTimeout(15000);
                raw.setRequestProperty("User-Agent", "dsh-watch");
                java.io.InputStream stream = raw.getInputStream();
                String text = readStream(stream, 4000);
                String body = text.replaceAll("(?is)<script.*?</script>", " ")
                        .replaceAll("(?is)<style.*?</style>", " ")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("[ \t\r\n]+", " ").trim();
                return body.isEmpty() ? "抓到了空内容" : body;
            }
            if ("file_read".equals(name)) {
                File file = resolve(context, args.optString("path"));
                if (file == null) return "路径不允许：" + args.optString("path");
                if (!file.exists()) return "没有这个文件：" + args.optString("path");
                return readFile(file, 8000);
            }
            if ("file_write".equals(name)) {
                File file = resolve(context, args.optString("path"));
                if (file == null) return "路径不允许：" + args.optString("path");
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream out = new FileOutputStream(file);
                out.write(args.optString("content").getBytes("UTF-8"));
                out.close();
                return "已经写到 " + args.optString("path") + "（" + file.length() + " 字节）";
            }
        } catch (Throwable err) {
            return "工具出错：" + err.getMessage();
        }
        return "没有这个工具：" + name;
    }

    /** Only the app's own files and Download/DSH-Go; ".." never escapes. */
    static File resolve(Context context, String path) {
        if (path == null || path.isEmpty()) return null;
        String clean = path.trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.contains("..")) return null;
        if (clean.startsWith("artifacts/") || clean.startsWith("artifacts")) {
            File dir = new File(context.getFilesDir(), ARTIFACTS);
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, clean.length() > 9 ? clean.substring(10) : "");
        }
        File external = new File("/sdcard/Download/DSH-Go");
        if (!external.exists()) external.mkdirs();
        return new File(external, clean);
    }

    /** Directory for list_files: "" means both roots. */
    static File resolveDir(Context context, String dir) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) {
            return context.getFilesDir();
        }
        File file = resolve(context, dir);
        return file != null && file.isDirectory() ? file : null;
    }

    static String readFileText(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) Math.min(file.length(), 200000)];
            int read = input.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8");
        } finally {
            input.close();
        }
    }

    static void writeFileText(File file, String text) throws Exception {
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(text.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    private static String readStream(java.io.InputStream stream, int max) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
            if (buffer.size() > max * 4) break;
        }
        stream.close();
        String text = new String(buffer.toByteArray(), "UTF-8");
        return text.length() > max ? text.substring(0, max) : text;
    }

    static File artifactsDir(Context context) {
        File dir = new File(context.getFilesDir(), ARTIFACTS);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String readFile(File file, int max) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] buffer = new byte[max];
        int n = in.read(buffer);
        in.close();
        if (n <= 0) return "（空文件）";
        return new String(buffer, 0, n, "UTF-8");
    }

    /** Recursive descent for + - * / and parentheses; nothing else is accepted. */
    static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        double parse() {
            double value = sum();
            skip();
            if (at < text.length()) throw new IllegalArgumentException("算不了：" + text);
            return value;
        }

        private double sum() {
            double value = product();
            while (true) {
                skip();
                if (take('+')) value += product();
                else if (take('-')) value -= product();
                else return value;
            }
        }

        private double product() {
            double value = atom();
            while (true) {
                skip();
                if (take('*')) value *= atom();
                else if (take('/')) value /= atom();
                else return value;
            }
        }

        private double atom() {
            skip();
            if (take('(')) {
                double value = sum();
                skip();
                take(')');
                return value;
            }
            if (take('-')) return -atom();
            int start = at;
            while (at < text.length()
                    && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at += 1;
            }
            if (start == at) throw new IllegalArgumentException("算不了：" + text);
            return Double.parseDouble(text.substring(start, at));
        }

        private boolean take(char wanted) {
            if (at < text.length() && text.charAt(at) == wanted) {
                at += 1;
                return true;
            }
            return false;
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at += 1;
        }
    }


    private static String clipGet(Context context) {
        android.content.ClipboardManager clip = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip == null || !clip.hasPrimaryClip()) return "剪贴板是空的";
        android.content.ClipData data = clip.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return "剪贴板是空的";
        CharSequence text = data.getItemAt(0).coerceToText(context);
        return text == null || text.length() == 0 ? "剪贴板是空的" : text.toString();
    }

    private static String clipSet(Context context, String text) {
        android.content.ClipboardManager clip = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip == null) return "剪贴板不可用";
        clip.setPrimaryClip(android.content.ClipData.newPlainText("DSH", text));
        return "放进剪贴板了";
    }

    private static String pushNote(Context context, String title, String text) {
        android.app.NotificationManager manager = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "通知服务不可用";
        String channel = "dsh-wear";
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel group = new android.app.NotificationChannel(
                    channel, "DSH", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(group);
        }
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, channel)
                : new android.app.Notification.Builder(context);
        builder.setSmallIcon(io.dsh.watch.R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        manager.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        return "通知发出去了";
    }

    private static String buzz(Context context, int ms) {
        android.os.Vibrator vibrator = (android.os.Vibrator)
                context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return "这块表没有马达";
        vibrator.vibrate(Math.max(50, Math.min(ms, 3000)));
        return "震了";
    }

    private static String timeout(Context context, int seconds) {
        try {
            if (seconds <= 0) {
                int now = android.provider.Settings.System.getInt(
                        context.getContentResolver(), android.provider.Settings.System
                                .SCREEN_OFF_TIMEOUT, 0);
                return "现在 " + (now / 1000) + " 秒后息屏";
            }
            android.provider.Settings.System.putInt(context.getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000);
            return "改成 " + seconds + " 秒了";
        } catch (Exception no) {
            return "改不了，要在设置里给本应用「修改系统设置」权限";
        }
    }

    /** Bing's RSS output: stable XML, no API key, no JavaScript. */
    static String search(String query, int count) {
        if (query == null || query.trim().isEmpty()) return "要搜什么？";
        int want = Math.max(1, Math.min(count, 8));
        try {
            String url = "https://cn.bing.com/search?format=rss&setlang=zh-CN&q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            java.net.URLConnection raw = new java.net.URL(url).openConnection();
            raw.setConnectTimeout(12000);
            raw.setReadTimeout(15000);
            raw.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.1)");
            String xml = readStream(raw.getInputStream(), 80000);
            java.util.regex.Matcher block = java.util.regex.Pattern
                    .compile("(?is)<item>(.*?)</item>").matcher(xml);
            StringBuilder out = new StringBuilder();
            int used = 0;
            while (block.find() && used < want) {
                String item = block.group(1);
                String title = pick(item, "title");
                String link = pick(item, "link");
                String body = pick(item, "description");
                if (title.isEmpty() || link.isEmpty()) continue;
                out.append(used + 1).append(". ").append(title).append('\n')
                        .append(link).append('\n');
                if (!body.isEmpty()) {
                    out.append(body.length() > 120 ? body.substring(0, 120) + "…" : body)
                            .append('\n');
                }
                used += 1;
            }
            if (used == 0) return "没搜到结果，换个说法再试";
            return out.toString().trim();
        } catch (Exception no) {
            return "搜索失败：" + no;
        }
    }

    /** One RSS field, tags stripped and the usual entities decoded. */
    static String pick(String xml, String tag) {
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(xml);
        if (!match.find()) return "";
        return match.group(1).replaceAll("(?is)<[^>]+>", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    /** Launch by package name, else by a name that contains the query. */
    static String openApp(Context context, String query) {
        if (query == null || query.trim().isEmpty()) return "要给我应用名或包名";
        String want = query.trim().toLowerCase(Locale.US);
        android.content.pm.PackageManager pm = context.getPackageManager();
        try {
            android.content.Intent direct = pm.getLaunchIntentForPackage(want);
            if (direct != null) {
                direct.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(direct);
                return "打开 " + want;
            }
        } catch (Exception ignored) {
        }
        java.util.List<android.content.pm.ApplicationInfo> apps =
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
        for (android.content.pm.ApplicationInfo info : apps) {
            String label = String.valueOf(pm.getApplicationLabel(info)).toLowerCase(Locale.US);
            String pkg = info.packageName.toLowerCase(Locale.US);
            if (!label.contains(want) && !pkg.contains(want)) continue;
            android.content.Intent intent = pm.getLaunchIntentForPackage(info.packageName);
            if (intent == null) continue;
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return "打开 " + pm.getApplicationLabel(info);
            } catch (Exception no) {
                return "打不开 " + info.packageName + "：" + no;
            }
        }
        return "没找到「" + query + "」，可以先 ui_screen 看看桌面上有什么";
    }
}
