package peergos.user.fs;

import org.bouncycastle.util.Arrays;
import peergos.crypto.SymmetricLink;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.user.UserContext;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class FileAccess {
    public enum Type{FILE, DIRECTORY};

    public final SymmetricLink parent2Meta;
    public final FileProperties fileProperties;
    public final FileRetriever fileRetriever;
    public final SymmetricLocationLink parentLink;
    public final Type type;

    public FileAccess(SymmetricLink parent2Meta, FileProperties fileProperties, FileRetriever fileRetriever, SymmetricLocationLink parentLink, Type type) {
        this.parent2Meta = parent2Meta;
        this.fileProperties = fileProperties;
        this.fileRetriever = fileRetriever;
        this.parentLink = parentLink;
        this.type = type;
    }

    public void serialize(DataOutputStream dout) throws IOException {
        dout.write(parent2Meta.serialize());
        dout.write(fileProperties.serialize());
        fileRetriever.serialize(dout);
        parentLink.serialize(dout);
        dout.writeUTF(type.name());
    }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey) {
        return parent2Meta.target(parentKey);
    }

    public boolean removeFragments(UserContext userContext) {
        throw new IllegalStateException("Deprecated");
    }



    public static class  FileRetriever{
        //todo
        public void serialize(DataOutputStream dout) throws IOException {
            throw new IllegalStateException("Unimplemented");
        }
    }


    //    private final SymmetricLink parentToMeta;
//    private final EncryptedFileProperties fileProperties;
//    private final FileRetriever retriever;
//    private final SymmetricLocationLink parentLink;

}
