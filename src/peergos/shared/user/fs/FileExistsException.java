package peergos.shared.user.fs;

public class FileExistsException extends RuntimeException {

    public FileExistsException(String filename){
        super("File already exists with name " + filename);
    }
}
