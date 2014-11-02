/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp.util;

import java.nio.*;

/**
 * First in - first out byte buffer
 *
 * @author Pawel Domas
 */
public class ByteFifoBuffer
{
    /**
     * Backing byte array
     */
    private byte[] array;
    /**
     * Current write position
     */
    private int write_pos = 0;
    /**
     * Stored bytes count
     */
    private int buffered = 0;
    /**
     * Current read position
     */
    private int read_pos = 0;
    
    /**
     * Creates buffer of specified size
     *
     * @param len buffer's size
     */
    public ByteFifoBuffer(int len)
    {
        array = new byte[len];
    }

    /**
     * @return buffer's capacity
     */
    public int length()
    {
        return array.length;
    }

    /**
     * Method reads <tt>count</tt> bytes into <tt>out_buffer</tt>. 
     * Current read position is incremented by count of bytes 
     * that has been successfully read.
     *
     * @param out_buffer
     * @param count
     * @return bytes successfully read
     */
    public int read(byte[] out_buffer, int count)
    {
        return read(out_buffer, 0, count);
    }
    
    /**
     * Read with buffer offset
     * @param out_buffer
     * @param buff_offset
     * @param count bytes to read
     * @return read byte count
     */
    public int read(byte[] out_buffer, int buff_offset, int count) {
        count = readLimit(count);
        if (count > 0)
        {
            readOp(out_buffer, buff_offset, count, array, read_pos, array.length);
            read_pos = (read_pos + count) % array.length;
            buffered -= count;
        }
        return count;
    }

    /**
     * Limits <tt>desiredReadCount</tt> to count that is actually available
     * @param desiredReadCount
     * @return 
     */
    private int readLimit(int desiredReadCount)
    {
        return desiredReadCount > buffered ? buffered : desiredReadCount;
    }

    /**
     * Utility method used for read operations
     * @param outBuffer
     * @param dst_buff_offset
     * @param count
     * @param srcBuffer
     * @param read_pos
     * @param buff_len 
     */
    private static void readOp(byte[] outBuffer, int dst_buff_offset, int count,
                               byte[] srcBuffer, int read_pos, int buff_len)
    {
        if (read_pos + count <= buff_len)
        {
            //single operation
            System.arraycopy(srcBuffer, read_pos, outBuffer, dst_buff_offset, 
                                                             count);
        }
        else
        {
            //two operations
            int tillEndCount = buff_len - read_pos;
            System.arraycopy(srcBuffer, read_pos, outBuffer,
                             dst_buff_offset, tillEndCount);
            int fromStartCount = count - tillEndCount;
            System.arraycopy(srcBuffer, 0, outBuffer,
                             dst_buff_offset + tillEndCount, fromStartCount);
        }
    }

    /**
     *
     * @return space left in buffer for write
     */
    public int getWriteRemaining()
    {
        return array.length - buffered;
    }

    /**
     *
     * @return bytes stored in buffer and available for reading
     */
    public int getBuffered()
    {
        return buffered;
    }

    /**
     * Writes <tt>count</tt> of bytes from the <tt>buffer</tt>
     *
     * @param buffer
     * @param count
     * @return bytes successfully written to buffer
     */
    public int write(byte[] buffer, int count)
    {
        return write(buffer, 0, count);
    }

    /**
     *
     * @param data source data
     * @param offset source buffer's offset
     * @param count
     * @return byte count actually read
     */
    public int write(byte[] data, int offset, int count)
    {
        /*
         * System.out.println("----write " + this + " " + len + " buffered " +
         * GetBuffered() + " buff avail: " + GetWriteRemaining());
         */
        count = writeLimit(count);
        writeOp(data, offset, count, array, write_pos, array.length);
        write_pos = (write_pos + count) % array.length;
        buffered += count;
        /*
         * System.out.println("----write "+this+" "+len+" buffered
         * "+GetBuffered()); for(int i=0; i < len; i++){
         * System.out.println("WDATA: "+data[i]); }
         */
        return count;
    }

    /**
     * Utility method for write operations
     * @param inBuffer
     * @param inOffset
     * @param count
     * @param outBuffer
     * @param write_pos
     * @param buff_len 
     */
    private static void writeOp(byte[] inBuffer,
                                int inOffset,
                                int count,
                                byte[] outBuffer,
                                int write_pos,
                                int buff_len)
    {
        if ((write_pos + count) <= buff_len)
        {
            //single op
            System.arraycopy(inBuffer, inOffset, outBuffer, write_pos, count);
        }
        else
        {
            //till end and from beginning
            int tillEndCount;
            int fromStartCount;
            tillEndCount = buff_len - write_pos;
            fromStartCount = count - tillEndCount;
            System.arraycopy(inBuffer, inOffset, outBuffer,
                             write_pos, tillEndCount);
            System.arraycopy(inBuffer, inOffset + tillEndCount,
                             outBuffer, 0, fromStartCount);            
        }
    }

    /**
     * Limits <tt>desiredWriteCount</tt> to what's actually available
     * @param desiredWriteCount
     * @return 
     */
    private int writeLimit(int desiredWriteCount)
    {
        return desiredWriteCount > (array.length - buffered) ? 
            (array.length - buffered) : desiredWriteCount;
    }

    /**
     * Checks if new write position is correct
     * 
     * @param newWrPos new write position
     */
    private void assertWriteLimit(int newWrPos)
        throws IllegalArgumentException
    {
        int spaceReq;
        int availSpace = getWriteRemaining();
        if (newWrPos < write_pos)
        {
            spaceReq = newWrPos + (array.length - write_pos);
        }
        else
        {
            spaceReq = newWrPos - write_pos;
        }

        if (spaceReq > availSpace)
        {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Advances current buffer's write position by <tt>count</tt> bytes
     * 
     * @param count
     */
    public void consumeWriteBuffer(int count)
        throws IllegalArgumentException,
               BufferOverflowException
    {
        if (count > getWriteRemaining())
        {
            throw new BufferOverflowException();
        }
        if (count < 0)
        {
            throw new IllegalArgumentException();
        }
        int newPos = (write_pos + count) % array.length;
        assertWriteLimit(newPos);
        
        write_pos = newPos;
        buffered += count;
    }

    /**
     * Sets new buffer's capacity
     *
     * @param new_size
     * @return <tt>true</tt> if operation is possible to perform, that is if new
     * buffered data fits into new buffer
     */
    public boolean setCapacity(int new_size)
    {
        if (new_size < getBuffered())
        {
            return false;
        }
        byte[] newBuff = new byte[new_size];
        readOp(newBuff, 0, buffered, array, read_pos, array.length);
        this.array = newBuff;
        return true;
    }

    /**
     * Aligns current read position by <tt>count</tt>
     *
     * @param count
     * @throws BufferUnderflowException if new position exceeds buffered data
     * count
     */
    public void consumeReadData(int count)
        throws IllegalArgumentException,
               BufferUnderflowException
    {
        /*
         * System.out.println("Consume read " + this + " " + count + " read pos:
         * " + read_pos);
         */
        if (count > buffered)
        {
            throw new BufferUnderflowException();
        }
        if (count < 0)
        {
            throw new IllegalArgumentException();
        }
        this.read_pos = (read_pos + count) % array.length;
        buffered -= count;
    }

    /**
     * Reads <tt>count</tt> bytes from buffer without storing new read position
     *
     * @param dst_buff
     * @param dst_buff_offset offset of destination buffer
     * @param count bytes to read
     * @param offset from current read position
     * @return bytes successfully read
     */
    public int readOffset(byte[] dst_buff,
                          int dst_buff_offset,
                          int count,
                          int offset)
    {
        //TODO: not sure if should decrease read count or throw an exception
        /*
         * System.out.println("Read dst offset " + dst_buff_offset + " offset "
         * + offset + " len " + count + " " + this);
         */
        int read_offset = (this.read_pos + offset) % array.length;
        readOp(dst_buff, dst_buff_offset, count, array, read_offset, array.length);

        return count;
    }

    /**
     * Writes <tt>count</tt> bytes from <tt>data</tt> to the buffer without
     * affecting buffer's write position
     *
     * @param data
     * @param count
     * @param nOffset from buffer's write position
     * @return bytes successfully written
     */
    public int writeOffset(byte[] data, int count, int nOffset)
        throws BufferOverflowException
    {
        if (count > getWriteRemaining())
        {
            throw new BufferOverflowException();
        }
        if (count < 0)
        {
            throw new IllegalArgumentException();
        }
        int offWritePos = (this.write_pos + nOffset) % array.length;
        count = writeLimit(count);
        assertWriteLimit(offWritePos + count);
        writeOp(data, 0, count, array, offWritePos, array.length);
        
        return count;
    }

    public void resetReadPosition()
    {
        this.read_pos = 0;
    }

    public void resetWritePosition()
    {
        this.write_pos = 0;
        this.buffered = 0;
    }
}
