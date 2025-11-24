package org.reactome.curation.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gk.persistence.DiagramGKBReader;
import org.gk.persistence.DiagramGKBWriter;
import org.gk.render.FlowLine;
import org.gk.render.HyperEdge;
import org.gk.render.Node;
import org.gk.render.ProcessNode;
import org.gk.render.Renderable;
import org.gk.render.RenderableCompartment;
import org.gk.render.RenderablePathway;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.curation.util.CytoscapJSToRenderableDiagramConverter;
import org.reactome.server.graph.domain.model.Pathway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class is used to handle pathway diagram overlay. Its major features are ported from DiseasePathwayImageEditor and DieasepathwayImageEditorViaEFS classes
 * in the Java desktop curator tool.
 */
@SuppressWarnings("unchecked")
@Component
public class PathwayDiagramOverlayer {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PathwayDiagramOverlayer.class);
    @Autowired
    private CurationRepository curationRepository;
    @Autowired
    private CurationService curationService;
    @Autowired
    private CytoscapJSToRenderableDiagramConverter diagramConverter;
    // Use to clone diagrams
    private DiagramGKBWriter diagramWriter;
    private DiagramGKBReader diagramReader;
    
    public PathwayDiagramOverlayer() {
        diagramWriter = new DiagramGKBWriter();
        diagramReader = new DiagramGKBReader();
        diagramWriter.setNeedRegistryCheck(false);
        diagramWriter.setNeedDisplayName(true);
    }
    
    public Map<Pathway, RenderablePathway> overlayDiagrams(RenderablePathway pdDiagram, 
                                                           List<Pathway> representedPathways) throws Exception {
        // Get the normal pathway id
        Collection<Long> normalPathwayIds = curationRepository.fetchNormalPathwayIdsForDiagram(pdDiagram.getReactomeDiagramId().intValue());
        if (normalPathwayIds.size() != 1) {
            logger.error("There should be exactly one normal pathway for diagram " + pdDiagram);
            throw new IllegalStateException("There should be exactly one normal pathway for diagram " + pdDiagram);
        }
        Long normalPathwayId = normalPathwayIds.iterator().next();
        Collection<Long> normalReactionIds = curationRepository.fetchPathwayReactionIds(normalPathwayId); 
        Map<Pathway, RenderablePathway> pathway2diagram = new HashMap<>();
        for (Pathway pathway : representedPathways) {
            if (pathway.getDbId().equals(normalPathwayId)) {
                // This is the normal pathway
                RenderablePathway normalDiagram = cloneDiagram(pdDiagram);
                Set<Renderable> normalComponents = getNormalComponents(normalDiagram, normalReactionIds);
                for (Iterator<Renderable> it = normalDiagram.getComponents().iterator(); it.hasNext();) {
                    Renderable r = it.next();
                    if (!normalComponents.contains(r))
                        it.remove();
                }
                pathway2diagram.put(pathway, normalDiagram);
            }
            else {
                // Disease pathway
                RenderablePathway diseaseDiagram = cloneDiagram(pdDiagram);
                DiagramOverlayHelper helper = getOverlayHelper(diseaseDiagram, 
                                                                pathway.getDbId(),
                                                               normalReactionIds);
                helper.overlayDiseaseDiagram();
                pathway2diagram.put(pathway, diseaseDiagram);
            }
        }
        return pathway2diagram;
    }
    
    private DiagramOverlayHelper getOverlayHelper(RenderablePathway diseaseDiagram,
                                                  Long diseasePathwayId,
                                                  Collection<Long> normalReactionIds) {
        DiagramOverlayHelper helper = new DiagramOverlayHelper();
        helper.setPathwayDiagram(diseaseDiagram);
        helper.setDiseasePathwayId(diseasePathwayId);
        // Since we are using a different cloned RenderablePathway diagram, we 
        // have to collected normal components from scratch and cannot really 
        // re-use the previous ones collected from another cloned diagram.
        Set<Renderable> normalComponents = getNormalComponents(diseaseDiagram, normalReactionIds);
        helper.setNormalComponents(normalComponents);
        helper.setCurationRepository(curationRepository);
        helper.setCurationService(curationService);
        helper.setPathwayEditor(diagramConverter.getPathwayEditor());
        return helper;
    }
   
    
    private RenderablePathway cloneDiagram(RenderablePathway diagram) throws Exception{
        String diagramXML = diagramWriter.generateXMLString(diagram);
        // Read it back so that we can validate it
        RenderablePathway rtn = diagramReader.openDiagram(diagramXML);
        return rtn;
    }
    
    private Set<Renderable> getNormalComponents(RenderablePathway diagram,
                                                Collection<Long> normalReactionIds) {
        Set<Renderable> normal = new HashSet<>();
        if (diagram.getComponents() == null || diagram.getComponents().size() == 0)
            return normal;
        // Check nodes
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            if (!(r instanceof Node))
                continue;
            // Pathway nodes and other nodes without reactome id (e.g. notes)
            // should be for normal pathway only and also all compartment for the time being
            if (r instanceof ProcessNode || r.getReactomeId() == null || r instanceof RenderableCompartment) {
                normal.add(r);
                continue;
            }
            // Check if it is linked to any normal reactions
            List<HyperEdge> edges = ((Node)r).getConnectedReactions();
            for (HyperEdge edge : edges) {
                if (normalReactionIds.contains(edge.getReactomeId())) {
                    normal.add(r);
                    break;
                }
            }
        }
        // Check edges
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            if (!(r instanceof HyperEdge))
                continue;
            if (normalReactionIds.contains(r.getReactomeId())) {
                normal.add(r);
                continue;
            }
            if (r instanceof FlowLine) {
                FlowLine flowLine = (FlowLine) r;
                Node input = flowLine.getInputNode(0);
                Node output = flowLine.getOutputNode(0);
                if (normal.contains(input) && normal.contains(output))
                    normal.add(r);
            }
        }
        return normal;
    }
}
