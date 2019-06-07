package peergos.shared.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class JavaScriptCompatibleURLEncoder {

    //see: https://stackoverflow.com/questions/607176/java-equivalent-to-javascripts-encodeuricomponent-that-produces-identical-outpu
    public static String encode(String s, String enc) throws UnsupportedEncodingException {
            return URLEncoder.encode(s, enc)
                    .replaceAll("\\+", "%20")
                    .replaceAll("\\%21", "!")
                    .replaceAll("\\%27", "'")
                    .replaceAll("\\%28", "(")
                    .replaceAll("\\%29", ")")
                    .replaceAll("\\%7E", "~");
    }
}
