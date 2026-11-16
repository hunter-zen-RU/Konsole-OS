package com.termux.app.konsole;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rootless Packet Capture (PCAP) Service powered by Android VpnService.
 * Writes standard libpcap binary files (LINKTYPE_RAW = 101) to $konsole/captures/
 * so captured traffic can be inspected directly with tcpdump / tshark / Wireshark.
 */
public class KonsolePcapVpnService extends VpnService {

    private static final String TAG = "KonsolePcapVpn";
    private static final String CHANNEL_ID = "konsole_pcap_capture_channel";
    private static final int NOTIF_ID = 7442;

    public static final String ACTION_START = "com.termux.konsole.PCAP_START";
    public static final String ACTION_STOP = "com.termux.konsole.PCAP_STOP";
    public static final String EXTRA_ROUTE_CIDR = "route_cidr";
    public static final String EXTRA_PREFIX_LEN = "prefix_len";

    public static final String CAPTURES_PATH = KonsoleConfigManager.KONSOLE_ROOT_PATH + "/captures";

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static final AtomicLong sPacketCount = new AtomicLong(0);
    private static final AtomicLong sByteCount = new AtomicLong(0);
    private static volatile String sActiveCapturePath = "";
    private static final LinkedList<String> sRecentSummaries = new LinkedList<>();

    private ParcelFileDescriptor mTunFd;
    private Thread mReaderThread;
    private FileOutputStream mPcapOut;

    public static boolean isRunning() {
        return sRunning.get();
    }

    public static long getPacketCount() {
        return sPacketCount.get();
    }

    public static long getByteCount() {
        return sByteCount.get();
    }

    public static String getActiveCapturePath() {
        return sActiveCapturePath;
    }

    public static synchronized List<String> getRecentSummaries() {
        return new ArrayList<>(sRecentSummaries);
    }

    private static synchronized void appendSummary(String summary) {
        if (summary == null || summary.isEmpty()) return;
        sRecentSummaries.addFirst(summary);
        while (sRecentSummaries.size() > 80) {
            sRecentSummaries.removeLast();
        }
    }

    public static List<File> listSavedCaptures() {
        File dir = new File(CAPTURES_PATH);
        if (!dir.isDirectory()) return Collections.emptyList();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".pcap"));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, files);
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    public static void stopCapture(Context context) {
        if (context == null || !sRunning.get()) return;
        Intent intent = new Intent(context, KonsolePcapVpnService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopInternal();
            stopSelf();
            return START_NOT_STICKY;
        }
        String routeCidr = intent != null && intent.hasExtra(EXTRA_ROUTE_CIDR)
                ? intent.getStringExtra(EXTRA_ROUTE_CIDR) : "0.0.0.0";
        int prefixLen = intent != null ? intent.getIntExtra(EXTRA_PREFIX_LEN, 0) : 0;
        startCapture(routeCidr, prefixLen);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopInternal();
        super.onDestroy();
    }

    private void startCapture(String routeCidr, int prefixLen) {
        if (sRunning.get()) return;
        startForegroundNotification();

        try {
            File dir = new File(CAPTURES_PATH);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Cannot create captures directory " + CAPTURES_PATH);
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File pcapFile = new File(dir, "capture_" + ts + ".pcap");

            Builder builder = new Builder()
                    .setSession("Konsole Rootless PCAP")
                    .setMtu(1500)
                    .addAddress("10.215.173.1", 32)
                    .addRoute(routeCidr != null && !routeCidr.isEmpty() ? routeCidr : "0.0.0.0", prefixLen);
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
            }

            mTunFd = builder.establish();
            if (mTunFd == null) {
                throw new IOException("VpnService.Builder.establish() returned null (VPN permission missing)");
            }

            mPcapOut = new FileOutputStream(pcapFile);
            mPcapOut.write(buildPcapGlobalHeader(65535, 101)); // 101 = LINKTYPE_RAW (IPv4/IPv6)
            mPcapOut.flush();

            sActiveCapturePath = pcapFile.getAbsolutePath();
            sPacketCount.set(0);
            sByteCount.set(0);
            synchronized (KonsolePcapVpnService.class) {
                sRecentSummaries.clear();
            }
            sRunning.set(true);

            mReaderThread = new Thread(this::readLoop, "KonsolePcapReader");
            mReaderThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed starting PCAP capture: " + e.getMessage(), e);
            stopInternal();
            stopSelf();
        }
    }

    private void readLoop() {
        if (mTunFd == null) return;
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(mTunFd.getFileDescriptor())) {
            while (sRunning.get()) {
                int len = in.read(buf);
                if (len < 0) break;
                if (len == 0) continue;

                long nowMs = System.currentTimeMillis();
                byte[] record = buildPcapPacketRecord(buf, len, nowMs);
                synchronized (this) {
                    if (mPcapOut != null) {
                        mPcapOut.write(record);
                        mPcapOut.flush();
                    }
                }
                sPacketCount.incrementAndGet();
                sByteCount.addAndGet(len);
                appendSummary(summarizeIpPacket(buf, len));
            }
        } catch (Exception e) {
            if (sRunning.get()) {
                Log.w(TAG, "PCAP read loop ended: " + e.getMessage());
            }
        }
    }

    private synchronized void stopInternal() {
        sRunning.set(false);
        if (mReaderThread != null) {
            mReaderThread.interrupt();
            mReaderThread = null;
        }
        if (mTunFd != null) {
            try {
                mTunFd.close();
            } catch (Exception ignored) {
            }
            mTunFd = null;
        }
        if (mPcapOut != null) {
            try {
                mPcapOut.flush();
                mPcapOut.close();
            } catch (Exception ignored) {
            }
            mPcapOut = null;
        }
        stopForeground(true);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Konsole PCAP Capture",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent stopIntent = new Intent(this, KonsolePcapVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle("Konsole Rootless PCAP Active")
                .setContentText("Capturing IP frames to $konsole/captures (Tap to stop)")
                .setContentIntent(stopPi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif);
    }

    /** Builds a 24-byte standard little-endian libpcap global header. */
    public static byte[] buildPcapGlobalHeader(int snapLen, int linkType) {
        ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0xa1b2c3d4); // magic_number
        bb.putShort((short) 2); // version_major
        bb.putShort((short) 4); // version_minor
        bb.putInt(0);           // thiszone
        bb.putInt(0);           // sigfigs
        bb.putInt(snapLen);     // snaplen
        bb.putInt(linkType);    // network (101 = LINKTYPE_RAW)
        return bb.array();
    }

    /** Builds a 16-byte per-packet libpcap record header + raw payload bytes. */
    public static byte[] buildPcapPacketRecord(byte[] packet, int len, long timestampMs) {
        int sec = (int) (timestampMs / 1000L);
        int usec = (int) ((timestampMs % 1000L) * 1000L);
        ByteBuffer bb = ByteBuffer.allocate(16 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(sec);
        bb.putInt(usec);
        bb.putInt(len);
        bb.putInt(len);
        bb.put(packet, 0, len);
        return bb.array();
    }

    /** Parses a raw IPv4/IPv6 frame into a human-readable one-line summary. */
    public static String summarizeIpPacket(byte[] pkt, int len) {
        if (pkt == null || len < 1) return "";
        int version = (pkt[0] >> 4) & 0xF;
        if (version == 4 && len >= 20) {
            int ihl = (pkt[0] & 0xF) * 4;
            int proto = pkt[9] & 0xFF;
            String src = (pkt[12] & 0xFF) + "." + (pkt[13] & 0xFF) + "." + (pkt[14] & 0xFF) + "." + (pkt[15] & 0xFF);
            String dst = (pkt[16] & 0xFF) + "." + (pkt[17] & 0xFF) + "." + (pkt[18] & 0xFF) + "." + (pkt[19] & 0xFF);
            String protoName = proto == 6 ? "TCP" : (proto == 17 ? "UDP" : (proto == 1 ? "ICMP" : "IP(" + proto + ")"));
            if ((proto == 6 || proto == 17) && len >= ihl + 4) {
                int sPort = ((pkt[ihl] & 0xFF) << 8) | (pkt[ihl + 1] & 0xFF);
                int dPort = ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
                return protoName + " " + src + ":" + sPort + " -> " + dst + ":" + dPort + " (" + len + "B)";
            }
            return protoName + " " + src + " -> " + dst + " (" + len + "B)";
        } else if (version == 6 && len >= 40) {
            int nextHdr = pkt[6] & 0xFF;
            return "IPv6 next=" + nextHdr + " (" + len + "B)";
        }
        return "RAW (" + len + "B)";
    }
}
