package defiance.crypto;

import defiance.directory.DirectoryServer;
import defiance.net.IP;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.crmf.CRMFException;
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

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
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
    public static final String SSL_KEYSTORE_FILENAME = "storage.p12";

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

        // Now convert to JCE objects
        KeyFactory keyFactory = KeyFactory.getInstance( "RSA");
        RSAKeyParameters rsa = (RSAKeyParameters) PublicKeyFactory.createKey((SubjectPublicKeyInfo)pub);
        RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getExponent());
        PublicKey publicKey = keyFactory.generatePublic(rsaSpec);
        return new KeyPair(publicKey, privateKey);
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
        PKCS10CertificationRequest csr = generateCSR(password, keypair, "storage.csr");
        PrivateKey myPrivateKey = keypair.getPrivate();

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
        ks.store(new FileOutputStream(SSL_KEYSTORE_FILENAME), password);
        return ks;
    }

    public static Certificate generateCertificate(char[] password, String commonName, PublicKey signee, PrivateKey signer)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
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

    public static Certificate generateRootCertificate(char[] password, KeyPair keypair)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        return generateCertificate(password, IP.getMyPublicAddress().getHostAddress(), keypair.getPublic(), keypair.getPrivate());
    }

    public static void generateAndSaveRootCertificate(char[] password)
    {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, password);
            KeyPair keypair = generateKeyPair();
            PrivateKey myPrivateKey = keypair.getPrivate();
            Certificate cert = generateRootCertificate(password, keypair);
            BufferedWriter w = new BufferedWriter(new FileWriter("defiance/crypto/RootCertificate.java"));
            w.write("package defiance.crypto;\n\nimport org.bouncycastle.util.encoders.Base64;\n\n" +
                    "public class RootCertificate {\n    public static final byte[] rootCA = Base64.decode(");
            printCertificate(cert, w);
            w.write(");\n}");
            w.flush();
            w.close();

            ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
            ks.setCertificateEntry("rootCA", cert);
            ks.store(new FileOutputStream("rootCA.p12"), password);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
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

    public static PKCS10CertificationRequest generateCertificateSignRequest(String outfile, char[] password,
                                                                            String commonName, PublicKey signee, PrivateKey signer)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException, CRMFException
    {
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        ks.load(null, password);

        X500NameBuilder builder = new X500NameBuilder(RFC4519Style.INSTANCE);
        builder.addRDN(RFC4519Style.cn, commonName);
        builder.addRDN(RFC4519Style.c, "AU");
        builder.addRDN(RFC4519Style.o, "Peergos");
        builder.addRDN(RFC4519Style.l, "Melbourne");
        builder.addRDN(RFC4519Style.st, "Victoria");
        builder.addRDN(PKCSObjectIdentifiers.pkcs_9_at_emailAddress, "hello.NSA.GCHQ.ASIO@goodluck.com");

        GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, commonName));

        Extension[] ext = new Extension[] {new Extension(Extension.subjectAlternativeName, false, new DEROctetString(subjectAltName))};
        PKCS10CertificationRequestBuilder requestBuilder = new JcaPKCS10CertificationRequestBuilder(builder.build(), signee);
        PKCS10CertificationRequest csr = requestBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,
                new Extensions(ext)).build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signer));

        BufferedWriter w = new BufferedWriter(new FileWriter(outfile));
        String type = "CERTIFICATE REQUEST";
        byte[] encoding = csr.getEncoded();
        PemObject pemObject = new PemObject(type, encoding);
        StringWriter str = new StringWriter();
        PEMWriter pemWriter = new PEMWriter(str);
        pemWriter.writeObject(pemObject);
        pemWriter.close();
        str.close();
        w.write(str.toString());
        w.flush();
        w.close();
        return csr;
    }

    public static PKCS10CertificationRequest loadCSR(String file) throws IOException
    {
        BufferedReader r = new BufferedReader(new FileReader(file));
        r.readLine();
        StringBuilder base64 = new StringBuilder();
        String line;
        while (!(line=r.readLine()).contains("-----END CERTIFICATE REQUEST-----"))
            base64.append(line);
        byte[] csrBytes = Base64.decode(base64.toString());
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
            BufferedWriter w = new BufferedWriter(new FileWriter("defiance/crypto/DirectoryCertificates.java"));
            w.write("package defiance.crypto;\n\nimport org.bouncycastle.util.encoders.Base64;\n\n" +
                    "public class DirectoryCertificates {\n    public static final int NUM_DIR_SERVERS = 1;\n"+
                    "    public static byte[][] directoryServers = new byte[NUM_DIR_SERVERS][];\n    static {\n"+
                    "        directoryServers[0] = Base64.decode(");
            printCertificate(signed, w);
            w.write(");\n    }\n}");
            w.flush();
            w.close();
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


            X509v3CertificateBuilder certGen = new X509v3CertificateBuilder(
                    new X500Name("CN="+issuerCN), new BigInteger("1"),
                    new Date(System.currentTimeMillis()),
                    new Date(System.currentTimeMillis() + 30 * 365 * 24 * 60 * 60 * 1000),
                    csr.getSubject(), keyInfo);

            AsymmetricKeyParameter foo = PrivateKeyFactory.createKey(priv.getEncoded());
            ContentSigner sigGen = new BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(foo);

            GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, getCommonName(csr)));
            X509CertificateHolder holder = certGen.addExtension(Extension.subjectAlternativeName, false, subjectAltName).build(sigGen);
//                    .addExtension(new ASN1ObjectIdentifier("2.5.29.17"), false, subjectAltName).build(sigGen);
            Certificate signed = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
            return signed;
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static void printCertificate(Certificate cert, BufferedWriter w) throws IOException
    {
        try {
            String encoded = Base64.toBase64String(cert.getEncoded());
            w.write("            \"" + encoded.substring(0, Math.min(70, encoded.length())) + "\" ");
            for (int i = 1; i <= encoded.length() / 70; i++)
                w.write("+\n            \"" + encoded.substring(i * 70, Math.min((i + 1) * 70, encoded.length())) + "\" ");
        } catch (CertificateEncodingException e) {e.printStackTrace();}
    }

    public static Certificate getRootCertificate()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
        return fact.generateCertificate(new ByteArrayInputStream(RootCertificate.rootCA));
    }

    public static KeyStore getRootKeyStore(char[] password)
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
        keyStore.load(new FileInputStream("rootCA.p12"), password);
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

    // Directory server certificates are signed by the root key
    public static Certificate[] getDirectoryServerCertificates()
            throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, IOException
    {
        Certificate[] dirs = new Certificate[DirectoryCertificates.NUM_DIR_SERVERS];
        for (int i =0; i < dirs.length; i++)
        {
            CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
            dirs[i] = fact.generateCertificate(new ByteArrayInputStream(DirectoryCertificates.directoryServers[i]));
        }
        return dirs;
    }
}
