package peergos.shared.user;

import java.nio.file.*;

/** This class stores locations of the different components of a user's profile
 *
 *  Each component is a separate file and can thus be shared or made public individually.
 */
public class ProfilePaths {

    public static final Path ROOT = Paths.get(".profile");
    private static final Path PHOTO = ROOT.resolve("photo");
    public static final Path PHOTO_HIGH_RES = PHOTO.resolve("highres");
    public static final Path BIO = ROOT.resolve("bio");
    public static final Path STATUS = ROOT.resolve("status");
    public static final Path FIRSTNAME = ROOT.resolve("firstname");
    public static final Path LASTNAME = ROOT.resolve("lastname");
    public static final Path PHONE = ROOT.resolve("phone");
    public static final Path EMAIL = ROOT.resolve("email");
    public static final Path WEBROOT = ROOT.resolve("webroot"); // The path in Peergos to this users web root

}
