package peergos.shared.util;

import java.nio.file.*;
import java.util.*;

public class PathUtil {

    public static Path get(String in, String... rest) {
        // tolerate windows path separators
        in = in.trim().replaceAll("\\\\", "/");
        if (in.startsWith("/"))
            in = in.substring(1);
        if (in.endsWith("/"))
            in = in.substring(0, in.length() - 1);
        String[] split = in.split("/");
        if (split.length == 0 && rest.length == 0)
            return Paths.get("");
        if (split.length == 1 && rest.length == 0)
            return Paths.get(split[0]);
        Path result = Paths.get(split[0], Arrays.copyOfRange(split, 1, split.length));
        if (rest.length == 0)
            return result;
        return result.resolve(get(rest[0], Arrays.copyOfRange(rest, 1, rest.length)));
    }

    /** Like get, but preserves leading and trailing spaces in names, for paths that come from a file listing
     *  rather than user input.
     */
    public static Path getUntrimmed(String in) {
        in = in.replaceAll("\\\\", "/");
        if (in.startsWith("/"))
            in = in.substring(1);
        if (in.endsWith("/"))
            in = in.substring(0, in.length() - 1);
        String[] split = in.split("/");
        return Paths.get(split[0], Arrays.copyOfRange(split, 1, split.length));
    }

    public static List<String> components(Path p) {
        List<String> res = new ArrayList<>();
        for (int i=0; i < p.getNameCount(); i++)
            res.add(p.getName(i).toString());
        return res;
    }
}
