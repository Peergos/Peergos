package peergos.net.upnp;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;

import java.net.InetAddress;
import java.util.Random;

public class Upnp
{
    public static void main(String[] args) throws Exception {
        GatewayDiscover discover = new GatewayDiscover();
        discover.discover();
        GatewayDevice d = discover.getValidGateway();
        if (d == null)
            throw new IllegalStateException("Couldn't find a gateway device!");

        System.out.printf("Found %s = %s: %s\n", d.getFriendlyName(), d.getModelName(), d.getModelDescription());
        System.out.println("Connected: " + d.isConnected());

        InetAddress localAddress = d.getLocalAddress();
        String externalIPAddress = d.getExternalIPAddress();

        PortMappingEntry portMapping = new PortMappingEntry();
        int pmCount = 0;
        while (true) {
            if (d.getGenericPortMappingEntry(pmCount,portMapping))
                System.out.println("Portmapping #"+pmCount+" successfully retrieved ("+portMapping.getPortMappingDescription()+":"+portMapping.getExternalPort()+")");
            else{
                System.out.println("Portmapping #" + pmCount + " retrieval failed");
                break;
            }
            pmCount++;
        }

        int port = 6991;
        while (d.getSpecificPortMappingEntry(port,"TCP",portMapping)) {
            System.out.println("Port "+port+" was already mapped. Trying another.");
            port = new Random().nextInt(65536);
            portMapping = new PortMappingEntry();
        }
        if (!d.addPortMapping(port, port, localAddress.getHostAddress(),"TCP","test")) {
            System.out.println("Port mapping attempt failed");
            System.out.println("Test FAILED");
        } else {
            System.out.println("SUCCESS!!!!!! BOOYAHHHH!");
            System.out.printf("%s:%d mapped to %s:%d\n", externalIPAddress, port, localAddress, port);
        }
    }
}
