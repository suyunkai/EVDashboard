# Sound recovery lifecycle

Automatic recovery remains an explicit, per-Android-user opt-in. It applies the saved sound rules using temporary CP resource overlays. It does not flash partitions, switch slots, reboot, launch the interface, or change vehicle power properties.

## Diagnosis and reference

On the connected Android 11 head unit, the previous recovery foreground service was running, background app operations were allowed, and the app was battery-whitelisted. Its saved failure was `Parking check unavailable; automatic changes denied`. The old gear/speed probe therefore blocked recovery even though startup had succeeded. The earlier implementation also stopped checking after its three-minute window, ignored a sticky service restart with no intent, and disabled recovery when a CP host key changed.

The supplied reference APK was inspected statically with JADX 1.5.5:

- Package: `com.yahooeu2k.ex5.profilesu`; version `3.8.5` / `386`.
- SHA-256: `b6828265add5bb908bede929ada844a0a60d613157d116d53d79764a321a63c7`.
- `com.yahooeu2k.ex5.profiles.BootReceiver` declares `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`, `SCREEN_ON`, `ACTION_POWER_CONNECTED`, `USER_PRESENT`, `USER_FOREGROUND`, `USER_BACKGROUND`, and `USER_UNLOCKED`.
- The receiver and application services are disabled in the manifest. Runtime authorization checks control component enablement with `PackageManager.setComponentEnabledSetting`; declaration alone does not prove delivery.
- Wake/user/power hints start or refresh foreground monitoring services. `USER_BACKGROUND` releases its gear-watchdog session. `BOOT_COMPLETED` can start a separate sound boot service. `DisplayStateMonitorService` returns `START_STICKY` and reinitializes its manager when necessary.
- Native/init and system-whitelist helpers are present as assets. EVDashboard does not install or execute those helpers. No decompiled source or reference APK is redistributed.

This is evidence of the reference's implementation, not proof of its behavior on the connected head unit. Android screen events require dynamic registration; adding a manifest action alone does not recover a dead process.

## Recovery in EVDashboard

1. Manifest boot/update/unlock/user-foreground/power/OEM wake receivers request the monitor. Process startup, sticky recreation and the user's enable switch also request a fresh check. Screen-on is registered dynamically; the OEM display-on action is also supported.
2. Duplicate requests share one settled, three-minute readiness window. Checks use bounded backoff. The foreground monitor requests another window every minute while the head unit is interactive, including after timeout or a CP-only runtime reset.
3. A persisted periodic `JobScheduler` job provides a best-effort 15-minute fallback for a lost monitor. Android controls its timing. No idle wake lock is held, and the monitor does not deliberately wake the head unit. See the [Android JobInfo API](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
4. Readiness checks and maintenance work share the application's serial executor. Background reads do not modify the visible task's progress. Fresh CP manifests and mount ownership are checked; matching immutable audio remains cached locally. If current resources already match, no apply task or playback-service restart is submitted.
5. The configured numeric IP, port 22 and root login replace SSH key pinning. CP key rotation no longer shows confirmation or disables recovery. This does not authenticate the server identity. Only a successful root login marks the connection verified; changing the configured IP still clears that record and disables automatic recovery. Legacy catalogs migrate after a matching saved IP/key record passes fresh resource inspection. No SSH password is saved.
6. Original-resource SHA-256 hashes, rule compatibility, foreign-overlay handling, the CP operation lock and unresolved partition-write guard remain. There is no gear/speed probe or P-gear confirmation anywhere in the workflow. Image writes retain power/OTA/risk confirmation, backup/slot checks and complete readback.
7. Before submitting a CP batch, the app durably records an unresolved-operation marker. Only verified completion clears it. Following a disconnect or process loss, automatic checks can confirm an already-correct result; otherwise they request manual application rather than blindly repeating an uncertain operation. A successful scoped edit does not clear a previously unresolved full operation.

`startupStatus`, `startupReason`, the ongoing notification and bounded private `startup.log` record recovery state. An Android force stop still requires reopening the app; background restrictions can prevent delivery or delay the fallback job.

## Validation boundaries

Unit tests cover wake-request merging, bounded backoff, cooldown, cancellation and a new check after the initial timeout. Instrumented lifecycle tests use an unconfigured documentation IP and cannot apply resources to CP. Separate explicitly opted-in device checks test root connection with a stale saved public key, legacy catalog migration, and restoration of existing user rules.

Live recovery testing restores only the originals for already-configured sounds, restores the user's saved rules, then requests recovery through an app-targeted wake broadcast and verifies the resulting resource hashes. It does not create arbitrary test rules or modify CP partitions. Real power-cycle/STR delivery and physical speaker output require separate observation; simulated broadcasts are not that proof.

### 0.2.7 verification

- Release JVM suite: 118 tests passed; release lint: 0 errors, 28 warnings.
- Android 11, active user 11, requested ADB endpoint `127.0.0.1:5557`: 4 lifecycle tests, 2 live read-only SSH/readiness tests, 18 UI/import regression tests and 1 live configured-overlay recovery test passed.
- Existing two turn-signal rules (including replacement and independent gain values) were preserved. Their original sounds were restored, then a package-targeted STR-resume hint triggered automatic reapplication. CP hashes and overlay state matched the saved configuration afterward; automatic mode remained enabled.
- After the monitor exited, a forced invocation of its persisted system job restarted foreground monitoring and completed a fresh no-op check. The subsequent minute-monitor cycle also completed without submitting another maintenance task or changing the saved rules.
- No physical power cycle, actual STR cycle, CP partition write, vehicle-power-property modification, or physical listening assessment was performed.
