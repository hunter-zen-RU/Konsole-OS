package com.termux.app.konsole;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Sandboxed runner activity for Konsole OS launchable plugins (.html, .js, .sh, .java, .kt).
 * Uses WebView + Konsole JS bridge for .html/.js and Termux prefix shell/toolchains for CLI scripts.
 * Strictly avoids dynamic DEX/JAR/SO classloading.
 */
public class KonsolePluginRunnerActivity extends AppCompatActivity {

    public static final String EXTRA_PLUGIN_DIR = "konsole_plugin_dir";

    private KonsolePlugin mPlugin;
    private WebView mWebView;
    private TextView mConsoleOutput;
    private ScrollView mConsoleScroll;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String dirPath = getIntent() != null ? getIntent().getStringExtra(EXTRA_PLUGIN_DIR) : null;
        if (dirPath == null || dirPath.trim().isEmpty()) {
            finish();
            return;
        }
        mPlugin = KonsolePlugin.fromDirectory(new File(dirPath));
        if (mPlugin == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080C10);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF0D1117);
        header.setPadding(dp(14), dp(10), dp(10), dp(10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(this);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextColor(0xFF00E5FF);
        titleTv.setTextSize(14f);
        titleTv.setText(mPlugin.name + "  v" + mPlugin.version);

        TextView subTv = new TextView(this);
        subTv.setTypeface(Typeface.MONOSPACE);
        subTv.setTextColor(0xFF8B949E);
        subTv.setTextSize(10.5f);
        subTv.setText("[" + mPlugin.build.toUpperCase() + "] " + mPlugin.startingFile);

        titleCol.addView(titleTv);
        titleCol.addView(subTv);
        header.addView(titleCol);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("CLOSE");
        closeBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        closeBtn.setTextColor(0xFFFF7B72);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setMinWidth(dp(64));
        closeBtn.setMinHeight(dp(48));
        closeBtn.setPadding(dp(12), 0, dp(12), 0);
        closeBtn.setContentDescription("Close plugin runner");
        closeBtn.setOnClickListener(v -> finish());
        header.addView(closeBtn);

        root.addView(header);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(0xFF1F2D3D);
        root.addView(divider);

        String ext = mPlugin.getEntryExtension();
        File entryFile = mPlugin.getEntryFile();

        if ("html".equals(ext) || "htm".equals(ext) || "js".equals(ext)) {
            mWebView = new WebView(this);
            mWebView.setBackgroundColor(0xFF080C10);
            WebSettings ws = mWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(false);
            mWebView.addJavascriptInterface(new KonsoleJsBridge(), "Konsole");
            mWebView.setWebViewClient(new WebViewClient());

            if ("html".equals(ext) || "htm".equals(ext)) {
                mWebView.setWebChromeClient(new WebChromeClient());
                mWebView.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                root.addView(mWebView);
                if (entryFile != null && entryFile.isFile()) {
                    mWebView.loadUrl("file://" + entryFile.getAbsolutePath());
                }
            } else {
                // JS plugin console runner
                addConsoleOutputView(root);
                mWebView.setLayoutParams(new LinearLayout.LayoutParams(1, 1));
                root.addView(mWebView);
                mWebView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage msg) {
                        appendConsole(msg.message() + "\n");
                        return true;
                    }
                });
                String jsBody = entryFile != null ? KonsolePluginManager.readFileUtf8(entryFile) : "";
                String htmlWrapper = "<!DOCTYPE html><html><body style='background:#080C10;color:#00FF88;'>"
                        + "<script>\n"
                        + "window.onerror = function(m,u,l){ console.error(m + ' (line ' + l + ')'); };\n"
                        + jsBody
                        + "\n</script></body></html>";
                mWebView.loadDataWithBaseURL("file://" + mPlugin.directory.getAbsolutePath() + "/",
                        htmlWrapper, "text/html", "UTF-8", null);
            }
        } else {
            addConsoleOutputView(root);
            runCliOrSourcePlugin(entryFile, ext);
        }

        setContentView(root);
    }

    private void addConsoleOutputView(LinearLayout root) {
        mConsoleScroll = new ScrollView(this);
        mConsoleScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mConsoleScroll.setPadding(dp(14), dp(14), dp(14), dp(14));

        mConsoleOutput = new TextView(this);
        mConsoleOutput.setTypeface(Typeface.MONOSPACE);
        mConsoleOutput.setTextColor(0xFF00FF88);
        mConsoleOutput.setTextSize(12.5f);
        mConsoleOutput.setText("[Konsole Plugin Runner] Starting " + mPlugin.name + "...\n\n");
        mConsoleScroll.addView(mConsoleOutput);
        root.addView(mConsoleScroll);
    }

    private void appendConsole(String text) {
        if (mConsoleOutput == null) return;
        runOnUiThread(() -> {
            mConsoleOutput.append(text);
            if (mConsoleScroll != null) {
                mConsoleScroll.post(() -> mConsoleScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void runCliOrSourcePlugin(File entryFile, String ext) {
        if (entryFile == null || !entryFile.isFile()) {
            appendConsole("Error: Starting file '" + mPlugin.startingFile + "' not found.\n");
            return;
        }
        new Thread(() -> {
            try {
                String binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String[] cmd;
                if ("sh".equals(ext)) {
                    File shBin = new File(binDir, "sh");
                    String shell = shBin.canExecute() ? shBin.getAbsolutePath() : "/system/bin/sh";
                    cmd = new String[]{shell, entryFile.getAbsolutePath()};
                } else if ("java".equals(ext)) {
                    File javaBin = new File(binDir, "java");
                    if (!javaBin.canExecute()) {
                        appendConsole("Java runtime not found in $PREFIX/bin.\nInstall via: pkg install openjdk-17\n\nEntry preview:\n"
                                + KonsolePluginManager.readFileUtf8(entryFile));
                        return;
                    }
                    cmd = new String[]{javaBin.getAbsolutePath(), entryFile.getAbsolutePath()};
                } else if ("kt".equals(ext)) {
                    File kotlinBin = new File(binDir, "kotlinc");
                    if (!kotlinBin.canExecute()) {
                        appendConsole("Kotlin compiler not found in $PREFIX/bin.\nInstall via: pkg install kotlin\n\nEntry preview:\n"
                                + KonsolePluginManager.readFileUtf8(entryFile));
                        return;
                    }
                    cmd = new String[]{kotlinBin.getAbsolutePath(), "-script", entryFile.getAbsolutePath()};
                } else {
                    appendConsole(KonsolePluginManager.readFileUtf8(entryFile));
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(mPlugin.directory);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("konsole", KonsoleConfigManager.KONSOLE_ROOT_PATH);
                env.put("KONSOLE_PLUGIN_DIR", mPlugin.directory.getAbsolutePath());
                env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                env.put("PATH", binDir + ":" + System.getenv("PATH"));

                Process p = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        appendConsole(line + "\n");
                    }
                }
                int rc = p.waitFor();
                appendConsole("\n[Exited with status " + rc + "]\n");
            } catch (Exception e) {
                appendConsole("Execution error: " + e.getMessage() + "\n");
            }
        }, "KonsolePluginCliRunner").start();
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    public class KonsoleJsBridge {
        @JavascriptInterface
        public String getVariable(String key) {
            Map<String, String> all = new HashMap<>(KonsoleTelemetryManager.getInstance().getLiveVariables());
            all.putAll(KonsoleConfigManager.getInstance().getUserVariables());
            all.put("konsole", KonsoleConfigManager.KONSOLE_ROOT_PATH);
            return all.getOrDefault(key, "");
        }

        @JavascriptInterface
        public String getAllVariables() {
            Map<String, String> all = new HashMap<>(KonsoleTelemetryManager.getInstance().getLiveVariables());
            all.putAll(KonsoleConfigManager.getInstance().getUserVariables());
            all.put("konsole", KonsoleConfigManager.KONSOLE_ROOT_PATH);
            return new JSONObject(all).toString();
        }

        @JavascriptInterface
        public String getPluginManifest() {
            return mPlugin.manifestFile != null ? KonsolePluginManager.readFileUtf8(mPlugin.manifestFile) : "{}";
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(KonsolePluginRunnerActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String execCommand(String command) {
            final StringBuilder out = new StringBuilder();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            KonsoleCommandDispatcher.getInstance().executeBridgeCommand(KonsolePluginRunnerActivity.this, command,
                    new KonsoleCommandDispatcher.CommandSink() {
                        @Override
                        public void write(String text) {
                            if (text != null) {
                                out.append(text.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", ""));
                            }
                        }

                        @Override
                        public void finish(int exitCode) {
                            latch.countDown();
                        }
                    });
            try {
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(KonsolePluginRunnerActivity.this::finish);
        }
    }
}
