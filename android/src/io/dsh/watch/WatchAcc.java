package io.dsh.watch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the on-watch agent look at the screen and operate it: read the visible
 * tree, click by text, tap coordinates, press the system keys.
 */
public final class WatchAcc extends AccessibilityService {

    private static WatchAcc instance;

    static boolean ready() {
        return instance != null;
    }

    static String status() {
        return instance == null ? "未开启" : "已开启";
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** Readable outline of what is on screen right now. */
    static String dump() {
        if (instance == null) return "无障碍未开启";
        AccessibilityNodeInfo root;
        try {
            root = instance.getRootInActiveWindow();
        } catch (Exception no) {
            return "读不到当前窗口：" + no;
        }
        if (root == null) return "当前没有窗口";
        StringBuilder out = new StringBuilder();
        walk(root, 0, out, 120);
        return out.length() == 0 ? "屏幕上没有可读的文字" : out.toString();
    }

    private static void walk(AccessibilityNodeInfo node, int depth, StringBuilder out, int limit) {
        if (node == null || out.length() > limit * 60 || depth > 12) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = !TextUtils.isEmpty(text) ? text.toString()
                : (!TextUtils.isEmpty(desc) ? desc.toString() : "");
        if (label.length() > 0) {
            Rect box = new Rect();
            node.getBoundsInScreen(box);
            String id = node.getViewIdResourceName();
            out.append(spaces(depth)).append('"').append(shorten(label)).append('"');
            if (node.isClickable()) out.append(" [可点]");
            if (node.isEditable()) out.append(" [可输入]");
            if (!TextUtils.isEmpty(id)) out.append(" #").append(id.substring(id.indexOf('/') + 1));
            out.append(" (").append(box.centerX()).append(',').append(box.centerY()).append(')');
            out.append('\n');
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i += 1) {
            walk(node.getChild(i), depth + (label.length() > 0 ? 1 : 0), out, limit);
        }
    }

    private static String spaces(int depth) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i += 1) pad.append("  ");
        return pad.toString();
    }

    static String shorten(String text) {
        String one = text.replace('\n', ' ').trim();
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    /** Click the first node whose text or description contains the words. */
    static String tapText(String text) {
        if (instance == null) return "无障碍未开启";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root == null) return "当前没有窗口";
            List<AccessibilityNodeInfo> hits = new ArrayList<>();
            collect(root, text, hits);
            if (hits.isEmpty()) return "没找到「" + text + "」";
            AccessibilityNodeInfo target = hits.get(0);
            AccessibilityNodeInfo clickable = target;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable == null) clickable = target;
            Rect box = new Rect();
            target.getBoundsInScreen(box);
            boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (ok) return "点了「" + shorten(String.valueOf(box.centerX() + "," + box.centerY())) + "」";
            return tap(box.centerX(), box.centerY()) ? "点了坐标" : "点不到";
        } catch (Exception no) {
            return "点击失败：" + no;
        }
    }

    private static void collect(AccessibilityNodeInfo node, String query, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 8) return;
        String label = "";
        if (!TextUtils.isEmpty(node.getText())) label = node.getText().toString();
        if (label.isEmpty() && !TextUtils.isEmpty(node.getContentDescription())) {
            label = node.getContentDescription().toString();
        }
        if (!label.isEmpty() && label.toLowerCase().contains(query.toLowerCase())) {
            out.add(node);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i += 1) {
            collect(node.getChild(i), query, out);
        }
    }

    /** Coordinate tap, for things the tree does not expose as clickable. */
    static boolean tap(int x, int y) {
        if (instance == null || Build.VERSION.SDK_INT < 24) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return instance.dispatchGesture(stroke, null, null);
    }

    /** 1 back, 2 home, 3 recents, 4 notifications. */
    static boolean key(int which) {
        if (instance == null) return false;
        switch (which) {
            case 1:
                return instance.performGlobalAction(GLOBAL_ACTION_BACK);
            case 2:
                return instance.performGlobalAction(GLOBAL_ACTION_HOME);
            case 3:
                return instance.performGlobalAction(GLOBAL_ACTION_RECENTS);
            case 4:
                return instance.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default:
                return false;
        }
    }
}
