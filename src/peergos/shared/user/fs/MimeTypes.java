package peergos.shared.user.fs;

public class MimeTypes {
    final static int[] ID3 = new int[]{'I', 'D', '3'};
    final static int[] MP3 = new int[]{0xff, 0xfb};

    final static int[] MP4 = new int[]{'f', 't', 'y', 'p'};
    final static int[] FLV = new int[]{'F', 'L', 'V'};
    final static int[] AVI = new int[]{'A', 'V', 'I', ' '};
    final static int[] OGG = new int[]{'O', 'g', 'g'};
    final static int[] WEBM = new int[]{'w', 'e', 'b', 'm'};
    final static int[] MATROSKA = new int[]{0x6D, 0x61, 0x74, 0x72, 0x6F, 0x73, 0x6B, 0x61};

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
        if (compareArrayContents(start, BMP))
            return "image/bmp";
        if (compareArrayContents(start, GIF))
            return "image/gif";
        if (compareArrayContents(start, PNG))
            return "image/png";
        if (compareArrayContents(start, JPEG))
            return "image/jpg";
        if (compareArrayContents(start, ICO))
            return "image/x-icon";
        if (compareArrayContents(start, CUR))
            return "image/x-icon";
        // many browsers don't support tiff
        if (compareArrayContents(start, TIFF1))
            return "image/tiff";
        if (compareArrayContents(start, TIFF2))
            return "image/tiff";

        if (compareArrayContents(start, 4, MP4))
            return "video/mp4";
        if (compareArrayContents(start, 24, WEBM))
            return "video/webm";
        if (compareArrayContents(start, OGG))
            return "video/ogg";
        if (compareArrayContents(start, 8, MATROSKA))
            return "video/x-matroska";
        if (compareArrayContents(start, FLV))
            return "video/x-flv";
        if (compareArrayContents(start, 8, AVI))
            return "video/avi";

        if (compareArrayContents(start, ID3))
            return "audio/mpeg3";
        if (compareArrayContents(start, MP3))
            return "audio/mpeg3";

        if (allAscii(start))
            return "text/plain";
        return "";
    }

    private static boolean allAscii(byte[] data) {
        for (byte b : data) {
            if ((b & 0xff) > 0x80)
                return false;
            if ((b & 0xff) < 0x20 && b != (byte)0x10 && b != (byte) 0x13)
                return false;
        }
        return true;
    }

    private static boolean compareArrayContents(byte[] a, int[] target) {
        return compareArrayContents(a, 0, target);
    }

    private static boolean compareArrayContents(byte[] a, int aOffset, int[] target) {
        if (a == null || target == null){
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
