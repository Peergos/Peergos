package peergos.crypto;

import org.bouncycastle.x509.extension.AuthorityKeyIdentifierStructure;
import peergos.directory.DirectoryServer;
import peergos.storage.net.IP;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

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

    public static KeyStore getTrustedKeyStore()
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        ks.load(null);
        X509Certificate rootCert = (X509Certificate) getRootCertificate();
        KeyStore.Entry root = new KeyStore.TrustedCertificateEntry(rootCert);
        String rootAlias = getCommonName(rootCert);
        ks.setEntry(rootAlias, root, null);
//        Certificate[] dirs = SSL.getDirectoryServerCertificates();
//        for (Certificate dir: dirs) {
//            String alias = ((X509Certificate) dir).getSubjectX500Principal().getName();
//            ks.setCertificateEntry(alias, dir);
//        }
        return ks;
    }

    public static String getCommonName(X509Certificate cert)
    {
        try {
            X500Name x500name = new JcaX509CertificateHolder(cert).getSubject();
            RDN cn = x500name.getRDNs(BCStyle.CN)[0];
            return IETFUtils.valueToString(cn.getFirst().getValue());
        } catch (CertificateEncodingException e) {e.printStackTrace(); throw new IllegalStateException(e.getMessage());}
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
        String ip = IP.getMyPublicAddress().getHostAddress();
        PKCS10CertificationRequest csr = generateCSR(password, ip, ip, keypair, "storage.csr");
        PrivateKey myPrivateKey = keypair.getPrivate();

        // make rootCA a trust source
        X509Certificate rootCert = (X509Certificate) getRootCertificate();

        Certificate[] dirs = SSL.getDirectoryServerCertificates();
        Certificate cert;
        Certificate dir;
        while (true) {
            dir = dirs[new SecureRandom().nextInt() % dirs.length];
//            String alias = getCommonName(dir);
//            ks.setCertificateEntry(alias, dir);

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
        // will throw exception if certificates don't verify by signer public key
        dir.verify(rootCert.getPublicKey());
        cert.verify(dir.getPublicKey());

        KeyStore.Entry root = new KeyStore.TrustedCertificateEntry(rootCert);
        ks.setEntry(getCommonName(rootCert), root, null);
        ks.setCertificateEntry(getCommonName(cert), cert);
        ks.setCertificateEntry(getCommonName(dir), dir);
        ks.setKeyEntry(getCommonName(cert), myPrivateKey, password, new Certificate[]{cert, dir, rootCert});
        Certificate[] chain = ks.getCertificateChain(getCommonName(cert));
        if (chain.length != 3)
            throw new IllegalStateException("Certificate chain must contain 3 certificates! "+chain.length);
        ks.store(new FileOutputStream(SSL_KEYSTORE_FILENAME), password);
        return ks;
    }

    public static Certificate generateSelfSignedCertificate(String commonName, String ipaddress, PublicKey signee, PrivateKey signer)
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
        // root doesn't want an IP, or domain!
//        GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, ipaddress));
//        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.17"), false, subjectAltName);

        // make cert a CA, 1 ensures a 3 certificate chain is possible root -> dir -> storage node
        certGen.addExtension(X509Extensions.BasicConstraints, true, new BasicConstraints(1));

        ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").build(signer);
        X509CertificateHolder certHolder = certGen.build(sigGen);
        return new JcaX509CertificateConverter().getCertificate(certHolder);
    }

    public static Certificate generateRootCertificate(KeyPair keypair)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        return generateSelfSignedCertificate("Peergos", IP.getMyPublicAddress().getHostAddress(), keypair.getPublic(), keypair.getPrivate());
    }

    public static void generateAndSaveRootCertificate(char[] password)
    {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, password);
            KeyPair keypair = generateKeyPair();
            PrivateKey myPrivateKey = keypair.getPrivate();
            Certificate cert = generateRootCertificate(keypair);
            BufferedWriter w = new BufferedWriter(new FileWriter("peergos/crypto/RootCertificate.java"));
            w.write("package peergos.crypto;\n\nimport org.bouncycastle.util.encoders.Base64;\n\n" +
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

    public static KeyPair generateCSR(char[] passphrase, String commonName, String ipAddress, String keyfile, String csrfile) throws IOException
    {
        String msg;
        try {
            KeyPair pair = generateAndSaveKeyPair(keyfile, passphrase);
            generateCSR(passphrase, commonName, ipAddress, loadKeyPair(keyfile, passphrase), csrfile);
            return pair;
        } catch (NoSuchAlgorithmException e) {e.printStackTrace(); msg= e.getMessage();}
        catch (InvalidKeySpecException e) {e.printStackTrace();msg= e.getMessage();}
        catch (OperatorCreationException e) {e.printStackTrace();msg= e.getMessage();}
        catch (PKCSException e) {e.printStackTrace();msg= e.getMessage();}
        throw new IllegalStateException(msg);
    }

    public static PKCS10CertificationRequest generateCSR(char[] password, String cn, String ipaddress, KeyPair keypair, String outfile)
    {
        try {
            return generateCertificateSignRequestAndSaveToFile(outfile, password, cn, ipaddress, keypair);
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static PKCS10CertificationRequest generateCertificateSignRequestAndSaveToFile(String outfile, char[] password,
                                                                            String commonName, String ipaddress, KeyPair keys)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException, CRMFException
    {
        PKCS10CertificationRequest csr = generateCertificateSignRequest(password, commonName, ipaddress, keys);
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

    public static PKCS10CertificationRequest generateCertificateSignRequest(char[] password, String commonName,
                                                                            String ipAddress, KeyPair keys)
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

        PKCS10CertificationRequestBuilder requestBuilder = new JcaPKCS10CertificationRequestBuilder(builder.build(), keys.getPublic());
        if (ipAddress != null) {
            GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, ipAddress));
            Extension[] ext = new Extension[]{new Extension(Extension.subjectAlternativeName, false, new DEROctetString(subjectAltName))};
            PKCS10CertificationRequest csr = requestBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,
                    new Extensions(ext)).build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keys.getPrivate()));
            return csr;
        }
        PKCS10CertificationRequest csr = requestBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keys.getPrivate()));
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

    public static Certificate signCertificate(String csrFile, char[] rootPassword, String type)
    {
        try {
            PKCS10CertificationRequest csr = loadCSR(csrFile);
            return signCertificateWithRoot(rootPassword, csr, type);
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static Certificate signCertificateWithRoot(char[] rootPassword, PKCS10CertificationRequest csr, String type)
    {
        try {
            KeyStore ks = getRootKeyStore(rootPassword);
            PrivateKey rootPriv = (PrivateKey) ks.getKey("private", rootPassword);
            Certificate signed = signCertificate(csr, rootPriv, getRootCertificate(), true);
            BufferedWriter w = new BufferedWriter(new FileWriter("peergos/crypto/"+type+"Certificates.java"));
            w.write("package peergos.crypto;\n\nimport org.bouncycastle.util.encoders.Base64;\n\n" +
                    "public class "+type+"Certificates {\n    public static final int NUM_SERVERS = 1;\n"+
                    "    public static byte[][] servers = new byte[NUM_SERVERS][];\n    static {\n"+
                    "        servers[0] = Base64.decode(");
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

    public static Certificate signCertificate(PKCS10CertificationRequest csr, PrivateKey priv, Certificate issuer, boolean issuerIsRoot)
    {
        try {
            SubjectPublicKeyInfo pkInfo = csr.getSubjectPublicKeyInfo();
            RSAKeyParameters rsa = (RSAKeyParameters) PublicKeyFactory.createKey(pkInfo);
            RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getExponent());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey dirPub = kf.generatePublic(rsaSpec);

            AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA");
            AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);

            JcaX509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(
                    (X509Certificate)issuer, BigInteger.probablePrime(1024, new Random()),
                    new Date(System.currentTimeMillis()),
                    new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000),
                    csr.getSubject(), dirPub);
            certGen.addExtension(X509Extensions.AuthorityKeyIdentifier, false,
                        new AuthorityKeyIdentifierStructure((X509Certificate)issuer));
            if (issuerIsRoot)
                certGen.addExtension(X509Extensions.BasicConstraints, true, new BasicConstraints(0));
            AsymmetricKeyParameter foo = PrivateKeyFactory.createKey(priv.getEncoded());
            ContentSigner sigGen = new BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(foo);

            String cn = getCommonName(csr);
            if (isIPAddress(cn)) {
                GeneralNames subjectAltName = new GeneralNames(new GeneralName(GeneralName.iPAddress, getCommonName(csr)));
                X509CertificateHolder holder = certGen.addExtension(Extension.subjectAlternativeName, false, subjectAltName).build(sigGen);
                Certificate signed = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
                return signed;
            }
            X509CertificateHolder holder = certGen.build(sigGen);
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isIPAddress(String name) {
        String[] s = name.split("\\.");
        if (s.length > 4) // TODO make handle IPV6
            return false;
        for (String part: s)
            try {
                Integer.parseInt(part);
            } catch (NumberFormatException e) {return false;}
        return true;
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
    public static Certificate[] getCoreServerCertificates()
    {
        try {
            Certificate[] dirs = new Certificate[CoreCertificates.NUM_SERVERS];
            for (int i = 0; i < dirs.length; i++) {
                CertificateFactory fact = CertificateFactory.getInstance("X.509", "BC");
                dirs[i] = fact.generateCertificate(new ByteArrayInputStream(CoreCertificates.servers[i]));
            }
            return dirs;
        } catch (NoSuchProviderException | CertificateException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Error in core certificates: " + e.getMessage());
        }
    }

    // Directory server certificates are signed by the root key
    public static Certificate[] getDirectoryServerCertificates()
    {
        List<Certificate> dirs = new ArrayList();
        for (int i =0; i < DirectoryCertificates.NUM_SERVERS; i++)
        {
            try {
                CertificateFactory  fact = CertificateFactory.getInstance("X.509", "BC");
                dirs.add(fact.generateCertificate(new ByteArrayInputStream(DirectoryCertificates.servers[i])));
            } catch (Exception e) {e.printStackTrace();}
        }
        return dirs.toArray(new Certificate[0]);
    }

    @org.junit.Test
    public void test() {
        try {
            KeyPair rootKeys = generateKeyPair();
            Certificate root = generateRootCertificate(rootKeys);

            char[] dirPass = "password".toCharArray();
            KeyPair dirKeys = generateKeyPair();
            String dirCN = "192.168.0.18";
            String dirIP = "192.168.0.18";
            PKCS10CertificationRequest dirCSR = generateCertificateSignRequest(dirPass, dirCN, dirIP, dirKeys);
            Certificate dir = signCertificate(dirCSR, rootKeys.getPrivate(), root, true);

            char[] userPass = "password".toCharArray();
            KeyPair userKeys = generateKeyPair();
            String userCN = "192.168.0.19";
            String userIP = "192.168.0.19";
            PKCS10CertificationRequest userCSR = generateCertificateSignRequest(userPass, userCN, userIP, userKeys);
            Certificate user = signCertificate(userCSR, dirKeys.getPrivate(), dir, false);

            // will throw exception if certificates don't verify by signer public key
            dir.verify(root.getPublicKey());
            user.verify(dir.getPublicKey());

            // save so we can analyse with openssl
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, userPass);
            ks.setKeyEntry(getCommonName(user), userKeys.getPrivate(), userPass, new Certificate[]{user, dir, root});
            Certificate[] chain = ks.getCertificateChain(getCommonName(user));
            if (!((X509Certificate)chain[1]).getIssuerX500Principal().equals(((X509Certificate)chain[2]).getSubjectX500Principal()))
                throw new IllegalStateException("chain error (1-2): "+ ((X509Certificate)chain[1]).getIssuerX500Principal() + " != "
                        + ((X509Certificate)chain[2]).getSubjectX500Principal());
            if (!((X509Certificate)chain[0]).getIssuerX500Principal().equals(((X509Certificate)chain[1]).getSubjectX500Principal()))
                throw new IllegalStateException("chain error (0-1): "+ ((X509Certificate)chain[0]).getIssuerX500Principal() + " != "
                        + ((X509Certificate)chain[1]).getSubjectX500Principal());

            if (((X509Certificate)root).getBasicConstraints() != 1)
                throw new IllegalStateException("Root cert must allow an intermediate cert to act as CA!");

            if (((X509Certificate)dir).getBasicConstraints() != 0)
                throw new IllegalStateException("Dir cert must act as a CA!");

            ks.store(new FileOutputStream("test.p12"), userPass);
            if (chain.length != 3)
                throw new IllegalStateException("Certificate chain must contain 3 certificates! "+chain.length);

        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
