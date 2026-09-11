# Horizon Overrides

A libxposed API 102 module for browsing and overriding Quest / Horizon OS MobileConfig values
(booleans, numbers, strings and lists), and optionally holding Meta's own changes until approved.

The app discovers MobileConfig names, types, and current values from Horizon on the headset. It
lists what you have overridden, Meta changes waiting for approval, a change log, and the live
catalogue by namespace. Tap a row to override or approve it.

## Application path

The module runs in Horizon only and applies the same effective set at every MobileConfig boundary:

- Meta's native `MobileConfigOverridesTableHolder`, for the descriptor IDs verified in
  `assets/param_ids.csv`. Unresolved names are never guessed or sent to arbitrary native IDs.
- Horizon's `DeviceConfigDebugHelper` typed override maps.
- The typed rows returned by Horizon's MobileConfig content provider to client packages.

Horizon applies a change immediately. Consumers load their values when they start, from a cache
they refresh from Horizon while running, so a new override reaches an app after it has fetched once
and then restarted; a normal reboot does both for everything that runs at boot. Force-killing apps
is neither needed nor sufficient: some only refresh their cache on their own schedule. Values that
Configuration mirrors into Android DeviceConfig (and the native daemons reading those properties)
follow at the next boot sync.

## Which values are shown

The module reads the live set directly from Horizon, so the repository does not contain a firmware
catalogue or captured flag values. The list updates with the headset software and Meta's current
configuration. The small `param_ids.csv` asset is not a catalogue; it maps a verified subset of
names to native descriptor IDs so those overrides also reach native MobileConfig readers.

## Approval mode

With “Hold Meta changes for approval” on, any value Meta changes keeps its approved value, is listed
under Held and posts one notification naming each change (lists show items added and removed).
Approve takes Meta's value; Keep turns the old value into an ordinary override. Held values are
persisted and enforced from boot. A change reaches consumers only for the moment between Meta
rewriting the table and Horizon re-reading it.

## Safety

Scope is `com.oculus.horizon` only; every other consumer reads its values from Horizon's provider.
The in-memory breaker stops overrides for the current boot if it detects repeated consumer PID
churn; configured values remain stored. Prefer a normal reboot over a Zygote soft restart for
applying changes: a soft restart once stalled init for 70 s and left the UI unresponsive.

This is an unofficial root/Xposed tool. MobileConfig includes experimental and safety-critical
controls; forcing incompatible values can crash services or break tracking until the override is
removed. Start with a small set, keep ADB recovery available, and leave unknown values at Meta's
default. No bulk override preset or device-derived preferences are included in this repository.
Holding Volume Down while Horizon starts triggers a per-boot failsafe and prevents overrides from
being applied. Re-enable the Apply overrides switch later to re-arm it.

## Recovery

Try these in order:

1. Hold **Volume Down** continuously while Horizon starts. Keep holding it for several seconds after
   the Meta logo appears. Horizon Overrides watches the startup input stream and leaves every
   override disabled for that boot when it detects the key.
2. If the UI opens, turn off **Apply overrides**. This keeps the saved list but immediately returns
   reads to Meta's values. Turning it on later re-arms the safety guards.
3. With ADB available, disable the module without deleting its data:

   ```sh
   adb shell su -c "/data/adb/lspd/cli modules disable com.quest.horizonconfig"
   adb reboot
   ```

4. To discard all overrides and remove the module:

   ```sh
   adb shell su -c "pm uninstall com.quest.horizonconfig"
   adb reboot
   ```

5. During a boot loop, ADB may appear before Android's package service. Wait for it and retry until
   `service check package` reports `found`, then run the uninstall command. On PowerShell:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb wait-for-device
   while ((& $adb shell service check package) -notmatch 'found') { Start-Sleep 1 }
   & $adb shell su -c "pm uninstall com.quest.horizonconfig"
   & $adb reboot
   ```

Uninstalling deletes the module's saved preferences. Disabling it is reversible and is the preferred
first ADB recovery step.

## Build and install

```powershell
.\build.ps1
```

The script increments the version, builds, and debug-signs the APK. Use `-Install` to install it to
the first connected ADB device. Enable Horizon Overrides in the Xposed manager for
`com.oculus.horizon`, then reboot to load the module.
