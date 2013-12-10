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
                    api.put(Arrays.hexToBytes(parts[2]), Arrays.hexToBytes(parts[3]), new PutHandlerCallback(){
                        public void callback(PutHandler handler) {
                            System.out.println("Put completed with no error");
                        }
                    });
                    System.out.println("Sent PUT message..");
                } else if (parts[1].equals("GET"))
                {
                    api.get(Arrays.hexToBytes(parts[2]), new GetHandlerCallback() {
                        @Override
                        public void callback(GetHandler handler) {
                            try {
                            System.out.println("GET result: "+new String(handler.getResult(), "UTF-8"));
                            } catch (UnsupportedEncodingException e) {e.printStackTrace();}
                        }
                    });
                    System.out.println("Sent Get message");
                } else if (parts[1].equals("CON"))
                {
                    api.contains(Arrays.hexToBytes(parts[2]), new ContainsHandlerCallback() {
                        @Override
                        public void callback(ContainsHandler handler) {
                             System.out.println(handler.getResult()? "true": "false");
                        }
                    });
                    System.out.println("Sent Contains message");
                } else
                    System.out.println("Unknown command: " + command);
            }
        } catch (IOException e)
        {
            e.printStackTrace();
        }
        System.out.println("Finished sending messages!");
        try {Thread.sleep(1000);} catch (InterruptedException e) {}
        System.exit(0);
    }
}
