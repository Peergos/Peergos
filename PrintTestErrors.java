import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Parse junit log files and print errors and exceptions including stack traces to the console
 *  Will exit with status 1 if there are any errors, otherwise exit status 0.
 *
 *  Scan all files in "./test.reports".
 *  Usage "java PrintTestErrors.java
 */
public class PrintTestErrors {

    public static void main(String[] args) throws IOException {
        List<Path> reports = Files.list(Path.of("test.reports")).collect(Collectors.toList());
        boolean anyError = false;
        for (Path report : reports) {
            boolean inErr = false;
            List<String> lines = Files.readAllLines(report);
            for (int i=0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("<error") || line.contains("<failure")) {
                    System.out.println(lines.get(i-1));
                    inErr = true;
                    anyError = true;
                }
                if (inErr)
                    System.out.println(line);
                if (line.contains("</error") || line.contains("</failure"))
                    inErr = false;
            }
        }
        if (anyError)
            throw new RuntimeException("Test failure(s)!");
    }
}
