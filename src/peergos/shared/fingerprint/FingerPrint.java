package peergos.shared.fingerprint;

import jsinterop.annotations.*;
import peergos.shared.QRCodeEncoder;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;
import peergos.shared.zxing.*;
import peergos.shared.zxing.common.*;
import peergos.shared.zxing.qrcode.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public class FingerPrint implements Cborable {
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
                                       List<PublicKeyHash> ourIdentityKey,
                                       String friendsName,
                                       List<PublicKeyHash> friendsIdentityKey,
                                       Hasher h) {
        try {
            byte[] us = calculateHalfFingerprint(ourname, ourIdentityKey, h);
            byte[] friend = calculateHalfFingerprint(friendsName, friendsIdentityKey, h);
            return new FingerPrint(us, friend, COMBINED_VERSION);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @JsMethod
    public static FingerPrint decodeFromPixels(int[] pixels, int width, int height) {
        // This source doesn't handle rotations or dilations
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        BinaryBitmap readBitmap = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        try {
            return fromString(reader.decode(readBitmap).getText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @JsMethod
    public boolean matches(FingerPrint other) {
        return version == other.version &&
                Arrays.equals(ourFingerPrint, other.friendsFingerPrint) &&
                Arrays.equals(friendsFingerPrint, other.ourFingerPrint);
    }

    public byte[] getQrCodeData() {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            String contents = ArrayOps.bytesToHex(toCbor().toByteArray());
            BitMatrix result = writer.encode(contents,
                    BarcodeFormat.QR_CODE, 512, 512);

            return QRCodeEncoder.encodeToPng(QRCodeEncoder.BW_MODE, result.getWidth(), result.getHeight(), result);
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
        byte[] bytes = ArrayOps.hexToBytes(scanned);
        return fromCbor(CborObject.fromByteArray(bytes));
    }

    private static String calculateDisplayString(byte[] us, byte[] friend) {
        String ourString = getDisplayStringFor(us);
        String friendString = getDisplayStringFor(friend);
        if (ourString.compareTo(friendString) <= 0)
            return ourString + friendString;
        return friendString + ourString;
    }

    private static int compareArrays(byte[] a, byte[] b) {
        if (a.length != b.length)
            return a.length - b.length;
        for (int i=0; i < a.length; i++)
            if (a[i] != b[i])
                return a[i] - b[i];
        return 0;
    }

    private static byte[] calculateHalfFingerprint(String name,
                                                   List<PublicKeyHash> identityKeys,
                                                   Hasher h) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        bout.write(VERSION);
        bout.write(name.getBytes("UTF-8"));
        List<byte[]> serializedKeys = identityKeys.stream()
                .map(x -> x.serialize())
                .sorted(FingerPrint::compareArrays)
                .collect(Collectors.toList());
        for (byte[] serializedKey : serializedKeys) {
            bout.write(serializedKey);
        }
        byte[] initial = bout.toByteArray();
        return hash(initial, 5200, h); // 112 bits of security
    }

    private static byte[] hash(byte[] input, int iterations, Hasher h) {
        for (int i=0; i < iterations; i++)
            input = h.blake2b(input, 64);
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
