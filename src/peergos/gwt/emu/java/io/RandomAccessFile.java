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

/** Saves binary data to the local storage; currently using hex encoding. The string is prefixed with "hex:"
 * @author haustein */
public class RandomAccessFile implements Closeable/* implements DataOutput, DataInput, Closeable */{
	
	public RandomAccessFile (File file, String mode) throws FileNotFoundException {
		throw new Error("Not implemented");
	}

	@Override
	public void close() throws IOException {
	}

	public long length () throws IOException {
		return -1;
	}
	
	public int read () throws IOException {
		return -1;
	}
	
	public int read (byte b[]) throws IOException {
		return -1;
	}

	public int read (byte b[], int offset, int len) throws IOException {
		return -1;
	}

	public void seek (long pos) throws IOException {
	}
	
	public void setLength (long newLength) throws IOException {
	}
	
	public void write (byte b[]) throws IOException {
	}

}