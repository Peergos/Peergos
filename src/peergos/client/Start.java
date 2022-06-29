package peergos.client;

import com.google.gwt.core.client.EntryPoint;
import peergos.shared.crypto.random.JSNaCl;

public class Start implements EntryPoint {
	public void onModuleLoad() {
		JSNaCl scriptJS = new JSNaCl();
		scriptJS.init();
		System.setOut(new ConsolePrintStream());
		System.setErr(new ConsolePrintStream());
	}
}
