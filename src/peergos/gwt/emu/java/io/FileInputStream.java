package java.io;

public class FileInputStream extends InputStream{
	protected InputStream in;

	public FileInputStream (InputStream in) {
		this.in = in;
	}

	public FileInputStream (File file) {
	}

	public FileInputStream (String filename) {
	}
	
	public int read () throws IOException {
		return in.read();
	}

	public int read (byte b[]) throws IOException {
		return read(b, 0, b.length);
	}

	public int read (byte b[], int off, int len) throws IOException {
		return in.read(b, off, len);
	}

	public long skip (long n) throws IOException {
		return 0;
	}

	//added
	public int available () throws IOException{
		return in.available();
	}

	public void close () throws IOException {
		in.close();
	}

	public synchronized void mark (int readlimit) {
	}

	public synchronized void reset () throws IOException {
	}

	public boolean markSupported () {
		return false;
	}
}
