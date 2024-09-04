package peergos.shared;

import peergos.shared.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class QRCodeEncoder {


    public static final int BW_MODE = 0;
    public static final int GREYSCALE_MODE = 1;
    public static final int COLOR_MODE = 2;

    private static void write(int i, OutputStream out, CRC32 crc) throws IOException {
        byte b[]={(byte)((i>>24)&0xff),(byte)((i>>16)&0xff),(byte)((i>>8)&0xff),(byte)(i&0xff)};
        write(b, out, crc);
    }

    private static void write(byte b[], OutputStream out, CRC32 crc) throws IOException {
        out.write(b);
        crc.update(b);
    }

    public static byte[] encodeToPng(int mode, int width, int height, BitMatrix pixels) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        final byte id[] = {-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13};
        CRC32 crc = new CRC32();
        write(id, bout, crc);
        crc.reset();
        write("IHDR".getBytes(), bout, crc);
        write(width, bout, crc);
        write(height, bout, crc);
        byte head[]=null;
        switch (mode) {
            case BW_MODE: head=new byte[]{1, 0, 0, 0, 0}; break;
            case GREYSCALE_MODE: head=new byte[]{8, 0, 0, 0, 0}; break;
            case COLOR_MODE: head=new byte[]{8, 2, 0, 0, 0}; break;
        }
        write(head, bout, crc);
        write((int) crc.getValue(), bout, crc);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        OutputStream dos =  new DeflaterOutputStream(compressed, new Deflater(9));
        int pixel;
        int color;
        int colorset;
        switch (mode) {
            case BW_MODE:
                int rest = width % 8;
                int bytes = width / 8;
                for (int y=0; y < height; y++) {
                    dos.write(0);
                    for (int x=0; x < bytes; x++) {
                        colorset=0;
                        for (int sh=0; sh < 8; sh++) {
                            pixel = getPixel(x*8 + sh,y, pixels);
                            color = ((pixel >> 16) & 0xff);
                            color += ((pixel >> 8) & 0xff);
                            color += (pixel & 0xff);
                            colorset <<= 1;
                            if (color >= 3*128)
                                colorset |= 1;
                        }
                        dos.write((byte)colorset);
                    }
                    if (rest>0) {
                        colorset=0;
                        for (int sh=0; sh < width % 8; sh++) {
                            pixel = getPixel(bytes*8 + sh,y, pixels);
                            color = ((pixel >> 16) & 0xff);
                            color += ((pixel >> 8) & 0xff);
                            color += (pixel & 0xff);
                            colorset <<= 1;
                            if (color >= 3*128)
                                colorset |= 1;
                        }
                        colorset <<= 8-rest;
                        dos.write((byte)colorset);
                    }
                }
                break;
            case GREYSCALE_MODE:
                for (int y=0; y < height; y++) {
                    dos.write(0);
                    for (int x=0; x < width; x++) {
                        pixel = getPixel(x,y, pixels);
                        color = ((pixel >> 16) & 0xff);
                        color += ((pixel >> 8) & 0xff);
                        color += (pixel & 0xff);
                        dos.write((byte)(color/3));
                    }
                }
                break;
             case COLOR_MODE:
                for (int y=0; y < height; y++) {
                    dos.write(0);
                    for (int x=0; x < width; x++) {
                        pixel = getPixel(x,y, pixels);
                        dos.write((byte)((pixel >> 16) & 0xff));
                        dos.write((byte)((pixel >> 8) & 0xff));
                        dos.write((byte)(pixel & 0xff));
                    }
                }
                break;
        }
        dos.close();
        write(compressed.size(), bout, crc);
        crc.reset();
        write("IDAT".getBytes(), bout, crc);
        write(compressed.toByteArray(), bout, crc);
        write((int) crc.getValue(), bout, crc);
        write(0, bout, crc);
        crc.reset();
        write("IEND".getBytes(), bout, crc);
        write((int) crc.getValue(), bout, crc);
        return bout.toByteArray();
    }

    private static int getPixel(int x, int y, BitMatrix pixels) {
        if (pixels.get(x, y))
            return  0xff000000;
        else
            return  0xffffffff;
    }
}
