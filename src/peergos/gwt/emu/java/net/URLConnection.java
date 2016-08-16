package java.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class URLConnection {

	public InputStream getInputStream() throws IOException {
		return null;
	}
	public OutputStream getOutputStream() throws IOException {
		return null;
	}
    public Map<String,List<String>> getHeaderFields() {
        return Collections.emptyMap();
    }
    public void setDoOutput(boolean dooutput) {
    	
    }
    public String getContentEncoding() {
    	return null;
    }
}
