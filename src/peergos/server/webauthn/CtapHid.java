package peergos.server.webauthn;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The CTAPHID transport to a security key, over /dev/hidraw.
 *
 *  The desktop app is the user agent here, the same way the android app is: we
 *  build the clientDataJSON ourselves and ask the key to sign it. That is what
 *  lets a key registered in a browser for peergos.net be used from localhost,
 *  which a browser would refuse to do.
 */
public class CtapHid implements Closeable {
    private static final int PACKET = 64;
    private static final int INIT_PAYLOAD = PACKET - 7;
    private static final int CONT_PAYLOAD = PACKET - 5;
    // command bytes carry the high bit in an initialisation packet
    static final byte CMD_INIT = (byte) 0x86;
    static final byte CMD_CBOR = (byte) 0x90;
    static final byte CMD_CANCEL = (byte) 0x91;
    private static final byte CMD_KEEPALIVE = (byte) 0xBB;
    private static final byte CMD_ERROR = (byte) 0xBF;
    private static final byte[] BROADCAST = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

    private final Path path;
    private final RandomAccessFile device;
    private byte[] channel = BROADCAST;

    private CtapHid(Path path) throws IOException {
        this.path = path;
        this.device = new RandomAccessFile(path.toFile(), "rw");
    }

    /** The hidraw nodes that declare the FIDO usage page. */
    public static List<Path> findAuthenticators() {
        List<Path> found = new ArrayList<>();
        Path sys = Paths.get("/sys/class/hidraw");
        if (! Files.isDirectory(sys))
            return found;
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(sys)) {
            for (Path node : nodes) {
                Path descriptor = node.resolve("device/report_descriptor");
                Path device = Paths.get("/dev", node.getFileName().toString());
                try {
                    if (Files.isReadable(descriptor) && isFido(Files.readAllBytes(descriptor)) && Files.exists(device))
                        found.add(device);
                } catch (IOException e) {
                    // an unreadable node is simply not a candidate
                }
            }
        } catch (IOException e) {
            return found;
        }
        return found;
    }

    /** Usage page 0xf1d0, as a short item: 06 d0 f1. */
    static boolean isFido(byte[] reportDescriptor) {
        for (int i = 0; i + 2 < reportDescriptor.length; i++) {
            if ((reportDescriptor[i] & 0xff) == 0x06
                    && (reportDescriptor[i + 1] & 0xff) == 0xd0
                    && (reportDescriptor[i + 2] & 0xff) == 0xf1)
                return true;
        }
        return false;
    }

    /** The first authenticator that answers, or empty if there is none plugged in. */
    public static java.util.Optional<CtapHid> openFirst() {
        for (Path candidate : findAuthenticators()) {
            try {
                CtapHid key = new CtapHid(candidate);
                key.init();
                return java.util.Optional.of(key);
            } catch (IOException e) {
                // permissions, or it went away between listing and opening
            }
        }
        return java.util.Optional.empty();
    }

    private void init() throws IOException {
        byte[] nonce = new byte[8];
        new SecureRandom().nextBytes(nonce);
        channel = BROADCAST;
        byte[] response = send(CMD_INIT, nonce, System.currentTimeMillis() + 3_000);
        if (response.length < 12 || ! Arrays.equals(nonce, Arrays.copyOf(response, 8)))
            throw new IOException("Unexpected INIT response from " + path);
        channel = Arrays.copyOfRange(response, 8, 12);
    }

    /** Send a CTAP2 command and return its response payload, without the status byte. */
    public byte[] cbor(byte command, byte[] request, long deadline) throws IOException {
        byte[] payload = new byte[1 + request.length];
        payload[0] = command;
        System.arraycopy(request, 0, payload, 1, request.length);
        byte[] response = send(CMD_CBOR, payload, deadline);
        if (response.length == 0)
            throw new IOException("Empty CTAP2 response");
        int status = response[0] & 0xff;
        if (status != 0)
            throw new Ctap2Exception(status);
        return Arrays.copyOfRange(response, 1, response.length);
    }

    /** Abandon the operation the key is waiting on, so the next one can start. */
    public void cancel() {
        try {
            writeRequest(CMD_CANCEL, new byte[0]);
        } catch (IOException e) {
            // we are giving up anyway
        }
    }

    private byte[] send(byte command, byte[] payload, long deadline) throws IOException {
        writeRequest(command, payload);
        return readResponse(command, deadline);
    }

    private void writeRequest(byte command, byte[] payload) throws IOException {
        byte[] packet = new byte[1 + PACKET]; // hidraw takes the report id first
        System.arraycopy(channel, 0, packet, 1, 4);
        packet[5] = command;
        packet[6] = (byte) (payload.length >> 8);
        packet[7] = (byte) payload.length;
        int sent = Math.min(payload.length, INIT_PAYLOAD);
        System.arraycopy(payload, 0, packet, 8, sent);
        device.write(packet);
        for (int sequence = 0; sent < payload.length; sequence++) {
            Arrays.fill(packet, (byte) 0);
            System.arraycopy(channel, 0, packet, 1, 4);
            packet[5] = (byte) sequence;
            int chunk = Math.min(payload.length - sent, CONT_PAYLOAD);
            System.arraycopy(payload, sent, packet, 6, chunk);
            device.write(packet);
            sent += chunk;
        }
    }

    private byte[] readResponse(byte command, long deadline) throws IOException {
        byte[] packet = new byte[PACKET];
        while (true) {
            readPacket(packet, deadline);
            if (! Arrays.equals(channel, Arrays.copyOf(packet, 4)))
                continue; // someone else's channel
            byte responseCommand = packet[4];
            if (responseCommand == CMD_KEEPALIVE)
                continue; // still waiting for the user to touch it
            if (responseCommand == CMD_ERROR)
                throw new IOException("CTAPHID error 0x" + Integer.toHexString(packet[7] & 0xff));
            if (responseCommand != command)
                continue;
            int length = ((packet[5] & 0xff) << 8) | (packet[6] & 0xff);
            byte[] payload = new byte[length];
            int read = Math.min(length, INIT_PAYLOAD);
            System.arraycopy(packet, 7, payload, 0, read);
            while (read < length) {
                readPacket(packet, deadline);
                if (! Arrays.equals(channel, Arrays.copyOf(packet, 4)))
                    continue;
                int chunk = Math.min(length - read, CONT_PAYLOAD);
                System.arraycopy(packet, 5, payload, read, chunk);
                read += chunk;
            }
            return payload;
        }
    }

    private void readPacket(byte[] packet, long deadline) throws IOException {
        if (System.currentTimeMillis() > deadline)
            throw new IOException("Timed out waiting for the security key");
        device.readFully(packet);
    }

    @Override
    public void close() throws IOException {
        device.close();
    }

    public static class Ctap2Exception extends IOException {
        public final int status;

        public Ctap2Exception(int status) {
            super(describe(status));
            this.status = status;
        }

        private static String describe(int status) {
            switch (status) {
                case 0x2e: return "The security key needs a PIN";
                case 0x30: return "The security key is locked, unplug it and try again";
                case 0x31: return "Wrong PIN";
                case 0x27: return "That security key is already registered";
                case 0x2b: return "No matching credential on this security key";
                case 0x2f: return "Timed out waiting for the security key to be touched";
                default: return "Security key error 0x" + Integer.toHexString(status);
            }
        }
    }
}
