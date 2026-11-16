package com.termux.app.konsole;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Konsole OS Pro Workstation Hub:
 * Unrooted Kali NetHunter-inspired control plane for Proot distros, KeX VNC,
 * local daemon sockets, Wireless ADB, rootless PCAP sniffer, network/TLS/RF recon,
 * USB-OTG serial communication, workspace profiles, and hardware-backed Vault.
 */
public class KonsoleWorkstationActivity extends AppCompatActivity {

    private TextView mOutputLog;
    private TextView mProfileStatus;
    private TextView mDistroStatus;
    private EditText mDistroInput;
    private LinearLayout mDaemonsContainer;
    private TextView mPcapStatus;
    private TextView mPcapToggleBtn;
    private EditText mTargetHostInput;
    private EditText mTargetPortsInput;
    private EditText mUsbPayloadInput;
    private TextView mVaultStatus;
    private EditText mVaultKeyInput;
    private EditText mVaultValInput;

    private Runnable mPendingPermissionAction;

    private final ActivityResultLauncher<Intent> mVpnConsentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startPcapService();
                } else {
                    appendOutput("[PCAP] VPN consent denied by user.");
                }
            });

    private final ActivityResultLauncher<String[]> mPermsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            grants -> {
                Runnable action = mPendingPermissionAction;
                mPendingPermissionAction = null;
                if (action != null) {
                    action.run();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_konsole_workstation);

        View rootView = findViewById(R.id.workstation_root);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        View col = findViewById(R.id.workstation_content_column);
        if (col != null) {
            int maxPx = Math.round(680 * getResources().getDisplayMetrics().density);
            int availPx = getResources().getDisplayMetrics().widthPixels - Math.round(32 * getResources().getDisplayMetrics().density);
            if (availPx > maxPx && col.getLayoutParams() != null) {
                col.getLayoutParams().width = maxPx;
            }
        }

        KonsoleConfigManager.getInstance().init(this);
        bindViews();
        wireActions();
        refreshAllStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllStates();
    }

    private void bindViews() {
        mOutputLog = findViewById(R.id.workstation_output_log);
        mOutputLog.setMovementMethod(new ScrollingMovementMethod());
        mProfileStatus = findViewById(R.id.workstation_profile_status);
        mDistroStatus = findViewById(R.id.workstation_distro_status);
        mDistroInput = findViewById(R.id.workstation_distro_input);
        mDaemonsContainer = findViewById(R.id.workstation_daemons_container);
        mPcapStatus = findViewById(R.id.workstation_pcap_status);
        mPcapToggleBtn = findViewById(R.id.btn_pcap_toggle);
        mTargetHostInput = findViewById(R.id.workstation_target_host);
        mTargetPortsInput = findViewById(R.id.workstation_target_ports);
        mUsbPayloadInput = findViewById(R.id.workstation_usb_payload);
        mVaultStatus = findViewById(R.id.workstation_vault_status);
        mVaultKeyInput = findViewById(R.id.workstation_vault_key);
        mVaultValInput = findViewById(R.id.workstation_vault_val);
    }

    private void wireActions() {
        ImageButton backBtn = findViewById(R.id.workstation_back_button);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        findViewById(R.id.workstation_panic_header_btn).setOnClickListener(v -> {
            String summary = KonsoleSecurityVault.getInstance().triggerPanicLockdown(this);
            refreshVaultStatus();
            refreshPcapStatus();
            appendOutput("[PANIC LOCKDOWN] " + summary);
            Toast.makeText(this, "Panic lockdown engaged", Toast.LENGTH_SHORT).show();
        });

        // Profiles & Incognito
        findViewById(R.id.btn_profile_default).setOnClickListener(v -> switchProfile("default"));
        findViewById(R.id.btn_profile_lab).setOnClickListener(v -> switchProfile("lab"));
        findViewById(R.id.btn_profile_stealth).setOnClickListener(v -> switchProfile("stealth"));
        findViewById(R.id.btn_incognito_shell).setOnClickListener(v -> launchIncognitoSession());

        // Proot & KeX
        findViewById(R.id.btn_distro_install).setOnClickListener(v -> {
            String distro = textOf(mDistroInput, "debian");
            dispatchToTerminal(KonsoleDaemonAndDistroManager.buildInstallDistroCommand(distro));
        });
        findViewById(R.id.btn_distro_login).setOnClickListener(v -> {
            String distro = textOf(mDistroInput, "debian");
            dispatchToTerminal(KonsoleDaemonAndDistroManager.buildLoginDistroCommand(distro));
        });
        findViewById(R.id.btn_kex_start).setOnClickListener(v -> {
            dispatchToTerminal(KonsoleDaemonAndDistroManager.buildKexStartCommand(1));
        });
        findViewById(R.id.btn_kex_vnc).setOnClickListener(v -> {
            if (!KonsoleDaemonAndDistroManager.launchVncViewerUri(this, 5901)) {
                appendOutput("[KeX VNC] No VNC viewer app registered for vnc://127.0.0.1:5901. Install bVNC/RealVNC or connect to 127.0.0.1:5901.");
            }
        });

        findViewById(R.id.btn_adb_status).setOnClickListener(v ->
                runAsyncTask(() -> KonsoleAdbBridge.getStatusReport(this)));
        findViewById(R.id.btn_dev_settings).setOnClickListener(v ->
                KonsoleAdbBridge.openWirelessDebugSettings(this));

        // PCAP
        mPcapToggleBtn.setOnClickListener(v -> {
            if (KonsolePcapVpnService.isRunning()) {
                KonsolePcapVpnService.stopCapture(this);
                refreshPcapStatus();
                appendOutput("[PCAP] Stopped capture. Saved captures in " + KonsolePcapVpnService.CAPTURES_PATH);
            } else {
                Intent prep = VpnService.prepare(this);
                if (prep != null) {
                    mVpnConsentLauncher.launch(prep);
                } else {
                    startPcapService();
                }
            }
        });
        findViewById(R.id.btn_pcap_list).setOnClickListener(v -> {
            List<File> files = KonsolePcapVpnService.listSavedCaptures();
            StringBuilder sb = new StringBuilder("[PCAP Captures (" + files.size() + ")]\n");
            for (File f : files) {
                sb.append("  ").append(f.getName()).append(" (").append(f.length()).append(" B)\n");
            }
            List<String> recent = KonsolePcapVpnService.getRecentSummaries();
            if (!recent.isEmpty()) {
                sb.append("\n[Recent Frames]\n");
                int max = Math.min(10, recent.size());
                for (int i = 0; i < max; i++) {
                    sb.append("  ").append(recent.get(i)).append("\n");
                }
            }
            appendOutput(sb.toString());
            refreshPcapStatus();
        });

        // Net & RF Recon
        findViewById(R.id.btn_net_ifaces).setOnClickListener(v ->
                runAsyncTask(KonsoleNetDiagnostics::getInterfacesAndArpReport));
        findViewById(R.id.btn_net_scan).setOnClickListener(v -> {
            String host = textOf(mTargetHostInput, "127.0.0.1");
            String ports = textOf(mTargetPortsInput, "22,80,443,5555,5901,8022,8080");
            runAsyncTask(() -> KonsoleNetDiagnostics.scanTcpPorts(host, KonsoleNetDiagnostics.parsePortSpec(ports), 400));
        });
        findViewById(R.id.btn_net_tls).setOnClickListener(v -> {
            String host = textOf(mTargetHostInput, "example.com");
            runAsyncTask(() -> KonsoleNetDiagnostics.inspectTlsCertificate(host, 443));
        });
        findViewById(R.id.btn_net_wifi).setOnClickListener(v -> runWithLocationPermission(() -> {
            appendOutput(KonsoleNetDiagnostics.scanWifiBss(this));
        }));
        findViewById(R.id.btn_net_ble).setOnClickListener(v -> runWithBlePermissions(() -> {
            appendOutput("[BLE] Scanning LE GATT advertisements for 3s...");
            KonsoleNetDiagnostics.scanBleAsync(this, 3000L, report -> runOnUiThread(() -> appendOutput(report)));
        }));
        findViewById(R.id.btn_net_mdns).setOnClickListener(v -> {
            appendOutput("[mDNS] Discovering _http._tcp. services...");
            KonsoleNetDiagnostics.discoverMdnsServicesAsync(this, "_http._tcp.", 3000L,
                    report -> runOnUiThread(() -> appendOutput(report)));
        });

        // USB OTG Serial
        findViewById(R.id.btn_usb_list).setOnClickListener(v ->
                appendOutput(KonsoleUsbSerialManager.formatDeviceReport(this)));
        findViewById(R.id.btn_usb_uart).setOnClickListener(v -> {
            List<KonsoleUsbSerialManager.UsbDeviceSummary> devs = KonsoleUsbSerialManager.listAttachedDevices(this);
            if (devs.isEmpty()) {
                appendOutput("[USB] No USB OTG devices attached.");
                return;
            }
            String payload = textOf(mUsbPayloadInput, "AT");
            runAsyncTask(() -> KonsoleUsbSerialManager.exchangeCdcSerial(this, devs.get(0).device, payload, 115200, 1200));
        });

        // Keystore Vault
        findViewById(R.id.btn_vault_put).setOnClickListener(v -> {
            String key = textOf(mVaultKeyInput, "");
            String val = mVaultValInput.getText() != null ? mVaultValInput.getText().toString() : "";
            if (key.isEmpty()) {
                appendOutput("[Vault] Enter a secret key name.");
                return;
            }
            boolean ok = KonsoleSecurityVault.getInstance().putSecret(this, key, val);
            mVaultValInput.setText("");
            refreshVaultStatus();
            appendOutput(ok ? "[Vault] Encrypted secret saved under key '" + key + "'."
                    : "[Vault] Failed saving secret.");
        });
        findViewById(R.id.btn_vault_ls).setOnClickListener(v -> {
            List<String> keys = KonsoleSecurityVault.getInstance().listSecretKeys(this);
            appendOutput("[Vault Keys (" + keys.size() + ")]\n  " + (keys.isEmpty() ? "(none)" : String.join(", ", keys)));
        });
        findViewById(R.id.btn_vault_lock).setOnClickListener(v -> {
            KonsoleSecurityVault vault = KonsoleSecurityVault.getInstance();
            if (vault.isLocked(this)) {
                String candidate = mVaultValInput.getText() != null ? mVaultValInput.getText().toString() : "";
                if (!vault.hasPassphrase(this)) {
                    vault.setLocked(this, false);
                    appendOutput("[Vault] Unlocked.");
                } else if (vault.unlockWithPassphrase(this, candidate)) {
                    mVaultValInput.setText("");
                    appendOutput("[Vault] Unlocked via passphrase.");
                } else {
                    appendOutput("[Vault] Invalid passphrase (enter passphrase in Secret Value box and tap LOCK/UNLOCK).");
                }
            } else {
                String pass = mVaultValInput.getText() != null ? mVaultValInput.getText().toString() : "";
                if (!vault.hasPassphrase(this) && pass.length() >= 4) {
                    vault.setPassphrase(this, pass);
                    mVaultValInput.setText("");
                }
                vault.setLocked(this, true);
                appendOutput("[Vault] Vault locked.");
            }
            refreshVaultStatus();
        });
    }

    private void startPcapService() {
        Intent intent = new Intent(this, KonsolePcapVpnService.class).setAction(KonsolePcapVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshPcapStatus();
        appendOutput("[PCAP] Capture started -> " + KonsolePcapVpnService.CAPTURES_PATH);
    }

    private void switchProfile(String profile) {
        if (KonsoleConfigManager.getInstance().setActiveProfile(profile)) {
            KonsoleFishBridge.syncStateFiles(this);
            TermuxActivity.updateTermuxActivityStyling(this, false);
            refreshProfileStatus();
            appendOutput("[Profile] Activated workspace profile: " + profile);
        }
    }

    private void launchIncognitoSession() {
        android.content.Context uiCtx = KonsoleFishBridge.getInstance().getUiContext(this);
        if (uiCtx instanceof TermuxActivity) {
            ((TermuxActivity) uiCtx).startIncognitoSession();
            Toast.makeText(this, "Started ephemeral incognito session", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            dispatchToTerminal("kons incognito");
        }
    }

    private void dispatchToTerminal(String shellCmd) {
        android.content.Context uiCtx = KonsoleFishBridge.getInstance().getUiContext(this);
        if (uiCtx instanceof TermuxActivity) {
            TerminalSession s = ((TermuxActivity) uiCtx).getCurrentSession();
            if (s != null) {
                String cmd = "\u0015" + shellCmd + "\n";
                byte[] b = cmd.getBytes(StandardCharsets.UTF_8);
                s.write(b, 0, b.length);
                finish();
                return;
            }
        }
        appendOutput("[Terminal Command]\n  " + shellCmd);
    }

    private void refreshAllStates() {
        refreshProfileStatus();
        refreshDistroAndDaemons();
        refreshPcapStatus();
        refreshVaultStatus();
    }

    private void refreshProfileStatus() {
        mProfileStatus.setText("Active profile: " + KonsoleConfigManager.getInstance().getActiveProfile()
                + " (profiles: " + String.join(", ", KonsoleConfigManager.getInstance().listProfiles()) + ")");
    }

    private void refreshDistroAndDaemons() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        boolean hasProot = KonsoleDaemonAndDistroManager.isProotDistroAvailable(prefix);
        List<String> distros = KonsoleDaemonAndDistroManager.getInstalledDistros(prefix);
        mDistroStatus.setText("proot-distro: " + (hasProot ? "READY" : "NOT INSTALLED")
                + " · Installed: " + (distros.isEmpty() ? "(none)" : String.join(", ", distros)));

        runAsyncTaskQuiet(() -> KonsoleDaemonAndDistroManager.probeCoreDaemons(prefix), list -> {
            mDaemonsContainer.removeAllViews();
            for (KonsoleDaemonAndDistroManager.DaemonStatus ds : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));

                TextView labelTv = new TextView(this);
                labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                labelTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                labelTv.setTextSize(11.5f);
                labelTv.setTextColor(ds.running ? 0xFF00FF88 : 0xFFE6EDF3);
                labelTv.setText(ds.label + " (:" + ds.port + ") · "
                        + (ds.running ? "LISTENING" : (ds.installed ? "STOPPED" : "NOT INSTALLED")));

                TextView actionBtn = new TextView(this);
                actionBtn.setMinimumWidth(dp(72));
                actionBtn.setMinimumHeight(dp(48));
                actionBtn.setGravity(Gravity.CENTER);
                actionBtn.setPadding(dp(10), 0, dp(10), 0);
                actionBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                actionBtn.setTextSize(11f);
                actionBtn.setBackgroundResource(R.drawable.bg_dashboard_chip);
                actionBtn.setText(ds.running ? "STOP" : "START");
                actionBtn.setTextColor(ds.running ? 0xFFFF5252 : 0xFF00E5FF);
                actionBtn.setContentDescription((ds.running ? "Stop " : "Start ") + ds.label);
                actionBtn.setOnClickListener(v -> {
                    TerminalSession s = null;
                    android.content.Context uiCtx = KonsoleFishBridge.getInstance().getUiContext(this);
                    if (uiCtx instanceof TermuxActivity) {
                        s = ((TermuxActivity) uiCtx).getCurrentSession();
                    }
                    KonsoleDaemonAndDistroManager.startOrStopDaemonInSession(this, ds.id, !ds.running, s);
                    appendOutput("[Daemon] Requested " + (!ds.running ? "start" : "stop") + " for " + ds.id);
                    mDaemonsContainer.postDelayed(this::refreshDistroAndDaemons, 500);
                });

                row.addView(labelTv);
                row.addView(actionBtn);
                mDaemonsContainer.addView(row);
            }
        });
    }

    private void refreshPcapStatus() {
        boolean running = KonsolePcapVpnService.isRunning();
        long pkts = KonsolePcapVpnService.getPacketCount();
        long bytes = KonsolePcapVpnService.getByteCount();
        mPcapStatus.setText("PCAP Sniffer: " + (running ? "CAPTURING" : "IDLE")
                + " · " + pkts + " pkts · " + bytes + " B");
        mPcapStatus.setTextColor(running ? 0xFF00FF88 : 0xFFFFD166);
        mPcapToggleBtn.setText(running ? "STOP PCAP" : "START PCAP");
        mPcapToggleBtn.setTextColor(running ? 0xFFFF5252 : 0xFF00E5FF);
    }

    private void refreshVaultStatus() {
        KonsoleSecurityVault vault = KonsoleSecurityVault.getInstance();
        boolean locked = vault.isLocked(this);
        boolean hasPass = vault.hasPassphrase(this);
        int count = vault.listSecretKeys(this).size();
        mVaultStatus.setText("Vault: " + (locked ? "LOCKED" : "UNLOCKED")
                + " · Passphrase: " + (hasPass ? "SET" : "NONE")
                + " · Secrets: " + count);
        mVaultStatus.setTextColor(locked ? 0xFFFF5252 : 0xFF00FF88);
    }

    private void runWithLocationPermission(Runnable action) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            action.run();
        } else {
            mPendingPermissionAction = action;
            mPermsLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    private void runWithBlePermissions(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scanOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean connOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            if (!scanOk || !connOk) {
                mPendingPermissionAction = action;
                mPermsLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                });
                return;
            }
        }
        runWithLocationPermission(action);
    }

    private interface ResultTask<T> {
        T run();
    }

    private interface UiConsumer<T> {
        void accept(T value);
    }

    private void runAsyncTask(ResultTask<String> task) {
        appendOutput("Running...");
        new Thread(() -> {
            String res;
            try {
                res = task.run();
            } catch (Exception e) {
                res = "Error: " + e.getMessage();
            }
            final String out = res;
            runOnUiThread(() -> appendOutput(out));
        }, "KonsoleWorkstationTask").start();
    }

    private <T> void runAsyncTaskQuiet(ResultTask<T> task, UiConsumer<T> onUi) {
        new Thread(() -> {
            try {
                T res = task.run();
                runOnUiThread(() -> onUi.accept(res));
            } catch (Exception ignored) {
            }
        }, "KonsoleWorkstationProbe").start();
    }

    private void appendOutput(String text) {
        if (mOutputLog == null || text == null) return;
        mOutputLog.setText(text.trim());
    }

    private static String textOf(EditText et, String fallback) {
        if (et == null || et.getText() == null) return fallback;
        String s = et.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
