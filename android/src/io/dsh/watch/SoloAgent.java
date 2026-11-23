package io.dsh.watch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
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
        return "file_write".equals(name) || "file_read".equals(name);
    }

    static String describe(String name, JSONObject args) {
        if (args == null) args = new JSONObject();
        if ("calc".equals(name)) return args.optString("expr");
        if ("note_add".equals(name)) return args.optString("text");
        if ("file_read".equals(name) || "file_write".equals(name)) {
            return args.optString("path");
        }
        if ("now".equals(name)) return "现在几点";
        if ("note_list".equals(name)) return "看看记事本";
        return name;
    }

    static String run(Context context, String name, JSONObject args) {
        try {
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
}
