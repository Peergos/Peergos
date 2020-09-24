// Copyright (C) 2013 - Will Glozer.  All rights reserved.

package peergos.server.crypto.hash.lambdaworks.jni;

/**
 * A native library loader that refuses to load libraries.
 *
 * @author Will Glozer
 */
public class NilLibraryLoader implements LibraryLoader {
    /**
     * Don't load a shared library.
     *
     * @param name      Name of the library to load.
     * @param verify    Ignored, no verification is done.
     *
     * @return false.
     */
    public boolean load(String name, boolean verify) {
        return false;
    }
}
