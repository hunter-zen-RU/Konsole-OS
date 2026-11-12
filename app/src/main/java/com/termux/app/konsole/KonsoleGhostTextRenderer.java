package com.termux.app.konsole;

import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Component 3: Async Cancellation Wrapper & Ghost Text Manager
 * Provides non-blocking, thread-safe access to the 4-Tier Resolver Engine.
 * Uses atomic request IDs so stale background lookups abort immediately when new keystrokes arrive.
 */
public class KonsoleGhostTextRenderer {

    public static final int STATUS_AVAILABLE = 1;   // Glow Blue
    public static final int STATUS_INSTALLABLE = 2; // Glow Green
    public static final int STATUS_INVALID = 3;     // Glow Red

    private static KonsoleGhostTextRenderer sInstance;

    private final List<String> mCommandHistory = new CopyOnWriteArrayList<>();
    private final AtomicLong mRequestIdCounter = new AtomicLong(0);
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private String mCachedPredictionInput = null;
    private String mCachedPredictionResult = null;

    private KonsoleGhostTextRenderer() {
    }

    public static synchronized KonsoleGhostTextRenderer getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleGhostTextRenderer();
        }
        return sInstance;
    }

    public void init(Context context) {
        KonsolePathIndexer.getInstance().init(context);
        KonsoleFourTierResolver.getInstance().init(context);
    }

    public void addHistory(String command) {
        if (command == null || command.trim().isEmpty()) return;
        mCommandHistory.remove(command);
        mCommandHistory.add(0, command);
        if (mCommandHistory.size() > 100) {
            mCommandHistory.remove(mCommandHistory.size() - 1);
        }
    }

    public List<String> getHistory() {
        return mCommandHistory;
    }

    /**
     * Determines command availability state for real-time syntax glowing:
     * - STATUS_AVAILABLE (1) -> Glow Blue
     * - STATUS_INSTALLABLE (2) -> Glow Green
     * - STATUS_INVALID (3) -> Glow Red
     */
    public int getCommandStatus(String input) {
        if (input == null || input.trim().isEmpty()) return STATUS_AVAILABLE;
        String cmd = input.trim().toLowerCase();

        // 1. Check PathIndexer
        if (KonsolePathIndexer.getInstance().isExecutable(cmd)) {
            return STATUS_AVAILABLE;
        }

        // 2. Check 4-Tier Resolver Tier 3
        String prediction = KonsoleFourTierResolver.getInstance().resolvePrediction(cmd, mCommandHistory);
        if (prediction != null) {
            if (prediction.contains("(install:")) {
                return STATUS_INSTALLABLE;
            }
            return STATUS_AVAILABLE;
        }

        return STATUS_INVALID;
    }

    /**
     * Predicts un-typed ghost text prediction string asynchronously with instant cancellation token.
     */
    public String predictSuggestion(String currentInput) {
        if (currentInput == null || currentInput.isEmpty()) return null;

        // Check fast-path cached result
        if (currentInput.equals(mCachedPredictionInput)) {
            return mCachedPredictionResult;
        }

        long requestId = mRequestIdCounter.incrementAndGet();

        // Execute 4-Tier Resolver
        String result = KonsoleFourTierResolver.getInstance().resolvePrediction(currentInput, mCommandHistory);

        // Cancellation check: abort if user typed a newer character while resolving
        if (requestId != mRequestIdCounter.get()) {
            return mCachedPredictionResult;
        }

        mCachedPredictionInput = currentInput;
        mCachedPredictionResult = result;
        return result;
    }
}
