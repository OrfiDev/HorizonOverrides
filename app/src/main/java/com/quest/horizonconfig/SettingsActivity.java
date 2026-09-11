package com.quest.horizonconfig;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * The one screen: switches at the top, then a single fast list that shows the overridden values,
 * the Meta changes held for approval, the change log, or the whole catalogue browsed by namespace.
 * Typing in the search box searches everything. Tapping a row opens its editor.
 */
public final class SettingsActivity extends AppCompatActivity {
    private static final int ACCENT = 0xffa9c7ff;
    private static final int MUTED = 0xffb6bfce;
    private static final int HELD = 0xffffc27a;
    private static final int BACKGROUND = 0xff14171d;
    private static final int SURFACE = 0xff1c212b;
    private static final int MAX_SEARCH_ROWS = 400;
    private static final Uri STATE_URI =
            Uri.parse("content://" + HorizonConfig.MDC_AUTHORITY + "/" + HorizonConfig.STATE_PATH);
    private static final Uri CHANGES_URI =
            Uri.parse("content://" + HorizonConfig.MDC_AUTHORITY + "/" + HorizonConfig.CHANGES_PATH);

    private enum Tab { OVERRIDDEN, HELD, CHANGES, BROWSE }

    private static WeakReference<SettingsActivity> visible = new WeakReference<>(null);
    private static XposedService service;
    private static boolean listening;

    private SharedPreferences preferences;
    private List<Catalog.Value> catalog = List.of();
    private final Map<String, Catalog.Value> byName = new HashMap<>();
    private Map<String, Overrides.Entry> overrides = Map.of();
    private Map<String, String> metaValues = Map.of();   // live, from Horizon
    private Map<String, String> heldValues = Map.of();   // name -> approved value still served
    private List<String[]> changes = List.of();          // {time, kind, name, description}
    private Map<String, Integer> liveTypes = Map.of();   // every live name -> type, from Horizon
    private boolean live;
    private boolean syncingSwitches; // true while switches show stored state, not a user tap
    private String safetyPause;      // which safety mechanism has paused overrides, if any
    private boolean onlyRead = true; // Browse/Search hide values no code references

    private Tab tab = Tab.OVERRIDDEN;
    private String namespace;
    private String query = "";

    private TextView status;
    private MaterialSwitch applySwitch, approvalSwitch;
    private TabLayout tabs;
    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        visible = new WeakReference<>(this);
        if (!listening) {
            listening = true;
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService bound) {
                    service = bound;
                    refresh();
                }

                @Override
                public void onServiceDied(XposedService dead) {
                    if (service == dead) service = null;
                    refresh();
                }

                private void refresh() {
                    SettingsActivity activity = visible.get();
                    if (activity != null) activity.runOnUiThread(activity::connect);
                }
            });
        }
        buildViews();
        showSafetyWarning();
        openRequestedView(getIntent().getStringExtra(HorizonConfig.EXTRA_VIEW));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tab == Tab.BROWSE && namespace != null && query.isEmpty()) {
                    namespace = null;
                    render();
                } else {
                    finish();
                }
            }
        });
        new Thread(() -> {
            List<Catalog.Value> loaded = Catalog.load();
            runOnUiThread(() -> {
                catalog = loaded;
                for (Catalog.Value param : loaded) byName.put(param.name, param);
                addLiveOnlyValues();
                render();
            });
        }).start();
        connect();
    }

    private void showSafetyWarning() {
        SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
        if (ui.getBoolean("safety_warning_seen", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("Experimental system controls")
                .setMessage("Changing Horizon flags can crash system services, break tracking, or make the headset fail to boot. Keep ADB recovery available and change small groups at a time. Hold Volume Down while Horizon starts to disable overrides for that boot.")
                .setCancelable(false)
                .setPositiveButton("I understand", (dialog, which) ->
                        ui.edit().putBoolean("safety_warning_seen", true).apply())
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openRequestedView(intent.getStringExtra(HorizonConfig.EXTRA_VIEW));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLiveState();
    }

    private void openRequestedView(String view) {
        if ("pending".equals(view)) tabs.selectTab(tabs.getTabAt(Tab.HELD.ordinal()));
        else if ("changes".equals(view)) tabs.selectTab(tabs.getTabAt(Tab.CHANGES.ordinal()));
    }

    // ------------------------------------------------------------------ Data

    private void connect() {
        try {
            preferences = service == null ? null : service.getRemotePreferences("settings");
        } catch (RuntimeException error) {
            preferences = null;
        }
        boolean connected = preferences != null;
        applySwitch.setEnabled(connected);
        approvalSwitch.setEnabled(connected);
        if (connected) {
            overrides = Overrides.read(preferences);
            syncingSwitches = true;
            applySwitch.setChecked(Overrides.enabled(preferences));
            approvalSwitch.setChecked(Overrides.approvalMode(preferences));
            syncingSwitches = false;
        }
        render();
    }

    /** Meta's live values, held changes and the change log, from the module inside Horizon. */
    private void loadLiveState() {
        new Thread(() -> {
            Map<String, String> meta = new HashMap<>(8192);
            Map<String, Integer> types = new HashMap<>(8192);
            Map<String, String> held = new HashMap<>();
            List<String[]> log = new ArrayList<>();
            boolean reached = false;
            String pausedBy = null;
            try (Cursor cursor = getContentResolver().query(STATE_URI, null, null, null, null)) {
                if (cursor != null) {
                    reached = true;
                    Bundle safety = cursor.getExtras();
                    if (safety.getBoolean("boot_guard")) pausedBy = "the boot-loop guard (Horizon kept restarting)";
                    else if (safety.getBoolean("breaker")) pausedBy = "the crash breaker (an app kept restarting)";
                    while (cursor.moveToNext()) {
                        meta.put(cursor.getString(0), cursor.getString(2));
                        types.put(cursor.getString(0), cursor.getInt(1));
                        if (!cursor.isNull(3)) held.put(cursor.getString(0), cursor.getString(3));
                    }
                }
            } catch (RuntimeException unreachable) {
                // Module not active in Horizon, so live values are unavailable.
                android.util.Log.w(HorizonConfig.TAG, "Horizon state not readable", unreachable);
            }
            try (Cursor cursor = getContentResolver().query(CHANGES_URI, null, null, null, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    log.add(new String[]{String.valueOf(cursor.getLong(0)), cursor.getString(1),
                            cursor.getString(2), cursor.getString(3)});
                }
            } catch (RuntimeException ignored) {
            }
            boolean horizonReached = reached && !meta.isEmpty();
            String paused = pausedBy;
            runOnUiThread(() -> {
                live = horizonReached;
                safetyPause = paused;
                metaValues = meta;
                heldValues = held;
                changes = log;
                liveTypes = types;
                addLiveOnlyValues();
                pruneApprovals();
                render();
            });
        }).start();
    }

    /** Populate Browse and Search from the names and types exposed by Horizon. */
    private void addLiveOnlyValues() {
        List<Catalog.Value> merged = null;
        for (Map.Entry<String, Integer> item : new TreeMap<>(liveTypes).entrySet()) {
            if (byName.containsKey(item.getKey())) continue;
            if (merged == null) merged = new ArrayList<>(catalog);
            Catalog.Value param = new Catalog.Value(item.getKey(), item.getValue(), "", false, false, "", "");
            merged.add(param);
            byName.put(param.name, param);
        }
        if (merged != null) catalog = merged;
    }

    /** Drops approvals Horizon has already taken over, so the stored set does not grow forever. */
    private void pruneApprovals() {
        if (preferences == null || !live) return;
        Map<String, String> approved = Overrides.readApproved(preferences);
        boolean removed = approved.entrySet().removeIf(entry ->
                !heldValues.containsKey(entry.getKey())
                        && Objects.equals(metaValues.get(entry.getKey()), entry.getValue()));
        if (removed) Overrides.writeApproved(preferences, approved);
    }

    /** Browse and Search skip values no code references, unless they are overridden or held. */
    private boolean hidden(Catalog.Value param) {
        return onlyRead && !liveTypes.containsKey(param.name) && !param.referenced() && !overrides.containsKey(param.name)
                && !heldValues.containsKey(param.name);
    }

    private static String shortPackages(String packages) {
        return packages.replace("com.oculus.", "").replace("com.meta.", "").replace(" ", ", ");
    }

    private String metaValue(String name) {
        String value = metaValues.get(name);
        if (value != null) return value;
        Catalog.Value param = byName.get(name);
        return param == null ? null : param.baseline;
    }

    // ------------------------------------------------------------------ Views

    private void buildViews() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(24), dp(20), dp(24), dp(8));

        root.addView(text("Horizon Overrides", 26, Color.WHITE));
        TextView warning = text("WARNING: Experimental overrides can break tracking or boot. Hold Volume Down during startup for the failsafe.", 14, 0xffff8a80);
        warning.setPadding(0, dp(6), 0, dp(8));
        root.addView(warning);
        status = text("", 13, MUTED);
        status.setPadding(0, dp(2), 0, dp(12));
        root.addView(status);

        LinearLayout card = column();
        card.setBackground(rounded(SURFACE));
        card.setPadding(dp(16), dp(4), dp(16), dp(8));
        applySwitch = toggle("Apply overrides");
        applySwitch.setOnCheckedChangeListener((view, checked) -> {
            if (preferences != null && !syncingSwitches) {
                preferences.edit().putBoolean(Overrides.ENABLED_KEY, checked).apply();
            }
        });
        card.addView(applySwitch, new LinearLayout.LayoutParams(-1, -2));
        card.addView(caption("Turn off to fall back to Meta's values everywhere without losing your list."));
        approvalSwitch = toggle("Hold Meta changes for approval");
        approvalSwitch.setOnCheckedChangeListener((view, checked) -> {
            if (preferences != null && !syncingSwitches) {
                preferences.edit().putBoolean(Overrides.APPROVAL_KEY, checked).apply();
                toast(checked ? "Meta changes are now held until you approve them"
                        : "Meta changes apply automatically");
            }
        });
        card.addView(approvalSwitch, new LinearLayout.LayoutParams(-1, -2));
        card.addView(caption("When Meta changes a value, keep the current one until you approve it under Held."));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        EditText search = new EditText(this);
        search.setHint("Search all config values");
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        MaterialSwitch onlyReadSwitch = new MaterialSwitch(this);
        onlyReadSwitch.setText("Only values code reads");
        onlyReadSwitch.setTextColor(MUTED);
        onlyReadSwitch.setChecked(onlyRead);
        onlyReadSwitch.setOnCheckedChangeListener((view, checked) -> {
            onlyRead = checked;
            render();
        });
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        searchRow.addView(onlyReadSwitch);
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));

        tabs = new TabLayout(this);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        tabs.setBackgroundColor(BACKGROUND);
        tabs.setSelectedTabIndicatorColor(ACCENT);
        tabs.setTabTextColors(MUTED, Color.WHITE);
        for (Tab value : Tab.values()) tabs.addTab(tabs.newTab().setTag(value));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab selected) {
                tab = (Tab) selected.getTag();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab unselected) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab reselected) {
                if (tab == Tab.BROWSE && namespace != null) {
                    namespace = null;
                    render();
                }
            }
        });
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(4));
        list.setPadding(0, dp(8), 0, 0);
        list.setClipToPadding(false);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.action != null) row.action.run();
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        if (status == null) return;
        int heldCount = heldValues.size();
        tabs.getTabAt(Tab.OVERRIDDEN.ordinal()).setText("Overridden (" + overrides.size() + ")");
        tabs.getTabAt(Tab.HELD.ordinal()).setText(heldCount > 0 ? "Held (" + heldCount + ")" : "Held");
        tabs.getTabAt(Tab.CHANGES.ordinal()).setText("Changes");
        tabs.getTabAt(Tab.BROWSE.ordinal()).setText("Browse");
        if (preferences == null) {
            status.setText("Module not connected. Enable Horizon Config for com.oculus.horizon and reopen.");
        } else {
            status.setText(safetyPause != null
                    ? "Overrides paused by " + safetyPause + ". Turn Apply overrides off and on to resume."
                    : catalog.size() + " config values · " + (live ? "live from Horizon"
                    : "Horizon not reachable; live values unavailable"));
            status.setTextColor(safetyPause != null ? HELD : MUTED);
        }

        rows.clear();
        if (query.length() >= 2) {
            renderSearch();
        } else {
            switch (tab) {
                case OVERRIDDEN -> renderOverridden();
                case HELD -> renderHeld();
                case CHANGES -> renderChanges();
                case BROWSE -> renderBrowse();
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void renderSearch() {
        int shown = 0;
        for (Catalog.Value param : catalog) {
            if (!param.name.toLowerCase(Locale.ROOT).contains(query) || hidden(param)) continue;
            if (shown++ >= MAX_SEARCH_ROWS) {
                rows.add(Row.note("More matches; keep typing to narrow them down."));
                return;
            }
            rows.add(valueRow(param.name, true));
        }
        if (shown == 0) rows.add(Row.note("Nothing matches “" + query + "”."));
    }

    private void renderOverridden() {
        if (overrides.isEmpty()) {
            rows.add(Row.note("No overrides yet. Search or Browse, then tap a value to override it."));
            return;
        }
        Row clearAll = Row.note("Clear all " + overrides.size() + " overrides");
        clearAll.titleColor = HELD;
        clearAll.action = () -> new AlertDialog.Builder(this)
                .setTitle("Clear all overrides?")
                .setMessage("Every value goes back to Meta's. Apps pick that up after their next restart; "
                        + "reboot to apply it everywhere.")
                .setPositiveButton("Clear all", (d, w) -> {
                    Overrides.write(preferences, new HashMap<>());
                    overrides = Map.of();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
        rows.add(clearAll);
        for (String name : new TreeMap<>(overrides).keySet()) rows.add(valueRow(name, true));
    }

    private void renderHeld() {
        if (!approvalSwitch.isChecked() && heldValues.isEmpty()) {
            rows.add(Row.note("Turn on “Hold Meta changes for approval” and every value Meta changes "
                    + "keeps its current setting until you approve it here."));
            return;
        }
        if (heldValues.isEmpty()) {
            rows.add(Row.note("Nothing waiting. New Meta changes will appear here."));
            return;
        }
        Row approveAll = Row.note("Approve all " + heldValues.size() + " changes");
        approveAll.titleColor = ACCENT;
        approveAll.action = this::approveAll;
        rows.add(approveAll);
        for (String name : new TreeMap<>(heldValues).keySet()) rows.add(valueRow(name, true));
    }

    private void renderChanges() {
        if (changes.isEmpty()) {
            rows.add(Row.note(live ? "No Meta changes recorded yet." : "Horizon not reachable."));
            return;
        }
        for (String[] change : changes) {
            Row row = new Row();
            row.title = change[2];
            row.detail = DateUtils.getRelativeTimeSpanString(Long.parseLong(change[0])) + " · " + change[3];
            row.badge = "held".equals(change[1]) ? "HELD" : "";
            row.badgeColor = HELD;
            row.action = () -> openEditor(change[2]);
            rows.add(row);
        }
    }

    private void renderBrowse() {
        if (namespace == null) {
            Map<String, int[]> counts = new TreeMap<>(); // namespace -> {values, overridden}
            for (Catalog.Value param : catalog) {
                if (hidden(param)) continue;
                int[] count = counts.computeIfAbsent(param.namespace(), key -> new int[2]);
                count[0]++;
                if (overrides.containsKey(param.name)) count[1]++;
            }
            for (Map.Entry<String, int[]> entry : counts.entrySet()) {
                Row row = new Row();
                row.title = entry.getKey();
                row.detail = entry.getValue()[0] + " values";
                row.badge = (entry.getValue()[1] > 0 ? entry.getValue()[1] + " set  " : "") + "›";
                row.badgeColor = ACCENT;
                row.action = () -> {
                    namespace = entry.getKey();
                    render();
                };
                rows.add(row);
            }
            return;
        }
        Row up = Row.note("‹ All namespaces  ·  " + namespace);
        up.titleColor = ACCENT;
        up.action = () -> {
            namespace = null;
            render();
        };
        rows.add(up);
        for (Catalog.Value param : catalog) {
            if (param.namespace().equals(namespace) && !hidden(param)) rows.add(valueRow(param.name, false));
        }
    }

    private Row valueRow(String name, boolean showNamespace) {
        Catalog.Value param = byName.get(name);
        Overrides.Entry override = overrides.get(name);
        String held = heldValues.get(name);
        Row row = new Row();
        int colon = name.indexOf(':');
        row.title = showNamespace || colon < 0 ? name : name.substring(colon + 1);
        StringBuilder detail = new StringBuilder(param == null ? "" : Catalog.typeName(param.type));
        if (held != null) {
            detail.append(" · Meta wants ").append(Overrides.describeChange(held, metaValue(name)));
        } else {
            detail.append(" · Meta ").append(Objects.toString(metaValue(name), "?"));
        }
        if (param != null && param.experiment) detail.append(" · A/B");
        if (param != null) detail.append(param.referenced() ? " · read by " + shortPackages(param.readBy) : " · no code reference");
        row.detail = detail.toString();
        if (override != null) {
            row.badge = param != null && param.type == Overrides.TYPE_BOOLEAN
                    ? ("true".equals(override.value) ? "ON" : "OFF") : override.value;
            row.badgeColor = ACCENT;
        } else if (held != null) {
            row.badge = "HELD";
            row.badgeColor = HELD;
        }
        row.action = () -> openEditor(name);
        return row;
    }

    // ------------------------------------------------------------------ Editing

    private void openEditor(String name) {
        if (preferences == null) {
            toast("Module not connected");
            return;
        }
        Catalog.Value param = byName.get(name);
        int type = param != null ? param.type : Overrides.TYPE_STRING;
        Overrides.Entry override = overrides.get(name);
        String meta = metaValue(name);
        String held = heldValues.get(name);

        StringBuilder info = new StringBuilder(Catalog.typeName(type))
                .append("\nMeta value: ").append(Objects.toString(meta, "unknown"));
        if (param != null) info.append("\nCatalogue value: ").append(param.baseline);
        if (held != null) info.append("\nHeld at: ").append(held);
        if (override != null) info.append("\nYour override: ").append(override.value);
        if (param != null) {
            info.append("\nRead by: ").append(param.referenced() ? param.readBy.replace(" ", ", ")
                    : "no code reference found (native code may still read it)");
            if (!param.declaredBy.isEmpty()) info.append("\nDeclared by: ").append(param.declaredBy.replace(" ", ", "));
        }
        if (param != null && param.inDeviceConfig) info.append("\nMirrored into Android DeviceConfig");
        if (param != null && param.experiment) info.append("\nMeta A/B experiment");

        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(name);
        if (held != null) {
            dialog.setMessage(info + "\n\nMeta changed this: " + Overrides.describeChange(held, meta))
                    .setPositiveButton("Approve", (d, w) -> approve(name))
                    .setNegativeButton("Keep " + held, (d, w) -> setOverride(name, type, held))
                    .setNeutralButton("Later", null)
                    .show();
            return;
        }
        if (type == Overrides.TYPE_BOOLEAN) {
            String[] choices = {"Default (Meta: " + meta + ")", "On", "Off"};
            int checked = override == null ? 0 : ("true".equals(override.value) ? 1 : 2);
            TextView message = text(info.toString(), 14, MUTED);
            message.setPadding(dp(24), dp(8), dp(24), 0);
            dialog.setSingleChoiceItems(choices, checked, (d, which) -> {
                        if (which == 0) removeOverride(name);
                        else setOverride(name, type, which == 1 ? "true" : "false");
                        d.dismiss();
                    })
                    .setView(message)
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), 0);
        body.addView(text(info.toString(), 14, MUTED));
        EditText field = new EditText(this);
        field.setSingleLine(type != Overrides.TYPE_STRING);
        field.setText(override != null ? override.value : Objects.toString(meta, ""));
        body.addView(field);
        dialog.setView(body)
                .setPositiveButton("Set", (d, w) -> setOverride(name, type, field.getText().toString()))
                .setNeutralButton("Default", (d, w) -> removeOverride(name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setOverride(String name, int type, String value) {
        if (type == Overrides.TYPE_LONG || type == Overrides.TYPE_DOUBLE) {
            try {
                if (type == Overrides.TYPE_LONG) Long.parseLong(value.trim());
                else Double.parseDouble(value.trim());
            } catch (NumberFormatException notANumber) {
                toast("“" + value + "” is not a " + Catalog.typeName(type));
                return;
            }
        }
        Map<String, Overrides.Entry> map = Overrides.read(preferences);
        map.put(name, new Overrides.Entry(type, value));
        Overrides.write(preferences, map);
        overrides = map;
        render();
    }

    private void removeOverride(String name) {
        Map<String, Overrides.Entry> map = Overrides.read(preferences);
        if (map.remove(name) != null) Overrides.write(preferences, map);
        overrides = map;
        render();
    }

    private void approve(String name) {
        Map<String, String> approved = Overrides.readApproved(preferences);
        approved.put(name, metaValues.get(name));
        Overrides.writeApproved(preferences, approved);
        Map<String, String> held = new HashMap<>(heldValues);
        held.remove(name);
        heldValues = held;
        render();
    }

    private void approveAll() {
        Map<String, String> approved = Overrides.readApproved(preferences);
        for (String name : heldValues.keySet()) approved.put(name, metaValues.get(name));
        Overrides.writeApproved(preferences, approved);
        heldValues = Map.of();
        render();
    }

    // ------------------------------------------------------------------ Rows

    private static final class Row {
        String title;
        String detail = "";
        String badge = "";
        int titleColor = Color.WHITE;
        int badgeColor = ACCENT;
        Runnable action;

        static Row note(String text) {
            Row row = new Row();
            row.title = text;
            row.titleColor = MUTED;
            return row;
        }
    }

    private final class RowAdapter extends BaseAdapter {
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
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout view = (LinearLayout) convertView;
            if (view == null) {
                view = new LinearLayout(SettingsActivity.this);
                view.setOrientation(LinearLayout.HORIZONTAL);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(16), dp(12), dp(16), dp(12));
                LinearLayout texts = column();
                texts.addView(text("", 15, Color.WHITE));
                texts.addView(text("", 12, MUTED));
                view.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = text("", 13, ACCENT);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setPadding(dp(12), 0, 0, 0);
                view.addView(badge);
            }
            Row row = rows.get(position);
            LinearLayout texts = (LinearLayout) view.getChildAt(0);
            TextView title = (TextView) texts.getChildAt(0);
            TextView detail = (TextView) texts.getChildAt(1);
            TextView badge = (TextView) view.getChildAt(1);
            title.setText(row.title);
            title.setTextColor(row.titleColor);
            detail.setText(row.detail);
            detail.setVisibility(row.detail.isEmpty() ? View.GONE : View.VISIBLE);
            badge.setText(row.badge);
            badge.setTextColor(row.badgeColor);
            view.setBackground(row.action == null ? null : rounded(SURFACE));
            return view;
        }
    }

    // ------------------------------------------------------------------ Helpers

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private MaterialSwitch toggle(String label) {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText(label);
        toggle.setTextColor(Color.WHITE);
        toggle.setEnabled(false);
        return toggle;
    }

    private TextView caption(String value) {
        TextView view = text(value, 12, MUTED);
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        return shape;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
