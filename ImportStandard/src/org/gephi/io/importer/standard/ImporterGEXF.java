/*
Copyright 2008 WebAtlas
Authors : Mathieu Bastian, Mathieu Jacomy, Julian Bilcke, Sebastien Heymann
Website : http://www.gephi.org

This file is part of Gephi.

Gephi is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Gephi is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Gephi.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gephi.io.importer.standard;

import java.awt.Color;
import java.util.HashMap;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.gephi.data.attributes.api.AttributeClass;
import org.gephi.data.attributes.api.AttributeColumn;
import org.gephi.data.attributes.api.AttributeOrigin;
import org.gephi.data.attributes.api.AttributeType;
import org.gephi.data.attributes.type.StringList;
import org.gephi.data.properties.EdgeProperties;
import org.gephi.data.properties.NodeProperties;
import org.gephi.io.container.EdgeDraft;
import org.gephi.io.container.ContainerLoader;
import org.gephi.io.container.EdgeDefault;
import org.gephi.io.container.NodeDraft;
import org.gephi.io.importer.FileType;
import org.gephi.io.importer.PropertiesAssociations;
import org.gephi.io.importer.PropertyAssociation;
import org.gephi.io.importer.XMLImporter;
import org.gephi.io.logging.Issue;
import org.gephi.io.logging.Report;
import org.gephi.utils.longtask.LongTask;
import org.gephi.utils.progress.Progress;
import org.gephi.utils.progress.ProgressTicket;
import org.openide.filesystems.FileObject;
import org.openide.util.NbBundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author Mathieu Bastian
 * @author Sebastien Heymann
 */
public class ImporterGEXF implements XMLImporter, LongTask {

    //Architecture
    private ContainerLoader container;
    private Report report;
    private ProgressTicket progressTicket;
    private boolean cancel = false;

    //Settings
    private boolean keepComplexAndEmptyAttributeTypes = true;

    //Attributes
    protected PropertiesAssociations properties = new PropertiesAssociations();
    private HashMap<String, NodeProperties> nodePropertiesAttributes;
    private HashMap<String, EdgeProperties> edgePropertiesAttributes;

    //Attributes options
    private HashMap<String, StringList> optionsAttributes;

    public ImporterGEXF() {
        //Default node associations
        properties.addNodePropertyAssociation(new PropertyAssociation<NodeProperties>(NodeProperties.LABEL, "label"));
        properties.addNodePropertyAssociation(new PropertyAssociation<NodeProperties>(NodeProperties.X, "x"));
        properties.addNodePropertyAssociation(new PropertyAssociation<NodeProperties>(NodeProperties.Y, "y"));
        properties.addNodePropertyAssociation(new PropertyAssociation<NodeProperties>(NodeProperties.Y, "z"));
        properties.addNodePropertyAssociation(new PropertyAssociation<NodeProperties>(NodeProperties.SIZE, "size"));

        //Default edge associations
        properties.addEdgePropertyAssociation(new PropertyAssociation<EdgeProperties>(EdgeProperties.LABEL, "label"));
        properties.addEdgePropertyAssociation(new PropertyAssociation<EdgeProperties>(EdgeProperties.WEIGHT, "weight"));
    }

    public void importData(Document document, ContainerLoader container, Report report) throws Exception {
        this.container = container;
        this.report = report;
        this.nodePropertiesAttributes = new HashMap<String, NodeProperties>();
        this.edgePropertiesAttributes = new HashMap<String, EdgeProperties>();
        this.optionsAttributes = new HashMap<String, StringList>();

        try {
            importData(document);
        } catch (Exception e) {
            clean();
            throw e;
        }
        clean();
    }

    private void clean() {
        //Clean
        this.container = null;
        this.progressTicket = null;
        this.report = null;
        this.nodePropertiesAttributes = null;
        this.edgePropertiesAttributes = null;
        this.optionsAttributes = null;
        this.cancel = false;
    }

    private void importData(Document document) throws Exception {
        Progress.start(progressTicket);        //Progress

        //Root
        Element root = document.getDocumentElement();

        //Version
        String version = root.getAttribute("version");
        if (version.isEmpty() || version.equals("1.0")) {
            ImporterGEXF10 importer = new ImporterGEXF10();
            importer.importData(document, container, report);
        } else {
            //XPath
            XPathFactory factory = XPathFactory.newInstance();
            XPath xpath = factory.newXPath();

            XPathExpression exp = xpath.compile("./graph/attributes[@class]/attribute[@id and normalize-space(@title) and normalize-space(@type)]");
            NodeList columnListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }

            exp = xpath.compile("./graph/nodes/node[@id and normalize-space(@label)]");
            NodeList nodeListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }
            exp = xpath.compile("./graph/edges/edge[@source and @target]");
            NodeList edgeListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }
            int taskMax = nodeListE.getLength() + edgeListE.getLength();
            Progress.switchToDeterminate(progressTicket, taskMax);

            // Default edge type
            exp = xpath.compile("./graph[@defaultedgetype]");
            NodeList edgeTypeE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (edgeTypeE != null && edgeTypeE.getLength() > 0) {
                String defaultEdgeType = ((Element) edgeTypeE.item(0)).getAttribute("defaultedgetype");

                if (defaultEdgeType.equals("undirected")) {
                    container.setEdgeDefault(EdgeDefault.UNDIRECTED);
                } else if (defaultEdgeType.equals("directed")) {
                    container.setEdgeDefault(EdgeDefault.DIRECTED);
                } else if (defaultEdgeType.equals("double")) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgedouble"), Issue.Level.WARNING));
                } else {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_defaultedgetype", defaultEdgeType), Issue.Level.SEVERE));
                }
            }


            //Attributes columns
            setAttributesColumns(columnListE);

            //Nodes
            for (int i = 0; i < nodeListE.getLength(); i++) {
                Element nodeE = (Element) nodeListE.item(i);

                //Id
                String nodeId = nodeE.getAttribute("id");
                if (nodeId.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_nodeid"), Issue.Level.SEVERE));
                    continue;
                }

                //Create node
                NodeDraft node = container.factory().newNodeDraft();
                node.setId(nodeId);

                //Parent
                if (!nodeE.getAttribute("pid").isEmpty() && !nodeE.getAttribute("pid").equals("0")) {
                    String parentId = nodeE.getAttribute("pid");
                    node.setParent(container.getNode(parentId));
                }

                //Label
                String nodeLabel = nodeE.getAttribute("label");
                if (nodeLabel.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_nodelabel", nodeId), Issue.Level.SEVERE));
                    continue;
                }
                node.setLabel(nodeLabel);

                //Get Attvalue child nodes, avoiding using descendants
                Node child = nodeE.getFirstChild();
                if (child != null) {
                    do {
                        if (child.getNodeName().equals("attvalues")) {
                            Node childE = child.getFirstChild();

                            if (childE != null) {
                                do {
                                    if (childE.getNodeName().equals("attvalue")) {
                                        Element dataE = (Element) childE;
                                        setNodeData(dataE, node, nodeId);
                                    }
                                } while ((childE = childE.getNextSibling()) != null);
                            }

                        }
                    } while ((child = child.getNextSibling()) != null);
                }

                //Node color
                Element nodeColor = (Element) nodeE.getElementsByTagName("viz:color").item(0);
                if (nodeColor != null) {
                    int r = Integer.parseInt(nodeColor.getAttribute("r"));
                    int g = Integer.parseInt(nodeColor.getAttribute("g"));
                    int b = Integer.parseInt(nodeColor.getAttribute("b"));
                    node.setColor(new Color(r, g, b));
                }

                //Node position
                Element nodePosition = (Element) nodeE.getElementsByTagName("viz:position").item(0);
                if (nodePosition != null) {
                    node.setX(Float.parseFloat(nodePosition.getAttribute("x")));
                    node.setY(Float.parseFloat(nodePosition.getAttribute("y")));
                    node.setZ(Float.parseFloat(nodePosition.getAttribute("z")));
                }

                //Node size
                Element nodeSize = (Element) nodeE.getElementsByTagName("viz:size").item(0);
                if (nodeSize != null) {
                    node.setSize(Float.parseFloat(nodeSize.getAttribute("value")));
                }

                //Append node
                container.addNode(node);
            }


            //Edges
            for (int i = 0; i < edgeListE.getLength(); i++) {
                Element edgeE = (Element) edgeListE.item(i);

                EdgeDraft edge = container.factory().newEdgeDraft();

                //Id
                String edgeId = edgeE.getAttribute("id");
                if (edgeId.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgeid"), Issue.Level.SEVERE));
                    continue;
                }

                String edgeSource = edgeE.getAttribute("source");
                String edgeTarget = edgeE.getAttribute("target");

                NodeDraft nodeSource = container.getNode(edgeSource);
                NodeDraft nodeTarget = container.getNode(edgeTarget);
                if (nodeSource == null || nodeTarget == null) {
                    throw new NullPointerException(edgeSource + "  " + edgeTarget);
                }
                edge.setSource(nodeSource);
                edge.setTarget(nodeTarget);

                // Type
                String edgeType = edgeE.getAttribute("type");
                if (!edgeType.isEmpty()) {
                    if (edgeType.equals("undirected")) {
                        edge.setType(EdgeDraft.EdgeType.UNDIRECTED);
                    } else if (edgeType.equals("directed")) {
                        edge.setType(EdgeDraft.EdgeType.DIRECTED);
                    } else if (edgeType.equals("double")) {
                        edge.setType(EdgeDraft.EdgeType.MUTUAL);
                    } else {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgetype", edgeType), Issue.Level.SEVERE));
                    }
                }

                //Weight
                String weightStr = edgeE.getAttribute("weight");
                if (!weightStr.isEmpty()) {
                    float weight = Float.parseFloat(weightStr);
                    edge.setWeight(weight);
                }

                //Label
                String edgeLabel = edgeE.getAttribute("label");
                if (!edgeLabel.isEmpty()) {
                    edge.setLabel(edgeLabel);
                }

                //Get Attvalue child nodes, avoiding using descendants
                Node child = edgeE.getFirstChild();
                if (child != null) {
                    do {
                        if (child.getNodeName().equals("attvalues")) {
                            Node childE = child.getFirstChild();

                            if (childE != null) {
                                do {
                                    if (childE.getNodeName().equals("attvalue")) {
                                        Element dataE = (Element) childE;
                                        setEdgeData(dataE, edge, edgeId);
                                    }
                                } while ((childE = childE.getNextSibling()) != null);
                            }

                        }
                    } while ((child = child.getNextSibling()) != null);
                }

                container.addEdge(edge);
            }
        }

        Progress.finish(progressTicket);
    }

    private void setAttributesColumns(NodeList columnListE) {
        //NodeColumn
        for (int i = 0; i < columnListE.getLength() && !cancel; i++) {
            Element columnE = (Element) columnListE.item(i);

            Progress.progress(progressTicket);

            //Id & Name
            String colId = columnE.getAttribute("id");
            String colTitle = columnE.getAttribute("title");
            if (colTitle.isEmpty()) {
                colTitle = colId;
            }

            //Class
            String colClass = ((Element) columnE.getParentNode()).getAttribute("class");
            if (colClass.isEmpty() || !(colClass.equals("node") || colClass.equals("edge"))) {
                report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_attributeclass", colTitle), Issue.Level.WARNING));
                continue;
            }

            //Try to see if the column is a node/edge property
            if (colClass.equals("node")) {
                NodeProperties prop = properties.getNodeProperty(colTitle);
                if (prop != null) {
                    nodePropertiesAttributes.put(colId, prop);
                    report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_nodeproperty", colTitle));
                    continue;
                }
            } else if (colClass.equals("edge")) {
                EdgeProperties prop = properties.getEdgeProperty(colTitle);
                if (prop != null) {
                    edgePropertiesAttributes.put(colId, prop);
                    report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_edgeeproperty", colTitle));
                    continue;
                }
            }

            //Type
            String keyType = columnE.getAttribute("type");
            AttributeType attributeType = AttributeType.STRING;
            if (keyType.equals("boolean")) {
                attributeType = AttributeType.BOOLEAN;
            } else if (keyType.equals("integer")) {
                attributeType = AttributeType.INT;
            } else if (keyType.equals("long")) {
                attributeType = AttributeType.LONG;
            } else if (keyType.equals("float")) {
                attributeType = AttributeType.FLOAT;
            } else if (keyType.equals("double")) {
                attributeType = AttributeType.DOUBLE;
            } else if (keyType.equals("string")) {
                attributeType = AttributeType.STRING;
            } else if (keyType.equals("liststring")) {
                attributeType = AttributeType.LIST_STRING;
            } else if (keyType.equals("anyURI")) {
                attributeType = AttributeType.STRING; //need to create a new type?
            } else {
                if (keepComplexAndEmptyAttributeTypes) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_attributetype1", colTitle), Issue.Level.WARNING));
                } else {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_attributetype2", colTitle), Issue.Level.WARNING));
                    continue;
                }
            }

            //Default
            NodeList defaultList = columnE.getElementsByTagName("default");
            Object defaultValue = null;
            if (defaultList.getLength() > 0) {
                Element defaultE = (Element) defaultList.item(0);
                String defaultValueStr = defaultE.getTextContent();
                try {
                    defaultValue = attributeType.parse(defaultValueStr);
                    report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_default", defaultValueStr, colTitle));
                } catch (Exception e) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_attributedefault", colTitle, attributeType.getTypeString()), Issue.Level.SEVERE));
                }
            }

            // Options
            NodeList optionsList = columnE.getElementsByTagName("options");
            if (optionsList.getLength() > 0) {
                Element optionE = (Element) optionsList.item(0);
                String optionsValueStr = optionE.getTextContent();
                try {
                    StringList optionValues = new StringList(optionsValueStr, "|");
                    optionsAttributes.put(colId, optionValues);
                    report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_options", optionsValueStr, colTitle));
                } catch (Exception e) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_attributeoptions", colTitle, attributeType.getTypeString()), Issue.Level.SEVERE));
                }
            }

            //Add as attribute
            if (colClass.equals("node")) {
                AttributeClass nodeClass = container.getAttributeManager().getNodeClass();
                nodeClass.addAttributeColumn(colId, colTitle, attributeType, AttributeOrigin.DATA, defaultValue);
                report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_nodeattribute", colTitle, attributeType.getTypeString()));
            } else if (colClass.equals("edge")) {
                AttributeClass edgeClass = container.getAttributeManager().getEdgeClass();
                edgeClass.addAttributeColumn(colId, colTitle, attributeType, AttributeOrigin.DATA, defaultValue);
                report.log(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_log_edgeattribute", colTitle, attributeType.getTypeString()));
            }
        }
    }

    private void setNodeData(Element dataE, NodeDraft nodeDraft, String nodeId) {
        //Key
        String dataKey = dataE.getAttribute("for");
        if (dataKey.isEmpty()) {
            report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datakey", nodeDraft), Issue.Level.SEVERE));
            return;
        }
        String dataValue = dataE.getAttribute("value");
        if (!dataValue.isEmpty()) {

            //Data attribute value
            AttributeColumn column = container.getAttributeManager().getNodeClass().getAttributeColumn(dataKey);
            if (column != null) {
                try {
                    Object value = column.getAttributeType().parse(dataValue);

                    //Check value
                    if (column.getAttributeType() != AttributeType.LIST_STRING) { //otherwise this is a nonsense
                        StringList options = optionsAttributes.get(dataKey);
                        if (options != null && !options.contains(value.toString())) {
                            report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_dataoptionsvalue", dataValue, nodeId, column.getTitle()), Issue.Level.SEVERE));
                            return;
                        }
                    }

                    nodeDraft.addAttributeValue(column, value);
                } catch (Exception e) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datavalue", dataKey, nodeId, column.getTitle()), Issue.Level.SEVERE));
                }
            }
        }
    }

    private void setEdgeData(Element dataE, EdgeDraft edgeDraft, String edgeId) {
        //Key
        String dataKey = dataE.getAttribute("for");
        if (dataKey.isEmpty()) {
            report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datakey", edgeDraft), Issue.Level.SEVERE));
            return;
        }
        String dataValue = dataE.getAttribute("value");
        if (!dataValue.isEmpty()) {

            //Data attribute value
            AttributeColumn column = container.getAttributeManager().getNodeClass().getAttributeColumn(dataKey);
            if (column != null) {
                try {
                    Object value = column.getAttributeType().parse(dataValue);

                    //Check value
                    if (column.getAttributeType() != AttributeType.LIST_STRING) { //otherwise this is a nonsense
                        StringList options = optionsAttributes.get(dataKey);
                        if (options != null && !options.contains(value.toString())) {
                            report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_dataoptionsvalue", dataValue, edgeId, column.getTitle()), Issue.Level.SEVERE));
                            return;
                        }
                    }

                    edgeDraft.addAttributeValue(column, value);
                } catch (Exception e) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datavalue", dataKey, edgeId, column.getTitle()), Issue.Level.SEVERE));
                }
            }
        }
    }

    public FileType[] getFileTypes() {
        FileType ft = new FileType(".gexf", NbBundle.getMessage(getClass(), "fileType_GEXF_Name"));
        return new FileType[]{ft};
    }

    public boolean isMatchingImporter(FileObject fileObject) {
        return fileObject.hasExt("gexf");
    }

    public boolean cancel() {
        cancel = true;
        return true;
    }

    public void setProgressTicket(ProgressTicket progressTicket) {
        this.progressTicket = progressTicket;
    }

    public final class ImporterGEXF10 {

        /**
         * GEXF 1.0 import
         */
        private void importData(Document document, ContainerLoader container, Report report) throws Exception {
            //Root
            Element root = document.getDocumentElement();

            //XPath
            XPathFactory factory = XPathFactory.newInstance();
            XPath xpath = factory.newXPath();

            XPathExpression exp = xpath.compile("./graph/attributes[@class]/attribute[@id and normalize-space(@title) and normalize-space(@type)]");
            NodeList columnListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }

            exp = xpath.compile("./graph/nodes/node[@id and normalize-space(@label)]");
            NodeList nodeListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }
            exp = xpath.compile("./graph/edges/edge[@source and @target]");
            NodeList edgeListE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (cancel) {
                return;
            }
            int taskMax = nodeListE.getLength() + edgeListE.getLength();
            Progress.switchToDeterminate(progressTicket, taskMax);

            // Default edge type
            exp = xpath.compile("./graph/edges[@defaultedgetype]");
            NodeList edgeTypeE = (NodeList) exp.evaluate(root, XPathConstants.NODESET);
            if (edgeTypeE != null && edgeTypeE.getLength() > 0) {
                String defaultEdgeType = ((Element) edgeTypeE.item(0)).getAttribute("defaultedgetype");

                if (!defaultEdgeType.isEmpty()) {
                    if (defaultEdgeType.equals("simple")) {
                        container.setEdgeDefault(EdgeDefault.UNDIRECTED);
                    } else if (defaultEdgeType.equals("directed")) {
                        container.setEdgeDefault(EdgeDefault.DIRECTED);
                    } else if (defaultEdgeType.equals("double")) {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgedouble"), Issue.Level.WARNING));
                    } else {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_defaultedgetype"), Issue.Level.SEVERE));
                    }
                }
            }

            //Attributes columns
            setAttributesColumns(columnListE);

            //Nodes
            for (int i = 0; i < nodeListE.getLength(); i++) {
                Element nodeE = (Element) nodeListE.item(i);

                //Id
                String nodeId = nodeE.getAttribute("id");
                if (nodeId.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_nodeid"), Issue.Level.SEVERE));
                    continue;
                }

                //Create node
                NodeDraft node = container.factory().newNodeDraft();
                node.setId(nodeId);

                //Parent
                if (!nodeE.getAttribute("pid").isEmpty() && !nodeE.getAttribute("pid").equals("0")) {
                    String parentId = nodeE.getAttribute("pid");
                    node.setParent(container.getNode(parentId));
                }

                //Label
                String nodeLabel = nodeE.getAttribute("label");
                if (nodeLabel.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_nodelabel", nodeId), Issue.Level.SEVERE));
                    continue;
                }
                node.setLabel(nodeLabel);

                //Get Attvalue child nodes, avoiding using descendants
                Node child = nodeE.getFirstChild();
                if (child != null) {
                    do {
                        if (child.getNodeName().equals("attvalues")) {
                            Node childE = child.getFirstChild();

                            if (childE != null) {
                                do {
                                    if (childE.getNodeName().equals("attvalue")) {
                                        Element dataE = (Element) childE;
                                        setNodeData(dataE, node, nodeId);
                                    }
                                } while ((childE = childE.getNextSibling()) != null);
                            }

                        }
                    } while ((child = child.getNextSibling()) != null);
                }

                //Node color
                Element nodeColor = (Element) nodeE.getElementsByTagName("viz:color").item(0);
                if (nodeColor != null) {
                    int r = Integer.parseInt(nodeColor.getAttribute("r"));
                    int g = Integer.parseInt(nodeColor.getAttribute("g"));
                    int b = Integer.parseInt(nodeColor.getAttribute("b"));
                    node.setColor(new Color(r, g, b));
                }

                //Node position
                Element nodePosition = (Element) nodeE.getElementsByTagName("viz:position").item(0);
                if (nodePosition != null) {
                    node.setX(Float.parseFloat(nodePosition.getAttribute("x")));
                    node.setY(Float.parseFloat(nodePosition.getAttribute("y")));
                    node.setZ(Float.parseFloat(nodePosition.getAttribute("z")));
                }

                //Node size
                Element nodeSize = (Element) nodeE.getElementsByTagName("viz:size").item(0);
                if (nodeSize != null) {
                    node.setSize(Float.parseFloat(nodeSize.getAttribute("value")));
                }

                //Append node
                container.addNode(node);
            }


            //Edges
            for (int i = 0; i < edgeListE.getLength(); i++) {
                Element edgeE = (Element) edgeListE.item(i);

                EdgeDraft edge = container.factory().newEdgeDraft();

                //Id
                String edgeId = edgeE.getAttribute("id");
                if (edgeId.isEmpty()) {
                    report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgeid"), Issue.Level.SEVERE));
                    continue;
                }

                String edgeSource = edgeE.getAttribute("source");
                String edgeTarget = edgeE.getAttribute("target");

                NodeDraft nodeSource = container.getNode(edgeSource);
                NodeDraft nodeTarget = container.getNode(edgeTarget);
                if (nodeSource == null || nodeTarget == null) {
                    throw new NullPointerException(edgeSource + "  " + edgeTarget);
                }
                edge.setSource(nodeSource);
                edge.setTarget(nodeTarget);

                // Type
                String edgeType = edgeE.getAttribute("type");
                if (!edgeType.isEmpty()) {
                    if (edgeType.equals("sim")) {
                        edge.setType(EdgeDraft.EdgeType.UNDIRECTED);
                    } else if (edgeType.equals("dir")) {
                        edge.setType(EdgeDraft.EdgeType.DIRECTED);
                    } else if (edgeType.equals("dou")) {
                        edge.setType(EdgeDraft.EdgeType.MUTUAL);
                    } else {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_edgetype", edgeType), Issue.Level.SEVERE));
                    }
                }

                //Get Attvalue child nodes, avoiding using descendants
                Node child = edgeE.getFirstChild();
                if (child != null) {
                    do {
                        if (child.getNodeName().equals("attvalues")) {
                            Node childE = child.getFirstChild();

                            if (childE != null) {
                                do {
                                    if (childE.getNodeName().equals("attvalue")) {
                                        Element dataE = (Element) childE;
                                        setEdgeData(dataE, edge, edgeId);
                                    }
                                } while ((childE = childE.getNextSibling()) != null);
                            }

                        }
                    } while ((child = child.getNextSibling()) != null);
                }

                //Cardinal
                String cardinalStr = edgeE.getAttribute("cardinal");
                if (!cardinalStr.isEmpty()) {
                    float cardinal = Float.parseFloat(cardinalStr);
                    edge.setWeight(cardinal);
                }

                container.addEdge(edge);
            }
        }

        private void setNodeData(Element dataE, NodeDraft nodeDraft, String nodeId) {
            //Key
            String dataKey = dataE.getAttribute("id");
            if (dataKey.isEmpty()) {
                report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datakey1", nodeDraft), Issue.Level.SEVERE));
                return;
            }
            String dataValue = dataE.getAttribute("value");
            if (!dataValue.isEmpty()) {

                //Data attribute value
                AttributeColumn column = container.getAttributeManager().getNodeClass().getAttributeColumn(dataKey);
                if (column != null) {
                    try {
                        Object value = column.getAttributeType().parse(dataValue);
                        nodeDraft.addAttributeValue(column, value);
                    } catch (Exception e) {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datavalue", dataKey, nodeId, column.getTitle()), Issue.Level.SEVERE));
                    }
                }
            }
        }

        private void setEdgeData(Element dataE, EdgeDraft edgeDraft, String edgeId) {
            //Key
            String dataKey = dataE.getAttribute("id");
            if (dataKey.isEmpty()) {
                report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datakey", edgeDraft), Issue.Level.SEVERE));
                return;
            }
            String dataValue = dataE.getAttribute("value");
            if (!dataValue.isEmpty()) {

                //Data attribute value
                AttributeColumn column = container.getAttributeManager().getNodeClass().getAttributeColumn(dataKey);
                if (column != null) {
                    try {
                        Object value = column.getAttributeType().parse(dataValue);
                        edgeDraft.addAttributeValue(column, value);
                    } catch (Exception e) {
                        report.logIssue(new Issue(NbBundle.getMessage(ImporterGEXF.class, "importerGEXF_error_datavalue", dataKey, edgeId, column.getTitle()), Issue.Level.SEVERE));
                    }
                }
            }
        }
    }
}
