/*
 * Copyright 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package java.io;

public class ByteArrayOutputStream extends OutputStream {

	protected int count;
	protected byte[] buf;

	public ByteArrayOutputStream () {
		this(16);
	}

	public ByteArrayOutputStream (int initialSize) {
		buf = new byte[initialSize];
	}

	@Override
	public void write (int b) {
		if (buf.length == count) {
			grow(count + 1);
		}

		buf[count++] = (byte)b;
	}

	//added
    public void write(byte b[], int off, int len) {
        if ((off < 0) || (off > b.length) || (len < 0) ||
            ((off + len) - b.length > 0)) {
            throw new IndexOutOfBoundsException();
        }
        if (count + len > buf.length) {
			grow(count + len);
		}
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    private void grow(int required) {
        int current = this.buf.length;
        int newSize = current << 1;
        if(newSize - required < 0) {
            newSize = required;
        }

        if(newSize - 2147483639 > 0) {
            newSize = hugeCapacity(required);
        }

        byte[] newBuf = new byte[newSize];
		System.arraycopy(buf, 0, newBuf, 0, count);
        this.buf = newBuf;
    }

    private static int hugeCapacity(int size) {
        if(size < 0) {
            throw new OutOfMemoryError();
        } else {
            return size > 2147483639 ? 2147483647 : 2147483639;
        }
    }
    
	public byte[] toByteArray () {
		byte[] result = new byte[count];
		System.arraycopy(buf, 0, result, 0, count);
		return result;
	}

	public int size () {
		return count;
	}

	public String toString () {
		return new String(buf, 0, count);
	}

	public String toString (String enc) throws UnsupportedEncodingException {
		return new String(buf, 0, count, enc);
	}

	public void close() throws IOException {
	}
}
