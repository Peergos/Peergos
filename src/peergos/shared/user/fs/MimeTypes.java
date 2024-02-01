package peergos.shared.user.fs;

public class MimeTypes {
    final static int[] MID = new int[]{'M', 'T', 'h', 'd'};
    final static int[] ID3 = new int[]{'I', 'D', '3'};
    final static int[] MP3 = new int[]{0xff, 0xfb};
    final static int[] MP3_2 = new int[]{0xff, 0xfa};
    final static int[] RIFF = new int[]{'R', 'I', 'F', 'F'};
    final static int[] WAV_2 = new int[]{'W', 'A', 'V', 'E'};
    final static int[] FLAC = new int[]{'f', 'L', 'a', 'C'};

    final static int[] MP4 = new int[]{'f', 't', 'y', 'p'};
    final static int[] ISO2 = new int[]{'i', 's', 'o', '2'};
    final static int[] ISOM = new int[]{'i', 's', 'o', 'm'};
    final static int[] DASH = new int[]{'d', 'a', 's', 'h'};
    final static int[] MP41 = new int[]{'m', 'p', '4', '1'};
    final static int[] MP42 = new int[]{'m', 'p', '4', '2'};
    final static int[] M4V = new int[]{'M', '4', 'V', ' '};
    final static int[] AVIF = new int[]{'a', 'v', 'i', 'f'};
    final static int[] HEIC = new int[]{'h', 'e', 'i', 'c'};
    final static int[] AVC1 = new int[]{'a', 'v', 'c', '1'};
    final static int[] M4A = new int[]{'M', '4', 'A', ' '};
    final static int[] QT = new int[]{'q', 't', ' ', ' '};
    final static int[] THREEGP = new int[]{'3', 'g', 'p'};

    final static int[] FLV = new int[]{'F', 'L', 'V'};
    final static int[] FORM = new int[]{'F', 'O', 'R', 'M'};
    final static int[] AIFF = new int[]{'A', 'I', 'F', 'F'};
    final static int[] AVI = new int[]{'A', 'V', 'I', ' '};
    final static int[] OGG = new int[]{'O', 'g', 'g', 'S', 0, 2};
    final static int[] WEBM = new int[]{'w', 'e', 'b', 'm'};
    final static int[] MATROSKA_START = new int[]{0x1a, 0x45, 0xdf, 0xa3};

    final static int[] ICO = new int[]{0, 0, 1, 0};
    final static int[] CUR = new int[]{0, 0, 2, 0};
    final static int[] BMP = new int[]{'B', 'M'};
    final static int[] GIF = new int[]{'G', 'I', 'F'};
    final static int[] JPEG = new int[]{255, 216};
    final static int[] TIFF1 = new int[]{'I', 'I', 0x2A, 0};
    final static int[] TIFF2 = new int[]{'M', 'M', 0, 0x2A};
    final static int[] PNG = new int[]{137, 'P', 'N', 'G', 13, 10, 26, 10};
    final static int[] WEBP = new int[]{'W', 'E', 'B', 'P'};
    final static int[] JPEGXL = new int[]{0xff, 0x0a};
    final static int[] JPEGXL2 = new int[]{0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A};

    final static int[] PDF = new int[]{0x25, 'P', 'D', 'F'};
    final static int[] PS = new int[]{'%', '!', 'P', 'S', '-', 'A', 'd', 'o', 'b', 'e', '-'};
    final static int[] ZIP = new int[]{'P', 'K', 3, 4};
    final static int[] GZIP = new int[]{0x1f, 0x8b, 0x08};
    final static int[] RAR = new int[]{'R', 'a', 'r', '!', 0x1a, 0x07};
    final static int[] WASM = new int[]{0, 'a', 's', 'm'};

    final static int[] ICS = new int[]{'B','E','G','I','N',':','V','C','A','L','E','N','D','A','R'};
    final static int[] VCF = new int[]{'B','E','G','I','N',':','V','C','A','R','D'};
    final static int[] XML = new int[]{'<','?','x','m','l'};
    final static int[] SVG = new int[]{'<','s','v','g',' '};
    final static int[] WOFF = new int[]{'w','O','F','F'};
    final static int[] WOFF2 = new int[]{'w','O','F','2'};
    final static int[] OTF = new int[]{'O','T','T', 'O'};
    final static int[] TTF = new int[]{0, 1, 0, 0};

    // mimetypes for files that are cbor list(mimetype int, map(data)), mimetypes < 24 use a single byte
    public static final String PEERGOS_TODO = "application/vnd.peergos-todo";
    public static final int CBOR_PEERGOS_TODO_INT = 10;
    final static int[] CBOR_PEERGOS_TODO = new int[]{0x82 /* cbor list with 2 elements*/, CBOR_PEERGOS_TODO_INT};

    public static final String PEERGOS_POST = "application/vnd.peergos-post";
    public static final int CBOR_PEERGOS_POST_INT = 17;
    final static int[] CBOR_PEERGOS_POST = new int[]{0x82 /* cbor list with 2 elements*/, CBOR_PEERGOS_POST_INT};

    public static final String PEERGOS_IDENTITY = "application/vnd.peergos-identity-proof";
    public static final int CBOR_PEERGOS_IDENTITY_PROOF_INT = 24;
    final static int[] CBOR_PEERGOS_IDENTITY_PROOF = new int[]{0x82 /* cbor list with 2 elements*/, 0x18 /*single byte int*/, CBOR_PEERGOS_IDENTITY_PROOF_INT};

    public static final String PEERGOS_EMAIL = "application/vnd.peergos-email";
    public static final int CBOR_PEERGOS_EMAIL_INT = 18;
    final static int[] CBOR_PEERGOS_EMAIL = new int[]{0x82 /* cbor list with 2 elements*/, CBOR_PEERGOS_EMAIL_INT};

    final static int HEADER_BYTES_TO_IDENTIFY_MIME_TYPE = 40;

    public static String calculateMimeType(byte[] start, String filename) {
        if (equalArrays(start, BMP))
            return "image/bmp";
        if (equalArrays(start, GIF))
            return "image/gif";
        if (equalArrays(start, PNG)) {
            if (filename.endsWith(".ico"))
                return "image/vnd.microsoft.icon";
            return "image/png";
        }
        if (equalArrays(start, JPEG))
            return "image/jpg";
        if (equalArrays(start, ICO))
            return "image/x-icon";
        if (equalArrays(start, CUR))
            return "image/x-icon";
        if (equalArrays(start, RIFF) && equalArrays(start, 8, WEBP))
            return "image/webp";
        if (equalArrays(start, JPEGXL) || equalArrays(start, JPEGXL2))
            return "image/jxl";
        // many browsers don't support tiff
        if (equalArrays(start, TIFF1))
            return "image/tiff";
        if (equalArrays(start, TIFF2))
            return "image/tiff";

        if (equalArrays(start, 4, MP4)) {
            if (equalArrays(start, 8, ISO2)
                    || equalArrays(start, 8, ISOM)
                    || equalArrays(start, 8, DASH)
                    || equalArrays(start, 8, MP42)
                    || equalArrays(start, 8, MP41))
                return "video/mp4";
            if (equalArrays(start, 8, M4V))
                return "video/m4v";
            if (equalArrays(start, 8, AVIF))
                return "image/avif";
            if (equalArrays(start, 8, HEIC))
                return "image/heic";
            if (equalArrays(start, 8, M4A))
                return "audio/mp4";
            if (equalArrays(start, 8, AVC1))
                return "video/h264";
            if (equalArrays(start, 8, QT))
                return "video/quicktime";
            if (equalArrays(start, 8, THREEGP))
                return "video/3gpp";
        }
        if (equalArrays(start, 24, WEBM))
            return "video/webm";
        if (equalArrays(start, OGG) && !filename.endsWith("oga"))
            return "video/ogg";
        if (equalArrays(start, MATROSKA_START))
            return "video/x-matroska";
        if (equalArrays(start, FLV))
            return "video/x-flv";
        if (equalArrays(start, 8, AVI))
            return "video/avi";

        if (equalArrays(start, MID))
            return "audio/midi";
        if (equalArrays(start, ID3))
            return "audio/mpeg";
        if (equalArrays(start, MP3))
            return "audio/mpeg";
        if (equalArrays(start, MP3_2))
            return "audio/mpeg";
        if (equalArrays(start, FLAC))
            return "audio/flac";
        if (equalArrays(start, OGG)) // not sure how to distinguish from ogg video easily
            return "audio/ogg";
        if (equalArrays(start, RIFF) && equalArrays(start, 8, WAV_2))
            return "audio/wav";
        if (equalArrays(start, FORM) && equalArrays(start, 8, AIFF))
            return "audio/aiff";

        if (equalArrays(start, PDF))
            return "application/pdf";

        if (equalArrays(start, PS))
            return "application/postscript";

        if (equalArrays(start, WASM))
            return "application/wasm";

        if (equalArrays(start, ZIP)) {
            if (filename.endsWith(".jar"))
                return "application/java-archive";
            if (filename.endsWith(".epub"))
                return "application/epub+zip";

            if (filename.endsWith(".pptx"))
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            if (filename.endsWith(".docx"))
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            if (filename.endsWith(".xlsx"))
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

            if (filename.endsWith(".odt"))
                return "application/vnd.oasis.opendocument.text";
            if (filename.endsWith(".ods"))
                return "application/vnd.oasis.opendocument.spreadsheet";
            if (filename.endsWith(".odp"))
                return "application/vnd.oasis.opendocument.presentation";

            if (filename.endsWith(".apk"))
                return "application/vnd.android.package-archive";

            return "application/zip";
        }

        if (equalArrays(start, GZIP))
            return "application/x-gzip";

        if (equalArrays(start, RAR))
            return "application/x-rar-compressed";

        if (equalArrays(start, WOFF))
            return "font/woff";
        if (equalArrays(start, WOFF2))
            return "font/woff2";
        if (equalArrays(start, OTF))
            return "font/otf";
        if (equalArrays(start, TTF))
            return "font/ttf";

        if (equalArrays(start, CBOR_PEERGOS_TODO))
            return PEERGOS_TODO;
        if (equalArrays(start, CBOR_PEERGOS_POST))
            return PEERGOS_POST;
        if (equalArrays(start, CBOR_PEERGOS_IDENTITY_PROOF))
            return PEERGOS_IDENTITY;

        if (validUtf8(start)) {
            if (filename.endsWith(".ics") && equalArrays(start, ICS))
                return "text/calendar";
            if (filename.endsWith(".vcf") && equalArrays(start, VCF))
                return "text/vcard";
            if (filename.endsWith(".html"))
                return "text/html";
            if (filename.endsWith(".css"))
                return "text/css";
            if (filename.endsWith(".js"))
                return "text/javascript";
            if (filename.endsWith(".svg") && (equalArrays(start, XML) || equalArrays(start, SVG)))
                return "image/svg+xml";
            if (filename.endsWith(".json"))
                return "application/json";
            String prefix = new String(start).trim().toLowerCase();
            if (prefix.contains("html>") || prefix.contains("<html"))
                return "text/html";
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static boolean isContinuationByte(byte b) {
        return (b & 0xc0) == 0x80;
    }

    private static boolean validUtf8(byte[] data) {
        // UTF-8 is 1-4 bytes, 1 byte chars are ascii
        // number of leading 1 bits in first byte determine number of bytes
        for (int i=0; i < data.length; i++) {
            byte b = data[i];
            if ((b & 0xff) < 0x80)
                continue; // ASCII

            // check rest of character
            int len = Integer.numberOfLeadingZeros(~b << 24);
            if (len > 4 || len < 2) // can't start with a continuation byte
                return false;
            if (i + len > data.length) {
                for (int x = i + 1; x < data.length; x++)
                    if (! isContinuationByte(data[x]))
                        return false;
                return true; // tolerate partial final chars as this is a prefix
            }
            for (int x = 1; x < len; x++)
                if (! isContinuationByte(data[i + x]))
                    return false;
            int val;
            if (len == 2) {
                val = ((b & 0x1f) << 6) | (data[i + 1] & 0x3f);
                if (val <= 0x7f)
                    return false;
            } else if (len == 3) {
                val = ((b & 0xf) << 12) | ((data[i + 1] & 0x3f) << 6) | (data[i + 2] & 0x3f);
                if (val <= 0x7ff)
                    return false;
            } else { // len == 4
                val = ((b & 0x7) << 18) | ((data[i + 1] & 0x3f) << 12) | ((data[i + 2] & 0x3f) << 6) | (data[i + 3] & 0x3f);
                if (val <= 0xffff)
                    return false;
            }

            if (val > 0x10ffff)
                return false;
            if (val > 0xd800 && val <= 0xdfff)
                return false;
            i += len-1;
        }
        return true;
    }

    private static boolean equalArrays(byte[] a, int[] target) {
        return equalArrays(a, 0, target);
    }

    private static boolean equalArrays(byte[] a, int aOffset, int[] target) {
        if (a == null || target == null || aOffset + target.length > a.length){
            return false;
        }

        for (int i=0; i < target.length; i++) {
            if ((a[i + aOffset] & 0xff) != (target[i] & 0xff)) {
                return false;
            }
        }
        return true;
    }
}
