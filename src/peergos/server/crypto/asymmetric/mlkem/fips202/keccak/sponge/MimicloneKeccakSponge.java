package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.sponge;

import peergos.server.crypto.asymmetric.mlkem.CryptoUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static peergos.server.crypto.asymmetric.mlkem.CryptoUtils.mod;

public class MimicloneKeccakSponge {

    // NOTE:
    // We are only implementing SHAKE128 and SHAKE256 XOF function to support FIPS203.
    // The original spec operates on bit strings, but the specific rate and capacity we
    // support will only use bit strings that align on byte boundaries.

    final int bitRate;
    final int byteRate;
    final int bitCapacity;
    final int byteCapacity;

    private MimicloneKeccakSponge(int bitLength) {
        bitRate = 1600 - (bitLength << 1);
        byteRate = bitRate >> 3;
        bitCapacity = 1600 - bitRate;
        byteCapacity = bitCapacity >> 3;
    }

    private long[] state = new long[25];
    private long[] absorbedState = new long[25];
    private byte[] dataQueue = new byte[192];
    private int bitsInQueue, fixedOutputLength;
    private boolean squeezing;

    public static MimicloneKeccakSponge create(int bitLength) {
        return new MimicloneKeccakSponge(bitLength);
    }

    // NOTE: This implementation assumes we will always pad a message, even if the message
    // falls exactly on a rate boundary
    byte[] pad(int messageLengthInBytes) {

        int paddingBytes = byteRate - mod(messageLengthInBytes, byteRate);

        // Message does not need padding
        if (paddingBytes == 0) {
            if (messageLengthInBytes >= byteRate) {
                return new byte[0];
            } else {
                return new byte[] {
                        (byte)0x80L,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x01,
                };
            }

        }

        // Message needs single padding byte
        else if (paddingBytes == 1) {
            return new byte[]{
                    (byte) 0b10000001L
            };
        }

        // Message needs at least two padding bytes
        else if (paddingBytes > 1) {
            byte firstByte = (byte) 0b10000000L;
            byte middleByte = (byte) 0b00000000L;
            byte lastByte = (byte) 0b00000001L;
            ByteBuffer buffer = ByteBuffer.allocate(paddingBytes);
            buffer.put(firstByte);
            if (paddingBytes > 2) {
                for (int i = 0; i < paddingBytes - 2; i++) {
                    buffer.put(middleByte);
                }
            }
            buffer.put(lastByte);
            return buffer.array();
        }

        // Something went wonky, our modulus operation returned a negative number
        else {
            throw new IllegalStateException("Modulus operation returned a negative value: " + paddingBytes);
        }

    }

    void absorb(byte[] data, int offset, int length) {

        // Ensure bit queue has even length
        if ((bitsInQueue & 7) != 0) {
            throw new IllegalStateException("Attempt to absorb with odd length queue");
        }

        // Ensure we aren't currently trying to squeeze the sponge
        if (squeezing) {
            throw new IllegalStateException("Attempt to absorb while squeezing");
        }

        // Right shift by 3 is division by 2^3 = 8
        var bytesInQueue = bitsInQueue >> 3;

        // Determine number of bytes available in the queue
        var available = byteRate - bytesInQueue;

        // Ensure we have enough room in the data queue
        if (length < available) {

            // Copy the data into the data queue
            System.arraycopy(data, offset, dataQueue, bytesInQueue, length);

            // Add 8*length to the bits in the queue
            bitsInQueue += length << 3;

        }

        var count = 0;

        // If there is any room available in the data queue
        if (bytesInQueue > 0) {

            // Fill the available space with the data
            System.arraycopy(data, offset, dataQueue, bytesInQueue, available);

            count += available;
            // TODO: KeccakAbsorb the data
        }

        int remaining;
        while ((remaining = length - count) >= byteRate) {
            // TODO: KeccakAbsorb the data
            count += byteRate;
        }

        // Put the rest of the data into the queue
        System.arraycopy(data, offset + count, dataQueue, 0, remaining);
        bitsInQueue = remaining << 3;
    }

    void padAndSwitchToSqueezingPhase() {
        dataQueue[bitsInQueue >> 3] |= (byte)(1 << (bitsInQueue & 7));

        if (++bitsInQueue == bitRate) {
            // TODO: KeccakAbsorb (dataQueue, 0)
        } else {
            int full = bitsInQueue >> 6;
            int partial = bitsInQueue & 63;
            int off = 0;
            for (int i = 0; i < full; i++) {
                state[i] ^= CryptoUtils.bytesToLong(ByteOrder.LITTLE_ENDIAN, dataQueue, off);
                off += 8;
            }

            if (partial > 0) {
                long mask = (1L << partial) - 1L;
                state[full] ^= CryptoUtils.bytesToLong(ByteOrder.LITTLE_ENDIAN, dataQueue, off) & mask;
            }
        }

        // XOR the most significant bit of the state
        state[(bitRate - 1) >> 6] ^= (1L << 63);

        bitsInQueue = 0;
        squeezing = true;
    }

    void squeeze(byte[] output, int offset, long outputBitLength) {

        if (!squeezing) {
            padAndSwitchToSqueezingPhase();

            // Save the absorbed state so we can squeeze multiple times
            System.arraycopy(state, 0, absorbedState, 0, 25);
        }

        // Ensure we are squeezing bytes
        if ((outputBitLength & 7L) != 0L) {
            throw new IllegalArgumentException("Squeezing requires the outputBitLength be a multiple of 8");
        }

        long i = 0;
        bitsInQueue = 0;

        // Copy the absorbed state into the state
        System.arraycopy(absorbedState, 0, state, 0, 25);

        while (i < outputBitLength) {
            if (bitsInQueue == 0) {
                // TODO: KeccakExtract();
            }

            int partialBlock = (int) Math.min(bitsInQueue, outputBitLength - i);
            System.arraycopy(dataQueue, (bitRate - bitsInQueue) >> 3, output, offset + (int)(i >> 3), partialBlock >> 3);

            bitsInQueue -= partialBlock;
            i += partialBlock;
        }

    }

}
