package com.quest.horizonconfig;

import android.app.Application;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.FileObserver;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;

/**
 * Entry point, loaded into Horizon only. Every MobileConfig consumer on the headset reads its values
 * through Horizon, so the effective override set is applied at each boundary there:
 *
 * <ul>
 *   <li>Meta's native overrides table, using the catalogue's real parameter IDs.</li>
 *   <li>Horizon's Java debug override map.</li>
 *   <li>Horizon provider rows returned to MobileConfig client packages.</li>
 * </ul>
 *
 * <p>The effective set is the user's overrides plus, in approval mode, "pins": Meta-side changes
 * held at the last approved value until the user accepts them in the app. The app reads Meta's
 * values, pending changes and the change log through two extra paths on Horizon's provider.
 *
 * <p>Horizon only ever <em>reads</em> the remote preferences; all writes come from
 * {@link SettingsActivity}. Everything is fail-open: a broken hook logs and leaves stock behaviour.
 */
public final class HorizonConfig extends XposedModule {
    static final String TAG = "HorizonConfig";
    static final String HORIZON = "com.oculus.horizon";
    static final String MDC_AUTHORITY = "com.facebook.mobileconfigservice.contentprovider";
    /** Provider paths answered by the module: Meta values + pending changes, and the change log. */
    static final String STATE_PATH = "horizonconfig_state";
    static final String CHANGES_PATH = "horizonconfig_changes";
    /** Which list the settings screen opens on when launched from a notification. */
    static final String EXTRA_VIEW = "view";

    private static final String NATIVE_HOLDER = "com.facebook.mobileconfig.MobileConfigOverridesTableHolder";
    private static final String DECORATOR = "com.oculus.deviceconfigservice.MrMobileConfigAccessorDecorator";
    private static final String DEBUG_HELPER = "com.oculus.deviceconfigservice.DeviceConfigDebugHelper";
    private static final String PROVIDER = "com.facebook.mobileconfigservice.contentprovider.MobileConfigContentProvider";

    private static final String FILES = "/data/data/" + HORIZON + "/files/";
    private static final File BASELINE_FILE = new File(FILES + "horizonconfig_values.json");
    private static final File PINS_FILE = new File(FILES + "horizonconfig_pins.json");
    private static final File CHANGES_FILE = new File(FILES + "horizonconfig_changes.txt");
    private static final int CHANGE_LOG_LIMIT = 300;
    private static final String NOTIFY_CHANNEL = "horizonconfig_meta";
    private static final int PENDING_NOTIFICATION = 1002;
    // A read returning far fewer than the usual ~6100 means the provider isn't ready yet; don't
    // baseline or diff off a partial read (that would fire false "changed" notifications).
    private static final int MIN_COMPLETE = 1000;

    private SharedPreferences settings;
    private SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private boolean packageReadyHandled;
    private final Breaker breaker = new Breaker();
    // Boot-loop guard: every Horizon start is counted and five minutes of uptime clears the count.
    // Three starts without that mean Horizon keeps coming back up (a boot loop, or a crash loop
    // that takes Horizon with it), so overrides stay off until the user re-arms them.
    private static final File BOOT_COUNT_FILE = new File(FILES + "horizonconfig_boots");
    private static final int BOOT_GUARD_LIMIT = 3;
    private static final long STABLE_UPTIME_MS = 5 * 60_000L;
    private volatile boolean bootGuardTripped;
    private volatile boolean bootKeyCheckPending = true;

    // What the user configured, and what is applied: pins overlaid by the user's overrides.
    private volatile Map<String, Overrides.Entry> userOverrides = Map.of();
    private volatile Map<String, Overrides.Entry> pins = Map.of();
    private volatile Map<String, String> proposals = Map.of(); // pinned name -> Meta's new value
    private volatile Map<String, Overrides.Entry> effective = Map.of();
    private volatile boolean enabled;

    // Meta's own values: the last raw read, and the approved baseline persisted across boots.
    private volatile Map<String, Overrides.Entry> rawValues;
    private Map<String, String> baseline;
    // Set while the module reads Horizon's provider itself, so its own cursor rewrite is skipped.
    private static final ThreadLocal<Boolean> RAW_READ = ThreadLocal.withInitial(() -> false);

    private Field accessAllowedField, instanceField, syncedField;
    private final List<String> appliedKeys = new ArrayList<>();
    private FileObserver mcObserver;
    private volatile long lastReapply;
    private volatile String lastFingerprint = "";
    private Context appContext;

    private final List<Object> nativeHolders = new CopyOnWriteArrayList<>();
    private final Set<Long> nativeAppliedIds = new HashSet<>();
    private Map<String, Long> parameterIds;
    private Method updateBool, updateLong, updateDouble, updateString, removeNative;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) detach();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (packageReadyHandled || !HORIZON.equals(param.getPackageName())) return;
        packageReadyHandled = true;
        try {
            settings = getRemotePreferences("settings");
            ClassLoader loader = param.getClassLoader();
            checkBootGuard();
            checkBootFailsafeKey();
            loadPins();
            loadSettings();
            try {
                installNativeOverrideBridge(loader);
            } catch (ClassNotFoundException missingHolder) {
                log(Log.WARN, TAG, "Native MobileConfig holder not found; provider overrides still apply");
            }
            installHorizon(loader);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "onPackageReady failed for " + param.getPackageName(), error);
        }
    }

    // ------------------------------------------------------------------ Settings

    private void loadSettings() {
        userOverrides = Overrides.read(settings);
        enabled = Overrides.enabled(settings);
        updateEffective();
    }

    private void updateEffective() {
        Map<String, Overrides.Entry> merged = new HashMap<>(pins);
        merged.putAll(userOverrides);
        effective = Collections.unmodifiableMap(merged);
    }

    /** Overrides apply only while the user wants them and neither safety mechanism has tripped. */
    private boolean active() {
        return enabled && !breaker.isTripped() && !bootGuardTripped && !bootKeyCheckPending;
    }

    /** Holding Volume Down while Horizon starts disables overrides for this boot. */
    private void checkBootFailsafeKey() {
        Thread check = new Thread(() -> {
            java.lang.Process input = null;
            try {
                input = new ProcessBuilder("su", "-c", "timeout 3 getevent -ql").redirectErrorStream(true).start();
                input.waitFor(4, TimeUnit.SECONDS);
                if (input.isAlive()) input.destroyForcibly();
                String output = new String(input.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT);
                if (output.contains("key_volumedown") || output.contains("volume_down")) {
                    bootGuardTripped = true;
                    writeBootCount(-1);
                    log(Log.WARN, TAG, "Boot failsafe: Volume Down detected; overrides are off until re-armed");
                }
            } catch (Throwable error) {
                log(Log.INFO, TAG, "Boot key check unavailable; continuing normally");
            } finally {
                if (input != null && input.isAlive()) input.destroyForcibly();
                bootKeyCheckPending = false;
                applyNativeOverrides();
                apply();
            }
        }, "HorizonConfig-boot-key");
        check.setDaemon(true);
        check.start();
    }

    private void checkBootGuard() {
        int starts = 0;
        try {
            if (BOOT_COUNT_FILE.isFile()) starts = Integer.parseInt(new String(Files.readAllBytes(BOOT_COUNT_FILE.toPath())).trim());
        } catch (Throwable ignored) {
        }
        if (starts < 0 || starts + 1 >= BOOT_GUARD_LIMIT) {
            bootGuardTripped = true;
            writeBootCount(-1); // stays tripped until the user re-arms from the app
            log(Log.WARN, TAG, "Boot-loop guard: Horizon restarted repeatedly; overrides are off until re-armed");
            return;
        }
        writeBootCount(starts + 1);
        Thread stable = new Thread(() -> {
            try {
                Thread.sleep(STABLE_UPTIME_MS);
                if (!bootGuardTripped) writeBootCount(0);
            } catch (InterruptedException ignored) {
            }
        }, "HorizonConfig-guard");
        stable.setDaemon(true);
        stable.start();
    }

    private static void writeBootCount(int count) {
        try {
            Files.write(BOOT_COUNT_FILE.toPath(), String.valueOf(count).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private void onSettingsChanged(String key) {
        if (Overrides.ENABLED_KEY.equals(key) && Overrides.enabled(settings)) {
            breaker.reset();
            bootGuardTripped = false;
            writeBootCount(0);
        }
        loadSettings();
        if ((Overrides.APPROVAL_KEY.equals(key) || Overrides.APPROVED_KEY.equals(key)) && rawValues != null) {
            recordValues(rawValues); // re-evaluate pins against the new decisions
        }
        applyNativeOverrides();
        apply();
    }

    // ------------------------------------------------------------- Native table

    /** Populates Meta's own native override table, which Horizon's C++ core consults on every read. */
    private void installNativeOverrideBridge(ClassLoader loader) throws Exception {
        Class<?> holder = loader.loadClass(NATIVE_HOLDER);
        updateBool = holder.getMethod("updateOverrideForBool", long.class, boolean.class);
        updateLong = holder.getMethod("updateOverrideForInt", long.class, long.class);
        updateDouble = holder.getMethod("updateOverrideForDouble", long.class, double.class);
        updateString = holder.getMethod("updateOverrideForString", long.class, String.class);
        removeNative = holder.getMethod("removeOverrideForParam", long.class);
        parameterIds = loadParameterIds();
        for (Constructor<?> constructor : holder.getDeclaredConstructors()) {
            hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                Object instance = chain.getThisObject();
                if (instance != null && !nativeHolders.contains(instance)) {
                    // Meta's tables persist their entries (mc_overrides.json) and reload them, so clear
                    // every id this module could have set before applying the current set.
                    for (long id : parameterIds.values()) removeNative.invoke(instance, id);
                    nativeHolders.add(instance);
                }
                applyNativeOverrides();
                return result;
            });
        }
        log(Log.INFO, TAG, "Native MobileConfig bridge active (" + parameterIds.size() + " ids)");
    }

    private Map<String, Long> loadParameterIds() throws Exception {
        Map<String, Long> ids = new HashMap<>(8192);
        try (ZipFile apk = new ZipFile(getModuleApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/param_ids.csv");
            if (entry == null) throw new IllegalStateException("param_ids.csv missing");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(apk.getInputStream(entry)))) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    int comma = line.lastIndexOf(',');
                    if (comma > 0) ids.put(line.substring(0, comma), Long.parseLong(line.substring(comma + 1)));
                }
            }
        }
        return ids;
    }

    /**
     * Only the user's overrides go into the native table. Pins stay out of it so the module can
     * still read Meta's real value underneath them (a native override hides it from every read).
     */
    private synchronized void applyNativeOverrides() {
        if (parameterIds == null || nativeHolders.isEmpty()) return;
        try {
            for (Object holder : nativeHolders) {
                for (long id : nativeAppliedIds) removeNative.invoke(holder, id);
            }
            nativeAppliedIds.clear();
            if (!active()) return;
            for (Map.Entry<String, Overrides.Entry> item : userOverrides.entrySet()) {
                Long id = parameterIds.get(item.getKey());
                if (id == null) continue;
                Overrides.Entry value = item.getValue();
                for (Object holder : nativeHolders) {
                    switch (value.type) {
                        case Overrides.TYPE_BOOLEAN -> updateBool.invoke(holder, id, Boolean.parseBoolean(value.value));
                        case Overrides.TYPE_LONG -> updateLong.invoke(holder, id, Long.parseLong(value.value.trim()));
                        case Overrides.TYPE_DOUBLE -> updateDouble.invoke(holder, id, Double.parseDouble(value.value.trim()));
                        default -> updateString.invoke(holder, id, value.value);
                    }
                }
                nativeAppliedIds.add(id);
            }
            Log.i(TAG, "Applied " + nativeAppliedIds.size() + " native override(s)");
        } catch (Throwable error) {
            Log.e(TAG, "Could not apply native overrides", error);
        }
    }

    // ------------------------------------------------------------------ Horizon

    private void installHorizon(ClassLoader loader) {
        try {
            Class<?> helper = loader.loadClass(DEBUG_HELPER);
            accessAllowedField = helper.getDeclaredField("sIsAccessAllowed");
            instanceField = helper.getDeclaredField("sInstance");
            syncedField = helper.getDeclaredField("mSyncedOverriddenValues");
            accessAllowedField.setAccessible(true);
            instanceField.setAccessible(true);
            syncedField.setAccessible(true);

            Class<?> decorator = loader.loadClass(DECORATOR);
            Constructor<?>[] constructors = decorator.getDeclaredConstructors();
            if (constructors.length != 1) {
                log(Log.ERROR, TAG, DECORATOR + " has " + constructors.length + " constructors; expected 1");
                return;
            }
            // The debug map is built inside this constructor, only if access is allowed. Force the
            // gate open before the body runs, then fill the freshly built map afterwards.
            hook(constructors[0]).intercept(chain -> {
                openGate();
                Object result = chain.proceed();
                apply();
                return result;
            });
            openGate();
            apply();

            // PackageReady can race Horizon's service construction on boot. Re-apply once the
            // application exists, then take the first reading of Meta's values off the main thread.
            Method appCreate = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            hook(appCreate).intercept(chain -> {
                Object result = chain.proceed();
                openGate();
                apply();
                new Thread(this::diffAndNotify, "HorizonConfig-read").start();
                return result;
            });

            settingsListener = (prefs, key) -> onSettingsChanged(key);
            settings.registerOnSharedPreferenceChangeListener(settingsListener);
            watchMobileConfigSource();
            log(Log.INFO, TAG, "Horizon config bridge active");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Could not install Horizon config bridge", error);
        }
        try {
            installProviderHook(loader);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Provider hook not installed; client rows are not rewritten", error);
        }
    }

    /**
     * Rewrites provider rows for client packages, answers the app's own paths, and feeds the
     * crash-loop breaker: every consumer re-reads its values through the provider on startup.
     */
    private void installProviderHook(ClassLoader loader) throws Exception {
        Class<?> provider = loader.loadClass(PROVIDER);
        // Horizon only serves allowlisted client packages; let this module's own app through too.
        int appUid = getModuleApplicationInfo().uid;
        hook(provider.getDeclaredMethod("onCheckWritePermissions")).intercept(chain ->
                Binder.getCallingUid() == appUid ? Boolean.TRUE : chain.proceed());

        Method doQuery = provider.getDeclaredMethod(
                "doQuery", Uri.class, String[].class, String.class, String[].class, String.class);
        hook(doQuery).intercept(chain -> {
            Uri uri = (Uri) chain.getArg(0);
            String path = uri == null ? null : uri.getLastPathSegment();
            if (STATE_PATH.equals(path)) return stateCursor();
            if (CHANGES_PATH.equals(path)) return changesCursor();
            if (RAW_READ.get()) return chain.proceed();

            int uid = Binder.getCallingUid();
            int pid = Binder.getCallingPid();
            Object result = chain.proceed();
            if (uid != Process.myUid() && breaker.onConsumerRead(uid, pid)) {
                log(Log.WARN, TAG, "Safety breaker tripped: " + breaker.reason());
                applyNativeOverrides();
                apply();
            }
            if (result instanceof Cursor cursor) return overrideCursor(cursor);
            return result;
        });
    }

    /** Replaces typed provider values by name for every MobileConfig client package. */
    private Cursor overrideCursor(Cursor cursor) {
        // doQuery runs on Horizon's Binder pool. Never perform a remote-preferences Binder call
        // from here: the preferences provider can be waiting on this same transaction and deadlock.
        // The listener-maintained immutable map is safe to read on this hot path.
        if (!active()) return cursor;
        Map<String, Overrides.Entry> values = effective;
        if (values.isEmpty()) return cursor;
        int nameColumn = cursor.getColumnIndex("CONFIG_PARAM_NAME");
        int valueColumn = cursor.getColumnIndex("VALUE");
        if (nameColumn < 0 || valueColumn < 0) return cursor;
        String[] columns = cursor.getColumnNames();
        MatrixCursor copy = new MatrixCursor(columns, cursor.getCount());
        while (cursor.moveToNext()) {
            Object[] row = new Object[columns.length];
            for (int column = 0; column < columns.length; column++) {
                row[column] = switch (cursor.getType(column)) {
                    case Cursor.FIELD_TYPE_NULL -> null;
                    case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column);
                    case Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column);
                    case Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column);
                    default -> cursor.getString(column);
                };
            }
            Overrides.Entry override = values.get(cursor.getString(nameColumn));
            if (override != null) row[valueColumn] = override.value;
            copy.addRow(row);
        }
        copy.setExtras(cursor.getExtras());
        cursor.close();
        return copy;
    }

    /** name, type, Meta's value and, for a held change, the approved value still being served. */
    private Cursor stateCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"NAME", "TYPE", "META_VALUE", "HELD_VALUE"});
        android.os.Bundle safety = new android.os.Bundle();
        safety.putBoolean("breaker", breaker.isTripped());
        safety.putBoolean("boot_guard", bootGuardTripped);
        cursor.setExtras(safety);
        Map<String, Overrides.Entry> raw = rawValues;
        Map<String, Overrides.Entry> held = pins;
        if (raw != null) {
            for (Map.Entry<String, Overrides.Entry> item : raw.entrySet()) {
                Overrides.Entry pin = held.get(item.getKey());
                cursor.addRow(new Object[]{item.getKey(), item.getValue().type, item.getValue().value,
                        pin == null ? null : pin.value});
            }
        }
        return cursor;
    }

    private Cursor changesCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"TIME", "KIND", "NAME", "CHANGE"});
        List<String> lines = readChangeLog();
        for (int i = lines.size() - 1; i >= 0; i--) { // newest first
            String[] parts = lines.get(i).split("\t", 4);
            if (parts.length == 4) cursor.addRow(new Object[]{Long.parseLong(parts[0]), parts[1], parts[2], parts[3]});
        }
        return cursor;
    }

    /**
     * Watches Horizon's own MobileConfig directory — the device's single config source — and re-reads
     * Meta's values whenever it is rewritten (a fetch drops a new mctable).
     */
    private void watchMobileConfigSource() {
        final File dir = new File(FILES + "mobileconfig");
        if (!dir.isDirectory()) {
            log(Log.WARN, TAG, "MobileConfig dir not found; not watching for updates");
            return;
        }
        lastFingerprint = fingerprint(dir);
        baseline = loadBaseline();
        int mask = FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE | FileObserver.DELETE;
        mcObserver = new FileObserver(dir, mask) {
            @Override
            public void onEvent(int event, String path) {
                long now = System.currentTimeMillis();
                if (now - lastReapply < 1500) return; // the dir churns; debounce bursts
                lastReapply = now;
                // Ignore the change_listeners heartbeat — only a rewritten mctable changes this.
                String current = fingerprint(dir);
                if (current.equals(lastFingerprint)) return;
                lastFingerprint = current;
                apply();
                diffAndNotify();
            }
        };
        mcObserver.startWatching();
        log(Log.INFO, TAG, "Watching MobileConfig source for Meta changes");
    }

    /** A cheap signature of the live mctable set; changes only when Meta actually rewrites values. */
    private static String fingerprint(File dir) {
        StringBuilder signature = new StringBuilder();
        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File data : children) {
                if (!data.isDirectory() || !data.getName().endsWith(".data")) continue;
                String index = "";
                try {
                    index = new String(Files.readAllBytes(new File(data, "latest.idx").toPath())).trim();
                } catch (Exception ignored) {
                }
                File table = new File(data, index + ".mctable");
                signature.append(data.getName()).append(':').append(index).append(':')
                        .append(table.length()).append(':').append(table.lastModified()).append(';');
            }
        }
        return signature.toString();
    }

    // ------------------------------------------------------------- Meta changes

    private void diffAndNotify() {
        // Read before taking this module's monitor: the query can wait on Horizon's service-init
        // locks while that init constructs an overrides table, whose hook needs the monitor.
        Map<String, Overrides.Entry> current = readMetaValues();
        if (current == null || current.size() < MIN_COMPLETE) return; // provider not ready / partial read
        recordValues(current);
    }

    /**
     * Compares Meta's values with the approved baseline. Outside approval mode every change is
     * accepted and reported. In approval mode a change to an existing value is pinned at the
     * baseline until the user approves it (or keeps the old value as an ordinary override).
     */
    private synchronized void recordValues(Map<String, Overrides.Entry> current) {
        rawValues = current;
        if (baseline == null) {
            baseline = new HashMap<>();
            for (Map.Entry<String, Overrides.Entry> item : current.entrySet()) {
                baseline.put(item.getKey(), item.getValue().value);
            }
            saveBaseline();
            log(Log.INFO, TAG, "Meta value baseline: " + baseline.size() + " values");
            return;
        }
        Map<String, Overrides.Entry> mine = userOverrides;
        boolean approval = Overrides.approvalMode(settings);
        Map<String, String> approved = Overrides.readApproved(settings);
        Map<String, Overrides.Entry> nextPins = new HashMap<>();
        Map<String, String> nextProposals = new HashMap<>();
        List<String[]> changed = new ArrayList<>(); // {name, description}
        List<String[]> held = new ArrayList<>();

        // ponytail: a pin lands when Horizon re-reads the rewritten mctable; a consumer that fetches
        // inside that window (usually well under a second) still sees Meta's new value until its
        // next fetch. Closing it means hooking Horizon's value update itself.
        for (Map.Entry<String, Overrides.Entry> item : current.entrySet()) {
            String name = item.getKey();
            if (mine.containsKey(name)) continue; // the user's own value; the baseline keeps Meta's last one
            String was = baseline.get(name);
            String now = item.getValue().value;
            if (Objects.equals(was, now)) continue;
            if (approval && was != null && !Objects.equals(now, approved.get(name))) {
                nextPins.put(name, new Overrides.Entry(item.getValue().type, was));
                nextProposals.put(name, now);
                if (!now.equals(proposals.get(name))) held.add(new String[]{name, Overrides.describeChange(was, now)});
                continue;
            }
            baseline.put(name, now);
            boolean reported = now.equals(proposals.get(name)) || now.equals(approved.get(name));
            if (!reported) {
                changed.add(new String[]{name, was == null ? "new, " + now : Overrides.describeChange(was, now)});
            }
        }
        for (String name : new ArrayList<>(baseline.keySet())) {
            if (!current.containsKey(name) && !mine.containsKey(name)) {
                baseline.remove(name);
                changed.add(new String[]{name, "removed"});
            }
        }
        saveBaseline();

        boolean pinsChanged = !nextProposals.equals(proposals);
        pins = Collections.unmodifiableMap(nextPins);
        proposals = Collections.unmodifiableMap(nextProposals);
        if (pinsChanged) {
            savePins();
            updateEffective();
            apply();
        }
        appendChangeLog(changed, held);
        notifyChanges(changed, held);
    }

    private static String shortName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** name -> (type, Meta value) for every parameter, read without this module's cursor rewrite. */
    private Map<String, Overrides.Entry> readMetaValues() {
        RAW_READ.set(true);
        try {
            Context context = context();
            if (context == null) return null;
            Cursor cursor = context.getContentResolver().query(
                    Uri.parse("content://" + MDC_AUTHORITY + "/debug_only_get_all_configs"), null, null, null, null);
            if (cursor == null) return null;
            Map<String, Overrides.Entry> values = new HashMap<>(8192);
            try {
                int nameColumn = cursor.getColumnIndex("CONFIG_PARAM_NAME");
                int typeColumn = cursor.getColumnIndex("TYPE");
                int valueColumn = cursor.getColumnIndex("VALUE");
                if (nameColumn < 0 || typeColumn < 0 || valueColumn < 0) return null;
                while (cursor.moveToNext()) {
                    values.put(cursor.getString(nameColumn),
                            new Overrides.Entry(cursor.getInt(typeColumn), cursor.getString(valueColumn)));
                }
            } finally {
                cursor.close();
            }
            return values;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not read Meta values", error);
            return null;
        } finally {
            RAW_READ.set(false);
        }
    }

    private Context context() throws ReflectiveOperationException {
        if (appContext == null) {
            appContext = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        }
        return appContext;
    }

    // ------------------------------------------------------------- Persistence

    private Map<String, String> loadBaseline() {
        try {
            if (!BASELINE_FILE.isFile()) return null;
            JSONObject json = new JSONObject(new String(Files.readAllBytes(BASELINE_FILE.toPath()), StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>(json.length() * 2);
            var names = json.keys();
            while (names.hasNext()) {
                String name = names.next();
                map.put(name, json.isNull(name) ? null : json.getString(name));
            }
            return map;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not load value baseline", error);
            return null;
        }
    }

    private void saveBaseline() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : baseline.entrySet()) {
                json.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
            }
            Files.write(BASELINE_FILE.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not save value baseline", error);
        }
    }

    /** Pins are persisted so held changes are enforced from the first read after a boot. */
    private void loadPins() {
        try {
            if (!PINS_FILE.isFile()) return;
            JSONObject json = new JSONObject(new String(Files.readAllBytes(PINS_FILE.toPath()), StandardCharsets.UTF_8));
            Map<String, Overrides.Entry> loadedPins = new HashMap<>();
            Map<String, String> loadedProposals = new HashMap<>();
            var names = json.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONObject pin = json.getJSONObject(name);
                loadedPins.put(name, new Overrides.Entry(pin.getInt("t"), pin.getString("v")));
                loadedProposals.put(name, pin.getString("p"));
            }
            pins = Collections.unmodifiableMap(loadedPins);
            proposals = Collections.unmodifiableMap(loadedProposals);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not load held changes", error);
        }
    }

    private void savePins() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Overrides.Entry> pin : pins.entrySet()) {
                json.put(pin.getKey(), new JSONObject()
                        .put("t", pin.getValue().type)
                        .put("v", pin.getValue().value)
                        .put("p", proposals.get(pin.getKey())));
            }
            Files.write(PINS_FILE.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not save held changes", error);
        }
    }

    private List<String> readChangeLog() {
        try {
            return CHANGES_FILE.isFile() ? Files.readAllLines(CHANGES_FILE.toPath()) : new ArrayList<>();
        } catch (Throwable error) {
            return new ArrayList<>();
        }
    }

    /** Tab-separated lines: time, kind (held/changed), full name, description. */
    private void appendChangeLog(List<String[]> changed, List<String[]> held) {
        if (changed.isEmpty() && held.isEmpty()) return;
        List<String> lines = new ArrayList<>(readChangeLog());
        long now = System.currentTimeMillis();
        for (String[] change : held) lines.add(now + "\theld\t" + change[0] + "\t" + change[1]);
        for (String[] change : changed) lines.add(now + "\tchanged\t" + change[0] + "\t" + change[1]);
        if (lines.size() > CHANGE_LOG_LIMIT) lines = lines.subList(lines.size() - CHANGE_LOG_LIMIT, lines.size());
        try {
            Files.write(CHANGES_FILE.toPath(), lines);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not write change log", error);
        }
    }

    // ------------------------------------------------------------ Notifications

    /** One notification per batch, listing each change; held changes share one updating notice. */
    private void notifyChanges(List<String[]> changed, List<String[]> held) {
        try {
            Context context = context();
            if (context == null) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(
                    NOTIFY_CHANNEL, "Meta config changes", NotificationManager.IMPORTANCE_DEFAULT));
            if (!held.isEmpty()) {
                manager.notify(PENDING_NOTIFICATION, changeNotification(context,
                        proposals.size() + " Meta change(s) awaiting approval", held, "pending"));
            } else if (proposals.isEmpty()) {
                manager.cancel(PENDING_NOTIFICATION);
            }
            if (!changed.isEmpty()) {
                manager.notify((int) (System.currentTimeMillis() / 1000), changeNotification(context,
                        "Meta changed " + changed.size() + " config value(s)", changed, "changes"));
            }
            for (String[] change : held) log(Log.INFO, TAG, "held " + change[0] + ": " + change[1]);
            for (String[] change : changed) log(Log.INFO, TAG, "changed " + change[0] + ": " + change[1]);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not post notification", error);
        }
    }

    private static Notification changeNotification(Context context, String title, List<String[]> changes, String view) {
        List<String> lines = new ArrayList<>(changes.size());
        for (String[] change : changes) lines.add(shortName(change[0]) + ": " + change[1]);
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int i = 0; i < Math.min(lines.size(), 7); i++) style.addLine(lines.get(i));
        if (lines.size() > 7) style.setSummaryText("+" + (lines.size() - 7) + " more in Horizon Config");
        Intent open = new Intent()
                .setComponent(new ComponentName("com.quest.horizonconfig", "com.quest.horizonconfig.SettingsActivity"))
                .putExtra(EXTRA_VIEW, view)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Notification.Builder(context, NOTIFY_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(lines.get(0))
                .setStyle(style)
                .setContentIntent(PendingIntent.getActivity(context, view.hashCode(), open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build();
    }

    // ----------------------------------------------------------- Debug override map

    /** Marks device-config debug access allowed so Horizon builds and keeps its override map. */
    private void openGate() {
        try {
            accessAllowedField.set(null, Boolean.TRUE);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not open the debug gate", error);
        }
    }

    /** Rewrites Horizon's override map to the effective set (or clears it when off/tripped). */
    private synchronized void apply() {
        try {
            Object instance = instanceField.get(null);
            if (instance == null) return; // Constructor/application hooks will retry once it is built.
            Object synced = syncedField.get(instance);

            Map<String, Boolean> booleans = mapField(synced, "mOverriddenBooleanValues");
            Map<String, Long> longs = mapField(synced, "mOverriddenLongValues");
            Map<String, Double> doubles = mapField(synced, "mOverriddenDoubleValues");
            Map<String, String> strings = mapField(synced, "mOverriddenStringValues");
            boolean active = active();

            synchronized (synced) {
                for (String key : appliedKeys) {
                    booleans.remove(key);
                    longs.remove(key);
                    doubles.remove(key);
                    strings.remove(key);
                }
                appliedKeys.clear();
                if (active) {
                    for (Map.Entry<String, Overrides.Entry> entry : effective.entrySet()) {
                        String name = entry.getKey();
                        String value = entry.getValue().value;
                        try {
                            switch (entry.getValue().type) {
                                case Overrides.TYPE_BOOLEAN -> booleans.put(name, Boolean.parseBoolean(value));
                                case Overrides.TYPE_LONG -> longs.put(name, Long.parseLong(value.trim()));
                                case Overrides.TYPE_DOUBLE -> doubles.put(name, Double.parseDouble(value.trim()));
                                default -> strings.put(name, value);
                            }
                            appliedKeys.add(name);
                        } catch (NumberFormatException badValue) {
                            log(Log.WARN, TAG, "Skipping " + name + ": not a number '" + value + "'");
                        }
                    }
                }
            }
            Log.i(TAG, (active ? "Applied " : "Cleared, was ") + appliedKeys.size() + " override(s), "
                    + pins.size() + " held");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Could not apply overrides", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> mapField(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, V>) field.get(owner);
    }
}
