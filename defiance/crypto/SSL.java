package defiance.crypto;

import defiance.net.IP;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.encoders.Base64;

import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;

public class SSL
{
    public static final String AUTH = "RSA";
    public static final int RSA_KEY_SIZE = 4096;
    public static final String SSL_KEYSTORE_FILENAME = "sslkeystore";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static KeyStore getKeyStore(char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        // store certificates to disk, otherwise an upgrade would induce a DDOS on the directory servers to sign all the new certificates
        KeyStore ks = KeyStore.getInstance("JKS");
        if (new File(SSL_KEYSTORE_FILENAME).exists())
        {
            ks.load(new FileInputStream(SSL_KEYSTORE_FILENAME), password);
            return ks;
        }
        ks.load(null, password);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
        kpg.initialize(RSA_KEY_SIZE);
        KeyPair keypair = kpg.generateKeyPair();
        PrivateKey myPrivateKey = keypair.getPrivate();
        java.security.PublicKey myPublicKey = keypair.getPublic();
        Certificate cert = generateRootCertificate(password, keypair);

        // synchronously contact a directory server to sign our certificate


        Certificate rootCert = getRootCertificate();
        KeyStore.Entry root = new KeyStore.TrustedCertificateEntry(rootCert);
        ks.setEntry("rootca", root, null);
        ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
        ks.setKeyEntry("public", myPublicKey, password, new Certificate[]{cert});
        ks.store(new FileOutputStream("sslkeystore"), password);
        return ks;
    }

    public static Certificate generateRootCertificate(char[] password, KeyPair keypair)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        return generateCertificate(password, IP.getMyPublicAddress().getHostAddress(), keypair.getPublic(), keypair.getPrivate());
    }

    public static Certificate generateCertificate(char[] password, String commonName, PublicKey signee, PrivateKey signer)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password);

        Date from = new Date();
        Date to = new Date(from.getTime() + 365 * 86400000l);
        BigInteger sn = new BigInteger(64, new SecureRandom());

        X500NameBuilder builder = new X500NameBuilder(RFC4519Style.INSTANCE);
        builder.addRDN(RFC4519Style.cn, commonName);
        builder.addRDN(RFC4519Style.c, "AU");
        builder.addRDN(RFC4519Style.o, "Peergos");
        builder.addRDN(RFC4519Style.l, "Melbourne");
        builder.addRDN(RFC4519Style.st, "Victoria");
        builder.addRDN(PKCSObjectIdentifiers.pkcs_9_at_emailAddress, "hello.NSA.GCHQ.ASIO@goodluck.com");

        System.out.println("SAN = " + commonName);
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(builder.build(), sn, from, to, builder.build(), signee);
        GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, commonName));
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.17"), false, subjectAltName);

        ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").build(signer);
        X509CertificateHolder certHolder = certGen.build(sigGen);
        return new JcaX509CertificateConverter().getCertificate(certHolder);
    }

    public static PKCS10CertificationRequest generateCertificateSignRequest(char[] password, String commonName, PublicKey signee, PrivateKey signer)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password);

        X500NameBuilder builder = new X500NameBuilder(RFC4519Style.INSTANCE);
        builder.addRDN(RFC4519Style.cn, commonName);
        builder.addRDN(RFC4519Style.c, "AU");
        builder.addRDN(RFC4519Style.o, "Peergos");
        builder.addRDN(RFC4519Style.l, "Melbourne");
        builder.addRDN(RFC4519Style.st, "Victoria");
        builder.addRDN(PKCSObjectIdentifiers.pkcs_9_at_emailAddress, "hello.NSA.GCHQ.ASIO@goodluck.com");

        PKCS10CertificationRequestBuilder p10Builder =
                new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=Requested Test Certificate"), signee);
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
        ContentSigner csigner = csBuilder.build(signer);
        PKCS10CertificationRequest csr = p10Builder.build(csigner);
        BufferedWriter w = new BufferedWriter(new FileWriter("dirCSR.pem"));
        w.write(Base64.toBase64String(csr.getEncoded()));
        w.flush();
        w.close();
        return csr;
    }

    public static void generateAndSaveRootCertificate(char[] password)
    {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, password);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
            kpg.initialize(RSA_KEY_SIZE);
            KeyPair keypair = kpg.generateKeyPair();
            PrivateKey myPrivateKey = keypair.getPrivate();
            Certificate cert = generateRootCertificate(password, keypair);
            String encoded = Base64.toBase64String(cert.getEncoded());
            for (int i=0; i <= encoded.length()/70; i++)
                System.out.println("\"" + encoded.substring(i*70, Math.min((i+1)*70, encoded.length())) + "\" +");

            ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
            ks.setCertificateEntry("rootCA", cert);
            ks.store(new FileOutputStream("rootCA.pem"), password);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void generateDirectoryCertificateAndCSR(char[] password)
    {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
            kpg.initialize(RSA_KEY_SIZE);
            KeyPair keypair = kpg.generateKeyPair();
            PrivateKey myPrivateKey = keypair.getPrivate();

            String cn = IP.getMyPublicAddress().getHostAddress();
//            Certificate cert = generateCertificate(password, cn, keypair.getPublic(), null);
            generateCertificateSignRequest(password, cn, keypair.getPublic(), myPrivateKey);
//            String encoded = Base64.toBase64String(cert.getEncoded());
//            for (int i=0; i <= encoded.length()/70; i++)
//                System.out.println("\"" + encoded.substring(i*70, Math.min((i+1)*70, encoded.length())) + "\" +");

//            ks.setCertificateEntry("dirserver", cert);
//            ks.store(new FileOutputStream("dirpublic.pem"), password);
            String encoded = Base64.toBase64String(myPrivateKey.getEncoded());
            BufferedWriter w = new BufferedWriter(new FileWriter("dirprivate.pem"));
            w.write(encoded);
            w.flush();
            w.close();
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static Certificate signDirectoryCertificate(String csrFIle, char[] rootPassword)
    {
        try {
            BufferedReader r = new BufferedReader(new FileReader(csrFIle));
            String base64 = r.readLine();
            byte[] csrBytes = Base64.decode(base64);
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(csrBytes);
            return signDirectoryCertificate(rootPassword, csr);
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static Certificate signDirectoryCertificate(char[] rootPassword, PKCS10CertificationRequest csr)
    {
        try {
            SubjectPublicKeyInfo pkInfo = csr.getSubjectPublicKeyInfo();
            RSAKeyParameters rsa = (RSAKeyParameters) PublicKeyFactory.createKey(pkInfo);
            RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getExponent());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey dirPub = kf.generatePublic(rsaSpec);

            AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA");
            AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);

            KeyStore ks = getRootKeyStore(rootPassword);
            PrivateKey rootCA = (PrivateKey) ks.getKey("private", rootPassword);
            AsymmetricKeyParameter foo = PrivateKeyFactory.createKey(rootCA.getEncoded());
            SubjectPublicKeyInfo keyInfo = SubjectPublicKeyInfo.getInstance(dirPub.getEncoded());

            X509v3CertificateBuilder myCertificateGenerator = new X509v3CertificateBuilder(
                    new X500Name("CN=issuer"), new BigInteger("1"),
                    new Date(System.currentTimeMillis()),
                    new Date(System.currentTimeMillis() + 30 * 365 * 24 * 60 * 60 * 1000),
                    csr.getSubject(), keyInfo);

            ContentSigner sigGen = new BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(foo);

            X509CertificateHolder holder = myCertificateGenerator.build(sigGen);
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] rootCA = Base64.decode("MIIFrTCCA5WgAwIBAgIIfKBkrHTAKBEwDQYJKoZIhvcNAQELBQAwgYsxEjAQBgNVBAMMCT" +
            "EwLjAuMi4xNTELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZXJnb3MxEjAQBgNVBAcMCU1l" +
            "bGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG9w0BCQEMIGhlbGxvLk5TQS" +
            "5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMB4XDTE0MDMyOTEzMDIxN1oXDTE1MDMyOTEzMDIx" +
            "N1owgYsxEjAQBgNVBAMMCTEwLjAuMi4xNTELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZX" +
            "Jnb3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG" +
            "9w0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMIICIjANBgkqhkiG9w" +
            "0BAQEFAAOCAg8AMIICCgKCAgEAoghXdRxwmjST8VUDu9iy+lI/rmHzMsv1ikYgtpgsQ36d" +
            "mhYR6PuZVFlrgGawNQ65DnwPs3w1S7G93xC0pHDIXZEWkw2Y0oW9GzFe+1h19Yah/Wer43" +
            "B5TKhVkdeBzIuh97LaJiCvFx3pcItIlSwgMR7plaZk6ut1+0JLFjGxHAcBZTdYC2DOOOmo" +
            "atKKOyBB7YqLhfbe5UCHOo4QcsthB11vFF6lmtrkgCxL5xEK7MLtdv8Gi6xnNi11D1+JeT" +
            "fUtnH1rOFez/L/p3xYk+Y5X+hRmHKpa0eHZYbvnobjKhNV1pgkIJuF8cmSZFBwVIRfg5lR" +
            "fo1/bKUXYhOKAbY7CmK72P780BayzkJgfzsIcVNg2FGXgzxaAlVz2JR1xzNFBXDv2guHBE" +
            "tvMvPxjz2kXiKu0DScU2M2cgNTivE7hRm7nWEOA9QHi/0BWjViogPJyBczA+IYGVBdPiMM" +
            "avTmdQ0/2lHqlZjyCzmb3Xo/oQZ//5zjurSolLt5Sco1jRy3OFv/vgy+S3rF6IESaP+Tm8" +
            "7FEPGzi8SbOAnNcQButQykpYRE4/Ly6IIjZAgXAolc8vAbLnId1RquS8Qz1Cfx1JzjDT+a" +
            "LjY9brdRAnIgh+ct0R6wyvjkLF41TvkNymB2ogBGYwXCU8w5PkjXdqSjasJfsMy2BFXYFR" +
            "3u/jVrgLMCAwEAAaMTMBEwDwYDVR0RBAgwBocECgACDzANBgkqhkiG9w0BAQsFAAOCAgEA" +
            "nX6Q22ReAERlwEjDJClzkZFPODAmn+oueFc9XgyVCpB1li3nmEskeAatWazsEarVi9Zosw" +
            "gfHUiEEoItv2b8BgWblXbq5s3j8aFKHM+/a3hcmDi+K1mYdrexMrpixMp3dtJtlxVAqOsj" +
            "ZGEN7vrEYJNGmhEshP4R4xoke3b2qBtbhNMnSYlXNrGIjbQpG6sqKncPzZCUxbtiK81VVh" +
            "50CPnG0gWuUFGJXkmLjKV9sN8EaBZAIqp41LQPv6dIlIKgAUt8YPuq5+UsS3Q2pBKk9HLu" +
            "9v/Aaf6F/n79y6k++aF7/seoq+7CaqNfa1NdKZmyHxSZ5bVNm3pU93ShMxMTTSo8DA/Aa3" +
            "Fs144OMWycEwwxeWMxMRQiKC1Ik6m2eBH91sdMxVwaDqEyRVReeApJMnUYt/nxCkPo9BAM" +
            "XoCkSV2in19MH8pQ688d2aMwaF4neIEx/Nkr8j2L98WPxfcjcDQpLskpGv/42SHBRmacDK" +
            "XKPadprsVev2EtI4CcxbeNsi90k7I/YbBKOI4kDVePU6HndttmUKKECFWI3PAIqc+uPTd/" +
            "bSQTqerx2I7wa9dPqDQ4/5WC3qCXMExt+8IMyLSdE6wWdaAWwharFwf6KDaGYIm8nhiLFF" +
            "p48/YW/ZxXUmRrNwQuValEJ8r3YnRbhpsl+jzxqL+S9UgchfOeMEI=");

    private static Certificate getRootCertificate()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
        ByteArrayInputStream input = new ByteArrayInputStream(rootCA);
        keyStore.load(input, "test".toCharArray());
        return keyStore.getCertificate("rootCA");
    }

    private static KeyStore getRootKeyStore(char[] password)
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
        keyStore.load(new FileInputStream("rootCA.pem"), password);
        return keyStore;
    }

    private static final int NUM_DIR_SERVERS = 1;
    private static byte[][] directoryServers = new byte[NUM_DIR_SERVERS][];
    static {
        directoryServers[0] = Base64.decode("aaaa");
    }

    // Directory server certificates are signed by the root key
    public static Certificate[] getDirectoryServerCertificates()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        Certificate[] dirs = new Certificate[NUM_DIR_SERVERS];
        for (int i =0; i < NUM_DIR_SERVERS; i++)
        {
            KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
            ByteArrayInputStream input = new ByteArrayInputStream(directoryServers[i]);
            keyStore.load(input, "dirserver".toCharArray());
            dirs[i] = keyStore.getCertificate("dirserver");
        }
        return dirs;
    }
}
