package org.reactome.curation.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.persistence.DiagramGKBWriter;
import org.gk.render.Node;
import org.gk.render.Renderable;
import org.gk.render.RenderablePathway;
import org.gk.render.RenderablePropertyNames;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.reactome.curation.repository.PathwayDiagramRepository;
import org.reactome.curation.util.CytoscapJSToRenderableDiagramConverter;
import org.reactome.server.diagram.converter.graph.DiagramGraphFactory;
import org.reactome.server.diagram.converter.graph.output.Graph;
import org.reactome.server.diagram.converter.layout.LayoutFactory;
import org.reactome.server.diagram.converter.layout.input.ProcessFactory;
import org.reactome.server.diagram.converter.layout.input.model.Process;
import org.reactome.server.diagram.converter.layout.output.Diagram;
import org.reactome.server.diagram.converter.layout.util.JsonWriter;
import org.reactome.server.diagram.converter.layout.util.TrivialChemicals;
import org.reactome.server.diagram.converter.utils.reports.TestReportsHelper;
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.PathwayDiagram;
import org.reactome.server.graph.service.AdvancedDatabaseObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.NoArgsConstructor;

/**
 * This class is intended to handle operations related to pathway diagrams. Most of functions in this
 * class are related to create overlay of disease related pathway diagrams onto the normal pathway diagrams.
 * The majority of the code is ported from the original Java-based curator tool.
 */
@Service
public class PathwayDiagramService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PathwayDiagramService.class);
    
    @Autowired
    private CurationService curationService;
    @Autowired
    private CytoscapJSToRenderableDiagramConverter converter;
    @Autowired
    private PathwayDiagramRepository diagramRepository;
    @Autowired
    private AdvancedDatabaseObjectService ados;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private PathwayDiagramOverlayer diagramOverlayer;
    
    // Other objects needed for conversion
    private DiagramGKBWriter diagramWriter;
    private DiagramGraphFactory graphFactory;
    private ProcessFactory processFactory;
    private TrivialChemicals trivialChemicals;
    
    public PathwayDiagramService() {
        diagramWriter = new DiagramGKBJSONWriter();
        diagramWriter.setNeedRegistryCheck(false);
        diagramWriter.setNeedDisplayName(true);
    }   
    
    public JsonNode loadDiagramJson(String fileName) throws IOException {
        String text = this.diagramRepository.loadDiagramJson(fileName);
        if (text == null) {
            return null;
        }
        JsonNode jsonNode = mapper.readTree(text);
        return jsonNode;
    }
    
    public boolean hasDiagramJson(Long dbId) throws IOException {
        return this.diagramRepository.hasDiagramJson(dbId);
    }
    
    public JsonNode loadCyNetwork(Long pathwayId) throws IOException {
        String text = diagramRepository.loadCyNetwork(pathwayId);
        JsonNode jsonNode = mapper.readTree(text);
        return jsonNode;
    }
    
    public Boolean hasCyNetwork(Long pathwayId) throws IOException {
        return diagramRepository.hasCyNetwork(pathwayId);
    }

    /**
     * Only a cytoscape JSON text for a PathwayDiagram, represented by pathwayDiagramId, will be saved.
     * The cytoscape JSON will be saved into the configured cytoscape folder, which contains files named by
     * PathwayDiagram instance dbIds. The JSON text will also be converted and exported to the diagram JSON text
     * used by the original pathway browser and the new angular-based cytoscape view. If a PathwayDiagram represents
     * more than one Pathways, one and only one of which should be a normal pathway, multiple diagram JSON files
     * will be generated. These diagram JSON files are named by their Pathway dbIds and saved in the configured
     * graph folder.
     * @param pathwayDiagramId
     * @param cytoscapeJSON
     * @throws Exception
     */
    public Set<Long> saveCyNetwork(Long pathwayDiagramId, JsonNode cytoscapeJSON) throws Exception {
        String text = this.mapper.writeValueAsString(cytoscapeJSON);
        this.diagramRepository.saveCyNetwork(pathwayDiagramId, text);
        return this.exportPathwayDiagramJSON(pathwayDiagramId, cytoscapeJSON);
    }

    // The following three methods are used to handle automatic backup of a cytosacpe diagram under editing.
    public Boolean hasBackupCyNetwork(Long pathwayDiagramId) {
        return this.diagramRepository.hasBackupCyNetwork(pathwayDiagramId);
    }

    /**
     * Back up a pathway diagram under active editing. This is differnt from uploading a pathway diagram
     * after editing. Therefore, this is called "backup". The front-end should handle it automatically
     * without users' intervention.
     * @param pathwayDiagramId
     * @param cytoscapeJson
     * @throws Exception
     */
    public void backupCyNetwork(Long pathwayDiagramId, JsonNode cytoscapeJson) throws Exception {
        String text = this.mapper.writeValueAsString(cytoscapeJson);
        this.diagramRepository.backupCyNetwork(pathwayDiagramId, text);
    }

    public JsonNode loadBackupCyNetwork(Long pathwayDiagramId) throws IOException {
        String text = this.diagramRepository.loadBackupCyNetwork(pathwayDiagramId);
        if (text == null) {
            return null;
        }
        JsonNode jsonNode = mapper.readTree(text);
        return jsonNode;
    }

    public void deleteBackupCyNetwork(Long pathwayDiagramId) {
        this.diagramRepository.deleteBackupCyNetwork(pathwayDiagramId);
    }
    
    public Set<Long> exportPathwayDiagramJSON(Long pathwayDiagramId, JsonNode cytoscapeJSON) throws Exception {
        this.initGraphConvertObjects();
        RenderablePathway diagram = converter.convert(cytoscapeJSON, pathwayDiagramId);
        diagram.setReactomeDiagramId(pathwayDiagramId); // Just in case this is not set
        // Step 1: Check if there is any need to create an overlay
        PathwayDiagram pathwayDiagram = (PathwayDiagram) curationService.findById(pathwayDiagramId);
        List<Pathway> representedPathways = pathwayDiagram.getRepresentedPathway();
        Set<Long> renderedInstanceDbIds = new HashSet<>();
        collectReactomeIds(diagram, renderedInstanceDbIds);
        // Step 2: If this is just a normal diagram (only one representedPathway), save it directly
        if (representedPathways.size() == 1) {
            Pathway pathway = representedPathways.get(0);
            generateDiagramJSON(diagram, pathway);
        }
        else if (representedPathways.size() > 1) {
            // Need to perform pathway diagram overlay.
            Map<Pathway, RenderablePathway> pathway2diagram = diagramOverlayer.overlayDiagrams(diagram,
                                                                                               representedPathways);
            for (Pathway pathway : pathway2diagram.keySet()) {
                RenderablePathway pDiagram = pathway2diagram.get(pathway);
                collectReactomeIds(pDiagram, renderedInstanceDbIds);
                generateDiagramJSON(pDiagram, pathway);
            }
        }
        return renderedInstanceDbIds;
    }

    /**
     * Computes the set of dbIds actually drawn in the passed Cytoscape JSON, without performing any of the
     * file-writing side effects of exportPathwayDiagramJSON. Used by the backfill script, and shares the
     * same conversion logic exportPathwayDiagramJSON uses for the live save path, so the two can never
     * silently diverge.
     */
    public Set<Long> computeRenderedInstanceDbIds(Long pathwayDiagramId, JsonNode cytoscapeJSON) throws Exception {
        this.initGraphConvertObjects();
        RenderablePathway diagram = converter.convert(cytoscapeJSON, pathwayDiagramId);
        diagram.setReactomeDiagramId(pathwayDiagramId);
        PathwayDiagram pathwayDiagram = (PathwayDiagram) curationService.findById(pathwayDiagramId);
        List<Pathway> representedPathways = pathwayDiagram.getRepresentedPathway();

        Set<Long> dbIds = new HashSet<>();
        collectReactomeIds(diagram, dbIds);
        if (representedPathways.size() > 1) {
            Map<Pathway, RenderablePathway> pathway2diagram = diagramOverlayer.overlayDiagrams(diagram, representedPathways);
            for (RenderablePathway pDiagram : pathway2diagram.values())
                collectReactomeIds(pDiagram, dbIds);
        }
        return dbIds;
    }

    /**
     * Computes the set of dbIds drawn in an already-built RenderablePathway - e.g. one parsed from the
     * legacy diagram XML stored in the relational database's storedATXML attribute (see
     * org.gk.persistence.DiagramGKBReader#openDiagram(String)) for a PathwayDiagram that has never been
     * edited through this tool and so has no Cytoscape JSON file. Shares the same classification logic
     * as the other overloads.
     */
    public Set<Long> computeRenderedInstanceDbIds(RenderablePathway diagram) {
        Set<Long> dbIds = new HashSet<>();
        collectReactomeIds(diagram, dbIds);
        return dbIds;
    }

    @SuppressWarnings("unchecked")
    private void collectReactomeIds(RenderablePathway diagram, Set<Long> dbIds) {
        if (diagram.getComponents() == null)
            return;
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            Long dbId = r.getReactomeId();
            if (dbId != null)
                dbIds.add(dbId);
        }
    }
    
    private void initGraphConvertObjects() {
        if (graphFactory == null) {
            this.graphFactory = new DiagramGraphFactory(ados);
            // TODO: Still need to figure out how to load the schema file.
            // However, the errors don't matter to us really!!!
            String processSchema = "process_schema.xsd";
            if (PathwayDiagramService.class.getClassLoader().getResource(processSchema) == null) {
                logger.error("Cannot find '" + processSchema + "' on classpath.");
                this.processFactory = new ProcessFactory("/" + processSchema);
            }
            else 
                this.processFactory = new ProcessFactory(processSchema);

            // Load schema to control where we should put process_schema.xsd file. This is really a hack to make sure the schema file is in the classpath.
            this.trivialChemicals = new TrivialChemicals(ados);
            // This is really a hack
            TestReportsHelper.setAdvancedDatabaseObjectService(ados);
        }
        if (converter.getCompartmentNameToIdMap() == null) {
            // Fetch the map from compartment name to id
            Map<String, Long> compartmentName2Id = this.curationService.getCurationRepository().fetchCompartmentNamesAndDbIds();
            converter.setCompartmentNameToIdMap(compartmentName2Id);
        }
    }
    
    private void generateDiagramJSON(RenderablePathway pathwayDiagram, 
                                     Pathway pathway) throws Exception {
        fillInSchemaClassNames(pathwayDiagram);
        String isDisease = (String) pathwayDiagram.getAttributeValue("isDisease");
        if (isDisease != null && isDisease.equalsIgnoreCase("true")) {
            generateDiseaseDiagramJSON(pathwayDiagram, pathway);
            return;
        }
        // For normal pathway diagram
        String xml = diagramWriter.generateXMLString(pathwayDiagram);
        _generateDiagramJSON(pathway, xml, pathwayDiagram.getComponents() == null || pathwayDiagram.getComponents().size() == 0);
    }
    
    @SuppressWarnings("unchecked")
    private void fillInSchemaClassNames(RenderablePathway diagram) {
        Set<Long> dbIds = new HashSet<>();
        if (diagram.getComponents() == null)
            return;
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            Long dbId = r.getReactomeId();
            if (dbId == null)
                continue;
            dbIds.add(dbId);
        }
        if (dbIds.size() == 0)
            return; // Nothing to do.
        Map<Long, String> id2schemaClass = curationService.getCurationRepository().fetchSchemaClasses(new ArrayList<>(dbIds));
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            Long dbId = r.getReactomeId();
            if (dbId == null)
                continue;
            String clsName = id2schemaClass.get(dbId);
            r.setAttributeValue(RenderablePropertyNames.SCHEMA_CLASS, clsName);
        }
    }

    private void _generateDiagramJSON(Pathway pathway, String xml, boolean isEmptyDiagram) throws Exception {
        Diagram diagram = null;
        if (isEmptyDiagram) {
            // For empty diagram, we don't have any component, so we can skip the process of creating graph and just create an empty diagram with the basic information.
            diagram = new Diagram();
            diagram.setDbId(pathway.getDbId());
            diagram.setDisplayName(pathway.getDisplayName());
            diagram.setSpeciesName(pathway.getSpeciesName());
            diagram.setStableId(pathway.getStId());
        }
        else {
            Process process = processFactory.createProcess(xml, pathway.getDbId() + "");
            // This is a hack to use PathwayDiagram instead of Pathway.
            diagram = LayoutFactory.getDiagramFromProcess(process, 
                    pathway.getDbId(),
                    pathway.getDisplayName(),
                    pathway.getStId(),
                    pathway.getSpeciesName());
        }
        // Bypass all QA checks here and generate the json text
        Graph graph = graphFactory.getGraph(diagram);
        diagram.createShadows(graph.getSubpathways());
        diagram = trivialChemicals.annotateTrivialChemicals(diagram, graphFactory.getEntityNodeMap());
        
        // Save to the output directory
        String outputDir = this.diagramRepository.getDiagramGraphDir();
        JsonWriter.serialiseGraph(graph, outputDir);
        JsonWriter.serialiseDiagram(diagram, outputDir);
    }
    
    @SuppressWarnings("unchecked")
    private void generateDiseaseDiagramJSON(RenderablePathway pathwayDiagram, 
                                            Pathway pathway) throws Exception {
        Element rootElm = diagramWriter.createRootElement(pathwayDiagram);
        // Add a label to show this is a disease pathway diagram
        rootElm.setAttribute("isDisease", Boolean.TRUE + "");
        rootElm.setAttribute("forNormalDraw", Boolean.FALSE + "");
        // Append some new information
        List<Renderable> normalComps = (List<Renderable>) pathwayDiagram.getAttributeValue("normalComponents");
        appendElement("normalComponents", 
                      rootElm, 
                      normalComps);
        List<Renderable> diseaseComps = (List<Renderable>) pathwayDiagram.getAttributeValue("diseaseComponents");
        appendElement("diseaseComponents",
                      rootElm,
                      diseaseComps);
        List<Node> crossedObjects = (List<Node>) pathwayDiagram.getAttributeValue("crossedComponents");
        appendElement("crossedComponents",
                      rootElm, 
                      crossedObjects);
        List<Renderable> overlaidComps = (List<Renderable>) pathwayDiagram.getAttributeValue("overlaidComponents");
        appendElement("overlaidComponents", 
                rootElm, 
                overlaidComps);
        List<Node> lofNodes = (List<Node>) pathwayDiagram.getAttributeValue("lofNodes");
        appendElement("lofNodes", rootElm, lofNodes);
        // Need to output
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
        outputter.output(rootElm, bos);
        _generateDiagramJSON(pathway, bos.toString(), false);
    }
    
    private void appendElement(String elmName,
                               Element root,
                               List<? extends Renderable> comps) {
        String text = generateTextForListOfComponents(comps);
        if (text == null)
            return;
        Element elm = new Element(elmName);
        elm.setText(text);
        root.addContent(elm);
    }
    
    private String generateTextForListOfComponents(List<? extends Renderable> comps) {
        if (comps == null || comps.size() == 0)
            return null;
        StringBuilder builder = new StringBuilder();
        for (Iterator<? extends Renderable> it = comps.iterator(); it.hasNext();) {
            Renderable r = it.next();
            builder.append(r.getID());
            if (it.hasNext())
                builder.append(",");
        }
        return builder.toString();
    }
    
    @NoArgsConstructor
    private class DiagramGKBJSONWriter extends DiagramGKBWriter {
        @Override
        protected Element createElementForRenderable(Renderable r) {
            Element elm = super.createElementForRenderable(r);
            String schemaClass = (String) r.getAttributeValue(RenderablePropertyNames.SCHEMA_CLASS);
            if (schemaClass != null)
                elm.setAttribute(RenderablePropertyNames.SCHEMA_CLASS, schemaClass);
            return elm;
        }
        
    }

}
