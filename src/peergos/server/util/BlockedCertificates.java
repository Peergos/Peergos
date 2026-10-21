package peergos.server.util;

import peergos.shared.util.*;

import javax.net.ssl.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.logging.*;

/** Rejects TLS chains containing a certificate, or a certificate's key, that has been compromised but which the
 *  platform trust store still accepts. Unlike the JDK's blocked.certs this also covers the runtimes we bundle, the
 *  user's own JDK and Android, none of which we can patch.
 */
public class BlockedCertificates {
    private static final Logger LOG = Logger.getLogger(BlockedCertificates.class.getName());

    /** SHA-256 of the DER encoded certificate */
    private static final Set<String> BLOCKED_CERTS = Set.of(
            // eMudhra intermediates whose keys were generated with weak entropy (2026-10-20), each issued both by
            // emSign Root TLS/CS CA - G3 and by Sectigo's AAA Certificate Services
            "42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493", // EM DV TLS CA - G3B
            "45A883F349AEDFD5F3A649B5498FFB121712BA8B448F3F2A91DBC5A766FCDDBE", // EM DV TLS CA - G3B (AAA)
            "98B896625C0C230ADEEC3EB2CF6AAD5001ECF9156A68644E82374CE5833BC25B", // EM OV TLS CA - G3B
            "5F1DED4DD590AF74631290CF073B122A2AAC6A1FA479778A0BD43579BD832699", // EM OV TLS CA - G3B (AAA)
            "E7568FE6E12331F4FD7DE61DA4C7559D0F8EB192A218D4741A3BA75184333838", // EM EV TLS CA - G3B
            "579480AEA166290BD3C643589E795FC0D0E54E86505D180F1DFF99EFD3199512", // EM EV TLS CA - G3B (AAA)
            "0164D7CEAE5BB909C272AF151FF44B1177AE9B47D9424B94F1D9558C30934D19", // EM DV TLS CA - G3C
            "62B7F9868263F72F6911FC78CEAF72C1979158B7368BF648683BB2AA39B02E72", // EM DV TLS CA - G3C (AAA)
            "CB6E15165AC802E9B3F993F16C25D41F4E81C21EBDE71AE1B012AC00BF9C7C3F", // EM OV TLS CA - G3C
            "7485B51C0ED192878FA1D2F948FB529AE1842CC5086C4B67F7BE5B2039DE9489", // EM OV TLS CA - G3C (AAA)
            "432EC25F0D28DE9006D97BF752F17BBB2CD41F669933FCB072D9A1148D0261F3", // EM EV TLS CA - G3C
            "FD9D5C2DBBD4995AB69B8D50C439D9527B00529199C2FD01B2174EB58F07021F", // EM EV TLS CA - G3C (AAA)
            "9E39DE2881273ACC225A149C72BA3E0F51424963669AB633EE1DC7DF5DB067B4", // EM OV CS CA - G3B
            "A496E23AC0CAB8155FC9B117B97EAE368EFCBB9041AEC8804D5A308D2D01C2D8", // EM OV CS CA - G3B (AAA)
            "4B2460A466F95FD40113BCDCCA2CEB29B3B093921DE45474DA7DF48874C630B1", // EM OV CS CA - G3C
            "84112972B8E59D14560AE7E7119654474AAD119990CF3722BFC1E3B91615E1FD"  // EM OV CS CA - G3C (AAA)
    );

    /** SHA-256 of the DER encoded SubjectPublicKeyInfo, catching any other certificate for a compromised key */
    private static final Set<String> BLOCKED_KEYS = Set.of(
            "EBED6475C2C2D80FCB199F3FE8DD6E6B3CE60B77EB15D4E42C3DEC0C6F5B5105", // EM DV TLS CA - G3B
            "43BFFA19D6EF651451F6841E019931E96F6F2EAEFF28AB4B6F1CE4576DE2717B", // EM OV TLS CA - G3B
            "E0254C7FCDC265E2F0F8A428DABB75AB4AE039B4872D62F5EE57F0877D5A8A41", // EM EV TLS CA - G3B
            "49DD353DC2B919A3770645117D0E60C16115E7D1BC259EEFE734B00EA434FE5F", // EM DV TLS CA - G3C
            "63ECEFEE431D49523BC1136C4E72D0206A477086CAF606CD5CF025686C109CF8", // EM OV TLS CA - G3C
            "5C0660A2B164910D8FF589C18E14552E9E0EC237DCEFE2CB736D41A4811F81A0", // EM EV TLS CA - G3C
            "EC4FFA1AD0AC88081314B7AAFFEF6A91F97CD57291BB5BA686C5415F5EB4F6B6", // EM OV CS CA - G3B
            "92C063D67E8F14DB71D288E11266FFDAF69CBF115F7BAB14F3BC2F68D25E0E30"  // EM OV CS CA - G3C
    );

    private static TrustManager[] trustManagers;
    private static boolean installed = false;

    /** Make the blocklist apply to SSLContext.getDefault(), and so to every HttpClient and HttpsURLConnection
     *  that doesn't set its own context. Call before any of them are created. */
    public static synchronized void install() {
        if (installed)
            return;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers(), null);
            SSLContext.setDefault(context);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            installed = true;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't install certificate blocklist", e);
        }
    }

    /** The platform's default trust managers, wrapped to reject blocked certificates */
    public static synchronized TrustManager[] trustManagers() {
        if (trustManagers != null)
            return trustManagers;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            trustManagers = Arrays.stream(tmf.getTrustManagers())
                    .map(tm -> {
                        if (tm instanceof X509ExtendedTrustManager ext)
                            return new Blocking(ext);
                        if (tm instanceof X509TrustManager)
                            LOG.severe("Certificate blocklist not applied to " + tm.getClass().getName());
                        return tm;
                    })
                    .toArray(TrustManager[]::new);
            return trustManagers;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't load default trust managers", e);
        }
    }

    public static X509ExtendedTrustManager trustManager() {
        return Arrays.stream(trustManagers())
                .filter(tm -> tm instanceof Blocking)
                .map(tm -> (Blocking) tm)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X509 trust manager"));
    }

    public static boolean isBlocked(X509Certificate cert) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            if (BLOCKED_CERTS.contains(ArrayOps.bytesToHex(sha256.digest(cert.getEncoded())).toUpperCase()))
                return true;
            return BLOCKED_KEYS.contains(ArrayOps.bytesToHex(sha256.digest(cert.getPublicKey().getEncoded())).toUpperCase());
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkChain(X509Certificate[] chain) throws CertificateException {
        if (chain == null)
            return;
        for (X509Certificate cert : chain) {
            if (isBlocked(cert)) {
                LOG.warning("Rejected TLS chain containing blocked certificate " + cert.getSubjectX500Principal());
                throw new CertificateException("Untrusted certificate: " + cert.getSubjectX500Principal());
            }
        }
    }

    private static class Blocking extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager target;

        public Blocking(X509ExtendedTrustManager target) {
            this.target = target;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkChain(chain);
            target.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkChain(chain);
            target.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkChain(chain);
            target.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkChain(chain);
            target.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkChain(chain);
            target.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkChain(chain);
            target.checkServerTrusted(chain, authType, engine);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return target.getAcceptedIssuers();
        }
    }
}
