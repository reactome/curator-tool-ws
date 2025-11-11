package org.reactome.curation.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.gk.persistence.DiagramGKBWriter;
import org.gk.render.RenderablePathway;
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

/**
 * This class is intended to handle operations related to pathway diagrams. Most of functions in this
 * class are related to create overlay of disease related pathway diagrams onto the normal pathway diagrams.
 * The majority of the code is ported from the original Java-based curator tool.
 */
@Service
public class PathwayDiagramService {
    
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
    
    // Other objects needed for conversion
    private DiagramGKBWriter diagramWriter;
    private DiagramGraphFactory graphFactory;
    private ProcessFactory processFactory;
    private TrivialChemicals trivialChemicals;
    
    public PathwayDiagramService() {
        diagramWriter = new DiagramGKBWriter();
        diagramWriter.setNeedRegistryCheck(false);
        diagramWriter.setNeedDisplayName(true);
    }   
    
    public JsonNode loadDiagramJson(String fileName) throws IOException {
        String text = this.diagramRepository.loadDiagramJson(fileName);
        JsonNode jsonNode = mapper.readTree(text);
        return jsonNode;
    }
    
    public JsonNode loadCytosapeNetwork(Long pathwayId) throws IOException {
        String text = diagramRepository.loadCytoscapeNetwork(pathwayId);
        JsonNode jsonNode = mapper.readTree(text); 
        return jsonNode;
    }
    
    public Boolean hasCytoscapeNetwork(Long pathwayId) throws IOException {
        return diagramRepository.hasCytoscapeNetwork(pathwayId);
    }
    
    public void saveCytoscapeNetwork(Long pathwayDiagramId, JsonNode cytoscapeJSON) throws Exception {
        String text = this.mapper.writeValueAsString(cytoscapeJSON);
        this.diagramRepository.saveCytoscapeNetwork(pathwayDiagramId, text);
        this.exportPathwayDiagramJSON(pathwayDiagramId, cytoscapeJSON);
    }
    
    public void exportPathwayDiagramJSON(Long pathwayDiagramId, JsonNode cytoscapeJSON) throws Exception {
        this.initGraphConvertObjects();
        RenderablePathway diagram = converter.convert(cytoscapeJSON, pathwayDiagramId);
        // Step 1: Check if there is any need to create an overlay
        PathwayDiagram pathwayDiagram = (PathwayDiagram) curationService.findById(pathwayDiagramId);
        List<Pathway> representedPathways = pathwayDiagram.getRepresentedPathway();
        // Step 2: If this is just a normal diagram (only one representedPathway), save it directly
        if (representedPathways.size() == 1) {
            String diagramXML = diagramWriter.generateXMLString(diagram);
            Pathway pathway = representedPathways.get(0);
            generateDiagramJSON(diagramXML, pathway);
            return;
        }
//        curationService.get
        // Step 3: If overlaying is needed, create a new diagram for each disease and save them
    }
    
    private void initGraphConvertObjects() {
        if (graphFactory == null) {
            this.graphFactory = new DiagramGraphFactory();
            this.graphFactory.setAos(ados);
            this.processFactory = new ProcessFactory("/process_schema.xsd");
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
    
    private void generateDiagramJSON(String xml, 
                                     Pathway pathway) throws Exception {
        // Generate XML first: This is the native XML contains both normal and disease layout information
        Process process = processFactory.createProcess(xml, pathway.getDbId() + "");
        // This is a hack to use PathwayDiagram instead of Pathway.
        Diagram diagram = LayoutFactory.getDiagramFromProcess(process, 
                                                              pathway.getDbId(),
                                                              pathway.getDisplayName(),
                                                              pathway.getStId(),
                                                              pathway.getSpeciesName());
        // Bypass all QA checks here and generate the json text
        Graph graph = graphFactory.getGraph(diagram);
        diagram.createShadows(graph.getSubpathways());
        diagram = trivialChemicals.annotateTrivialChemicals(diagram, graphFactory.getEntityNodeMap());
        
        // Save to the output directory
        String outputDir = this.diagramRepository.getDiagramGraphDir();
        JsonWriter.serialiseGraph(graph, outputDir);
        JsonWriter.serialiseDiagram(diagram, outputDir);
    }

}
