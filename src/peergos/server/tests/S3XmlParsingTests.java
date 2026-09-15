package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import peergos.server.storage.S3AdminRequests;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** S3 responses come from whatever endpoint is configured, and the local s3 emulator parses request
 *  bodies, so neither can be trusted to avoid naming an external entity.
 */
public class S3XmlParsingTests {

    private static final String SECRET = "a local file the parser must not read";

    private static Document parse(DocumentBuilder builder, String xml) throws Exception {
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Asserts on the outcome rather than on the parser's wording: either the document is rejected,
     *  or it parsed and must not contain the file the entity named.
     */
    private static void assertDoesNotResolveExternalEntities(DocumentBuilder builder) throws Exception {
        Path secret = Files.createTempFile("peergos-xxe", ".txt");
        Files.write(secret, SECRET.getBytes(StandardCharsets.UTF_8));
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE ListBucketResult [ <!ENTITY xxe SYSTEM \"file://" + secret + "\"> ]>\n"
                + "<ListBucketResult><Contents><Key>&xxe;</Key></Contents></ListBucketResult>";
        try {
            Document doc = parse(builder, xml);
            String key = doc.getElementsByTagName("Key").item(0).getTextContent();
            Assert.assertFalse("the entity resolved to the contents of a local file", key.contains(SECRET));
            Assert.fail("a doctype declaration was accepted");
        } catch (Exception expected) {
            // rejected outright, before any entity could be resolved
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void s3ResponsesDoNotResolveExternalEntities() throws Exception {
        assertDoesNotResolveExternalEntities(S3AdminRequests.builder.get());
    }

    /** The same factory backs the local s3 emulator's delete parsing, whose body is caller supplied. */
    @Test
    public void theSharedFactoryDoesNotResolveExternalEntities() throws Exception {
        assertDoesNotResolveExternalEntities(S3AdminRequests.secureXmlFactory().newDocumentBuilder());
    }

    /** The hardening must not change how ordinary responses parse - they are walked by unqualified
     *  node name, which namespace awareness would break.
     */
    @Test
    public void anOrdinaryNamespacedResponseStillParses() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>some/block</Key><Size>1024</Size></Contents>"
                + "</ListBucketResult>";
        Document doc = parse(S3AdminRequests.builder.get(), xml);
        Assert.assertEquals("ListBucketResult", doc.getFirstChild().getNodeName());
        NodeList keys = doc.getElementsByTagName("Key");
        Assert.assertEquals(1, keys.getLength());
        Assert.assertEquals("some/block", keys.item(0).getTextContent());
    }
}
