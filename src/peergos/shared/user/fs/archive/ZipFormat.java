package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

/** The constants and little endian primitives of the ZIP format.
 */
class ZipFormat {

    static final int LOCAL_HEADER_SIG = 0x04034b50;
    static final int CENTRAL_HEADER_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int ZIP64_EOCD_SIG = 0x06064b50;
    static final int ZIP64_LOCATOR_SIG = 0x07064b50;

    static final int EOCD_SIZE = 22;
    static final int ZIP64_EOCD_SIZE = 56;
    static final int ZIP64_LOCATOR_SIZE = 20;
    static final int LOCAL_HEADER_SIZE = 30;
    static final int CENTRAL_HEADER_SIZE = 46;
    static final int MAX_COMMENT_SIZE = 0xFFFF;

    static final int STORED = 0;
    static final int DEFLATED = 8;

    static final int FLAG_ENCRYPTED = 1;
    static final int FLAG_DATA_DESCRIPTOR = 1 << 3;
    static final int FLAG_STRONG_ENCRYPTION = 1 << 6;
    static final int FLAG_UTF8_NAMES = 1 << 11;

    static final int ZIP64_EXTRA_ID = 0x0001;
    static final int UNICODE_NAME_EXTRA_ID = 0x7075;
    static final int EXTENDED_TIMESTAMP_EXTRA_ID = 0x5455;

    static final long U32_MAX = 0xFFFFFFFFL;
    static final int U16_MAX = 0xFFFF;

    /** The 128 high characters of code page 437, the ZIP default encoding for entry names. */
    private static final String CP437_HIGH =
            "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
            "áíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
            "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
            "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ";

    static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    static int i32(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16) | ((d[i + 3] & 0xFF) << 24);
    }

    static long u32(byte[] d, int i) {
        return i32(d, i) & U32_MAX;
    }

    static long u64(byte[] d, int i) {
        long low = u32(d, i);
        long high = u32(d, i + 4);
        if (high > Integer.MAX_VALUE)
            throw new IllegalStateException("Zip value too large: " + high + " * 2^32");
        return low | (high << 32);
    }

    static String name(byte[] d, int offset, int length, boolean utf8) {
        if (utf8) {
            byte[] raw = new byte[length];
            System.arraycopy(d, offset, raw, 0, length);
            try {
                return new String(raw, "UTF-8");
            } catch (Exception e) {
                throw new IllegalStateException("Invalid UTF-8 in zip entry name");
            }
        }
        StringBuilder res = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int b = u8(d, offset + i);
            res.append(b < 0x80 ? (char) b : CP437_HIGH.charAt(b - 0x80));
        }
        return res.toString();
    }

    /** Convert an MS-DOS date and time pair to milliseconds since the epoch, in the local timezone,
     *  which is all the DOS format records.
     */
    static long dosTimeToMillis(int dosTime, int dosDate) {
        if (dosDate == 0)
            return 0;
        int year = 1980 + ((dosDate >> 9) & 0x7F);
        int month = (dosDate >> 5) & 0x0F;
        int day = dosDate & 0x1F;
        int hour = (dosTime >> 11) & 0x1F;
        int minute = (dosTime >> 5) & 0x3F;
        int second = (dosTime & 0x1F) * 2;
        if (month < 1 || month > 12 || day < 1 || day > 31)
            return 0;
        long days = daysFromCivil(year, month, day);
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L;
    }

    /** Days since 1970-01-01 from a proleptic Gregorian date, from Howard Hinnant's civil_from_days.
     */
    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    static void writeU16(byte[] d, int i, int value) {
        d[i] = (byte) value;
        d[i + 1] = (byte) (value >> 8);
    }

    static void writeU32(byte[] d, int i, long value) {
        for (int b = 0; b < 4; b++)
            d[i + b] = (byte) (value >> (8 * b));
    }

    static void writeU64(byte[] d, int i, long value) {
        for (int b = 0; b < 8; b++)
            d[i + b] = (byte) (value >> (8 * b));
    }

    static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode " + value);
        }
    }

    /** The MS-DOS date and time pair for a moment, as {time, date}.
     */
    static int[] millisToDosTime(long millis) {
        long seconds = Math.floorDiv(millis, 1000L);
        long days = Math.floorDiv(seconds, 86400L);
        int secondOfDay = (int) Math.floorMod(seconds, 86400L);
        int[] date = civilFromDays(days);
        if (date[0] < 1980)
            return new int[]{0, 0x21}; // the earliest the format can express: 1980-01-01
        int dosDate = ((date[0] - 1980) << 9) | (date[1] << 5) | date[2];
        int dosTime = ((secondOfDay / 3600) << 11) | (((secondOfDay / 60) % 60) << 5) | ((secondOfDay % 60) / 2);
        return new int[]{dosTime, dosDate};
    }

    /** The proleptic Gregorian date of a day count since 1970-01-01, as {year, month, day}, from
     *  Howard Hinnant's civil_from_days.
     */
    private static int[] civilFromDays(long days) {
        long z = days + 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        return new int[]{(int) (y + (m <= 2 ? 1 : 0)), (int) m, (int) d};
    }

    /** Read exactly length bytes, since an AsyncReader is free to return fewer.
     */
    static CompletableFuture<byte[]> readFully(AsyncReader source, byte[] res, int offset, int length) {
        if (length == 0)
            return Futures.of(res);
        return source.readIntoArray(res, offset, length)
                .thenCompose(read -> {
                    if (read <= 0)
                        throw new IllegalStateException("Unexpected end of zip file");
                    return read == length ? Futures.of(res) : readFully(source, res, offset + read, length - read);
                });
    }

    static CompletableFuture<byte[]> read(AsyncReader source, long offset, int length) {
        return source.seek(offset)
                .thenCompose(at -> readFully(at, new byte[length], 0, length));
    }

    private ZipFormat() {}
}
