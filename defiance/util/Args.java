package defiance.util;

import java.util.*;

public class Args
{
    private Set<String> options = new HashSet();
    private Map<String, String> params = new HashMap();

    public Args(String[] args)
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

    public boolean hasOption(String opt)
    {
        return options.contains(opt);
    }

    public boolean hasParameter(String param)
    {
        return params.keySet().contains(param);
    }

    public int getInt(String param, int def)
    {
        if (!params.containsKey(param))
            return def;
        return Integer.parseInt(params.get(param));
    }

    public String getParameter(String param, String def)
    {
        if (!params.containsKey(param))
            return def;
        return params.get(param);
    }

    public String getParameter(String param)
    {
        return params.get(param);
    }
}
