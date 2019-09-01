package peergos.server.cli;

import org.jline.reader.*;
import java.util.List;
import java.util.function.Function;


public class ListFilesCompleter implements Completer {
    /**
     * Auto completer for files/directories in  the pwd of the remote Peergos file-system.
     * @param lineReader
     * @param parsedLine
     * @param list
     */
    private final Function<String, List<String>> lsSupplier; //lsSupplier(path) -> children of path

    public ListFilesCompleter(Function<String, List<String>> lsSupplier) {
        this.lsSupplier = lsSupplier;
    }

    @Override
    public void complete(LineReader lineReader, ParsedLine parsedLine, List<Candidate> list) {
        if (parsedLine.words().size() > 1)
            throw new IllegalStateException();


        String remotePathPartialArg = parsedLine.word();
        List<String> remotePathChildren = lsSupplier.apply(remotePathPartialArg);
        remotePathChildren.stream()
                .map(Candidate::new)
                .forEach(list::add);
    }
}
