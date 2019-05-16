package peergos.shared.user.fs;

import java.util.concurrent.CompletableFuture;

import jsinterop.annotations.JsType;

@JsType(namespace = "thumbnail", isNative = true)
public class NativeJSThumbnail {
	public native CompletableFuture<String> generateThumbnail(AsyncReader imageBlob, int fileSize, String fileName) ;
	public native CompletableFuture<String> generateVideoThumbnail(AsyncReader imageBlob, int fileSize, String fileName, String mimeType) ;
}
