package com.tpn.adbautoenable;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import androidx.annotation.NonNull;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public class AdbHelper {
    private static final String TAG = "ADBAutoEnable";
    private final Context context;

    public AdbHelper(Context context) {
        this.context = context;

        try {
            // Remove Android's legacy built-in BC provider so the modern bundled version is used
            Security.removeProvider("BC");

            // Insert the modern BouncyCastle and Conscrypt providers
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Security.insertProviderAt(Conscrypt.newProvider(), 2);

            Log.i(TAG, "Security providers initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up security providers", e);
        }
    }

    public boolean pair(String host, int port, String code) {
        SimpleAdbManager manager = null;
        try {
            Log.i(TAG, "Pairing with " + host + ":" + port + " using code: " + code);
            manager = new SimpleAdbManager(context);
            manager.pair(host, port, code);
            Log.i(TAG, "Pairing successful!");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pairing failed", e);
            return false;
        } finally {
            if (manager != null) {
                try {
                    manager.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing manager after pair", e);
                }
            }
        }
    }

    public boolean connect(String host, int port) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            manager.connect(host, port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connect failed", e);
            return false;
        }
    }

    public boolean ensureTclAutoStart(String host, int port, String packageName) {
        if (executeTclAutoStart("127.0.0.1", port, packageName)) {
            return true;
        }
        if (!host.equals("127.0.0.1")) {
            Log.i(TAG, "TCL auto-start setup failed via loopback, retrying via " + host);
            return executeTclAutoStart(host, port, packageName);
        }
        return false;
    }

    private boolean executeTclAutoStart(String host, int port, String packageName) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            manager.connect(host, port);
            executeShell(manager, "appops set " + packageName + " AUTO_START allow");
            String state = executeShell(manager, "appops get " + packageName + " AUTO_START");
            boolean allowed = state.toLowerCase(Locale.ROOT).contains("allow");
            Log.i(TAG, "TCL AUTO_START allowed: " + allowed);
            return allowed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure TCL AUTO_START on " + host + ":" + port, e);
            return false;
        }
    }

    private String executeShell(SimpleAdbManager manager, String command) throws Exception {
        StringBuilder output = new StringBuilder();
        try (AdbStream stream = manager.openStream("shell:" + command);
             InputStream inputStream = stream.openInputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            try {
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    output.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                if (!"Stream closed.".equals(e.getMessage())) {
                    throw e;
                }
                Log.d(TAG, "ADB shell command completed with a closed stream");
            }
        }
        return output.toString();
    }

    public boolean selfGrantPermission(String host, int port, String packageName, String permission) {
        // Try loopback first
        if (executeSelfGrant("127.0.0.1", port, packageName, permission)) {
            return true;
        }
        // Fall back to active host/IP if different (Fixes #8)
        if (!host.equals("127.0.0.1")) {
            Log.i(TAG, "Self-grant failed via loopback, retrying via " + host + "...");
            return executeSelfGrant(host, port, packageName, permission);
        }
        return false;
    }

    private boolean executeSelfGrant(String host, int port, String packageName, String permission) {
        SimpleAdbManager manager = null;
        try {
            Log.i(TAG, "Attempting self-grant on " + host + ":" + port + " for package " + packageName);
            manager = new SimpleAdbManager(context);
            manager.connect(host, port);

            // Check if already granted
            if (checkPermissionGranted(manager, packageName, permission)) {
                Log.i(TAG, "Permission " + permission + " is already granted, skipping grant");
                return true;
            }

            Log.i(TAG, "Connected, sending pm grant shell command...");
            String command = "shell:pm grant " + packageName + " " + permission;
            try (AdbStream stream = manager.openStream(command);
                 InputStream is = stream.openInputStream()) {
                byte[] buffer = new byte[1024];
                while (is.read(buffer) != -1) {
                    // Drain stream completely
                }
            } catch (Exception e) {
                Log.d(TAG, "Stream read completed: " + e.getMessage());
            }

            // Allow PackageManagerService 1 second to apply the permission change
            Log.i(TAG, "Waiting 1000ms for PackageManagerService to process grant...");
            Thread.sleep(1000);

            // Verify permission status
            boolean isGranted = checkPermissionGranted(manager, packageName, permission);
            if (isGranted) {
                Log.i(TAG, "Successfully granted permission " + permission + "!");
                return true;
            } else {
                Log.w(TAG, "pm grant executed but dumpsys returned granted=false");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to grant permission on " + host + ":" + port, e);
            return false;
        } finally {
            if (manager != null) {
                try {
                    manager.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing manager after selfGrant", e);
                }
            }
        }
    }

    /**
     * Switches ADB from its current port to a target TCP port.
     *
     * @param host       The host address (e.g. "192.168.x.x" or "127.0.0.1")
     * @param port       The currently active ADB port (e.g. mDNS paired port)
     * @param targetPort The desired target port (e.g. 5555, 65432, etc.)
     * @return true if command was sent successfully
     */
    public boolean switchToPort(String host, int port, int targetPort) {
        final int maxAttempts = 3;

        Log.i(TAG, "switchToPort: source=" + host + ":" + port
                + ", targetPort=" + targetPort);

        // If the requested listener is already up, do not disturb adbd again.
        if (waitForTcpListener(host, targetPort, 300)) {
            Log.i(TAG, "switchToPort: target port " + targetPort + " is already reachable");
            return true;
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            SimpleAdbManager manager = null;
            boolean serviceOpened = false;
            String response = "";
            try {
                Log.i(TAG, "switchToPort: attempt " + attempt + "/" + maxAttempts
                        + " connecting to " + host + ":" + port);
                manager = new SimpleAdbManager(context);
                manager.connect(host, port);

                // Some Android 14+ wireless-debugging transports need a brief
                // settle period before a local service can be opened.
                Thread.sleep(350);

                Log.i(TAG, "switchToPort: requesting device service tcpip:" + targetPort);
                StringBuilder output = new StringBuilder();
                try (AdbStream stream = manager.openStream("tcpip:" + targetPort);
                     InputStream inputStream = stream.openInputStream()) {
                    serviceOpened = true;
                    byte[] buffer = new byte[256];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        output.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    // A successful tcpip service intentionally restarts adbd and
                    // can tear down the TLS stream before libadb reports EOF.
                    Log.w(TAG, "switchToPort: tcpip service stream closed: " + e.getMessage());
                }

                response = output.toString().trim();
                if (!response.isEmpty()) {
                    Log.i(TAG, "switchToPort: service response: " + response);
                }
            } catch (Exception e) {
                Log.w(TAG, "switchToPort: attempt " + attempt
                        + " could not complete tcpip service request", e);
            } finally {
                if (manager != null) {
                    try {
                        manager.close();
                    } catch (Exception e) {
                        Log.d(TAG, "switchToPort: manager close after tcpip request: "
                                + e.getMessage());
                    }
                }
            }

            // The service's observable contract is that adbd restarts with
            // service.adb.tcp.port set. The TLS stream itself may disappear as
            // part of that restart, so the new listener is the authoritative
            // success signal.
            if (waitForTcpListener(host, targetPort, 6000)) {
                Log.i(TAG, "switchToPort: verified ADB listener at "
                        + host + ":" + targetPort);
                return true;
            }

            if (serviceOpened) {
                Log.w(TAG, "switchToPort: tcpip service opened but target listener "
                        + targetPort + " did not appear");
            } else {
                Log.w(TAG, "switchToPort: tcpip service was not opened on attempt " + attempt);
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        Log.e(TAG, "switchToPort: failed to verify target listener "
                + host + ":" + targetPort);
        return false;
    }

    private boolean waitForTcpListener(String host, int port, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        do {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 300);
                return true;
            } catch (Exception ignored) {
                // Listener may still be restarting.
            }

            if (timeoutMs <= 300) break;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);

        return false;
    }

    /**
     * Backward-compatible wrapper defaulting to port 5555.
     */
    public boolean switchToPort5555(String host, int port) {
        return switchToPort(host, port, 5555);
    }

    private boolean checkPermissionGranted(SimpleAdbManager manager, String packageName, String permission) {
        try {
            Log.i(TAG, "Checking if permission is granted: " + permission);
            String command = "shell:dumpsys package " + packageName + " | grep " + permission;

            StringBuilder sb = new StringBuilder();
            try (AdbStream stream = manager.openStream(command);
                 InputStream inputStream = stream.openInputStream()) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, bytesRead));
                }
            } catch (Exception e) {
                Log.d(TAG, "Stream check read completed: " + e.getMessage());
            }

            String response = sb.toString();
            Log.i(TAG, "Permission check response:\n" + response);
            boolean isGranted = response.contains(permission + ": granted=true");
            Log.i(TAG, "Permission " + permission + " is granted: " + isGranted);
            return isGranted;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission", e);
            return false;
        }
    }

    private static class SimpleAdbManager extends AbsAdbConnectionManager {
        private PrivateKey privateKey;
        private PublicKey publicKey;
        private X509Certificate certificate;
        private final File keyFile;
        private final File pubKeyFile;
        private final File certFile;

        public SimpleAdbManager(Context context) throws Exception {
            Log.i(TAG, "SimpleAdbManager constructor starting");
            setApi(Build.VERSION.SDK_INT);
            keyFile = new File(context.getFilesDir(), "adb_key");
            pubKeyFile = new File(context.getFilesDir(), "adb_key.pub");
            certFile = new File(context.getFilesDir(), "adb_cert");
            Log.i(TAG, "Key files: " + keyFile.getAbsolutePath());
            loadOrGenerateKeyPair();
            Log.i(TAG, "SimpleAdbManager initialized successfully");
        }

        private void loadOrGenerateKeyPair() throws Exception {
            Log.i(TAG, "Loading or generating key pair");
            if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
                Log.i(TAG, "Loading existing key pair and certificate");
                try {
                    // Load private key
                    byte[] privateKeyBytes = readFileBytes(keyFile);
                    PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(privateKeyBytes);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    privateKey = keyFactory.generatePrivate(privateSpec);
                    Log.i(TAG, "Private key loaded");

                    // Load public key
                    byte[] publicKeyBytes = readFileBytes(pubKeyFile);
                    X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(publicKeyBytes);
                    publicKey = keyFactory.generatePublic(publicSpec);
                    Log.i(TAG, "Public key loaded");

                    // Load certificate
                    byte[] certBytes = readFileBytes(certFile);
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
                    Log.i(TAG, "Certificate loaded");

                } catch (Exception e) {
                    Log.e(TAG, "Failed to load existing keys, generating new ones", e);
                    generateNewKeyPairAndCert();
                }

            } else {
                Log.i(TAG, "No existing keys found, generating new ones");
                generateNewKeyPairAndCert();
            }
        }

        private byte[] readFileBytes(File file) throws IOException {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead = fis.read(bytes);
                if (bytesRead != bytes.length) {
                    throw new IOException("Failed to read entire file");
                }
            }
            return bytes;
        }

        private void writeFileBytes(File file, byte[] bytes) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
        }

        private void generateNewKeyPairAndCert() throws Exception {
            Log.i(TAG, "Generating new RSA key pair");
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
            Log.i(TAG, "Key pair generated");

            // Generate self-signed certificate
            Log.i(TAG, "Generating self-signed certificate");
            certificate = generateSelfSignedCertificate(keyPair);
            Log.i(TAG, "Certificate generated");

            // Save keys
            Log.i(TAG, "Saving keys to files");
            writeFileBytes(keyFile, privateKey.getEncoded());
            writeFileBytes(pubKeyFile, publicKey.getEncoded());
            writeFileBytes(certFile, certificate.getEncoded());
            Log.i(TAG, "Keys and certificate saved successfully");
        }

        private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
            X500Name issuer = new X500Name("CN=ADBAutoEnable");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
            Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);
            SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                    issuer,
                    serial,
                    notBefore,
                    notAfter,
                    issuer,
                    publicKeyInfo
            );

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(keyPair.getPrivate());

            X509CertificateHolder certHolder = certBuilder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(certHolder);
        }

        @Override
        @NonNull
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        @NonNull
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        @NonNull
        protected String getDeviceName() {
            return "ADBAutoEnable";
        }
    }
}
