package java.util;

import com.badlogic.gdx.utils.Base64Coder;

public class Base64 {
	public static Decoder getDecoder() {
		return new Decoder();
	}
	public static Encoder getEncoder() {
		return new Encoder();
	}
	public static class Decoder {
		public byte[] decode(String src) {
			return Base64Coder.decode(src);
		}
	}
	public static class Encoder {
		public byte[] encode(byte[] src) {
			char[] base64 = Base64Coder.encode(src); 
			String str = new String(base64);
			return str.getBytes();
		}
		public String encodeToString(byte[] src) {
			char[] base64 = Base64Coder.encode(src); 
			return new String(base64);
		}
	}
}
