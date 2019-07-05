package peergos.shared.fingerprint;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.zxing.*;
import peergos.shared.zxing.common.*;
import peergos.shared.zxing.qrcode.*;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

public class FingerPrint implements Cborable {
    private static final String QRCODE_ENCODING = "ISO-8859-1";
    private static final byte[] VERSION = new byte[] {0, 0};
    private static final long COMBINED_VERSION = 1;

    private final long version;
    private final byte[] ourFingerPrint, friendsFingerPrint;

    public FingerPrint(byte[] ourFingerPrint, byte[] friendsFingerPrint, long version) {
        this.ourFingerPrint = ourFingerPrint;
        this.friendsFingerPrint = friendsFingerPrint;
        this.version = version;
    }

    @JsMethod
    public String getDisplayString() {
        return calculateDisplayString(ourFingerPrint, friendsFingerPrint);
    }

    @JsMethod
    public String getBase64Thumbnail() {
        String base64Data = Base64.getEncoder().encodeToString(getQrCodeData());
        return "data:image/png;base64," + base64Data;
    }

    public static FingerPrint generate(String ourname,
                                       PublicKeyHash ourIdentityKey,
                                       String friendsName,
                                       PublicKeyHash friendsIdentityKey) {
        try {
            byte[] us = calculateHalfFingerprint(ourname, ourIdentityKey);
            byte[] friend = calculateHalfFingerprint(friendsName, friendsIdentityKey);
            return new FingerPrint(us, friend, COMBINED_VERSION);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] getQrCodeData() {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix result = writer.encode(new String(toCbor().toByteArray(), QRCODE_ENCODING), BarcodeFormat.QR_CODE, 512, 512);
            BufferedImage original = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < result.getHeight(); y++) {
                for (int x = 0; x < result.getWidth(); x++) {
                    if (result.get(x, y))
                        original.setRGB(x, y, 0xff000000);
                    else
                        original.setRGB(x, y, 0xffffffff);
                }
            }
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ImageIO.write(original, "png", bout);
            return bout.toByteArray();
        } catch (WriterException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FingerPrint that = (FingerPrint) o;
        return version == that.version &&
                Arrays.equals(ourFingerPrint, that.ourFingerPrint) &&
                Arrays.equals(friendsFingerPrint, that.friendsFingerPrint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version);
        result = 31 * result + Arrays.hashCode(ourFingerPrint);
        result = 31 * result + Arrays.hashCode(friendsFingerPrint);
        return result;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("v", new CborObject.CborLong(version));
        state.put("u", new CborObject.CborByteArray(ourFingerPrint));
        state.put("f", new CborObject.CborByteArray(friendsFingerPrint));
        return CborObject.CborMap.build(state);
    }

    public static FingerPrint fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FingerPrint! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long version = m.getLong("v");
        byte[] us = m.getByteArray("u");
        byte[] friend = m.getByteArray("f");
        return new FingerPrint(us, friend, version);
    }

    public static FingerPrint fromString(String scanned) {
        try {
            byte[] bytes = scanned.getBytes(QRCODE_ENCODING);
            return fromCbor(CborObject.fromByteArray(bytes));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String calculateDisplayString(byte[] us, byte[] friend) {
        String ourString = getDisplayStringFor(us);
        String friendString = getDisplayStringFor(friend);
        if (ourString.compareTo(friendString) <= 0)
            return ourString + friendString;
        return friendString + ourString;
    }

    private static byte[] calculateHalfFingerprint(String name,
                                                   PublicKeyHash identityKey) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        bout.write(VERSION);
        bout.write(name.getBytes("UTF-8"));
        bout.write(identityKey.serialize());
        byte[] initial = bout.toByteArray();
        return hash(initial, 5200); // 112 bits of security
    }

    private static byte[] hash(byte[] input, int iterations) {
        for (int i=0; i < iterations; i++)
            input = Blake2b.Digest.newInstance(64).digest(input);
        return Arrays.copyOfRange(input, 0, 32);
    }

    private static String getDisplayStringFor(byte[] fingerprint) {
        return getEncodedChunk(fingerprint, 0)  +
                getEncodedChunk(fingerprint, 5)  +
                getEncodedChunk(fingerprint, 10) +
                getEncodedChunk(fingerprint, 15) +
                getEncodedChunk(fingerprint, 20) +
                getEncodedChunk(fingerprint, 25);
    }

    private static String getEncodedChunk(byte[] hash, int offset) {
        long chunk = byteArray5ToLong(hash, offset) % 100000;
        if (chunk < 10)
            return "0000" + chunk;
        if (chunk < 100)
            return "000" + chunk;
        if (chunk < 1000)
            return "00" + chunk;
        if (chunk < 10000)
            return "0" + chunk;
        return "" + chunk;
    }

    private static long byteArray5ToLong(byte[] in, int start) {
        return in[start] & 0xffL |
                ((in[start +  1] & 0xffL) << 8) |
                ((in[start +  2] & 0xffL) << 16) |
                ((in[start +  3] & 0xffL) << 24) |
                ((in[start +  4] & 0xffL) << 32);
    }
}
