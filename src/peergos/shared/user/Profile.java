package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.Optional;

@JsType
public class Profile {
    public final Optional<byte[]> profilePhoto;
    public final Optional<String> bio;
    public final Optional<String> status;
    public final Optional<String> firstName;
    public final Optional<String> lastName;
    public final Optional<String> phone;
    public final Optional<String> email;
    public final Optional<String> webRoot;

    public Profile(Optional<byte[]> profilePhoto,
                   Optional<String> bio,
                   Optional<String> status,
                   Optional<String> firstName,
                   Optional<String> lastName,
                   Optional<String> phone,
                   Optional<String> email,
                   Optional<String> webRoot) {
        this.profilePhoto = profilePhoto;
        this.bio = bio;
        this.status = status;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
        this.webRoot = webRoot;
    }
}
