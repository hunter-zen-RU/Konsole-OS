package com.termux.app.konsole;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core Automation Engine orchestrating:
 * - Phase A: Trigger detection (dynamic BroadcastReceiver, AlarmManager cron schedules, 30s ticker, optional Notification/Accessibility hooks)
 * - Phase B: JSON rule evaluation, device state constraint checks, and %var% / export variable injection
 * - Phase C: Shell + Native execution and bidirectional CLI bridge ('macro' & 'auto-trigger' in $PREFIX/bin)
 */
public class KonsoleAutomationEngine {

    private static final String TAG = "KonsoleAutomationEngine";
    public static final String ACTION_CRON_ALARM = "com.termux.konsole.ACTION_CRON_ALARM";
    public static final String EXTRA_MACRO_ID = "macro_id";

    @SuppressLint("StaticFieldLeak")
    private static KonsoleAutomationEngine sInstance;

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Handler mTickerHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> mLastIntervalFireMs = new HashMap<>();
    private final Map<String, String> mLastCronMinuteKey = new HashMap<>();

    private Context mAppContext;
    private KonsoleTriggerReceiver mTriggerReceiver;
    private volatile boolean mRunning = false;

    private final Runnable mCronTicker = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || mAppContext == null) return;
            mExecutor.execute(() -> evaluatePeriodicAndCronRules(mAppContext));
            mTickerHandler.postDelayed(this, 30_000L);
        }
    };

    private KonsoleAutomationEngine() {
    }

    public static synchronized KonsoleAutomationEngine getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleAutomationEngine();
        }
        return sInstance;
    }

    public synchronized void start(Context context) {
        if (context == null) return;
        mAppContext = context.getApplicationContext();
        if (!mRunning) {
            mRunning = true;
            if (mTriggerReceiver == null) {
                mTriggerReceiver = new KonsoleTriggerReceiver();
            }
            mTriggerReceiver.register(mAppContext);
            rescheduleAllCrons(mAppContext);
            mTickerHandler.removeCallbacks(mCronTicker);
            mTickerHandler.postDelayed(mCronTicker, 2000L);
            Log.i(TAG, "Konsole Automation Engine started");
        }
    }

    public synchronized void stop() {
        mRunning = false;
        mTickerHandler.removeCallbacks(mCronTicker);
        if (mTriggerReceiver != null && mAppContext != null) {
            mTriggerReceiver.unregister(mAppContext);
        }
        Log.i(TAG, "Konsole Automation Engine stopped");
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void dispatchTrigger(Context context,
                                KonsoleMacroRule.TriggerType triggerType,
                                Map<String, String> triggerExtras) {
        Context ctx = context != null ? context.getApplicationContext() : mAppContext;
        if (ctx == null || triggerType == null) return;

        mExecutor.execute(() -> {
            List<KonsoleMacroRule> rules = KonsoleMacroStore.getInstance().loadMacros(ctx);
            for (KonsoleMacroRule rule : rules) {
                if (!rule.enabled) continue;
                if (rule.triggerType != triggerType) continue;
                if (!matchesTriggerFilter(rule, triggerExtras)) continue;

                executeMacroInternal(ctx, rule, triggerType.name(), triggerExtras, false);
            }
        });
    }

    private boolean matchesTriggerFilter(KonsoleMacroRule rule, Map<String, String> extras) {
        String filter = rule.triggerValue != null ? rule.triggerValue.trim() : "";
        if (filter.isEmpty()) return true;

        if (rule.triggerType == KonsoleMacroRule.TriggerType.APP_OPENED && extras != null) {
            String pkg = extras.getOrDefault("foreground_app", "");
            return pkg.toLowerCase(Locale.US).contains(filter.toLowerCase(Locale.US));
        }
        if (rule.triggerType == KonsoleMacroRule.TriggerType.NOTIFICATION && extras != null) {
            String hay = (extras.getOrDefault("notification_pkg", "") + " "
                    + extras.getOrDefault("notification_title", "") + " "
                    + extras.getOrDefault("notification_text", "")).toLowerCase(Locale.US);
            return hay.contains(filter.toLowerCase(Locale.US));
        }
        return true;
    }

    public synchronized KonsoleMacroStore.ExecutionRecord testMacro(Context context,
                                                                    String macroId,
                                                                    boolean bypassConstraints) {
        Context ctx = context != null ? context.getApplicationContext() : mAppContext;
        if (ctx == null) {
            KonsoleMacroStore.ExecutionRecord err = new KonsoleMacroStore.ExecutionRecord();
            err.summary = "Context unavailable";
            err.exitCode = 1;
            return err;
        }
        KonsoleMacroRule rule = KonsoleMacroStore.getInstance().findByIdOrName(ctx, macroId);
        if (rule == null) {
            KonsoleMacroStore.ExecutionRecord err = new KonsoleMacroStore.ExecutionRecord();
            err.macroId = macroId;
            err.summary = "Macro not found: " + macroId;
            err.exitCode = 1;
            return err;
        }
        return executeMacroInternal(ctx, rule, "MANUAL_TEST", null, bypassConstraints);
    }

    public KonsoleMacroStore.ExecutionRecord executeMacroInternal(Context context,
                                                                  KonsoleMacroRule rule,
                                                                  String triggerLabel,
                                                                  Map<String, String> triggerExtras,
                                                                  boolean bypassConstraints) {
        KonsoleMacroStore.ExecutionRecord record = new KonsoleMacroStore.ExecutionRecord();
        record.timestampMs = System.currentTimeMillis();
        record.macroId = rule.id;
        record.macroName = rule.name;
        record.triggerType = triggerLabel;
        record.forced = bypassConstraints;

        KonsoleConstraintEvaluator.EvaluationResult eval =
                KonsoleConstraintEvaluator.evaluate(context, rule.constraints);
        record.constraintsPassed = eval.passed;

        if (!eval.passed && !bypassConstraints) {
            record.exitCode = 2;
            record.summary = "Skipped (constraints unmet): " + eval.summary;
            record.output = eval.summary;
            KonsoleMacroStore.getInstance().appendHistory(context, record);
            return record;
        }

        Map<String, String> vars = KonsoleConstraintEvaluator.buildVariableMap(
                context, rule, triggerLabel, triggerExtras);

        KonsoleMacroExecutor.ExecutionOutcome outcome =
                KonsoleMacroExecutor.executeActions(context, rule, vars);

        record.exitCode = outcome.exitCode;
        record.output = outcome.output;
        record.summary = (bypassConstraints && !eval.passed ? "[FORCED] " : "") + outcome.summary;

        rule.runCount++;
        rule.lastRunTimeMs = record.timestampMs;
        rule.lastRunSummary = record.summary;
        KonsoleMacroStore.getInstance().upsertMacro(context, rule);
        KonsoleMacroStore.getInstance().appendHistory(context, record);

        return record;
    }

    private void evaluatePeriodicAndCronRules(Context context) {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        String minuteKey = new SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(new Date(now));

        List<KonsoleMacroRule> rules = KonsoleMacroStore.getInstance().loadMacros(context);
        for (KonsoleMacroRule rule : rules) {
            if (!rule.enabled) continue;
            if (rule.triggerType == KonsoleMacroRule.TriggerType.CRON) {
                if (KonsoleCronParser.matches(rule.triggerValue, cal)) {
                    String lastKey = mLastCronMinuteKey.get(rule.id);
                    if (!minuteKey.equals(lastKey)) {
                        mLastCronMinuteKey.put(rule.id, minuteKey);
                        executeMacroInternal(context, rule, "CRON (" + rule.triggerValue + ")", null, false);
                        scheduleAlarmForCronRule(context, rule);
                    }
                }
            } else if (rule.triggerType == KonsoleMacroRule.TriggerType.INTERVAL) {
                long intervalMin = parsePositiveLong(rule.triggerValue, 15L);
                long intervalMs = intervalMin * 60_000L;
                Long lastFired = mLastIntervalFireMs.get(rule.id);
                long ref = lastFired != null ? lastFired : rule.lastRunTimeMs;
                if (ref == 0L || (now - ref) >= intervalMs) {
                    mLastIntervalFireMs.put(rule.id, now);
                    executeMacroInternal(context, rule, "INTERVAL (" + intervalMin + "m)", null, false);
                }
            }
        }
    }

    public void onCronAlarmFired(Context context, String macroId) {
        Context ctx = context != null ? context.getApplicationContext() : mAppContext;
        if (ctx == null) return;
        mExecutor.execute(() -> {
            if (macroId == null || macroId.trim().isEmpty()) {
                evaluatePeriodicAndCronRules(ctx);
                rescheduleAllCrons(ctx);
                return;
            }
            KonsoleMacroRule rule = KonsoleMacroStore.getInstance().findByIdOrName(ctx, macroId);
            if (rule != null && rule.enabled && rule.triggerType == KonsoleMacroRule.TriggerType.CRON) {
                String minuteKey = new SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(new Date());
                if (!minuteKey.equals(mLastCronMinuteKey.get(rule.id))) {
                    mLastCronMinuteKey.put(rule.id, minuteKey);
                    executeMacroInternal(ctx, rule, "CRON_ALARM (" + rule.triggerValue + ")", null, false);
                }
                scheduleAlarmForCronRule(ctx, rule);
            }
        });
    }

    public void rescheduleAllCrons(Context context) {
        if (context == null) return;
        List<KonsoleMacroRule> rules = KonsoleMacroStore.getInstance().loadMacros(context);
        for (KonsoleMacroRule rule : rules) {
            if (rule.enabled && rule.triggerType == KonsoleMacroRule.TriggerType.CRON) {
                scheduleAlarmForCronRule(context, rule);
            } else {
                cancelAlarmForRule(context, rule.id);
            }
        }
    }

    private void scheduleAlarmForCronRule(Context context, KonsoleMacroRule rule) {
        if (context == null || rule == null) return;
        long nextMs = KonsoleCronParser.computeNextTriggerTimeMs(rule.triggerValue, System.currentTimeMillis());
        if (nextMs <= 0) return;

        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, KonsoleCronAlarmReceiver.class);
            intent.setAction(ACTION_CRON_ALARM);
            intent.putExtra(EXTRA_MACRO_ID, rule.id);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, rule.id.hashCode(), intent, flags);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pi);
                } catch (SecurityException sec) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pi);
                }
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, nextMs, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed scheduling cron alarm for " + rule.name + ": " + e.getMessage());
        }
    }

    private void cancelAlarmForRule(Context context, String macroId) {
        if (context == null || macroId == null) return;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, KonsoleCronAlarmReceiver.class);
            intent.setAction(ACTION_CRON_ALARM);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, macroId.hashCode(), intent, flags);
            if (pi != null) am.cancel(pi);
        } catch (Exception ignored) {
        }
    }

    /**
     * Handles bidirectional CLI invocations from Fish/Bash scripts via `$PREFIX/bin/macro` & `$PREFIX/bin/auto-trigger`.
     */
    public void handleMacroCli(Context context, KonsoleCommandDispatcher.CommandSink sink, String rawArgs) {
        if (sink == null) return;
        Context ctx = context != null ? context : mAppContext;
        if (ctx == null) {
            sink.write("Konsole Automation context unavailable.\n");
            sink.finish(1);
            return;
        }

        String trimmed = rawArgs == null ? "" : rawArgs.trim();
        if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed) || "--help".equalsIgnoreCase(trimmed)) {
            sink.write(
                "\033[1;36mKonsole Automation CLI (macro / auto-trigger)\033[0m\n" +
                "  macro lab                          Open visual UI Macro Lab\n" +
                "  macro list                         List configured macros & cron schedules\n" +
                "  macro status                       Show engine, alarm & listener status\n" +
                "  macro trigger <id|name> [--force]  Run a macro immediately (--force skips constraints)\n" +
                "  macro enable|disable <id|name>     Enable or disable a macro\n" +
                "  macro notify <title> <msg...>      Dispatch native Android notification mid-script\n" +
                "  macro toast <msg...>               Show native toast mid-script\n" +
                "  macro flash <on|off|toggle>        Control device flashlight mid-script\n" +
                "  macro vibrate [pattern]            Vibrate device (e.g. 200 or 0,150,80,150)\n" +
                "  macro launch <app-or-pkg>          Launch Android app mid-script\n" +
                "  macro tap <x> <y>                  Dispatch accessibility tap at coordinates\n" +
                "  macro swipe <x1,y1> <x2,y2> [ms]   Dispatch accessibility swipe gesture\n"
            );
            sink.finish(0);
            return;
        }

        String[] parts = trimmed.split("\\s+");
        String sub = parts[0].toLowerCase(Locale.US);

        switch (sub) {
            case "lab":
            case "ui":
            case "editor": {
                Intent intent = new Intent(ctx, KonsoleMacroLabActivity.class);
                if (!(ctx instanceof android.app.Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(intent);
                sink.write("\033[1;32m[Automation]\033[0m Opening Konsole Macro Lab...\n");
                sink.finish(0);
                return;
            }

            case "status": {
                List<KonsoleMacroRule> all = KonsoleMacroStore.getInstance().loadMacros(ctx);
                long enabledCount = 0;
                for (KonsoleMacroRule r : all) if (r.enabled) enabledCount++;
                sink.write(
                    "\033[1;36m[Automation Engine Status]\033[0m\n" +
                    "  Engine Running      : " + mRunning + "\n" +
                    "  Rule Store JSON     : " + KonsoleMacroStore.getInstance().getActiveStoragePath(ctx) + "\n" +
                    "  Macros Loaded       : " + all.size() + " (" + enabledCount + " enabled)\n" +
                    "  Notification Listener: " + (KonsoleNotificationListenerService.isConnected() ? "CONNECTED" : "DISABLED (optional)") + "\n" +
                    "  Accessibility Bridge : " + (KonsoleAutomationAccessibilityService.isConnected() ? "CONNECTED" : "DISABLED (optional)") + "\n"
                );
                sink.finish(0);
                return;
            }

            case "list":
            case "ls": {
                List<KonsoleMacroRule> all = KonsoleMacroStore.getInstance().loadMacros(ctx);
                if (all.isEmpty()) {
                    sink.write("(No macros saved yet. Run 'macro lab' to create one.)\n");
                } else {
                    sink.write("\033[1;36m[Konsole Macros]\033[0m (" + all.size() + " total)\n");
                    for (KonsoleMacroRule r : all) {
                        String state = r.enabled ? "\033[1;32mON \033[0m" : "\033[1;33mOFF\033[0m";
                        String trig = r.triggerType.name()
                                + (r.triggerValue != null && !r.triggerValue.isEmpty() ? " [" + r.triggerValue + "]" : "");
                        sink.write("  " + state + " \033[1;36m" + r.id + "\033[0m · " + r.name + " (" + trig + ") · runs=" + r.runCount + "\n");
                    }
                }
                sink.finish(0);
                return;
            }

            case "trigger":
            case "run": {
                if (parts.length < 2) {
                    sink.write("Usage: macro trigger <id|name> [--force]\n");
                    sink.finish(1);
                    return;
                }
                boolean force = trimmed.contains("--force");
                String target = trimmed.substring(parts[0].length()).replace("--force", "").trim();
                KonsoleMacroStore.ExecutionRecord rec = testMacro(ctx, target, force);
                sink.write("\033[1;36m[Macro Result]\033[0m " + rec.summary + "\n");
                if (rec.output != null && !rec.output.isEmpty()) {
                    sink.write(rec.output + "\n");
                }
                sink.finish(rec.exitCode);
                return;
            }

            case "enable":
            case "disable": {
                if (parts.length < 2) {
                    sink.write("Usage: macro " + sub + " <id|name>\n");
                    sink.finish(1);
                    return;
                }
                String target = trimmed.substring(parts[0].length()).trim();
                KonsoleMacroRule rule = KonsoleMacroStore.getInstance().findByIdOrName(ctx, target);
                if (rule == null) {
                    sink.write("Macro not found: " + target + "\n");
                    sink.finish(1);
                    return;
                }
                rule.enabled = "enable".equals(sub);
                KonsoleMacroStore.getInstance().upsertMacro(ctx, rule);
                rescheduleAllCrons(ctx);
                sink.write("Macro '" + rule.name + "' " + (rule.enabled ? "ENABLED" : "DISABLED") + ".\n");
                sink.finish(0);
                return;
            }

            case "notify": {
                if (parts.length < 3) {
                    sink.write("Usage: macro notify <title> <message>\n");
                    sink.finish(1);
                    return;
                }
                String title = parts[1];
                String msg = joinFrom(parts, 2);
                KonsoleMacroExecutor.postNotification(ctx, title, msg);
                sink.write("Notification posted: " + title + "\n");
                sink.finish(0);
                return;
            }

            case "toast": {
                if (parts.length < 2) {
                    sink.write("Usage: macro toast <message>\n");
                    sink.finish(1);
                    return;
                }
                String msg = joinFrom(parts, 1);
                KonsoleMacroExecutor.showToast(ctx, msg);
                sink.write("Toast shown: " + msg + "\n");
                sink.finish(0);
                return;
            }

            case "flash":
            case "flashlight": {
                String mode = parts.length >= 2 ? parts[1] : "toggle";
                String res = KonsoleMacroExecutor.executeFlashlight(ctx, mode);
                sink.write(res + "\n");
                sink.finish(0);
                return;
            }

            case "vibrate": {
                String pat = parts.length >= 2 ? parts[1] : "200";
                KonsoleMacroExecutor.triggerVibrate(ctx, pat);
                sink.write("Vibrated (" + pat + ")\n");
                sink.finish(0);
                return;
            }

            case "launch": {
                if (parts.length < 2) {
                    sink.write("Usage: macro launch <app-or-package>\n");
                    sink.finish(1);
                    return;
                }
                String app = joinFrom(parts, 1);
                boolean ok = KonsoleMacroExecutor.launchApp(ctx, app);
                sink.write(ok ? "Launched: " + app + "\n" : "App not found: " + app + "\n");
                sink.finish(ok ? 0 : 1);
                return;
            }

            case "tap": {
                if (parts.length < 3) {
                    sink.write("Usage: macro tap <x> <y>\n");
                    sink.finish(1);
                    return;
                }
                boolean ok = KonsoleMacroExecutor.dispatchTap(parts[1], parts[2]);
                sink.write(ok ? "Tap dispatched.\n" : "Tap failed (enable Accessibility service in Macro Lab).\n");
                sink.finish(ok ? 0 : 1);
                return;
            }

            case "swipe": {
                if (parts.length < 3) {
                    sink.write("Usage: macro swipe <x1,y1> <x2,y2> [durationMs]\n");
                    sink.finish(1);
                    return;
                }
                String dur = parts.length >= 4 ? parts[3] : "300";
                boolean ok = KonsoleMacroExecutor.dispatchSwipe(parts[1], parts[2], dur);
                sink.write(ok ? "Swipe dispatched.\n" : "Swipe failed (enable Accessibility service in Macro Lab).\n");
                sink.finish(ok ? 0 : 1);
                return;
            }

            default:
                sink.write("Unknown macro subcommand '" + sub + "'. Run 'macro help'.\n");
                sink.finish(1);
        }
    }

    private static String joinFrom(String[] parts, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static long parsePositiveLong(String raw, long fallback) {
        try {
            long val = Long.parseLong(raw.trim());
            return Math.max(1L, val);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
