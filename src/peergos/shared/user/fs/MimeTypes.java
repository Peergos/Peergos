package peergos.shared.user.fs;

public class MimeTypes {
    final static int[] ID3 = new int[]{'I', 'D', '3'};
    final static int[] MP3 = new int[]{0xff, 0xfb};
    final static int[] WAV_1 = new int[]{'R', 'I', 'F', 'F'};
    final static int[] WAV_2 = new int[]{'W', 'A', 'V', 'E'};

    final static int[] MP4 = new int[]{'f', 't', 'y', 'p'};
    final static int[] ISO2 = new int[]{'i', 's', 'o', '2'};
    final static int[] ISOM = new int[]{'i', 's', 'o', 'm'};
    final static int[] MP41 = new int[]{'m', 'p', '4', '1'};
    final static int[] MP42 = new int[]{'m', 'p', '4', '2'};
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

    final static int HEADER_BYTES_TO_IDENTIFY_MIME_TYPE = 28;

    public static final String calculateMimeType(byte[] start) {
        if (equalArrays(start, BMP))
            return "image/bmp";
        if (equalArrays(start, GIF))
            return "image/gif";
        if (equalArrays(start, PNG))
            return "image/png";
        if (equalArrays(start, JPEG))
            return "image/jpg";
        if (equalArrays(start, ICO))
            return "image/x-icon";
        if (equalArrays(start, CUR))
            return "image/x-icon";
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

        if (equalArrays(start, ID3))
            return "audio/mpeg";
        if (equalArrays(start, MP3))
            return "audio/mpeg";
        if (equalArrays(start, OGG)) // not sure how to distinguish from ogg video easily
            return "audio/ogg";
        if (equalArrays(start, WAV_1) && equalArrays(start, 8, WAV_2))
            return "audio/wav";

        if (allAscii(start))
            return "text/plain";
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
