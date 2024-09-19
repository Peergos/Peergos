/*
 * Copyright 1999,2004 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package peergos.server.webdav.modeshape.webdav.fromcatalina;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.*;

public class XMLHelper {

    public static Node findSubElement( Node parent,
                                       String localName ) {
        if (parent == null) {
            return null;
        }
        Node child = parent.getFirstChild();
        while (child != null) {
            if ((child.getNodeType() == Node.ELEMENT_NODE) && (child.getLocalName().equals(localName))) {
                return child;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    public static Vector<String> getPropertiesFromXML( Node propNode ) {
        Vector<String> properties;
        properties = new Vector<String>();
        NodeList childList = propNode.getChildNodes();

        for (int i = 0; i < childList.getLength(); i++) {
            Node currentNode = childList.item(i);
            if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = currentNode.getLocalName();
                String namespace = currentNode.getNamespaceURI();
                // href is a live property which is handled differently
                properties.addElement(namespace + ":" + nodeName);
            }
        }
        return properties;
    }

    public static Map<String, Object> getPropertiesWithValuesFromXML( Node propNode ) {
        Map<String, Object> propertiesWithValues = new HashMap<String, Object>();

        NodeList childList = propNode.getChildNodes();
        for (int i = 0; i < childList.getLength(); i++) {
            Node currentNode = childList.item(i);
            if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = currentNode.getLocalName();
                String namespace = currentNode.getNamespaceURI();
                // href is a live property which is handled differently
                String fqn = namespace + ":" + nodeName;
                propertiesWithValues.put(fqn, nodeValue(currentNode));

            }
        }
        return propertiesWithValues;
    }

    private static Object nodeValue( Node node ) {
        NodeList childList = node.getChildNodes();
        if (childList.getLength() == 0) {
            return "";
        } else if (childList.getLength() == 1 && childList.item(0).getNodeType() == Node.TEXT_NODE) {
            return node.getTextContent().trim();
        } else {
            List<Object> value = new ArrayList<Object>();
            for (int i = 0; i < childList.getLength(); i++) {
                value.add(nodeValue(childList.item(i)));
            }
            return value;
        }
    }
}
