/*
 * Copyright 2018 Google Inc.
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

/**
 * Reads a stream of characters.
 */
public abstract class Reader {
  /**
   * The maximum buffer size to incrementally read in {@link #skip}.
   */
  private static final int MAX_SKIP_BUFFER_SIZE = 1024;

  /**
   * Closes the reader, and releases any associated resources.
   */
  public abstract void close() throws IOException;

  /**
   * Marks the present position in the stream. Until {@code readAheadLimit} more
   * characters have been read, the current point in the stream will be stored
   * as the mark. Calls to {@link #reset} will reposition the point in the
   * stream to the mark.
   *
   * @throws IOException If the stream does not support mark().
   */
  public void mark(int readAheadLimit) throws IOException {
    throw new IOException("Not supported");
  }

  /**
   * Returns whether {@link #mark} is implemented.
   */
  public boolean markSupported() {
    return false;
  }

  /**
   * Reads a single character, or -1 if we are at the end of the stream.
   */
  public int read() throws IOException {
    char chr[] = new char[1];
    return (read(chr) == -1) ? -1 : chr[0];
  }

  /**
   * Attempts to fill {@code buf} with characters up to the size of the array.
   */
  public int read(char[] buf) throws IOException {
    return read(buf, 0, buf.length);
  }

  /**
   * Attempts to fill {@code buf} with up to {@code len} characters. Characters
   * will be stored in {@code buf} starting at index {@code off}.
   */
  public abstract int read(char[] buf, int off, int len) throws IOException;

  /**
   * Returns whether the stream is ready for reading characters.
   */
  public boolean ready() throws IOException {
    return false;
  }

  /**
   * Attempts to reset the stream to the previous mark.
   */
  public void reset() throws IOException {
    throw new IOException("Not supported");
  }

  /**
   * Skips {@code n} characters, returning the number of characters that were actually skipped.
   */
  public long skip(long n) throws IOException {
    long remaining = n;
    int bufferSize = Math.min((int) n, MAX_SKIP_BUFFER_SIZE);
    char[] skipBuffer = new char[bufferSize];
    while (remaining > 0) {
      long numRead = read(skipBuffer, 0, (int) remaining);
      if (numRead < 0) {
        break;
      }
      remaining -= numRead;
    }
    return n - remaining;
  }
}
