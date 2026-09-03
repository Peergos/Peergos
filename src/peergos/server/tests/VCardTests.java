package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.VCard;

import java.util.List;
import java.util.Optional;

public class VCardTests {

    private static String card(String... properties) {
        StringBuilder vcf = new StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n");
        for (String property : properties)
            vcf.append(property).append("\r\n");
        return vcf.append("END:VCARD\r\n").toString();
    }

    private static ICal.Property named(String vcf, String name) {
        return VCard.properties(vcf).stream().filter(p -> p.name.equals(name)).findFirst().get();
    }

    @Test
    public void readsUidAndValues() {
        String vcf = card("UID:contact-1", "FN:Alice Smith", "EMAIL:a@example.com", "EMAIL:alice@work.com");
        Assert.assertEquals(Optional.of("contact-1"), VCard.uid(vcf));
        Assert.assertEquals(List.of("a@example.com", "alice@work.com"), VCard.values(vcf, "email"));
        Assert.assertEquals(List.of(), VCard.values(vcf, "NOTE"));
    }

    /** A folded property is one property, whichever half the client's parser stopped at. */
    @Test
    public void unfoldsBeforeParsing() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nNOTE:a long\r\n  note\r\nEND:VCARD\r\n";
        Assert.assertEquals(List.of("a long note"), VCard.values(vcf, "NOTE"));
    }

    @Test
    public void stripsAGroupPrefix() {
        Assert.assertEquals(List.of("a@example.com"),
                VCard.values(card("item1.EMAIL:a@example.com"), "EMAIL"));
    }

    /** vCard 3.0 repeats TYPE where 4.0 gives it one comma separated value. */
    @Test
    public void readsTypesInBothForms() {
        Assert.assertEquals(List.of("WORK", "VOICE"),
                VCard.types(named(card("TEL;TYPE=WORK;TYPE=VOICE:+441234"), "TEL")));
        Assert.assertEquals(List.of("WORK", "VOICE"),
                VCard.types(named(card("TEL;TYPE=\"work,voice\":+441234"), "TEL")));
        Assert.assertEquals(List.of(), VCard.types(named(card("TEL:+441234"), "TEL")));
    }

    /** A colon inside a quoted parameter is not the one that ends the name. */
    @Test
    public void quotedParametersDoNotEndTheName() {
        ICal.Property photo = named(card("PHOTO;VALUE=\"uri:x\";TYPE=JPEG:http://example.com/a.jpg"), "PHOTO");
        Assert.assertEquals("http://example.com/a.jpg", photo.value);
    }

    @Test
    public void splitsStructuredValues() {
        Assert.assertEquals(List.of("Smith", "Alice", "Jane", "Dr", ""),
                VCard.structured("Smith;Alice;Jane;Dr;"));
        // an escaped semicolon is part of the component, not a separator
        Assert.assertEquals(List.of("", "", "1 High St; Flat 2", "London", "", "N1 1AA", "UK"),
                VCard.structured(";;1 High St\\; Flat 2;London;;N1 1AA;UK"));
    }

    @Test
    public void unescapesTextValues() {
        Assert.assertEquals("Tea, cake; and\nbiscuits", VCard.unescape("Tea\\, cake\\; and\\nbiscuits"));
        Assert.assertEquals("back\\slash", VCard.unescape("back\\\\slash"));
    }

    @Test
    public void recognisesACard() {
        Assert.assertTrue(VCard.isVCard(card("FN:Alice")));
        Assert.assertFalse(VCard.isVCard("not a vcard at all"));
    }
}
