/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package provider.wz;

import constants.game.GameConstants;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import provider.Data;
import provider.DataEntity;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class XMLDomMapleData implements Data {
    private final Node node;
    /* Shared by every wrapper over the same parsed document and inherited down the tree. Each
     * wrapper is built fresh around a child Node (getChildByPath, getChildren, getParent), so a
     * monitor on the wrapper instance is never shared between two readers of one file and the old
     * synchronized modifiers serialized nothing: concurrent readers built Xerces' lazy NodeListCache
     * at the same time, which threw
     * "Cannot assign field fChildIndex because this.fNodeListCache is null" on strenuous reads. */
    private final Object domLock;
    private Path imageDataDir;

    private static final Map<Document, Object> DOCUMENT_LOCKS = Collections.synchronizedMap(new WeakHashMap<>());

    /* The lock every wrapper of one document shares. Weakly keyed: a live wrapper keeps its document
     * (and with it this entry) alive through Node.getOwnerDocument(), so the map cannot grow past the
     * documents the provider still holds. Consulted once per parsed file, never per navigation step -
     * children inherit the object instead of looking it up again. */
    private static Object lockFor(Node node) {
        Document document = node.getNodeType() == Node.DOCUMENT_NODE ? (Document) node : node.getOwnerDocument();
        if (document == null) {
            return new Object(); // a detached node is shared with nobody
        }
        return DOCUMENT_LOCKS.computeIfAbsent(document, d -> new Object());
    }

    public XMLDomMapleData(FileInputStream fis, Path imageDataDir) {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(fis);
            this.node = document.getFirstChild();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.imageDataDir = imageDataDir;
        this.domLock = lockFor(this.node);
    }

    private XMLDomMapleData(Node node, Object domLock) {
        this.node = node;
        this.domLock = domLock;
    }

    @Override
    public Data getChildByPath(String path) {
        String[] segments = path.split("/");
        if (segments[0].equals("..")) {
            return ((Data) getParent()).getChildByPath(path.substring(path.indexOf("/") + 1));
        }

        Node myNode;
        synchronized (domLock) { // the node-list walk below is what used to race inside Xerces
            myNode = node;
            for (String s : segments) {
                NodeList childNodes = myNode.getChildNodes();
                boolean foundChild = false;
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node childNode = childNodes.item(i);
                    if (childNode.getNodeType() == Node.ELEMENT_NODE
                            && childNode.getAttributes().getNamedItem("name").getNodeValue().equals(s)) {
                        myNode = childNode;
                        foundChild = true;
                        break;
                    }
                }
                if (!foundChild) {
                    return null;
                }
            }
        }

        XMLDomMapleData ret = new XMLDomMapleData(myNode, domLock);
        ret.imageDataDir = imageDataDir.resolve(getName().trim()).resolve(path).getParent();
        return ret;
    }

    @Override
    public List<Data> getChildren() {
        List<Data> ret = new ArrayList<>();

        synchronized (domLock) { // same node-list walk as getChildByPath, same lock
            NodeList childNodes = node.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node childNode = childNodes.item(i);
                if (childNode.getNodeType() == Node.ELEMENT_NODE) {
                    XMLDomMapleData child = new XMLDomMapleData(childNode, domLock);
                    child.imageDataDir = imageDataDir.resolve(getName().trim());
                    ret.add(child);
                }
            }
        }

        return ret;
    }

    @Override
    public Object getData() {
        synchronized (domLock) { // getAttributes() materializes Xerces' attribute map lazily
            NamedNodeMap attributes = node.getAttributes();
            DataType type = getType();
            switch (type) {
                case DOUBLE:
                case FLOAT:
                case INT:
                case SHORT: {
                    String value = attributes.getNamedItem("value").getNodeValue();
                    Number nval = GameConstants.parseNumber(value);

                    switch (type) {
                        case DOUBLE:
                            return nval.doubleValue();
                        case FLOAT:
                            return nval.floatValue();
                        case INT:
                            return nval.intValue();
                        case SHORT:
                            return nval.shortValue();
                        default:
                            return null;
                    }
                }
                case STRING:
                case UOL: {
                    String value = attributes.getNamedItem("value").getNodeValue();
                    return value;
                }
                case VECTOR: {
                    String x = attributes.getNamedItem("x").getNodeValue();
                    String y = attributes.getNamedItem("y").getNodeValue();
                    return new Point(Integer.parseInt(x), Integer.parseInt(y));
                }
                default:
                    return null;
            }
        }
    }

    @Override
    public DataType getType() {
        synchronized (domLock) {
            String nodeName = node.getNodeName();

            switch (nodeName) {
                case "imgdir":
                    return DataType.PROPERTY;
                case "canvas":
                    return DataType.CANVAS;
                case "convex":
                    return DataType.CONVEX;
                case "sound":
                    return DataType.SOUND;
                case "uol":
                    return DataType.UOL;
                case "double":
                    return DataType.DOUBLE;
                case "float":
                    return DataType.FLOAT;
                case "int":
                    return DataType.INT;
                case "short":
                    return DataType.SHORT;
                case "string":
                    return DataType.STRING;
                case "vector":
                    return DataType.VECTOR;
                case "null":
                    return DataType.IMG_0x00;
            }
            return null;
        }
    }

    @Override
    public DataEntity getParent() {
        synchronized (domLock) {
            Node parentNode;
            parentNode = node.getParentNode();
            if (parentNode.getNodeType() == Node.DOCUMENT_NODE) {
                return null;
            }
            XMLDomMapleData parentData = new XMLDomMapleData(parentNode, domLock);
            parentData.imageDataDir = imageDataDir.getParent();
            return parentData;
        }
    }

    @Override
    public String getName() {
        synchronized (domLock) {
            return node.getAttributes().getNamedItem("name").getNodeValue();
        }
    }

    @Override
    public Iterator<Data> iterator() {
        return getChildren().iterator(); // getChildren holds the document lock for the walk
    }
}
