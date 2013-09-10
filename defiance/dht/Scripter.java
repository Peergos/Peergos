package defiance.dht;

import defiance.util.*;

import java.io.*;

public class Scripter extends Thread
{
    BufferedReader commands;
    API api;

    public Scripter(API api, String source)
    {
        this.api = api;
        try
        {
            commands = new BufferedReader(new FileReader(source));
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public void run()
    {
        String command;
        try
        {
            while ((command = commands.readLine()) != null)
            {
                String[] parts = command.split(" ");
                try
                {
                    Thread.sleep(Integer.parseInt(parts[0]));
                } catch (InterruptedException e)
                {
                }
                if (parts[1].equals("PUT"))
                {
                    api.put(Arrays.hexToBytes(parts[2]), Arrays.hexToBytes(parts[3]));
                } else if (parts[1].equals("GET"))
                {
                    api.get(Arrays.hexToBytes(parts[2]));
                } else if (parts[1].equals("CON"))
                {
                    api.contains(Arrays.hexToBytes(parts[2]));
                } else
                    System.out.println("Unknown command: " + command);
            }
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
