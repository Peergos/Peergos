package defiance.crypto;

import sun.security.x509.*;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.Date;

public class SSL
{
    public static final String AUTH = "RSA";
    public static final int RSA_KEY_SIZE = 4096;

    public static KeyStore getKeyStore(char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException, NoSuchProviderException, SignatureException
    {
        // never store certificates to disk, if program is restarted, we generate a new certificate and rejoin network
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
        kpg.initialize(RSA_KEY_SIZE);
        KeyPair keypair = kpg.generateKeyPair();
        PrivateKey myPrivateKey = keypair.getPrivate();

        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + 365 * 86400000l);
        CertificateValidity interval = new CertificateValidity(from, to);
        info.set(X509CertInfo.VALIDITY, interval);
        BigInteger sn = new BigInteger(64, new SecureRandom());
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));

        X500Name owner = new X500Name("EMAIL=hello.NSA.GCHQ.ASIO@goodluck.com");
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
        info.set(X509CertInfo.KEY, new CertificateX509Key(keypair.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.SHA512_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        // Sign the cert to identify the algorithm that's used.
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(myPrivateKey, "MD5WithRSA");

        ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
        ks.store(new FileOutputStream("sslkeystore"), password);
        return ks;
    }
}
