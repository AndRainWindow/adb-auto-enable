package com.tpn.adbautoenable;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Shared local-device discovery. Invoke only from a worker thread. */
@SuppressWarnings("deprecation")
final class AdbDiscovery {
    private static final String TAG = "ADBAutoEnable";
    private static final String CONNECT_TYPE = "_adb-tls-connect._tcp";
    private static final String PAIRING_TYPE = "_adb-tls-pairing._tcp";
    // Prevent boot and web flows from competing for the legacy resolver API.
    private static final Object DISCOVERY_LOCK = new Object();

    private AdbDiscovery() {}

    static AdbEndpoint discover(Context context) {
        return discover(context, CONNECT_TYPE, -1);
    }

    /**
     * Discovers the temporary Android 11+ wireless-debugging pairing service.
     * If the Settings UI supplied a port, prefer an mDNS service advertising
     * that exact port so we do not accidentally pair against a stale service.
     */
    static AdbEndpoint discoverPairing(Context context, int preferredPort) {
        return discover(context, PAIRING_TYPE, preferredPort);
    }

    private static AdbEndpoint discover(Context context, String serviceType, int preferredPort) {
        synchronized (DISCOVERY_LOCK) {
            if (Thread.currentThread().isInterrupted()) return null;
            AdbPortDiagnostic.logTlsPort();
            if (Thread.currentThread().isInterrupted()) return null;
            NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (manager == null) return null;
            WifiManager.MulticastLock multicast = null;
            Session session = new Session(context, manager, preferredPort);
            boolean requested = false;
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    try {
                        multicast = wifi.createMulticastLock("adb-auto-enable-discovery");
                        multicast.setReferenceCounted(false);
                        multicast.acquire();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not acquire multicast lock; continuing NSD discovery", e);
                    }
                }
                Log.i(TAG, "Discovering " + serviceType + "; LAN addresses: "
                        + NetworkUtils.getPhysicalAddresses(context));
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, session);
                requested = true;
                // Collect for the whole window unless an exact preferred port is found.
                session.done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Log.w(TAG, "mDNS discovery failed for " + serviceType, e);
            } finally {
                session.finish();
                if (requested) {
                    try { manager.stopServiceDiscovery(session); }
                    catch (RuntimeException e) { Log.w(TAG, "Could not stop mDNS discovery", e); }
                }
                if (multicast != null && multicast.isHeld()) multicast.release();
            }
            if (Thread.currentThread().isInterrupted()) return null;
            AdbEndpoint result = session.result();
            Log.i(TAG, "mDNS collection finished for " + serviceType + ": "
                    + (result == null ? "no local endpoint" : result));
            return result;
        }
    }

    private static final class Session implements NsdManager.DiscoveryListener {
        final CountDownLatch done = new CountDownLatch(1);
        private final Context context;
        private final NsdManager manager;
        private final int preferredPort;
        private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        private final Map<String, Long> generations = new HashMap<>();
        private long generation;
        private boolean resolving;
        private boolean active = true;
        private AdbEndpoint endpoint;
        private String endpointName;

        Session(Context context, NsdManager manager, int preferredPort) {
            this.context = context;
            this.manager = manager;
            this.preferredPort = preferredPort;
        }

        synchronized void finish() { active = false; queue.clear(); }
        synchronized AdbEndpoint result() { return endpoint; }

        @Override public void onDiscoveryStarted(String type) {
            Log.i(TAG, "mDNS discovery started: " + type);
        }

        @Override public synchronized void onServiceFound(NsdServiceInfo service) {
            if (!active) return;
            generations.put(service.getServiceName(), ++generation);
            queue.add(service);
            resolveNext();
        }

        private synchronized void resolveNext() {
            if (!active || resolving || queue.isEmpty()) return;
            NsdServiceInfo service = queue.remove();
            String name = service.getServiceName();
            Long token = generations.get(name);
            if (token == null) { resolveNext(); return; }
            resolving = true;
            try {
                manager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                        synchronized (Session.this) {
                            Log.w(TAG, "mDNS resolve failed: " + name + " code=" + error);
                            resolving = false;
                            resolveNext();
                        }
                    }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        synchronized (Session.this) {
                            try {
                                if (!active || !token.equals(generations.get(name))) return;
                                InetAddress address = info.getHost();
                                String host = address == null ? null : address.getHostAddress();
                                int port = info.getPort();
                                if (host != null && port > 0 && port <= 65535
                                        && NetworkUtils.isLocalPhysicalAddress(context, host)) {
                                    AdbEndpoint candidate = new AdbEndpoint(host, port);
                                    boolean exactPreferred = preferredPort > 0 && port == preferredPort;
                                    if (endpoint == null || exactPreferred) {
                                        endpoint = candidate;
                                        endpointName = name;
                                    }
                                    Log.i(TAG, "Accepted local ADB service " + name + " at " + candidate
                                            + (exactPreferred ? " (preferred port match)" : ""));
                                    if (exactPreferred) done.countDown();
                                } else {
                                    Log.i(TAG, "Ignored non-LAN/invalid ADB service " + name + " at " + host + ":" + port);
                                }
                            } finally {
                                resolving = false;
                                resolveNext();
                            }
                        }
                    }
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not request mDNS resolution for " + name, e);
                resolving = false;
                resolveNext();
            }
        }

        @Override public synchronized void onServiceLost(NsdServiceInfo service) {
            if (!active) return;
            String name = service.getServiceName();
            generations.remove(name);
            if (name.equals(endpointName)) { endpoint = null; endpointName = null; }
        }
        @Override public void onDiscoveryStopped(String type) {
            Log.i(TAG, "mDNS discovery stopped: " + type);
        }
        @Override public void onStartDiscoveryFailed(String type, int error) {
            Log.w(TAG, "mDNS discovery start failed: " + error);
            done.countDown();
        }
        @Override public void onStopDiscoveryFailed(String type, int error) {
            Log.w(TAG, "mDNS discovery stop failed: " + error);
        }
    }
}
