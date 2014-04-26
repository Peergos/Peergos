package peergos.tests;

import peergos.storage.dht.*;
import peergos.storage.net.HTTPSMessenger;
import peergos.util.*;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.*;
import java.util.concurrent.TimeUnit;

public class Scripter extends Thread
{
    BufferedReader commands;
    DHTAPI api;

    public Scripter(DHTAPI api, String source)
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
            FiniteDuration timeout = Duration.create(30, TimeUnit.SECONDS);
            while ((command = commands.readLine()) != null)
            {
                final String[] parts = command.split(" ");
                try {
                    Thread.sleep(Integer.parseInt(parts[0]));
                } catch (InterruptedException e) {}

                try {
                    if (parts[1].equals("PUT")) {
                        Future<Object> fut = api.put(Arrays.hexToBytes(parts[2]), Arrays.hexToBytes(parts[3]), new PutHandlerCallback() {
                            public void callback(PutOffer offer) {
                                System.out.println("Put completed with no error");
                            }
                        });
                        System.out.println("Sent Put message..");
                        Await.result(fut, timeout);
                    } else if (parts[1].equals("GET")) {
                        Future<Object> fut = api.get(Arrays.hexToBytes(parts[2]), new GetHandlerCallback() {
                            @Override
                            public void callback(GetOffer offer) {
                                try {
                                    byte[] fragment = HTTPSMessenger.getFragment(offer.getTarget().addr, offer.getTarget().port, "/" + parts[2]);
                                    System.out.println("GET result: " + new String(fragment, "UTF-8"));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        System.out.println("Sent Get message");
                        Await.result(fut, timeout);
                    } else if (parts[1].equals("CON")) {
                        Future<Object> fut = api.contains(Arrays.hexToBytes(parts[2]), new GetHandlerCallback() {
                            @Override
                            public void callback(GetOffer offer) {
                                System.out.println(offer.getSize() > 0 ? "true" : "false");
                            }
                        });
                        System.out.println("Sent Contains message");
                        Await.result(fut, timeout);
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
