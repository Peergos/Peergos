package java.net;

public class HttpURLConnection extends URLConnection{

	public static final int HTTP_OK = 200;
	
	public void setRequestMethod(String method) throws ProtocolException {
		
	}
	public void setRequestProperty(String key, String value) {
		
	}
	public void setUseCaches(boolean b) {

	}
	public void setDoInput(boolean b) {
		
	}
	public int getResponseCode() {
		return HTTP_OK;
	}
	public void connect() {
	}
	public void disconnect() {
	}
}
