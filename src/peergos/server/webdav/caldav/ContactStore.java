package peergos.server.webdav.caldav;

import peergos.shared.user.UserContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * vCard storage for CardDAV.
 *
 * <pre>
 *   &lt;user&gt;/.apps/contacts/data/App.config             {"addressbooks":[{name,directory}]}
 *   &lt;user&gt;/.apps/contacts/data/&lt;dir&gt;/addressbook.inf  {"name"}
 *   &lt;user&gt;/.apps/contacts/data/&lt;dir&gt;/&lt;uid&gt;.vcf
 * </pre>
 *
 * Unlike the calendar layout this one is defined here rather than discovered, because
 * there is no contacts app in the web UI yet — so until there is, the bridge is the only
 * reader and writer of it. It mirrors the calendar app's conventions (an App.config
 * listing the collections, a per-directory info file) so that a contacts app can be
 * written against it without a migration, and it is flat, because a vCard has no date to
 * shard on and address books stay small.
 */
public class ContactStore extends AppDataStore {

    public static final String APP_NAME = "contacts";
    public static final String ADDRESSBOOK_INFO_FILENAME = "addressbook.inf";
    public static final String VCF_SUFFIX = ".vcf";

    public ContactStore(UserContext context) {
        super(context, APP_NAME, "addressbooks", ADDRESSBOOK_INFO_FILENAME, VCF_SUFFIX,
                "default", "Contacts", "");
    }

    @Override
    protected List<ObjectRef> readObjects(String directory) {
        List<ObjectRef> objects = new ArrayList<>();
        for (var file : children(collectionPath(directory))) {
            if (! file.isDirectory() && file.getName().endsWith(VCF_SUFFIX))
                objects.add(new ObjectRef(file.getName(), "", file));
        }
        return objects;
    }

    /** Flat, so anything that parses as a vCard belongs directly in the collection. */
    @Override
    protected Optional<String> shardFor(byte[] content) {
        return VCard.isVCard(new String(content, StandardCharsets.UTF_8)) ?
                Optional.of("") : Optional.empty();
    }
}
