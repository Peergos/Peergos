package defiance.util;

import java.util.*;

public class Args
{
    private static Set<String> options = new HashSet();
    private static Map<String, String> params = new HashMap();

    public static void parse(String[] args)
    {
        int i=0;
        while (i < args.length)
        {
            if (!args[i].startsWith("-"))
                throw new IllegalStateException("Error parsing arg "+args[i]);
            if (i == args.length-1)
                options.add(args[i].substring(1));
            else if (args[i+1].startsWith("-") )
                options.add(args[i].substring(1));
            else
            {
                params.put(args[i].substring(1), args[i+1]);
                i++;
            }
            i++;
        }
    }

    public static boolean hasOption(String opt)
    {
        return options.contains(opt);
    }

    public static boolean hasParameter(String param)
    {
        return params.keySet().contains(param);
    }

    public static int getInt(String param, int def)
    {
        if (!params.containsKey(param))
            return def;
        return Integer.parseInt(params.get(param));
    }

    public static String getParameter(String param, String def)
    {
        if (!params.containsKey(param))
            return def;
        return params.get(param);
    }

    public static String getParameter(String param)
    {
        return params.get(param);
    }
}
