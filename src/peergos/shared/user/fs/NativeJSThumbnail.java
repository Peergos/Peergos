package peergos.shared.user.fs;

import java.util.concurrent.CompletableFuture;

import jsinterop.annotations.JsType;

@JsType(namespace = "thumbnail", isNative = true)
public class NativeJSThumbnail {
	public native CompletableFuture<byte[]> generateThumbnail(AsyncReader imageBlob, int fileSize, String fileName) ;
}
