package defiance.crypto;

import defiance.net.IP;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.*;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.io.pem.PemObject;

import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
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

    public static KeyPair generateKeyPair()
    {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
            kpg.initialize(RSA_KEY_SIZE);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
            throw new IllegalStateException(e.getMessage());
        }
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
        KeyPair keypair = generateKeyPair();
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

    public static PKCS10CertificationRequest generateCertificateSignRequest(String outfile, char[] password, String commonName, PublicKey signee, PrivateKey signer)
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
                new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN="+commonName), signee);
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
        ContentSigner csigner = csBuilder.build(signer);
        PKCS10CertificationRequest csr = p10Builder.build(csigner);
        BufferedWriter w = new BufferedWriter(new FileWriter(outfile));
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
            KeyPair keypair = generateKeyPair();
            PrivateKey myPrivateKey = keypair.getPrivate();
            Certificate cert = generateRootCertificate(password, keypair);
            printCertificate(cert);

            ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
            ks.setCertificateEntry("rootCA", cert);
            ks.store(new FileOutputStream("rootCA.pem"), password);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static KeyPair generateAndSaveKeyPair(String filename, char[] passphrase) throws IOException
    {
        KeyPair pair = generateKeyPair();
        saveKeyPair(filename, passphrase, pair);
        return pair;
    }

    public static void saveKeyPair(String filename, char[] passphrase, KeyPair keypair) throws IOException
    {
        try {
            JceOpenSSLPKCS8EncryptorBuilder encryptorBuilder = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC);
            encryptorBuilder.setRandom(new SecureRandom());
            encryptorBuilder.setPasssword(passphrase);
            OutputEncryptor oe = encryptorBuilder.build();
            JcaPKCS8Generator gen = new JcaPKCS8Generator(keypair.getPrivate(), oe);
            PemObject obj = gen.generate();

            PEMWriter pemWrt = new PEMWriter(new FileWriter(filename));
            pemWrt.writeObject(obj);
            pemWrt.writeObject(keypair.getPublic());
            pemWrt.close();
        } catch (OperatorCreationException e)
        {
            e.printStackTrace();
        }
    }

    public static KeyPair loadKeyPair(String filename, char[] passphrase)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, OperatorCreationException, PKCSException
    {
        PEMParser pairReader = new PEMParser(new FileReader(filename));
        Object priv = pairReader.readObject();
        Object pub = pairReader.readObject();

        InputDecryptorProvider pkcs8Prov = new JceOpenSSLPKCS8DecryptorProviderBuilder().build(passphrase);
        PKCS8EncryptedPrivateKeyInfo encPrivKeyInfo = (PKCS8EncryptedPrivateKeyInfo)priv;
        JcaPEMKeyConverter   converter = new JcaPEMKeyConverter().setProvider("BC");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey)converter.getPrivateKey(encPrivKeyInfo.decryptPrivateKeyInfo(pkcs8Prov));

        // Now convert to Java objects
        KeyFactory keyFactory = KeyFactory.getInstance( "RSA");
        RSAKeyParameters rsa = (RSAKeyParameters) PublicKeyFactory.createKey((SubjectPublicKeyInfo)pub);
        RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getExponent());
        PublicKey publicKey = keyFactory.generatePublic(rsaSpec);
        return new KeyPair(publicKey, privateKey);
    }

    public static KeyPair generateCSR(char[] passphrase, String keyfile, String csrfile) throws IOException
    {
        String msg;
        try {
            KeyPair pair = generateAndSaveKeyPair(keyfile, passphrase);
            generateCSR(passphrase, loadKeyPair(keyfile, passphrase), csrfile);
            return pair;
        } catch (NoSuchAlgorithmException e) {e.printStackTrace(); msg= e.getMessage();}
        catch (InvalidKeySpecException e) {e.printStackTrace();msg= e.getMessage();}
        catch (OperatorCreationException e) {e.printStackTrace();msg= e.getMessage();}
        catch (PKCSException e) {e.printStackTrace();msg= e.getMessage();}
        throw new IllegalStateException(msg);
    }

    public static void generateCSR(char[] password, KeyPair keypair, String outfile)
    {
        try {
            PrivateKey myPrivateKey = keypair.getPrivate();
            String cn = IP.getMyPublicAddress().getHostAddress();
            generateCertificateSignRequest(outfile, password, cn, keypair.getPublic(), myPrivateKey);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static PKCS10CertificationRequest loadCSR(String file) throws IOException
    {
        BufferedReader r = new BufferedReader(new FileReader(file));
        String base64 = r.readLine();
        byte[] csrBytes = Base64.decode(base64);
        return new PKCS10CertificationRequest(csrBytes);
    }

    public static Certificate signDirectoryCertificate(String csrFile, char[] rootPassword)
    {
        try {
            PKCS10CertificationRequest csr = loadCSR(csrFile);
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
            KeyStore ks = getRootKeyStore(rootPassword);
            PrivateKey rootPriv = (PrivateKey) ks.getKey("private", rootPassword);
            Certificate signed = signCertificate(csr, rootPriv, "issuer");
            printCertificate(signed);
            return signed;
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static Certificate signCertificate(PKCS10CertificationRequest csr, PrivateKey priv, String issuerCN)
    {
        try {
            SubjectPublicKeyInfo pkInfo = csr.getSubjectPublicKeyInfo();
            RSAKeyParameters rsa = (RSAKeyParameters) PublicKeyFactory.createKey(pkInfo);
            RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getExponent());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey dirPub = kf.generatePublic(rsaSpec);
            SubjectPublicKeyInfo keyInfo = SubjectPublicKeyInfo.getInstance(dirPub.getEncoded());

            AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA");
            AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);

            AsymmetricKeyParameter foo = PrivateKeyFactory.createKey(priv.getEncoded());

            X509v3CertificateBuilder myCertificateGenerator = new X509v3CertificateBuilder(
                    new X500Name("CN="+issuerCN), new BigInteger("1"),
                    new Date(System.currentTimeMillis()),
                    new Date(System.currentTimeMillis() + 30 * 365 * 24 * 60 * 60 * 1000),
                    csr.getSubject(), keyInfo);

            ContentSigner sigGen = new BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(foo);

            X509CertificateHolder holder = myCertificateGenerator.build(sigGen);
            Certificate signed = new JcaX509CertificateConverter().getCertificate(holder);
            return signed;
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static void printCertificate(Certificate cert)
    {
        try {
            String encoded = Base64.toBase64String(cert.getEncoded());
            for (int i = 0; i <= encoded.length() / 70; i++)
                System.out.println("\"" + encoded.substring(i * 70, Math.min((i + 1) * 70, encoded.length())) + "\" +");
        } catch (CertificateEncodingException e) {e.printStackTrace();}
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

    public static Certificate getRootCertificate()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
        return fact.generateCertificate(new ByteArrayInputStream(rootCA));
    }

    public static KeyStore getRootKeyStore(char[] password)
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
        keyStore.load(new FileInputStream("rootCA.pem"), password);
        return keyStore;
    }

    public static String getCommonName(Certificate cert)
    {
        try {
            X500Name x500name = new JcaX509CertificateHolder((X509Certificate) cert).getSubject();
            RDN cn = x500name.getRDNs(BCStyle.CN)[0];
            return IETFUtils.valueToString(cn.getFirst().getValue());
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
            throw new IllegalStateException(e.getMessage());
        }
    }

    private static final int NUM_DIR_SERVERS = 1;
    private static byte[][] directoryServers = new byte[NUM_DIR_SERVERS][];
    static {
        directoryServers[0] = Base64.decode("MIIEnjCCAoagAwIBAgIBATANBgkqhkiG9w0BAQsFADARMQ8wDQYDVQQDDAZpc3N1ZXIwHh" +
                "cNMTQwMzMwMTIzMzQ5WhcNMTQwNDEzMDYyMDIzWjAUMRIwEAYDVQQDEwkxMC4wLjIuMTUw" +
                "ggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCSobZuAL1ofjUVUf9Ajihve4HUpX" +
                "1i29dpdN9d0mPtMrwrH4jBPD7GErrSwcxHXG7KY/0ELL2bAHhL20XtbrR1D5JCMAMilGbx" +
                "1ARa1jBC5X+SsTKzTJyV7D7yY3g9qSpl8td75rskgZC2ny6gz/m3XI/FAt9SzZNASY49E1" +
                "olqGJaUY/NfRXxtp22XFUvG7nPR0gjNyWRaFtQ64dSrTdsFhBhaP8SldcYu7F4D3TC5kX4" +
                "iTYQXuEiLqZAJOXZgDI/9LBJsk6fHKZ6zF/nsICIiyeRpUVBCx1lrLAbR5YdM91JCwh/HM" +
                "IdUEBEuNnKd78epKj+/kqFrLVjygfByMEzSAMvcvSlZ5xq96uzhd9Kql+WOxplV4hVnqpn" +
                "NFQtzCJ875mB3KecQ/9+BfWJ/CqtTfrqeT3eWyzXjk1xnAhVRpYveJL/GJjIFmWrAVE9zZ" +
                "x/q187hIm159fBy8cPvZ3SYlSfohUMxlHDan6uOIqTz9h20RQv8VA4qntpbRS3l7QHK53w" +
                "7P7NGScPMRm/Bc1KnTmhJVJazInQQU3htYGAByB+f9xK73t5GxfxPhdnAF0/N40wBwfEbr" +
                "gr4T/gD5S0ovp258s/ZWCG7PQ9MbWKPIOpT4W90UYWHNa5RE4S/90EsgAQTWUKYFnvfZx5" +
                "oFh9Bkxr6eSxnRy3Znr/Tfn/TQIDAQABMA0GCSqGSIb3DQEBCwUAA4ICAQCNmt5g3XrGhP" +
                "s8ow4KkRYHD0a49QCldhD/JeMYLSa8OcZHAqH+/kgxzEwT/ZBdmA63iM7sQsgnEzNmmELL" +
                "XQmVOJlGTir6Fpm0HaxzWhctIU0VdnChO6BWsxeOicDP1U67ZeRBsmu/rfWu+B7tLilWGo" +
                "vB1C7gHTbJ9sW+T/jx0Js+MbNTAcH/MoYy2+GrEjoAUDEFHQu7JnkfDOReFTTpAiNMCEHS" +
                "Ok/UKK3r2aMGI/tdVjBhqiYip9jP0Vv9vcgeDz0spPlil9cNPMyfJ1TkW3cYQYioiAMeqN" +
                "tLmiOF4wVxEY/TtxOQcCHR+PiBHgZdbDNP9/s66j5LaWeCmZ9quPoz6dV7TTRERgB6WRjc" +
                "i7W6T6etY3AXalN/GwMQAQ/fi4gAfD2NxMpft0Nn2tlzqsN9OyHuppzqCR2s2GOvz4JyP/" +
                "rbVqiduqlJicdWbdiUGXnzpeQwEn2evTcuGPioyzkufIJjAP8zzpfeUbM8ReLeBfEvBu22" +
                "ucKW/LQCYty5GnHPTBUu3gvfk+7Ub2xHvs2Vjc8ExPIIwxrij9fk+Fl40BRjiWs9qUnrac" +
                "dzcvSLBTnvd+mEyrQaSYRbm17/6XoDHwY6kkVWf4fLtSr26R/WCiMiA9R+FuBj9PFuh+bH" +
                "1qhqORKdFWmGqQYBfsANFi73q5WsYZ3PlGTah515/A==");
    }

    // Directory server certificates are signed by the root key
    public static Certificate[] getDirectoryServerCertificates()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        Certificate[] dirs = new Certificate[NUM_DIR_SERVERS];
        for (int i =0; i < NUM_DIR_SERVERS; i++)
        {
            CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
            dirs[i] = fact.generateCertificate(new ByteArrayInputStream(directoryServers[i]));
        }
        return dirs;
    }
}
