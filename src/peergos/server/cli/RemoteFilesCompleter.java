package peergos.server.cli;

import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class RemoteFilesCompleter  implements Completer {
    /**
     * Auto completer for files/directories in  the pwd of the remote Peergos file-system.
     * @param lineReader
     * @param parsedLine
     * @param list
     */
    private final Function<String, List<String>> lsSupplier; //lsSupplier(path) -> children of path

    public RemoteFilesCompleter(Function<String, List<String>> lsSupplier) {
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
