package io.dsh.watch;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local storage for standalone mode: several API profiles and several chats,
 * all in shared preferences so the watch keeps them without any network.
 */
final class SoloStore {

    static final String PREFS = "dshwatch";

    private SoloStore() {
    }

    // ------------------------------------------------------------- profiles

    static JSONArray profiles(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("soloProfiles", "[]"));
        } catch (Exception bad) {
            return new JSONArray();
        }
    }

    static void saveProfiles(SharedPreferences prefs, JSONArray list) {
        prefs.edit().putString("soloProfiles", list.toString()).apply();
    }

    /** The profile in use: by id, falling back to the first one. */
    static JSONObject activeProfile(SharedPreferences prefs) {
        JSONArray list = profiles(prefs);
        String wanted = prefs.getString("soloActive", "");
        JSONObject first = null;
        for (int i = 0; i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            if (first == null) first = item;
            if (item.optString("id").equals(wanted)) return item;
        }
        return first;
    }

    static JSONObject addProfile(SharedPreferences prefs, String name, String base,
                                 String key, String model) {
        JSONArray list = profiles(prefs);
        JSONObject item = new JSONObject();
        try {
            item.put("id", "p" + System.currentTimeMillis());
            item.put("name", name);
            item.put("base", base);
            item.put("key", key);
            item.put("model", model);
            list.put(item);
            saveProfiles(prefs, list);
            prefs.edit().putString("soloActive", item.optString("id")).apply();
        } catch (Exception ignored) {
        }
        return item;
    }

    static void updateProfile(SharedPreferences prefs, String id, String name, String base,
                              String key, String model) {
        JSONArray list = profiles(prefs);
        for (int i = 0; i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || !id.equals(item.optString("id"))) continue;
            try {
                item.put("name", name);
                item.put("base", base);
                item.put("key", key);
                item.put("model", model);
            } catch (Exception ignored) {
            }
        }
        saveProfiles(prefs, list);
    }

    static void removeProfile(SharedPreferences prefs, String id) {
        JSONArray list = profiles(prefs);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || id.equals(item.optString("id"))) continue;
            kept.put(item);
        }
        saveProfiles(prefs, kept);
    }

    // -------------------------------------------------------------- sessions

    static JSONArray sessions(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("soloSessions", "[]"));
        } catch (Exception bad) {
            return new JSONArray();
        }
    }

    static void saveSessions(SharedPreferences prefs, JSONArray list) {
        prefs.edit().putString("soloSessions", list.toString()).apply();
    }

    static JSONObject session(SharedPreferences prefs, String id) {
        JSONArray list = sessions(prefs);
        for (int i = 0; i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && id != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    static JSONObject newSession(SharedPreferences prefs, String title) {
        JSONArray list = sessions(prefs);
        JSONObject item = new JSONObject();
        try {
            item.put("id", "s" + System.currentTimeMillis());
            item.put("title", title == null || title.isEmpty() ? "新对话" : title);
            item.put("messages", new JSONArray());
            item.put("at", System.currentTimeMillis());
            list.put(0, item);
            saveSessions(prefs, list);
            prefs.edit().putString("soloOpen", item.optString("id")).apply();
        } catch (Exception ignored) {
        }
        return item;
    }

    static void touchSession(SharedPreferences prefs, JSONObject session) {
        JSONArray list = sessions(prefs);
        JSONArray order = new JSONArray();
        try {
            session.put("at", System.currentTimeMillis());
            order.put(session);
            for (int i = 0; i < list.length(); i += 1) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || session.optString("id").equals(item.optString("id"))) continue;
                order.put(item);
            }
        } catch (Exception ignored) {
        }
        saveSessions(prefs, order);
    }

    static void removeSession(SharedPreferences prefs, String id) {
        JSONArray list = sessions(prefs);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < list.length(); i += 1) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || id.equals(item.optString("id"))) continue;
            kept.put(item);
        }
        saveSessions(prefs, kept);
    }

    static String titleOf(JSONObject session) {
        String title = session.optString("title", "");
        if (!title.isEmpty() && !"新对话".equals(title)) return title;
        JSONArray messages = session.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i += 1) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null || !"user".equals(item.optString("role"))) continue;
            String text = item.optString("content", "").replace('\n', ' ').trim();
            if (!text.isEmpty()) return text.length() > 18 ? text.substring(0, 18) + "…" : text;
        }
        return "新对话";
    }
}
