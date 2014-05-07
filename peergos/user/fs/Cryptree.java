package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.util.ArrayOps;

public class Cryptree
{

    public static class Test
    {
        public Test() {}

        @org.junit.Test
        public void all()
        {
            // create read cryptree

            SymmetricKey rootKey = SymmetricKey.random();
            String name = "/";
            byte[] rootMapKey = ArrayOps.random(32); // root would be stored under this in the core node
            DirReadAccess root = new DirReadAccess(rootKey, name.getBytes());

            // add subfolder
            String name2 = "photos"; // /photos/
            SymmetricKey photosKey = SymmetricKey.random();
            byte[] photosMapKey = ArrayOps.random(32);
            DirReadAccess photos = new DirReadAccess(photosKey, name2.getBytes());
            root.addSubFolder(photosMapKey, rootKey, photosKey);

            // add a file
            String filename = "tree.jpg"; // /photos/tree.jpg
            SymmetricKey fileKey = SymmetricKey.random();
            byte[] fileMapKey = ArrayOps.random(32);
            FileReadAccess file = new FileReadAccess(fileKey, filename.getBytes());
            photos.addFile(fileMapKey, photosKey, fileKey);


        }
    }
}
