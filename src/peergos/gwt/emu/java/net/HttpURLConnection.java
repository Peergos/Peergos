package java.net;

public class HttpURLConnection extends URLConnection{

	public static final int HTTP_OK = 200;
	public static final int HTTP_NO_CONTENT = 204;

	public void setRequestMethod(String method) throws ProtocolException {
		
	}
	public void setRequestProperty(String key, String value) {
		
	}
	public void setUseCaches(boolean b) {

	}
	public void setDoInput(boolean b) {
		
	}

	public void setConnectTimeout(int timeout) {}
	public void setReadTimeout(int timeout) {}
	public int getResponseCode() {
		return HTTP_OK;
	}
	public int getContentLength() {
		return -1;
	}
	public void connect() {
	}
	public void disconnect() {
	}
	public String getHeaderField(String name) {
		return null;
	}

	public String getResponseMessage() {
		return null;
	}
}
