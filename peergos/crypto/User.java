package peergos.crypto;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

import java.io.*;
import java.security.*;
import javax.crypto.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class User extends UserPublicKey
{
    private final PrivateKey privateKey;

    public User(KeyPair pair)
    {
        super(pair.getPublic());
        this.privateKey = pair.getPrivate();
    }

    public User(PrivateKey privateKey, PublicKey publicKey)
    {
        super(publicKey);
        this.privateKey = privateKey;
    }

    public byte[] hashAndSignMessage(byte[] input)
    {
        byte[] hash = hash(input);
        return signMessage(hash);
    }

    public byte[] signMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.ENCRYPT_MODE, privateKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] decryptMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.DECRYPT_MODE, privateKey);
            DataInput din = new DataInputStream(new ByteArrayInputStream(input));
            byte[] encryptedSym = Serialize.deserializeByteArray(din, UserPublicKey.RSA_KEY_BITS);
            byte[] iv = Serialize.deserializeByteArray(din, SymmetricKey.IV_SIZE);
            byte[] encryptedContent = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
            byte[] rawSym = c.doFinal(encryptedSym);
            SymmetricKey sym = new SymmetricKey(rawSym);
            return sym.decrypt(encryptedContent, iv);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static User create(byte[] priv, byte[] pub)
    {
        try {
            PublicKey publicKey = KeyFactory.getInstance(KEYS).generatePublic(new X509EncodedKeySpec(pub));
            PrivateKey privateKey = KeyFactory.getInstance(KEYS).generatePrivate(new PKCS8EncodedKeySpec(priv));;
            return new User(privateKey, publicKey);
        } catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't create public key");
        } catch (InvalidKeySpecException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't create public key");
        }
    }

    public static PrivateKey deserializePrivate(byte[] encoded)
    {
        try {
            return KeyFactory.getInstance(KEYS, "BC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (NoSuchAlgorithmException|NoSuchProviderException|InvalidKeySpecException e)
        {
            throw new IllegalStateException("Couldn't create private key:" + e.getMessage());
        }
    }

    public static KeyPair generateKeyPair()
    {
        try
        {
            ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec(ECC_CURVE);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
            kpg.initialize(ecSpec);
            return kpg.genKeyPair();
        } catch (NoSuchAlgorithmException|NoSuchProviderException|InvalidAlgorithmParameterException e)
        {
            throw new IllegalStateException("No such algorithm: "+KEYS);
        }
    }

    public static User random()
    {
        return new User(generateKeyPair());
    }

    public static User generateUserCredentials(String username, String password)
    {
        // need usernames and public keys to be in 1-1 correspondence, and the private key to be derivable from the username+password
        // username is essentially salt against rainbow table attacks
        byte[] hash = hash(username+password);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEYS, "BC");
            SecureRandom random = SecureRandom.getInstance(SECURE_RANDOM);
            random.setSeed(hash);
            ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec(ECC_CURVE);
            kpg.initialize(ecSpec, random);
            long start = System.nanoTime();
            User u = new User(kpg.generateKeyPair());
            long end = System.nanoTime();
            System.out.printf("User credential generation took %d mS\n", (end-start)/1000000);
            return u;
        } catch (NoSuchAlgorithmException|NoSuchProviderException|InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't generate key-pair from password - "+e.getMessage());
        }
    }

    public static byte[] convertPKCS8to1(byte[] v8) throws IOException {
        PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(ASN1Sequence.getInstance(v8));
        return privKeyInfo.parsePrivateKey().toASN1Primitive().getEncoded();
    }

    public static byte[] convertPKCS1to8(byte[] v1) throws IOException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
        PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, new DERNull()),
                RSAPrivateKey.getInstance(v1));
        try {
            return converter.getPrivateKey(privKeyInfo).getEncoded();
        } catch (PEMException pex) {throw new IOException(pex);}
    }

    private static void testFormatInversion() {
        try {
            KeyPair pair = generateKeyPair();
            byte[] v1 = convertPKCS8to1(pair.getPrivate().getEncoded());
            byte[] v8 = convertPKCS1to8(v1);
            byte[] same = convertPKCS8to1(v8);
            assert (Arrays.equals(v1, same));
        } catch (Exception e) {e.printStackTrace();}
    }

    public static class KeyPairUtils {

        private KeyPairUtils(){}

        public static void serialize(KeyPair keyPair, File f) throws IOException {
             OutputStream out = new FileOutputStream(f);
            try
            {
                serialize(keyPair, out);
            } finally {
                out.close();
            }
        }

        public static void serialize(KeyPair keyPair, OutputStream out) throws IOException {
            DataOutputStream dout = new DataOutputStream(out);
            try {
                byte[] _public = keyPair.getPublic().getEncoded();
                dout.writeInt(_public.length);
                dout.write(_public);

                byte[] _private = keyPair.getPrivate().getEncoded();
                dout.writeInt(_private.length);
                dout.write(_private);

                dout.flush();
            } finally {
                dout.close();
            }
        }

        public static KeyPair deserialize(File f) throws IOException {
            InputStream in = new FileInputStream(f);
            try
            {
               return deserialize(in);
            } finally {
                in.close();
            }
        }
        public static KeyPair deserialize(InputStream in) throws IOException {

            DataInputStream din = new DataInputStream(in);
            try {
                int publicLength = din.readInt();
                byte[] _public = new byte[publicLength];
                din.readFully(_public);
                PublicKey publicKey = deserializePublic(_public);

                int privateLength = din.readInt();
                byte[] _private = new byte[privateLength];
                din.readFully(_private);
                PrivateKey privateKey = deserializePrivate(_private);

                return new KeyPair(publicKey, privateKey);
            } finally {
                din.close();
            }
        }
    }

    public static void main(String[] args) {
        User user = User.generateUserCredentials("Username", "password");
        byte[] message = "G'day mate!".getBytes();
        byte[] cipher = user.encryptMessageFor(message);
        System.out.println("Cipher: "+ArrayOps.bytesToHex(cipher));
        byte[] clear = user.decryptMessage(cipher);
        assert (Arrays.equals(message, clear));
    }
}
