package com.termux.app.konsole;

import android.app.Application;
import android.widget.TextView;

import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class KonsoleWorkstationProTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        KonsoleSecurityVault.getInstance().clearPassphraseAndUnlock(app);
        KonsoleConfigManager.getInstance().resetDefaultCustomizeXml();
        KonsoleConfigManager.getInstance().setActiveProfile("default");
        KonsolePluginManager.getInstance().ensureSeedFiles(app);
    }

    @Test
    public void testSecurityVaultEncryptionPassphraseAndPanicLockdown() throws Exception {
        KonsoleSecurityVault vault = KonsoleSecurityVault.getInstance();

        Assert.assertFalse(vault.hasPassphrase(app));
        Assert.assertTrue(vault.setPassphrase(app, "kali-rootless-99"));
        Assert.assertTrue(vault.hasPassphrase(app));
        Assert.assertTrue(vault.verifyPassphrase(app, "kali-rootless-99"));
        Assert.assertFalse(vault.verifyPassphrase(app, "wrong-pass"));

        Assert.assertTrue(vault.putSecret(app, "ssh_id", "PRIVATE_KEY_PAYLOAD"));
        Assert.assertTrue(vault.putSecret(app, "api_token", "tok_12345"));
        List<String> keys = vault.listSecretKeys(app);
        Assert.assertEquals(2, keys.size());
        Assert.assertTrue(keys.contains("ssh_id"));
        Assert.assertTrue(keys.contains("api_token"));
        Assert.assertEquals("PRIVATE_KEY_PAYLOAD", vault.getSecret(app, "ssh_id"));

        // Create ephemeral incognito file and legacy external sudo.xml to test panic & migration cleanup
        File incognitoDir = new File(app.getCacheDir(), "konsole_incognito/test_session");
        Assert.assertTrue(incognitoDir.exists() || incognitoDir.mkdirs());
        File tempFile = new File(incognitoDir, "scratch.sh");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write("echo secret\n".getBytes(StandardCharsets.UTF_8));
        }

        File legacySudo = new File(KonsoleConfigManager.CUSTOMIZE_PATH, "sudo.xml");
        try (FileOutputStream fos = new FileOutputStream(legacySudo)) {
            fos.write("<sudo><password>plaintext</password></sudo>".getBytes(StandardCharsets.UTF_8));
        }
        KonsoleSecurityVault.migrateRemoveLegacyExternalSudoXml();
        Assert.assertFalse("Legacy plaintext sudo.xml must be removed", legacySudo.exists());

        // Panic locks vault and wipes incognito cache
        String panicSummary = vault.triggerPanicLockdown(app);
        Assert.assertTrue(panicSummary.contains("Vault locked"));
        Assert.assertTrue(vault.isLocked(app));
        Assert.assertFalse(tempFile.exists());
        Assert.assertNull("Secrets cannot be read while vault is locked", vault.getSecret(app, "ssh_id"));

        // Unlock via passphrase restores access
        Assert.assertFalse(vault.unlockWithPassphrase(app, "nope"));
        Assert.assertTrue(vault.unlockWithPassphrase(app, "kali-rootless-99"));
        Assert.assertFalse(vault.isLocked(app));
        Assert.assertEquals("PRIVATE_KEY_PAYLOAD", vault.getSecret(app, "ssh_id"));

        Assert.assertTrue(vault.deleteSecret(app, "ssh_id"));
        Assert.assertNull(vault.getSecret(app, "ssh_id"));
    }

    @Test
    public void testPcapHeaderRecordAndIpv4TcpSummary() {
        byte[] header = KonsolePcapVpnService.buildPcapGlobalHeader(65535, 101);
        Assert.assertEquals(24, header.length);
        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(0xa1b2c3d4, hb.getInt()); // magic
        Assert.assertEquals(2, hb.getShort());         // major
        Assert.assertEquals(4, hb.getShort());         // minor
        hb.getInt(); // thiszone
        hb.getInt(); // sigfigs
        Assert.assertEquals(65535, hb.getInt());       // snaplen
        Assert.assertEquals(101, hb.getInt());         // LINKTYPE_RAW

        // Construct minimal 40-byte IPv4 + TCP SYN frame: 10.13.37.2:54321 -> 93.184.216.34:443
        byte[] pkt = new byte[40];
        pkt[0] = 0x45; // IPv4, IHL=5 (20 bytes)
        pkt[9] = 6;    // TCP
        pkt[12] = 10; pkt[13] = 13; pkt[14] = 37; pkt[15] = 2;
        pkt[16] = 93; pkt[17] = (byte) 184; pkt[18] = (byte) 216; pkt[19] = 34;
        pkt[20] = (byte) ((54321 >> 8) & 0xff); pkt[21] = (byte) (54321 & 0xff);
        pkt[22] = (byte) ((443 >> 8) & 0xff);   pkt[23] = (byte) (443 & 0xff);

        byte[] record = KonsolePcapVpnService.buildPcapPacketRecord(pkt, pkt.length, 1700000123456L);
        Assert.assertEquals(16 + 40, record.length);
        ByteBuffer rb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(1700000123, rb.getInt());
        Assert.assertEquals(456000, rb.getInt());
        Assert.assertEquals(40, rb.getInt());
        Assert.assertEquals(40, rb.getInt());

        String summary = KonsolePcapVpnService.summarizeIpPacket(pkt, pkt.length);
        Assert.assertEquals("TCP 10.13.37.2:54321 -> 93.184.216.34:443 (40B)", summary);
    }

    @Test
    public void testWorkspaceProfilesDesktopXmlAndProShadeDefaults() {
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();

        Assert.assertTrue(new File(KonsoleConfigManager.FILE_DESKTOP_XML).isFile());
        Assert.assertTrue(cfg.getDesktopBoolean("desktopAutoSwitch", false));
        Assert.assertEquals(13, cfg.getDesktopInt("fontSize", 14, 8, 36));
        Assert.assertEquals(320, cfg.getDesktopInt("drawerWidthDp", 268, 180, 480));

        List<String> profiles = cfg.listProfiles();
        Assert.assertTrue(profiles.contains("default"));
        Assert.assertTrue(profiles.contains("lab"));
        Assert.assertTrue(profiles.contains("stealth"));

        Assert.assertTrue(cfg.setActiveProfile("lab"));
        Assert.assertEquals("lab", cfg.getActiveProfile());
        Assert.assertEquals("#FF5252", cfg.getUiProperty("accentColor", ""));
        Assert.assertTrue(cfg.getExperienceBoolean("keepScreenOn", false));

        String shadeTiles = cfg.getShadeProperty("tiles", "");
        Assert.assertTrue(shadeTiles.contains("workstation"));
        Assert.assertTrue(shadeTiles.contains("pcap"));
        Assert.assertTrue(shadeTiles.contains("daemon_sshd"));
        Assert.assertTrue(shadeTiles.contains("panic"));
    }

    @Test
    public void testExtensionPluginHooksAndSeededNetProbeExtension() {
        KonsolePluginManager pm = KonsolePluginManager.getInstance();
        KonsolePlugin netProbe = pm.findPluginByName("net-probe-ext");
        Assert.assertNotNull("net-probe-ext extension plugin should be seeded", netProbe);
        Assert.assertTrue(netProbe.isExtension());
        Assert.assertFalse(netProbe.isLaunchable());

        List<KonsolePlugin> launchables = pm.getLaunchablePlugins();
        for (KonsolePlugin p : launchables) {
            Assert.assertNotEquals("net-probe-ext", p.dirName);
        }

        boolean foundInExt = false;
        for (KonsolePlugin p : pm.getExtensionPlugins()) {
            if ("net-probe-ext".equals(p.dirName)) {
                foundInExt = true;
                break;
            }
        }
        Assert.assertTrue(foundInExt);

        List<KonsolePlugin.ExtensionShadeTile> extTiles = pm.getExtensionShadeTiles();
        Assert.assertFalse(extTiles.isEmpty());
        Assert.assertEquals("NET ARP", extTiles.get(0).title);

        List<KonsolePlugin> hudChips = pm.getExtensionHudChips();
        Assert.assertFalse(hudChips.isEmpty());
        Assert.assertEquals("ARP", hudChips.get(0).hudChipLabel);
        Assert.assertEquals("kons net arp", hudChips.get(0).hudChipCommand);
    }

    @Test
    public void testDistroNetPortsAndUsbChipsetHelpers() {
        String installCmd = KonsoleDaemonAndDistroManager.buildInstallDistroCommand("kali; rm -rf /");
        Assert.assertEquals("pkg update -y && pkg install -y proot-distro && proot-distro install kalirm-rf", installCmd);

        String loginCmd = KonsoleDaemonAndDistroManager.buildLoginDistroCommand("ubuntu");
        Assert.assertTrue(loginCmd.contains("proot-distro login ubuntu"));
        Assert.assertTrue(loginCmd.contains(":/mnt/konsole"));

        String kexStart = KonsoleDaemonAndDistroManager.buildKexStartCommand(2);
        Assert.assertTrue(kexStart.contains("vncserver :2"));
        Assert.assertTrue(kexStart.contains("-localhost yes"));

        List<Integer> ports = KonsoleNetDiagnostics.parsePortSpec("22,80-82,443,99999");
        Assert.assertEquals(5, ports.size());
        Assert.assertEquals(Integer.valueOf(22), ports.get(0));
        Assert.assertEquals(Integer.valueOf(80), ports.get(1));
        Assert.assertEquals(Integer.valueOf(81), ports.get(2));
        Assert.assertEquals(Integer.valueOf(82), ports.get(3));
        Assert.assertEquals(Integer.valueOf(443), ports.get(4));

        Assert.assertTrue(KonsoleUsbSerialManager.identifyChipset(0x0403, 0x6001, 0).contains("FTDI UART"));
        Assert.assertTrue(KonsoleUsbSerialManager.identifyChipset(0x1A86, 0x7523, 0).contains("CH340"));
        Assert.assertTrue(KonsoleUsbSerialManager.identifyChipset(0x10C4, 0xEA60, 0).contains("CP210x"));
    }

    @Test
    public void testWorkstationAndSettingsActivitiesInflate() {
        KonsoleWorkstationActivity ws = Robolectric.buildActivity(KonsoleWorkstationActivity.class)
                .create()
                .start()
                .resume()
                .get();

        TextView log = ws.findViewById(R.id.workstation_output_log);
        Assert.assertNotNull(log);
        Assert.assertNotNull(ws.findViewById(R.id.btn_pcap_toggle));
        Assert.assertNotNull(ws.findViewById(R.id.btn_incognito_shell));
        Assert.assertNotNull(ws.findViewById(R.id.workstation_panic_header_btn));
        Assert.assertNotNull(ws.findViewById(R.id.btn_vault_lock));

        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class)
                .create()
                .start()
                .resume()
                .get();
        PreferenceFragmentCompat frag = (PreferenceFragmentCompat) settings.getSupportFragmentManager()
                .findFragmentById(R.id.settings);
        Assert.assertNotNull(frag);
        Assert.assertNotNull(frag.findPreference("konsole_workstation"));
        Assert.assertNotNull(frag.findPreference("konsole_dashboard"));
        Assert.assertNotNull(frag.findPreference("konsole_macro_lab"));
        Assert.assertNull(frag.findPreference("termux_api"));
        Assert.assertNull(frag.findPreference("termux_float"));
    }
}
