package defiance.crypto;

import defiance.directory.DirectoryServer;
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
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
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
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        if (new File(SSL_KEYSTORE_FILENAME).exists())
        {
            ks.load(new FileInputStream(SSL_KEYSTORE_FILENAME), password);
            return ks;
        }
        ks.load(null, password);
        KeyPair keypair = generateKeyPair();
        PKCS10CertificationRequest csr = generateCSR(password, keypair, "storageCSR.pem");
        PrivateKey myPrivateKey = keypair.getPrivate();
        java.security.PublicKey myPublicKey = keypair.getPublic();

        // make rootCA a trust source
        Certificate rootCert = getRootCertificate();
        KeyStore.Entry root = new KeyStore.TrustedCertificateEntry(rootCert);
        ks.setEntry("rootca", root, null);

        Certificate[] dirs = SSL.getDirectoryServerCertificates();
        Certificate cert;
        while (true) {
            Certificate dir = dirs[new SecureRandom().nextInt() % dirs.length];
            ks.setCertificateEntry("dir", dir);

            // synchronously contact a directory server to sign our certificate
            String address = SSL.getCommonName(dir);
            URL target = new URL("http", address, DirectoryServer.PORT, "/sign");
            System.out.println("sending CSR to " + target.toString());
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setDoOutput(true);
//            conn.setDoInput(true);
            conn.setRequestMethod("PUT");
            OutputStream out = conn.getOutputStream();
            byte[] raw = csr.getEncoded();
            out.write(raw);
            out.close();

            InputStream in = conn.getInputStream();
            byte[] buf = new byte[4*1024];
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            while (true)
            {
                int r = in.read(buf);
                if (r < 0)
                    break;
                bout.write(buf, 0, r);
            }
            CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
            cert = fact.generateCertificate(new ByteArrayInputStream(bout.toByteArray()));
            break;
        }
        ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
//        ks.setKeyEntry("public", myPublicKey, password, new Certificate[]{cert});
        ks.store(new FileOutputStream(SSL_KEYSTORE_FILENAME), password);
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

    public static PKCS10CertificationRequest generateCSR(char[] password, KeyPair keypair, String outfile)
    {
        try {
            PrivateKey myPrivateKey = keypair.getPrivate();
            String cn = IP.getMyPublicAddress().getHostAddress();
            return generateCertificateSignRequest(outfile, password, cn, keypair.getPublic(), myPrivateKey);
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
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

    private static byte[] rootCA = Base64.decode("MIIFrjCCA5agAwIBAgIJAJ98aTF/iUu5MA0GCSqGSIb3DQEBCwUAMIGLMRIwEAYDVQQDDA" +
            "kxMC4wLjIuMTUxCzAJBgNVBAYTAkFVMRAwDgYDVQQKDAdQZWVyZ29zMRIwEAYDVQQHDAlN" +
            "ZWxib3VybmUxETAPBgNVBAgMCFZpY3RvcmlhMS8wLQYJKoZIhvcNAQkBDCBoZWxsby5OU0" +
            "EuR0NIUS5BU0lPQGdvb2RsdWNrLmNvbTAeFw0xNDAzMzExNDE0MTFaFw0xNTAzMzExNDE0" +
            "MTFaMIGLMRIwEAYDVQQDDAkxMC4wLjIuMTUxCzAJBgNVBAYTAkFVMRAwDgYDVQQKDAdQZW" +
            "VyZ29zMRIwEAYDVQQHDAlNZWxib3VybmUxETAPBgNVBAgMCFZpY3RvcmlhMS8wLQYJKoZI" +
            "hvcNAQkBDCBoZWxsby5OU0EuR0NIUS5BU0lPQGdvb2RsdWNrLmNvbTCCAiIwDQYJKoZIhv" +
            "cNAQEBBQADggIPADCCAgoCggIBAJNauILsFZY9aw2hljfzqBKXZZgV/OANDfExQ8wY0Leg" +
            "flBcXS8VezmA6+5A6t0KyUatN0t0+YUMT8v2o51L5U9gOUBtttf6hBHJDKCklC4hw8p/Oc" +
            "ZUgGAG8IEiTX0qr7/gnDMYmgUlGRkaII3OQ2xaOgLuHck7i9eCz8zd5Yl3vmOD5Z5PHl80" +
            "1l79gq8jvNFNv/pF6WLXrpylx7Z4ia185po3fE8JDXpH9n+yx/y+YwdR2sR7J/jqwNv6u4" +
            "/D4TRileQruVHqff2eV8MAwl1HACKh3aOw7Q6urr/uTn9i6yEkmauYjrr03K4Wtju5UtsQ" +
            "3+G7evBEbWi2lwr0bftGVCmu0w29I8q2UQwss3iA+ZqbHDBVn4yBxIOvJCux8mbqoGGV7O" +
            "6+GlxQXYgSiTyZnHKdsp4rJafSGJYwzeoli0Jrda8LW6b5ueWe/dJYelUcDO//rreCf75W" +
            "0VRSfG+ZwoRMODNbmUeL/Of0D6LUoT+tTUR29q+rKMTlf66zbiRzdqtjGtx+Nz4u58B+ue" +
            "DNYj78R+UWjEWX53dhmz+GJCDZ4HUsXxwFEpVcI6w4bYGoa2i1Lk7oClk8sMHUU1gftZw9" +
            "P3CFA6eQJqODTPUwmXT+HnWRtocoX28hj239FP8ZRcBV9zXhjr4E8B3NL7S5qYKs0vuGH6" +
            "ycWeugvUurAgMBAAGjEzARMA8GA1UdEQQIMAaHBAoAAg8wDQYJKoZIhvcNAQELBQADggIB" +
            "AB+6a0MPUUCwsL5/9jV5c/XUEXY6rjb9O0nAEU6FUMNDjcWvMNtx4iifkUGUwqLYFcAWoV" +
            "wzYIeRSPqOho0nTjG5870w1dzbbjNSQUNov5lSzMAiVL3Ck8bi2rIZhekQyDOnsqbp7ZG+" +
            "Rqw9cXHD6ftGrSd7TOzxHtQjGC6Oi39uTj4JIDtyyU0NInVY2wM7VzdFZJOcZ/BuuTgFtY" +
            "+Dl7XlkUo19EnSobxY7EJ0wpk7Jlv4qyKk8aa6ID8Ed3OPjtGvbO+w1kAZS1JvTOwB3AnP" +
            "sdBuV1kw3JXHzXyhKVkt+9KgrjBfgCybeO5RqR0HvfrZK85zFgjLiEcXEeHkbkCMXEAgEe" +
            "hToAI19S+lwACOGfE2snNmoXPIh3QwY17vCA/m9sj0ttcDrTeW7xRtRFzljBN0IYBlehZZ" +
            "THUqcDhpLut7ASTWquF1Wvu93TiPdzKilajlKj7fIsngo3o5+WhpzseH3s7l6/YC9GfQys" +
            "nkyjUWnhPem2ctCmvKdOzjlrnLG/KxUYLqLaRZzwBPRAFTvNiqFgC4ewy/OrvL7Xb6OCI2" +
            "gIxSkRmdDUFaUMfkIV4+OwFn9eldn95gy+GRvF40Vk6hdOImQ8ED6B5LqcntVKXv0oBaIH" +
            "ffcBlt6Dqm1kEWzaUM3UIc6zGs6t0p212A0NBkpRRzfagirRfemqFP");

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

    public static String getCommonName(PKCS10CertificationRequest csr)
    {
        X500Name x500name = csr.getSubject();
        RDN cn = x500name.getRDNs(BCStyle.CN)[0];
        return IETFUtils.valueToString(cn.getFirst().getValue());
    }

    private static final int NUM_DIR_SERVERS = 1;
    private static byte[][] directoryServers = new byte[NUM_DIR_SERVERS][];
    static {
        directoryServers[0] = Base64.decode("MIIEnjCCAoagAwIBAgIBATANBgkqhkiG9w0BAQsFADARMQ8wDQYDVQQDDAZpc3N1ZXIwHh" +
                "cNMTQwMzMxMTQzOTQ3WhcNMTQwNDE0MDgyNjIxWjAUMRIwEAYDVQQDEwkxMC4wLjIuMTUw" +
                "ggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCeUD1oVx5voIRMRwNXkZ5yyW2p7e" +
                "c1a43grOmYA7L31jNj86pFnnylhbxGyQP1kI7JVjFa+8eXkldIwAWHR09Jsh5+vucSO9/V" +
                "YzLTaOmEwchPtA3cUqBQIib/iv7LKmYmGo2D2DVG2+atXydvF1pskshTwUy3/VC0rt7SIW" +
                "Krh/26GiRUL4qaqjOwMiCMzep1T4tGrCV52I0w6kT401x1pCpAsZIu5oEYvH0difrN0vqP" +
                "mZJeleh8SMg6VOE1cN5hszIp+NGPxhHEFEpVhX0OmsZqz3J20Bw0Ow5nsJHGAV6qIsOcLW" +
                "ioKR0PdicGNFwY/x2Y44EdEbtLr0iym6Efxl+DhSQACB+Wv0hRET/UIalD7T/JXqpgzzvx" +
                "SSO7eDzBFLuON1ueuepNppTsJu7K0UAZKJhsMnS5lN+MZ9hSCJAobY2gXN9fULNVD/PlE4" +
                "Cg6s8SMTMzk5ZRZIhtFfsBtBdoa4kGi3dZvivqRGn88yjYNT9tBIgek4fTIqWgdBAG0EA1" +
                "0ZrEkrp+rJP7u2oIt3lzaMB4jvhg1bOfyTkiWzKrJ9U4a/LHrKPUhmQw8wPgncZdHjP64o" +
                "gziJ+rRpcUrEae5u502kfWnmvgAzY7uz0ZrjGbsu0sVuD44vvzNQEEUY+knhBVUd7Anx+4" +
                "vZPBgrX1eBxywpaF5iliEPHDuQIDAQABMA0GCSqGSIb3DQEBCwUAA4ICAQBYDxQ4/WJgk3" +
                "5yp7OgJBUP0OoAxtljMWPi4WvJQ/BUltBV7MsKhnl6KcMt2vGGwCcwmlLJJkkprbVyPkoH" +
                "qF0kpP8zRNQgNfv3gWoqAtOAg+pQEEsXlYzmcXnAiGkDSgo7o8tpVm4YugDtErL2kSE4aD" +
                "drCCQ0Yo9nugOKSqmyHcFPYmiNddRCgN9TjA9/watVD6yCVrhD7dtVdE+VfnuUw3AFh6Gu" +
                "rHFCgTX3sV41fUfvPU0lXbkmUhU+hb6urlhuNEWpdVXPYe59cA1TJfcSwc6omoNHZrTpt7" +
                "1Pi3Xx5IT6WP+LghwsUVIImzoQFAbFeXEQpg27KopEkNb/E+PLVvOnge7COPqpCJpKAnJc" +
                "V0ehfRtqcHUFshLN5fyi+fBTZ0rTe2UmwGAQNViaJNCZf0LVFjE6o9dBKDo01ZA/x+uVn7" +
                "x6MJ/ZdQQYlIPoRbfPwJYAtd7IVL7PZpYvrtR4TWdAZ9WJ/z6zOmPqgjkhtzPOo9j1l0WB" +
                "Udj3kb82948855xKw/Mw2AObZvhLviazaxeHzyOjpoZkew+5ajb9QPZtQ1jkil+bgFXhKD" +
                "BT6mUh+VuojvR7c9qUBEPD2AcGryDNUBKd1LZdscImdllus7iywvsaad/wM2TGCYn0HW7B" +
                "w9doK4Lq/9JIV0reEaKlmxZ25BbyT5CuVZ2VT1Z6nQ==");
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
