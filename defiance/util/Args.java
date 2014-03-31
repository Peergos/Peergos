package defiance.util;

import java.util.*;

public class Args
{
    private static Set<String> options = new HashSet();
    private static Map<String, String> params = new HashMap();

    public static void parse(String[] args)
    {
        options.clear();
        params.clear();
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
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: "+param);
        return params.get(param);
    }

    public static final Map<String, String> OPTIONS = new LinkedHashMap();
    static
    {
        OPTIONS.put("help", "Show this help.");
        OPTIONS.put("firstNode", " This is the first node in the network (don't attempt to join the network).");
        OPTIONS.put("logMessages", "Print every received message to the console.");
    }
    public static final Map<String, String> PARAMS = new LinkedHashMap();
    static
    {
        PARAMS.put("port", " the I/O port to listen on.");
        PARAMS.put("contactIP", "name or IP address of contact point to join the network");
        PARAMS.put("contactPort", "port of contact point to join the network");
        PARAMS.put("test", "number of local nodes to start in test mode");
        PARAMS.put("script", "script of commands to run during test");
    }

    public static void printOptions()
    {
        System.out.println("\nDefiance RoutingServer help.");
        System.out.println("\nOptions:");
        for (String k: OPTIONS.keySet())
            System.out.println("-"+ k + "\t " + OPTIONS.get(k));
        System.out.println("\nParameters:");
        for (String k: PARAMS.keySet())
            System.out.println("-"+ k + "\t " + PARAMS.get(k));
    }
}
