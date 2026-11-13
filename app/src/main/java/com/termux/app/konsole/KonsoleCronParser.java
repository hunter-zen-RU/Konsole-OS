package com.termux.app.konsole;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight 5-field Cron expression parser & next-fire-time evaluator:
 * Format: "minute hour day-of-month month day-of-week"
 * Supports: *, numbers, comma lists (1,15,30), ranges (9-17), steps (star/5, 0-30/5),
 * and presets: @hourly, @daily, @midnight, @weekly, @every_1m, @every_5m, @every_15m, @every_30m.
 */
public final class KonsoleCronParser {

    private KonsoleCronParser() {
    }

    public static String normalizePreset(String expr) {
        if (expr == null) return "";
        String trimmed = expr.trim().toLowerCase(Locale.US);
        switch (trimmed) {
            case "@every_1m":
                return "* * * * *";
            case "@every_5m":
                return "*/5 * * * *";
            case "@every_15m":
                return "*/15 * * * *";
            case "@every_30m":
                return "*/30 * * * *";
            case "@hourly":
                return "0 * * * *";
            case "@daily":
            case "@midnight":
                return "0 0 * * *";
            case "@weekly":
                return "0 0 * * 0";
            default:
                return expr.trim();
        }
    }

    public static boolean isValid(String rawExpr) {
        String normalized = normalizePreset(rawExpr);
        if (normalized.isEmpty()) return false;
        String[] fields = normalized.split("\\s+");
        if (fields.length != 5) return false;
        try {
            parseField(fields[0], 0, 59);  // minute
            parseField(fields[1], 0, 23);  // hour
            parseField(fields[2], 1, 31);  // day of month
            parseField(fields[3], 1, 12);  // month (1-12)
            parseField(fields[4], 0, 7);   // day of week (0 or 7 = Sunday)
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean matches(String rawExpr, Calendar cal) {
        if (cal == null) return false;
        String normalized = normalizePreset(rawExpr);
        String[] fields = normalized.split("\\s+");
        if (fields.length != 5) return false;

        try {
            boolean[] mins = parseField(fields[0], 0, 59);
            boolean[] hours = parseField(fields[1], 0, 23);
            boolean[] doms = parseField(fields[2], 1, 31);
            boolean[] months = parseField(fields[3], 1, 12);
            boolean[] dows = parseField(fields[4], 0, 7);
            if (dows[7]) dows[0] = true; // treat 7 as Sunday (0)

            int m = cal.get(Calendar.MINUTE);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            int month = cal.get(Calendar.MONTH) + 1;
            int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // Calendar.SUNDAY=1 -> 0

            return mins[m] && hours[h] && doms[dom] && months[month] && dows[dow];
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Computes the next timestamp (epoch ms) strictly after {@code fromTimeMs}
     * (rounded up to the next minute boundary) matching the cron expression.
     * Searches up to 366 days ahead; returns -1L if invalid or unreachable.
     */
    public static long computeNextTriggerTimeMs(String rawExpr, long fromTimeMs) {
        if (!isValid(rawExpr)) return -1L;

        String normalized = normalizePreset(rawExpr);
        String[] fields = normalized.split("\\s+");
        boolean[] mins = parseField(fields[0], 0, 59);
        boolean[] hours = parseField(fields[1], 0, 23);
        boolean[] doms = parseField(fields[2], 1, 31);
        boolean[] months = parseField(fields[3], 1, 12);
        boolean[] dows = parseField(fields[4], 0, 7);
        if (dows[7]) dows[0] = true;

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(fromTimeMs);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MINUTE, 1);

        // Up to 366 days * 24h * 60m = 527,040 minutes
        final int maxIterations = 366 * 24 * 60;
        for (int i = 0; i < maxIterations; i++) {
            int month = cal.get(Calendar.MONTH) + 1;
            if (!months[month]) {
                cal.add(Calendar.MONTH, 1);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                continue;
            }
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
            if (!doms[dom] || !dows[dow]) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                continue;
            }
            int h = cal.get(Calendar.HOUR_OF_DAY);
            if (!hours[h]) {
                cal.add(Calendar.HOUR_OF_DAY, 1);
                cal.set(Calendar.MINUTE, 0);
                continue;
            }
            int m = cal.get(Calendar.MINUTE);
            if (mins[m]) {
                return cal.getTimeInMillis();
            }
            cal.add(Calendar.MINUTE, 1);
        }
        return -1L;
    }

    public static String describe(String rawExpr) {
        if (!isValid(rawExpr)) {
            return "Invalid cron (need 5 fields: min hour dom mon dow, or @hourly/@daily/@every_5m)";
        }
        long next = computeNextTriggerTimeMs(rawExpr, System.currentTimeMillis());
        if (next <= 0) return "Valid cron (no upcoming match within 1 year)";
        SimpleDateFormat fmt = new SimpleDateFormat("EEE HH:mm (yyyy-MM-dd)", Locale.getDefault());
        return "Next run: " + fmt.format(new Date(next));
    }

    private static boolean[] parseField(String field, int min, int max) {
        boolean[] allowed = new boolean[max + 1];
        if (field == null || field.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty cron field");
        }
        String[] parts = field.trim().split(",");
        for (String part : parts) {
            if (part.isEmpty()) throw new IllegalArgumentException("Empty segment");
            int step = 1;
            String rangePart = part;
            int slashIdx = part.indexOf('/');
            if (slashIdx >= 0) {
                rangePart = part.substring(0, slashIdx);
                String stepStr = part.substring(slashIdx + 1);
                step = Integer.parseInt(stepStr);
                if (step <= 0) throw new IllegalArgumentException("Step must be > 0");
            }

            int start;
            int end;
            if ("*".equals(rangePart)) {
                start = min;
                end = max;
            } else if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                start = Integer.parseInt(bounds[0]);
                end = Integer.parseInt(bounds[1]);
                if (start < min || end > max || start > end) {
                    throw new IllegalArgumentException("Out of range: " + rangePart);
                }
            } else {
                start = Integer.parseInt(rangePart);
                if (start < min || start > max) {
                    throw new IllegalArgumentException("Value out of range: " + start);
                }
                end = slashIdx >= 0 ? max : start;
            }

            for (int v = start; v <= end; v += step) {
                allowed[v] = true;
            }
        }
        return allowed;
    }
}
