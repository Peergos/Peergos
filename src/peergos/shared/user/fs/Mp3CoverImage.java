package peergos.shared.user.fs;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** This is derived from https://github.com/mpatric/mp3agic and maintains the original MIT license
 *
 */
public class Mp3CoverImage {

    public final byte[] imageData;
    public final String mimeType;

    public Mp3CoverImage(byte[] imageData, String mimeType) {
        this.imageData = imageData;
        this.mimeType = mimeType;
    }

    public static Mp3CoverImage extractCoverArt(byte[] rawMp3) throws  NoSuchTagException, UnsupportedTagException, InvalidDataException {
        byte[] bytes = Arrays.copyOfRange(rawMp3, 0, 10);

        sanityCheckTag(bytes);
        int fileStart = AbstractID3v2Tag.HEADER_LENGTH +
                unpackSynchsafeInteger(
                        bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET],
                        bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 1],
                        bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 2],
                        bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 3]);

        byte[] headers = Arrays.copyOfRange(rawMp3, 0, fileStart);
        AbstractID3v2Tag tag = createTag(headers);
        return new Mp3CoverImage(tag.getAlbumImage(), tag.getAlbumImageMimeType());
    }

    public static class NoSuchTagException extends Exception {
        public NoSuchTagException(String msg) {super(msg);}
        public NoSuchTagException() {super();}
    }
    public static class UnsupportedTagException extends Exception {
        public UnsupportedTagException(String msg) {super(msg);}
    }
    public static class InvalidDataException extends Exception {
        public InvalidDataException(String msg) {super(msg);}

        public InvalidDataException(String msg, Throwable cause) {super(msg, cause);}
    }

    private static int unpackSynchsafeInteger(byte b1, byte b2, byte b3, byte b4) {
        int value = ((byte) (b4 & 0x7f));
        value += shiftByte((byte) (b3 & 0x7f), -7);
        value += shiftByte((byte) (b2 & 0x7f), -14);
        value += shiftByte((byte) (b1 & 0x7f), -21);
        return value;
    }

    private static int unpackInteger(byte b1, byte b2, byte b3, byte b4) {
        int value = b4 & 0xff;
        value += shiftByte(b3, -8);
        value += shiftByte(b2, -16);
        value += shiftByte(b1, -24);
        return value;
    }

    private static int shiftByte(byte c, int places) {
        int i = c & 0xff;
        if (places < 0) {
            return i << -places;
        } else if (places > 0) {
            return i >> places;
        }
        return i;
    }

    private static AbstractID3v2Tag createTag(byte[] bytes) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        sanityCheckTag(bytes);
        int majorVersion = bytes[AbstractID3v2Tag.MAJOR_VERSION_OFFSET];
        switch (majorVersion) {
            case 2:
                ID3v22Tag tag = new ID3v22Tag(bytes);
                if (tag.getFrameSets().isEmpty()) {
                    tag = new ID3v22Tag(bytes, true);
                }
                return tag;
            case 3:
                return new ID3v23Tag(bytes);
            case 4:
                return new ID3v24Tag(bytes);
        }
        throw new UnsupportedTagException("Tag version not supported");
    }

    private static boolean checkBit(byte b, int bitPosition) {
        return ((b & (0x01 << bitPosition)) != 0);
    }

    private static final String defaultCharsetName = "ISO-8859-1";

    private static String byteBufferToStringIgnoringEncodingIssues(byte[] bytes, int offset, int length) {
        try {
            return byteBufferToString(bytes, offset, length, defaultCharsetName);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String byteBufferToString(byte[] bytes, int offset, int length) throws UnsupportedEncodingException {
        return byteBufferToString(bytes, offset, length, defaultCharsetName);
    }

    private static String byteBufferToString(byte[] bytes, int offset, int length, String charsetName) throws UnsupportedEncodingException {
        if (length < 1) return "";
        return new String(bytes, offset, length, charsetName);
    }

    private static void sanityCheckTag(byte[] bytes) throws NoSuchTagException, UnsupportedTagException {
        if (bytes.length < AbstractID3v2Tag.HEADER_LENGTH) {
            throw new NoSuchTagException("Buffer too short");
        }
        if (!AbstractID3v2Tag.TAG.equals(byteBufferToStringIgnoringEncodingIssues(bytes, 0, AbstractID3v2Tag.TAG.length()))) {
            throw new NoSuchTagException();
        }
        int majorVersion = bytes[AbstractID3v2Tag.MAJOR_VERSION_OFFSET];
        if (majorVersion != 2 && majorVersion != 3 && majorVersion != 4) {
            int minorVersion = bytes[AbstractID3v2Tag.MINOR_VERSION_OFFSET];
            throw new UnsupportedTagException("Unsupported version 2." + majorVersion + "." + minorVersion);
        }
    }

    private static int indexOfTerminator(byte[] bytes, int fromIndex, int terminatorLength) {
        int marker = -1;
        for (int i = fromIndex; i <= bytes.length - terminatorLength; i++) {
            if ((i - fromIndex) % terminatorLength == 0) {
                int matched;
                for (matched = 0; matched < terminatorLength; matched++) {
                    if (bytes[i + matched] != 0) break;
                }
                if (matched == terminatorLength) {
                    marker = i;
                    break;
                }
            }
        }
        return marker;
    }

    private static int indexOfTerminatorForEncoding(byte[] bytes, int fromIndex, int encoding) {
        int terminatorLength = (encoding == EncodedText.TEXT_ENCODING_UTF_16 || encoding == EncodedText.TEXT_ENCODING_UTF_16BE) ? 2 : 1;
        return indexOfTerminator(bytes, fromIndex, terminatorLength);
    }

    private static int sizeSynchronisationWouldSubtract(byte[] bytes) {
        int count = 0;
        for (int i = 0; i < bytes.length - 2; i++) {
            if (bytes[i] == (byte) 0xff && bytes[i + 1] == 0 && ((bytes[i + 2] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 2] == 0)) {
                count++;
            }
        }
        if (bytes.length > 1 && bytes[bytes.length - 2] == (byte) 0xff && bytes[bytes.length - 1] == 0) count++;
        return count;
    }

    private static byte[] synchroniseBuffer(byte[] bytes) {
        // synchronisation is replacing instances of:
        // 11111111 00000000 111xxxxx with 11111111 111xxxxx and
        // 11111111 00000000 00000000 with 11111111 00000000
        int count = sizeSynchronisationWouldSubtract(bytes);
        if (count == 0) return bytes;
        byte[] newBuffer = new byte[bytes.length - count];
        int i = 0;
        for (int j = 0; j < newBuffer.length - 1; j++) {
            newBuffer[j] = bytes[i];
            if (bytes[i] == (byte) 0xff && bytes[i + 1] == 0 && ((bytes[i + 2] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 2] == 0)) {
                i++;
            }
            i++;
        }
        newBuffer[newBuffer.length - 1] = bytes[i];
        return newBuffer;
    }

    private static class ID3v22Tag extends AbstractID3v2Tag {

        public ID3v22Tag(byte[] buffer) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            super(buffer);
        }

        public ID3v22Tag(byte[] buffer, boolean obseleteFormat) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            super(buffer, obseleteFormat);
        }

        @Override
        protected void unpackFlags(byte[] bytes) {
            unsynchronisation = checkBit(bytes[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
            compression = checkBit(bytes[FLAGS_OFFSET], COMPRESSION_BIT);
        }
    }

    private static class ID3v23Tag extends AbstractID3v2Tag {

        public ID3v23Tag(byte[] buffer) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            super(buffer);
        }

        @Override
        protected void unpackFlags(byte[] buffer) {
            unsynchronisation = checkBit(buffer[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
            extendedHeader = checkBit(buffer[FLAGS_OFFSET], EXTENDED_HEADER_BIT);
            experimental = checkBit(buffer[FLAGS_OFFSET], EXPERIMENTAL_BIT);
        }
    }

    private static class ID3v2Frame {

        private static final int HEADER_LENGTH = 10;
        private static final int ID_OFFSET = 0;
        private static final int ID_LENGTH = 4;
        private static final int DATA_LENGTH_OFFSET = 4;
        private static final int FLAGS1_OFFSET = 8;
        private static final int FLAGS2_OFFSET = 9;
        private static final int PRESERVE_TAG_BIT = 6;
        private static final int PRESERVE_FILE_BIT = 5;
        private static final int READ_ONLY_BIT = 4;
        private static final int GROUP_BIT = 6;
        private static final int COMPRESSION_BIT = 3;
        private static final int ENCRYPTION_BIT = 2;
        private static final int UNSYNCHRONISATION_BIT = 1;
        private static final int DATA_LENGTH_INDICATOR_BIT = 0;

        protected String id;
        protected int dataLength = 0;
        protected byte[] data = null;
        private boolean preserveTag = false;
        private boolean preserveFile = false;
        private boolean readOnly = false;
        private boolean group = false;
        private boolean compression = false;
        private boolean encryption = false;
        private boolean unsynchronisation = false;
        private boolean dataLengthIndicator = false;

        public ID3v2Frame(byte[] buffer, int offset) throws InvalidDataException {
            unpackFrame(buffer, offset);
        }

        public ID3v2Frame(String id, byte[] data) {
            this.id = id;
            this.data = data;
            dataLength = data.length;
        }

        protected final void unpackFrame(byte[] buffer, int offset) throws InvalidDataException {
            int dataOffset = unpackHeader(buffer, offset);
            sanityCheckUnpackedHeader();
            data = Arrays.copyOfRange(buffer, dataOffset, dataOffset + dataLength);
        }

        protected int unpackHeader(byte[] buffer, int offset) {
            id = byteBufferToStringIgnoringEncodingIssues(buffer, offset + ID_OFFSET, ID_LENGTH);
            unpackDataLength(buffer, offset);
            unpackFlags(buffer, offset);
            return offset + HEADER_LENGTH;
        }

        protected void unpackDataLength(byte[] buffer, int offset) {
            dataLength = unpackInteger(buffer[offset + DATA_LENGTH_OFFSET], buffer[offset + DATA_LENGTH_OFFSET + 1], buffer[offset + DATA_LENGTH_OFFSET + 2], buffer[offset + DATA_LENGTH_OFFSET + 3]);
        }

        private void unpackFlags(byte[] buffer, int offset) {
            preserveTag = checkBit(buffer[offset + FLAGS1_OFFSET], PRESERVE_TAG_BIT);
            preserveFile = checkBit(buffer[offset + FLAGS1_OFFSET], PRESERVE_FILE_BIT);
            readOnly = checkBit(buffer[offset + FLAGS1_OFFSET], READ_ONLY_BIT);
            group = checkBit(buffer[offset + FLAGS2_OFFSET], GROUP_BIT);
            compression = checkBit(buffer[offset + FLAGS2_OFFSET], COMPRESSION_BIT);
            encryption = checkBit(buffer[offset + FLAGS2_OFFSET], ENCRYPTION_BIT);
            unsynchronisation = checkBit(buffer[offset + FLAGS2_OFFSET], UNSYNCHRONISATION_BIT);
            dataLengthIndicator = checkBit(buffer[offset + FLAGS2_OFFSET], DATA_LENGTH_INDICATOR_BIT);
        }

        protected void sanityCheckUnpackedHeader() throws InvalidDataException {
            for (int i = 0; i < id.length(); i++) {
                if (!((id.charAt(i) >= 'A' && id.charAt(i) <= 'Z') || (id.charAt(i) >= '0' && id.charAt(i) <= '9'))) {
                    throw new InvalidDataException("Not a valid frame - invalid tag " + id);
                }
            }
        }

        public String getId() {
            return id;
        }

        public int getLength() {
            return dataLength + HEADER_LENGTH;
        }

        public byte[] getData() {
            return data;
        }
    }

    private static class ID3v24Tag extends AbstractID3v2Tag {

        public ID3v24Tag(byte[] buffer) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            super(buffer);
        }

        @Override
        protected void unpackFlags(byte[] buffer) {
            unsynchronisation = checkBit(buffer[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
            extendedHeader = checkBit(buffer[FLAGS_OFFSET], EXTENDED_HEADER_BIT);
            experimental = checkBit(buffer[FLAGS_OFFSET], EXPERIMENTAL_BIT);
            footer = checkBit(buffer[FLAGS_OFFSET], FOOTER_BIT);
        }

        @Override
        protected boolean useFrameUnsynchronisation() {
            return unsynchronisation;
        }
    }

    private static abstract class AbstractID3v2FrameData {

        boolean unsynchronisation;

        public AbstractID3v2FrameData(boolean unsynchronisation) {
            this.unsynchronisation = unsynchronisation;
        }

        protected final void synchroniseAndUnpackFrameData(byte[] bytes) throws InvalidDataException {
            if (unsynchronisation && sizeSynchronisationWouldSubtract(bytes) > 0) {
                byte[] synchronisedBytes = synchroniseBuffer(bytes);
                unpackFrameData(synchronisedBytes);
            } else {
                unpackFrameData(bytes);
            }
        }

        protected abstract void unpackFrameData(byte[] bytes) throws InvalidDataException;
    }

    private static class EncodedText {

        public static final byte TEXT_ENCODING_ISO_8859_1 = 0;
        public static final byte TEXT_ENCODING_UTF_16 = 1;
        public static final byte TEXT_ENCODING_UTF_16BE = 2;
        public static final byte TEXT_ENCODING_UTF_8 = 3;

        public static final String CHARSET_ISO_8859_1 = "ISO-8859-1";
        public static final String CHARSET_UTF_16 = "UTF-16LE";
        public static final String CHARSET_UTF_16BE = "UTF-16BE";
        public static final String CHARSET_UTF_8 = "UTF-8";

        private static final String[] characterSets = {
                CHARSET_ISO_8859_1,
                CHARSET_UTF_16,
                CHARSET_UTF_16BE,
                CHARSET_UTF_8
        };

        private static final byte[][] terminators = {
                {0},
                {0, 0},
                {0, 0},
                {0}
        };

        private byte[] value;
        private byte textEncoding;

        public EncodedText(byte textEncoding, byte[] value) {
            // if encoding type 1 and big endian BOM is present, switch to big endian
            if ((textEncoding == TEXT_ENCODING_UTF_16) &&
                    (textEncodingForBytesFromBOM(value) == TEXT_ENCODING_UTF_16BE)) {
                this.textEncoding = TEXT_ENCODING_UTF_16BE;
            } else {
                this.textEncoding = textEncoding;
            }
            this.value = value;
            this.stripBomAndTerminator();
        }

        public EncodedText(byte textEncoding, String string) {
            this.textEncoding = textEncoding;
            value = stringToBytes(string, characterSetForTextEncoding(textEncoding));
            this.stripBomAndTerminator();
        }

        private static byte textEncodingForBytesFromBOM(byte[] value) {
            if (value.length >= 2 && value[0] == (byte) 0xff && value[1] == (byte) 0xfe) {
                return TEXT_ENCODING_UTF_16;
            } else if (value.length >= 2 && value[0] == (byte) 0xfe && value[1] == (byte) 0xff) {
                return TEXT_ENCODING_UTF_16BE;
            } else if (value.length >= 3 && (value[0] == (byte) 0xef && value[1] == (byte) 0xbb && value[2] == (byte) 0xbf)) {
                return TEXT_ENCODING_UTF_8;
            } else {
                return TEXT_ENCODING_ISO_8859_1;
            }
        }

        private static String characterSetForTextEncoding(byte textEncoding) {
            try {
                return characterSets[textEncoding];
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Invalid text encoding " + textEncoding);
            }
        }

        private void stripBomAndTerminator() {
            int leadingCharsToRemove = 0;
            if (value.length >= 2 && ((value[0] == (byte) 0xfe && value[1] == (byte) 0xff) || (value[0] == (byte) 0xff && value[1] == (byte) 0xfe))) {
                leadingCharsToRemove = 2;
            } else if (value.length >= 3 && (value[0] == (byte) 0xef && value[1] == (byte) 0xbb && value[2] == (byte) 0xbf)) {
                leadingCharsToRemove = 3;
            }
            int trailingCharsToRemove = 0;
            byte[] terminator = terminators[textEncoding];
            if (value.length - leadingCharsToRemove >= terminator.length) {
                boolean haveTerminator = true;
                for (int i = 0; i < terminator.length; i++) {
                    if (value[value.length - terminator.length + i] != terminator[i]) {
                        haveTerminator = false;
                        break;
                    }
                }
                if (haveTerminator) trailingCharsToRemove = terminator.length;
            }
            if (leadingCharsToRemove + trailingCharsToRemove > 0) {
                int newLength = value.length - leadingCharsToRemove - trailingCharsToRemove;
                byte[] newValue = new byte[newLength];
                if (newLength > 0) {
                    System.arraycopy(value, leadingCharsToRemove, newValue, 0, newValue.length);
                }
                value = newValue;
            }
        }

        public byte[] getTerminator() {
            return terminators[textEncoding];
        }

        private static byte[] stringToBytes(String s, String characterSet) {
            return s.getBytes(Charset.forName(characterSet));
        }
    }

    private static class ID3v2PictureFrameData extends AbstractID3v2FrameData {

        protected String mimeType;
        protected byte pictureType;
        protected EncodedText description;
        protected byte[] imageData;

        public ID3v2PictureFrameData(boolean unsynchronisation, byte[] bytes) throws InvalidDataException {
            super(unsynchronisation);
            synchroniseAndUnpackFrameData(bytes);
        }

        @Override
        protected void unpackFrameData(byte[] bytes) throws InvalidDataException {
            int marker = indexOfTerminator(bytes, 1, 1);
            if (marker >= 0) {
                try {
                    mimeType = byteBufferToString(bytes, 1, marker - 1);
                } catch (UnsupportedEncodingException e) {
                    mimeType = "image/unknown";
                }
            } else {
                mimeType = "image/unknown";
            }
            pictureType = bytes[marker + 1];
            marker += 2;
            int marker2 = indexOfTerminatorForEncoding(bytes, marker, bytes[0]);
            if (marker2 >= 0) {
                description = new EncodedText(bytes[0], Arrays.copyOfRange(bytes, marker, marker2));
                marker2 += description.getTerminator().length;
            } else {
                description = new EncodedText(bytes[0], "");
                marker2 = marker;
            }
            imageData = Arrays.copyOfRange(bytes, marker2, bytes.length);
        }

        public String getMimeType() {
            return mimeType;
        }

        public EncodedText getDescription() {
            return description;
        }

        public byte[] getImageData() {
            return imageData;
        }
    }

    private static class ID3v2FrameSet {

        private String id;
        private ArrayList<ID3v2Frame> frames;

        public ID3v2FrameSet(String id) {
            this.id = id;
            frames = new ArrayList<>();
        }

        public void clear() {
            frames.clear();
        }

        public void addFrame(ID3v2Frame frame) {
            frames.add(frame);
        }

        public List<ID3v2Frame> getFrames() {
            return frames;
        }

        @Override
        public String toString() {
            return this.id + ": " + frames.size();
        }
    }

    private static class ID3v2ObseletePictureFrameData extends ID3v2PictureFrameData {

        public ID3v2ObseletePictureFrameData(boolean unsynchronisation, byte[] bytes) throws InvalidDataException {
            super(unsynchronisation, bytes);
        }

        @Override
        protected void unpackFrameData(byte[] bytes) throws InvalidDataException {
            String filetype;
            try {
                filetype = byteBufferToString(bytes, 1, 3);
            } catch (UnsupportedEncodingException e) {
                filetype = "unknown";
            }
            mimeType = "image/" + filetype.toLowerCase();
            pictureType = bytes[4];
            int marker = indexOfTerminatorForEncoding(bytes, 5, bytes[0]);
            if (marker >= 0) {
                description = new EncodedText(bytes[0], Arrays.copyOfRange(bytes, 5, marker));
                marker += description.getTerminator().length;
            } else {
                description = new EncodedText(bytes[0], "");
                marker = 1;
            }
            imageData = Arrays.copyOfRange(bytes, marker, bytes.length);
        }
    }

    private static class ID3v2ObseleteFrame extends ID3v2Frame {

        private static final int HEADER_LENGTH = 6;
        private static final int ID_OFFSET = 0;
        private static final int ID_LENGTH = 3;
        protected static final int DATA_LENGTH_OFFSET = 3;

        public ID3v2ObseleteFrame(byte[] buffer, int offset) throws InvalidDataException {
            super(buffer, offset);
        }

        @Override
        protected int unpackHeader(byte[] buffer, int offset) {
            id = byteBufferToStringIgnoringEncodingIssues(buffer, offset + ID_OFFSET, ID_LENGTH);
            unpackDataLength(buffer, offset);
            return offset + HEADER_LENGTH;
        }

        @Override
        protected void unpackDataLength(byte[] buffer, int offset) {
            dataLength = unpackInteger((byte) 0, buffer[offset + DATA_LENGTH_OFFSET], buffer[offset + DATA_LENGTH_OFFSET + 1], buffer[offset + DATA_LENGTH_OFFSET + 2]);
        }

        @Override
        public int getLength() {
            return dataLength + HEADER_LENGTH;
        }
    }

    private static abstract class AbstractID3v2Tag {

        public static final String ID_IMAGE = "APIC";
        public static final String ID_IMAGE_OBSELETE = "PIC";

        protected static final String TAG = "ID3";
        protected static final String FOOTER_TAG = "3DI";
        protected static final int HEADER_LENGTH = 10;
        protected static final int FOOTER_LENGTH = 10;
        protected static final int MAJOR_VERSION_OFFSET = 3;
        protected static final int MINOR_VERSION_OFFSET = 4;
        protected static final int FLAGS_OFFSET = 5;
        protected static final int DATA_LENGTH_OFFSET = 6;
        protected static final int FOOTER_BIT = 4;
        protected static final int EXPERIMENTAL_BIT = 5;
        protected static final int EXTENDED_HEADER_BIT = 6;
        protected static final int COMPRESSION_BIT = 6;
        protected static final int UNSYNCHRONISATION_BIT = 7;
        protected static final int PADDING_LENGTH = 256;

        protected boolean unsynchronisation = false;
        protected boolean extendedHeader = false;
        protected boolean experimental = false;
        protected boolean footer = false;
        protected boolean compression = false;
        protected boolean padding = false;
        protected String version = null;
        private int dataLength = 0;
        private int extendedHeaderLength;
        private byte[] extendedHeaderData;
        private boolean obseleteFormat = false;

        private final Map<String, ID3v2FrameSet> frameSets;

        public AbstractID3v2Tag(byte[] bytes) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            this(bytes, false);
        }

        public AbstractID3v2Tag(byte[] bytes, boolean obseleteFormat) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            frameSets = new TreeMap<>();
            this.obseleteFormat = obseleteFormat;
            unpackTag(bytes);
        }

        private void unpackTag(byte[] bytes) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
            sanityCheckTag(bytes);
            int offset = unpackHeader(bytes);
            try {
                if (extendedHeader) {
                    offset = unpackExtendedHeader(bytes, offset);
                }
                int framesLength = dataLength;
                if (footer) framesLength -= 10;
                offset = unpackFrames(bytes, offset, framesLength);
                if (footer) {
                    offset = unpackFooter(bytes, dataLength);
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new InvalidDataException("Premature end of tag", e);
            }
        }

        private int unpackHeader(byte[] bytes) throws UnsupportedTagException, InvalidDataException {
            int majorVersion = bytes[MAJOR_VERSION_OFFSET];
            int minorVersion = bytes[MINOR_VERSION_OFFSET];
            version = majorVersion + "." + minorVersion;
            if (majorVersion != 2 && majorVersion != 3 && majorVersion != 4) {
                throw new UnsupportedTagException("Unsupported version " + version);
            }
            unpackFlags(bytes);
            if ((bytes[FLAGS_OFFSET] & 0x0F) != 0) throw new UnsupportedTagException("Unrecognised bits in header");
            dataLength = unpackSynchsafeInteger(bytes[DATA_LENGTH_OFFSET], bytes[DATA_LENGTH_OFFSET + 1], bytes[DATA_LENGTH_OFFSET + 2], bytes[DATA_LENGTH_OFFSET + 3]);
            if (dataLength < 1) throw new InvalidDataException("Zero size tag");
            return HEADER_LENGTH;
        }

        protected abstract void unpackFlags(byte[] bytes);

        private int unpackExtendedHeader(byte[] bytes, int offset) {
            extendedHeaderLength = unpackSynchsafeInteger(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]) + 4;
            extendedHeaderData = Arrays.copyOfRange(bytes, offset + 4, offset + 4 + extendedHeaderLength);
            return extendedHeaderLength;
        }

        protected int unpackFrames(byte[] bytes, int offset, int framesLength) {
            int currentOffset = offset;
            while (currentOffset <= framesLength) {
                ID3v2Frame frame;
                try {
                    frame = createFrame(bytes, currentOffset);
                    addFrame(frame, false);
                    currentOffset += frame.getLength();
                } catch (InvalidDataException e) {
                    break;
                }
            }
            return currentOffset;
        }

        protected void addFrame(ID3v2Frame frame, boolean replace) {
            ID3v2FrameSet frameSet = frameSets.get(frame.getId());
            if (frameSet == null) {
                frameSet = new ID3v2FrameSet(frame.getId());
                frameSet.addFrame(frame);
                frameSets.put(frame.getId(), frameSet);
            } else if (replace) {
                frameSet.clear();
                frameSet.addFrame(frame);
            } else {
                frameSet.addFrame(frame);
            }
        }

        protected ID3v2Frame createFrame(byte[] bytes, int currentOffset) throws InvalidDataException {
            if (obseleteFormat) return new ID3v2ObseleteFrame(bytes, currentOffset);
            return new ID3v2Frame(bytes, currentOffset);
        }

        private int unpackFooter(byte[] bytes, int offset) throws InvalidDataException {
            if (!FOOTER_TAG.equals(byteBufferToStringIgnoringEncodingIssues(bytes, offset, FOOTER_TAG.length()))) {
                throw new InvalidDataException("Invalid footer");
            }
            return FOOTER_LENGTH;
        }

        protected boolean useFrameUnsynchronisation() {
            return false;
        }

        public Map<String, ID3v2FrameSet> getFrameSets() {
            return frameSets;
        }

        public byte[] getAlbumImage() {
            ID3v2PictureFrameData frameData = createPictureFrameData(obseleteFormat ? ID_IMAGE_OBSELETE : ID_IMAGE);
            if (frameData != null) return frameData.getImageData();
            return null;
        }

        public String getAlbumImageMimeType() {
            ID3v2PictureFrameData frameData = createPictureFrameData(obseleteFormat ? ID_IMAGE_OBSELETE : ID_IMAGE);
            if (frameData != null && frameData.getMimeType() != null) return frameData.getMimeType();
            return null;
        }

        private ID3v2PictureFrameData createPictureFrameData(String id) {
            ID3v2FrameSet frameSet = frameSets.get(id);
            if (frameSet != null) {
                ID3v2Frame frame = frameSet.getFrames().get(0);
                ID3v2PictureFrameData frameData;
                try {
                    if (obseleteFormat)
                        frameData = new ID3v2ObseletePictureFrameData(useFrameUnsynchronisation(), frame.getData());
                    else frameData = new ID3v2PictureFrameData(useFrameUnsynchronisation(), frame.getData());
                    return frameData;
                } catch (InvalidDataException e) {
                    // do nothing
                }
            }
            return null;
        }
    }
}
