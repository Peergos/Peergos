package peergos.client;

import com.google.gwt.core.client.EntryPoint;
import jsinterop.annotations.JsMethod;
public class Start implements EntryPoint {
	public void onModuleLoad() {
	    new NativeInit().init();
	}
}
