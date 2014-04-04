package peergos.tests;

import peergos.dht.*;
import peergos.util.*;

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
                final String[] parts = command.split(" ");
                try {
                    Thread.sleep(Integer.parseInt(parts[0]));
                } catch (InterruptedException e) {}

                if (parts[1].equals("PUT"))
                {
                    api.put(Arrays.hexToBytes(parts[2]), Arrays.hexToBytes(parts[3]), new PutHandlerCallback(){
                        public void callback(PutHandler handler) {
                            System.out.println("Put completed with no error");
                        }
                    });
                    System.out.println("Sent Put message..");
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
                } else if (parts[1].equals("KEY_PUT"))
                {
                    api.createUser(parts[2].getBytes(), Arrays.hexToBytes(parts[3]), new PublicKeyPutHandlerCallback() {
                        @Override
                        public void callback(PublicKeyPutHandler handler) {
                             System.out.println(handler.getResult()? "Public key put succeeded": "Public key put failed");
                        }
                    });
                } else if (parts[1].equals("KEY_GET"))
                {
                    api.getPublicKey(parts[2].getBytes(), new PublicKeyGetHandlerCallback() {
                        @Override
                        public void callback(PublicKeyGetHandler handler) {
                             System.out.println(handler.isValid()? "Public key("+parts[2]+") = "+Arrays.bytesToHex(handler.getResult()): "Unable to retrieve public key");
                        }
                    });
                } else if (parts[1].equals("KILL"))
                {
                    System.exit(0);
                } else
                    System.out.println("Unknown command: " + command);
            }
        } catch (IOException e)
        {
            e.printStackTrace();
        }
        System.out.println("Finished sending scripted messages!");
    }
}
