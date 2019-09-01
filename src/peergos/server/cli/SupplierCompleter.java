package peergos.server.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.Supplier;

/**
 * Generalized completer that presents a list of options
 */
public class SupplierCompleter implements Completer {
    Supplier<List<String>> optionsSupplier;

    public SupplierCompleter(Supplier<List<String>> optionsSuppllier) {
        this.optionsSupplier = optionsSuppllier;
    }

    @Override
    public void complete(LineReader lineReader, ParsedLine parsedLine, List<Candidate> list) {
        optionsSupplier.get()
                .stream()
                .map(Candidate::new)
                .forEach(list::add);
    }
}
