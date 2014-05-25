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
            try {
                // create cryptree
                SymmetricKey rootRKey = SymmetricKey.random();
                SymmetricKey rootWKey = SymmetricKey.random();
                String name = "/";
                byte[] rootMapKey = ArrayOps.random(32); // root would be stored under this in the core node
                DirAccess root = new DirAccess(rootRKey, name.getBytes(), rootWKey);

                // add subfolder
                String name2 = "photos"; // /photos/
                SymmetricKey photosRKey = SymmetricKey.random();
                SymmetricKey photosWKey = SymmetricKey.random();
                byte[] photosMapKey = ArrayOps.random(32);
                DirAccess photos = new DirAccess(photosRKey, name2.getBytes(), photosWKey);
                root.addSubFolder(photosMapKey, rootRKey, photosRKey);

                // add a file
                String filename = "tree.jpg"; // /photos/tree.jpg
                SymmetricKey fileKey = SymmetricKey.random();
                byte[] fileMapKey = ArrayOps.random(32);
                FileAccess file = new FileAccess(fileKey, filename.getBytes());
                photos.addFile(fileMapKey, photosRKey, fileKey);


            } catch (Throwable t)
            {
                t.printStackTrace();
            }
        }
    }

}
