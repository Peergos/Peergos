package peergos.util;

import java.util.*;

public class Args
{
    private final Map<String, String> params = new HashMap();

    public static Args parse(String[] args)
    {
        Args a = new Args();
        for (int i=0; i<args.length; i++)
        {
            String argName = args[i];
            if (argName.startsWith("-"))
                argName = argName.substring(1);

            if ((i == args.length-1) || args[i+1].startsWith("-"))
                a.params.put(argName, "true");
            else
                a.params.put(argName, args[++i]);
        }
        return a;
    }

    public String getArg(String param, String def)
    {
        if (!params.containsKey(param))
            return def;
        return params.get(param);
    }

    public String getArg(String param)
    {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: "+param);
        return params.get(param);
    }

    public boolean hasArg(String arg)
    {
        return params.containsKey(arg);
    }

    public boolean getBoolean(String param, boolean def)
    {
        if (!params.containsKey(param))
            return def;
        return "true".equals(params.get(param));
    }

    public int getInt(String param, int def)
    {
        if (!params.containsKey(param))
            return def;
        return Integer.parseInt(params.get(param));
    }

    public int getInt(String param)
    {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: "+param);
        return Integer.parseInt(params.get(param));
    }

    public  long getLong(String param)
    {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: "+param);
        return Long.parseLong(params.get(param));
    }

    public double getDouble(String param)
    {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: "+param);
        return Double.parseDouble(params.get(param));
    }

    public String getFirstArg(String[] paramNames, String def)
    {
        for (int i=0; i<paramNames.length; i++)
        {
            String result = getArg(paramNames[i], null);
            if (result != null)
                return result;
        }
        return def;
    }
}
