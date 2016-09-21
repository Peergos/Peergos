package java.net;

import java.io.InputStream;


public class URL {

	public URL(String protocol, String host, int port, String file) throws MalformedURLException
	{
	}
	
	public URL(String url) throws MalformedURLException
	{
	}   
	
	public URL(URL context, String spec) throws MalformedURLException {
		
	}
	
    public URLConnection openConnection() throws java.io.IOException {
    	return null;
    }
    
    public final InputStream openStream() throws java.io.IOException {
    	return null;
    }
}
