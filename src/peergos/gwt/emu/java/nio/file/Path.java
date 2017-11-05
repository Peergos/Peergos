package java.nio.file;

import java.io.File;

public class Path {

    private static final String SEPARATOR = "/";

    private final String pathString;

    protected Path (String pathString) {
        this.pathString = pathString;
    }

    public String toString()
    {
        return pathString;
    }

    public Path getParent() {

        int index = pathString.lastIndexOf(SEPARATOR);
        if (index == -1) {
            throw new IllegalArgumentException("Illegal path");
        } else if (index == 0) {
            String name = pathString.substring(index);
            if(name.equals(SEPARATOR)) {
                return null;
            }
            return new Path(SEPARATOR);
        } else {
            return new Path(pathString.substring(0, index));
        }
    }

    public Path getFileName() {
        throw new IllegalArgumentException("Not implemented!");
    }

    public Path resolve(String other) {
        throw new IllegalArgumentException("Not implemented!");
    }

    public File toFile() {
        throw new IllegalArgumentException("Not implemented!");
    }

    public Path getName(int index) {
        throw new IllegalArgumentException("Not implemented!");
    }

    public int getNameCount() {
        throw new IllegalArgumentException("Not implemented!");
    }

    public Path subpath(int from, int to) {
        throw new IllegalArgumentException("Not implemented!");
    }

    public static void main(String[] args)
    {
        test();
    }
    private static void assertTrue(boolean value, String msg) {
        if(!value) {
            throw new Error(msg);
        }
    }

    private static void test(){
        Path path = new Path("/dirOne/dirTwo/filename.txt");
        String toStr = path.toString();
        assertTrue(toStr.equals("/dirOne/dirTwo/filename.txt"), "ToStr wrong!");
        Path parent1 = path.getParent();
        assertTrue(parent1.toString().equals("/dirOne/dirTwo"), "ToStr parent wrong!");
        Path parent2 = parent1.getParent();
        assertTrue(parent2.toString().equals("/dirOne"), "ToStr parent of parent wrong!");
        Path parent3 = parent2.getParent();
        assertTrue(parent3.toString().equals("/"), "ToStr should be root!");
        Path parent4 = parent3.getParent();
        assertTrue(parent4 == null, "ToStr should be null!");
    }
}
