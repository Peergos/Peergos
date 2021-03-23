package peergos.shared.user.fs;

public class MimeTypes {
    final static int[] MID = new int[]{'M', 'T', 'h', 'd'};
    final static int[] ID3 = new int[]{'I', 'D', '3'};
    final static int[] MP3 = new int[]{0xff, 0xfb};
    final static int[] MP3_2 = new int[]{0xff, 0xfa};
    final static int[] RIFF = new int[]{'R', 'I', 'F', 'F'};
    final static int[] WAV_2 = new int[]{'W', 'A', 'V', 'E'};

    final static int[] MP4 = new int[]{'f', 't', 'y', 'p'};
    final static int[] ISO2 = new int[]{'i', 's', 'o', '2'};
    final static int[] ISOM = new int[]{'i', 's', 'o', 'm'};
    final static int[] MP41 = new int[]{'m', 'p', '4', '1'};
    final static int[] MP42 = new int[]{'m', 'p', '4', '2'};
    final static int[] AVIF = new int[]{'a', 'v', 'i', 'f'};
    final static int[] M4A = new int[]{'M', '4', 'A', ' '};
    final static int[] QT = new int[]{'q', 't', ' ', ' '};
    final static int[] THREEGP = new int[]{'3', 'g', 'p'};

    final static int[] FLV = new int[]{'F', 'L', 'V'};
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

    final static int[] PDF = new int[]{0x25, 'P', 'D', 'F'};
    final static int[] ZIP = new int[]{'P', 'K', 3, 4};

    final static int[] ICS = new int[]{'B','E','G','I','N',':','V','C','A','L','E','N','D','A','R'};
    final static int[] VCF = new int[]{'B','E','G','I','N',':','V','C','A','R','D'};
    final static int[] XML = new int[]{'<','?','x','m','l'};
    final static int[] WOFF = new int[]{'w','O','F','F'};
    final static int[] WOFF2 = new int[]{'w','O','F','2'};

    // mimetypes for files that are cbor list(mimetype int, map(data)), mimetypes < 24 use a single byte
    public static final String PEERGOS_TODO = "application/vnd.peergos-todo";
    public static final int CBOR_PEERGOS_TODO_INT = 10;
    final static int[] CBOR_PEERGOS_TODO = new int[]{0x82 /* cbor list with 2 elements*/, CBOR_PEERGOS_TODO_INT};
    public static final String PEERGOS_POST = "application/vnd.peergos-post";
    public static final int CBOR_PEERGOS_POST_INT = 17;
    final static int[] CBOR_PEERGOS_POST = new int[]{0x82 /* cbor list with 2 elements*/, CBOR_PEERGOS_POST_INT};

    final static int HEADER_BYTES_TO_IDENTIFY_MIME_TYPE = 40;

    public static final String calculateMimeType(byte[] start, String filename) {
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
        // many browsers don't support tiff
        if (equalArrays(start, TIFF1))
            return "image/tiff";
        if (equalArrays(start, TIFF2))
            return "image/tiff";

        if (equalArrays(start, 4, MP4)) {
            if (equalArrays(start, 8, ISO2)
                    || equalArrays(start, 8, ISOM)
                    || equalArrays(start, 8, MP42)
                    || equalArrays(start, 8, MP41))
                return "video/mp4";
            if (equalArrays(start, 8, AVIF))
                return "image/avif";
            if (equalArrays(start, 8, M4A))
                return "audio/mp4";
            if (equalArrays(start, 8, QT))
                return "video/quicktime";
            if (equalArrays(start, 8, THREEGP))
                return "video/3gpp";
        }
        if (equalArrays(start, 24, WEBM))
            return "video/webm";
        if (equalArrays(start, OGG))
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
        if (equalArrays(start, OGG)) // not sure how to distinguish from ogg video easily
            return "audio/ogg";
        if (equalArrays(start, RIFF) && equalArrays(start, 8, WAV_2))
            return "audio/wav";

        if (equalArrays(start, PDF))
            return "application/pdf";

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

            return "application/zip";
        }

        if (equalArrays(start, WOFF))
            return "font/woff";
        if (equalArrays(start, WOFF2))
            return "font/woff2";

        if (equalArrays(start, CBOR_PEERGOS_TODO))
            return PEERGOS_TODO;
        if (equalArrays(start, CBOR_PEERGOS_POST))
            return PEERGOS_POST;

        if (allAscii(start)) {
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
            if (filename.endsWith(".svg") && equalArrays(start, XML))
                return "text/svg+xml";
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static boolean allAscii(byte[] data) {
        for (byte b : data) {
            if ((b & 0xff) > 0x80)
                return false;
            if ((b & 0xff) < 0x20 && b != (byte)0xA && b != (byte) 0xD)
                return false;
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
