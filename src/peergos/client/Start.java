package peergos.client;

import com.google.gwt.core.client.EntryPoint;

public class Start implements EntryPoint {
	public void onModuleLoad() {
		System.setOut(new ConsolePrintStream());
		System.setErr(new ConsolePrintStream());
	}
}
