/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.nio;

/** A buffer of chars.
 * <p>
 * A char buffer can be created in either one of the following ways:
 * </p>
 * <ul>
 * <li>{@link #allocate(int) Allocate} a new char array and create a buffer based on it;</li>
 * <li>{@link #wrap(char[]) Wrap} an existing char array to create a new buffer;</li>
 * <li>{@link #wrap(CharSequence) Wrap} an existing char sequence to create a new buffer;</li>
 * <li>Use {@link java.nio.ByteBuffer#asCharBuffer() ByteBuffer.asCharBuffer} to create a char buffer based on a byte buffer.</li>
 * </ul>
 * 
 * @since Android 1.0 */
public abstract class CharBuffer extends Buffer implements Comparable<CharBuffer>, CharSequence, Appendable {// , Readable {

	/** Constructs a {@code CharBuffer} with given capacity.
	 * 
	 * @param capacity the capacity of the buffer.
	 * @since Android 1.0 */
	CharBuffer (int capacity) {
		super(capacity);
	}

	/** Writes the given char to the current position and increases the position by 1.
	 * 
	 * @param c the char to write.
	 * @return this buffer.
	 * @exception BufferOverflowException if position is equal or greater than limit.
	 * @exception ReadOnlyBufferException if no changes may be made to the contents of this buffer.
	 * @since Android 1.0 */
	public abstract CharBuffer put (char c);

	/** Writes chars from the given char array to the current position and increases the position by the number of chars written.
	 * <p>
	 * Calling this method has the same effect as {@code put(src, 0, src.length)}.
	 * </p>
	 * 
	 * @param src the source char array.
	 * @return this buffer.
	 * @exception BufferOverflowException if {@code remaining()} is less than {@code src.length}.
	 * @exception ReadOnlyBufferException if no changes may be made to the contents of this buffer.
	 * @since Android 1.0 */
	public final CharBuffer put (char[] src) {
		return put(src, 0, src.length);
	}

	/** Writes chars from the given char array, starting from the specified offset, to the current position and increases the
	 * position by the number of chars written.
	 * 
	 * @param src the source char array.
	 * @param off the offset of char array, must not be negative and not greater than {@code src.length}.
	 * @param len the number of chars to write, must be no less than zero and no greater than {@code src.length - off}.
	 * @return this buffer.
	 * @exception BufferOverflowException if {@code remaining()} is less than {@code len}.
	 * @exception IndexOutOfBoundsException if either {@code off} or {@code len} is invalid.
	 * @exception ReadOnlyBufferException if no changes may be made to the contents of this buffer.
	 * @since Android 1.0 */
	public CharBuffer put (char[] src, int off, int len) {
		int length = src.length;
		if ((off < 0) || (len < 0) || (long)off + (long)len > length) {
			throw new IndexOutOfBoundsException();
		}

		if (len > remaining()) {
			throw new BufferOverflowException();
		}
		for (int i = off; i < off + len; i++) {
			put(src[i]);
		}
		return this;
	}
}
