package peergos.server.tests;

    import org.junit.Assert;
    import org.junit.Test;
    import peergos.shared.user.fs.FileTreeNode;

    import java.io.*;
    import java.net.URISyntaxException;
    import java.nio.file.Files;
    import java.nio.file.Path;

public class ThumbnailTest {

    public byte[] readFile(String filename) throws IOException, URISyntaxException {
        InputStream is = ThumbnailTest.class.getResourceAsStream(filename);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    @Test
    public void thumbnailTest() throws IOException, URISyntaxException {
        byte[] imageBlob = readFile("logo.png");
        byte[] thumbnail = FileTreeNode.generateThumbnail(imageBlob);
        Assert.assertTrue(thumbnail.length > 0);
    }
}