package com.quest.horizonconfig;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of config overrides the user has configured, and how they are stored.
 *
 * <p>The source of truth is a single JSON string in the module's remote preferences (key
 * {@link #KEY}). Each entry maps a MobileConfig parameter name ("namespace:param") to a typed
 * value string tagged with the MobileConfig type code Horizon itself uses: 1 boolean, 2 long,
 * 3 string, 4 double. Keeping the code with the value means Horizon's override maps can be
 * populated without guessing a parameter's type.
 */
final class Overrides {
    static final String KEY = "overrides";
    static final String ENABLED_KEY = "overrides_enabled";
    /** When on, Horizon holds every Meta-side value change at the last approved value. */
    static final String APPROVAL_KEY = "approval_mode";
    /** name -> Meta value the user accepted; Horizon moves its baseline to it. */
    static final String APPROVED_KEY = "approved";

    static final int TYPE_BOOLEAN = 1;
    static final int TYPE_LONG = 2;
    static final int TYPE_STRING = 3;
    static final int TYPE_DOUBLE = 4;

    static final class Entry {
        final int type;
        final String value;

        Entry(int type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    /** Parses the stored override set. Order is preserved so the settings list stays stable. */
    static Map<String, Entry> read(SharedPreferences preferences) {
        Map<String, Entry> result = new LinkedHashMap<>();
        String stored = preferences.getString(KEY, null);
        if (stored == null || stored.isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(stored);
            var names = root.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONObject entry = root.getJSONObject(name);
                result.put(name, new Entry(entry.getInt("t"), entry.getString("v")));
            }
        } catch (Exception ignored) {
            // A corrupt blob is treated as no overrides rather than crashing a system process.
        }
        return result;
    }

    static void write(SharedPreferences preferences, Map<String, Entry> entries) {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                JSONObject value = new JSONObject();
                value.put("t", entry.getValue().type);
                value.put("v", entry.getValue().value);
                root.put(entry.getKey(), value);
            }
        } catch (Exception ignored) {
        }
        preferences.edit().putString(KEY, root.toString()).apply();
    }

    /** True unless the safety breaker (or the user) has switched overrides off. */
    static boolean enabled(SharedPreferences preferences) {
        return preferences.getBoolean(ENABLED_KEY, true);
    }

    static boolean approvalMode(SharedPreferences preferences) {
        return preferences.getBoolean(APPROVAL_KEY, false);
    }

    static Map<String, String> readApproved(SharedPreferences preferences) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(preferences.getString(APPROVED_KEY, "{}"));
            var names = root.keys();
            while (names.hasNext()) {
                String name = names.next();
                result.put(name, root.getString(name));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static void writeApproved(SharedPreferences preferences, Map<String, String> approved) {
        preferences.edit().putString(APPROVED_KEY, new JSONObject(approved).toString()).apply();
    }

    /**
     * Human-readable difference between two values. List-shaped values (JSON arrays or
     * comma-separated) report the items added and removed; anything else reads "old → new".
     */
    static String describeChange(String before, String after) {
        List<String> oldItems = items(before);
        List<String> newItems = items(after);
        if (oldItems == null || newItems == null) return clip(before) + " → " + clip(after);
        List<String> parts = new ArrayList<>();
        for (String item : newItems) {
            if (!oldItems.contains(item)) parts.add("+" + clip(item));
        }
        for (String item : oldItems) {
            if (!newItems.contains(item)) parts.add("−" + clip(item));
        }
        return parts.isEmpty() ? "reordered" : String.join(", ", parts);
    }

    private static List<String> items(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                JSONArray array = new JSONArray(trimmed);
                List<String> items = new ArrayList<>(array.length());
                for (int i = 0; i < array.length(); i++) items.add(String.valueOf(array.get(i)));
                return items;
            } catch (Exception notJson) {
                return null;
            }
        }
        if (!trimmed.contains(",")) return null;
        List<String> items = new ArrayList<>();
        for (String item : trimmed.split(",")) items.add(item.trim());
        return items;
    }

    private static String clip(String value) {
        if (value == null) return "(none)";
        return value.length() <= 40 ? value : value.substring(0, 37) + "…";
    }

    private Overrides() {
    }
}
