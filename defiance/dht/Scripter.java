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
                    PutHandler put = api.put(Arrays.hexToBytes(parts[2]), Arrays.hexToBytes(parts[3]));
                    System.out.println("Sent PUT message..");
                    while (!put.isCompleted()) try {Thread.sleep(100);} catch (InterruptedException e) {}
                    System.out.println("Put completed with result: "+ !put.isFailed());
                } else if (parts[1].equals("GET"))
                {
                    GetHandler get = api.get(Arrays.hexToBytes(parts[2]));
                    System.out.println("Sent Get message");
                    while (!get.isCompleted()) try {Thread.sleep(100);} catch (InterruptedException e) {}
                    System.out.println(new String(get.getResult(), "UTF-8"));
                } else if (parts[1].equals("CON"))
                {
                    ContainsHandler con = api.contains(Arrays.hexToBytes(parts[2]));
                    System.out.println("Sent Contains message");
                    while (!con.isCompleted()) try {Thread.sleep(100);} catch (InterruptedException e) {}
                    System.out.println(con.getResult()? "true": "false");
                } else
                    System.out.println("Unknown command: " + command);
            }
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
