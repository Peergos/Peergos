package peergos.tests;

import peergos.storage.dht.*;
import peergos.storage.net.HttpMessenger;
import peergos.util.*;

import java.io.*;

public class Scripter extends Thread
{
    BufferedReader commands;
    PeergosDHT api;

    public Scripter(PeergosDHT api, String source)
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
        // execute commands one after the other, waiting for completion between
        String command;
        try
        {
            while ((command = commands.readLine()) != null)
            {
                final String[] parts = command.split(" ");
                try {
                    Thread.sleep(Integer.parseInt(parts[0]));
                } catch (InterruptedException e) {}
                try {
                    if (parts[1].equals("PUT")) {
                        api.put(ArrayOps.hexToBytes(parts[2]), ArrayOps.hexToBytes(parts[3]),
                                ArrayOps.hexToBytes(parts[4]), ArrayOps.hexToBytes(parts[5]), ArrayOps.hexToBytes(parts[6]), ArrayOps.hexToBytes(parts[7]))
                                .thenAccept(offer -> System.out.println("Put completed with no error"));
                        System.out.println("Sent Put message..");
                    } else if (parts[1].equals("GET")) {
                        api.get(ArrayOps.hexToBytes(parts[2]))
                                .thenAccept((offer) -> {
                                    try {
                                        byte[] fragment = HttpMessenger.getFragment(offer.getTarget().external, "/" + parts[2]);
                                        System.out.println("GET result: " + new String(fragment, "UTF-8"));
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                        System.out.println("Sent Get message");
                    } else if (parts[1].equals("CON")) {
                        api.contains(ArrayOps.hexToBytes(parts[2]))
                                .thenAccept((offer) -> System.out.println(offer.getSize() > 0 ? "true" : "false"));
                        System.out.println("Sent Contains message");
                    } else if (parts[1].equals("KILL")) {
                        System.exit(0);
                    } else
                        System.out.println("Unknown command: " + command);
                } catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        } catch (IOException e)
        {
            e.printStackTrace();
        }
        System.out.println("Finished sending scripted messages!");
    }
}
