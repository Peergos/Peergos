// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package peergos.server.crypto.hash.lambdaworks.jni;

/**
 * {@code LibraryLoaders} will create the appropriate {@link LibraryLoader} for
 * the VM it is running on.
 *
 * The system property {@code com.lambdaworks.jni.loader} may be used to override
 * loader auto-detection, or to disable loading native libraries entirely via use
 * of the nil loader.
 *
 * @author Will Glozer
 */
public class LibraryLoaders {
    /**
     * Create a new {@link LibraryLoader} for the current VM.
     *
     * @return the loader.
     */
    public static LibraryLoader loader() {
    	return new NilLibraryLoader();
    }
}
