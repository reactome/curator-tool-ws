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
import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.PathwayDiagram;
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
        Map<Pathway, RenderablePathway> pathway2diagram = new HashMap<>();
        for (Pathway pathway : representedPathways) {
            if (pathway.getDbId().equals(normalPathwayId)) {
                // This is the normal pathway
                RenderablePathway normalDiagram = cloneDiagram(pdDiagram);
                Set<Renderable> normalComponents = getNormalComponents(normalDiagram, normalPathwayId);
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
//                overlaidDiagrams.add(diseaseDiagram);
            }
        }
        return pathway2diagram;
    }
    
    private RenderablePathway cloneDiagram(RenderablePathway diagram) throws Exception{
        String diagramXML = diagramWriter.generateXMLString(diagram);
        // Read it back so that we can validate it
        RenderablePathway rtn = diagramReader.openDiagram(diagramXML);
        return rtn;
    }
    
    private Set<Renderable> getNormalComponents(RenderablePathway diagram,
                                                Long normalPathwayId) {
        Set<Renderable> normal = new HashSet<>();
        if (diagram.getComponents() == null || diagram.getComponents().size() == 0)
            return normal;
        Collection<Long> normalReactionIds = curationRepository.fetchPathwayReactionIds(normalPathwayId); 
        // Check nodes
        for (Renderable r : (List<Renderable>) diagram.getComponents()) {
            if (!(r instanceof Node))
                continue;
            // Pathway nodes and other nodes without reactome id (e.g. notes)
            // should be for normal pathway only and also all compartment for the time being
            if (r instanceof ProcessNode || r.getReactomeId() == null || r instanceof RenderableCompartment)
                normal.add(r);
            // Check if it is linked to any normal reactions
            List<HyperEdge> edges = ((Node)r).getConnectedReactions();
            for (HyperEdge edge : edges) {
                if (normalReactionIds.contains(edge.getReactomeId()))
                    normal.add(r);
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

    
    /**
     * Split objects into normal and disease components
     */
    private void splitObjects(RenderablePathway diagram,
                              List<Pathway> patwhays,
                              Long normalPathwayId) {
        Collection<Long> normalReactionIds = curationRepository.fetchPathwayReactionIds(normalPathwayId); 
//        normalIds = new HashSet<Long>();
//        diseaseIds = new HashSet<Long>();
//            GKInstance diseasePathway = null;
//            GKInstance normalPathway = null;
//            GKInstance disease = (GKInstance) pathway.getAttributeValue(ReactomeJavaConstants.disease);
//            if (disease == null && contains(pathway, pathways)) {
//                normalPathway = pathway;
//            }
//            else {
//                diseasePathway = pathway;
//                // There should be a normal pathway contained by a disease pathway
//                normalPathway = findNormalPathway(diseasePathway, pdInst);
//            }
//            if (normalPathway != null) {
//                // Get all disease related objects
//                Set<GKInstance> normalEvents = InstanceUtilities.grepPathwayEventComponents(normalPathway);
//                for (GKInstance inst : normalEvents)
//                    normalIds.add(inst.getDBID());
//                Set<GKInstance> normalEntities = InstanceUtilities.grepPathwayParticipants(normalPathway);
//                for (GKInstance inst : normalEntities)
//                    normalIds.add(inst.getDBID());
//                if (diseasePathway != null) {
//                    Set<GKInstance> allEvents = InstanceUtilities.grepPathwayEventComponents(diseasePathway);
//                    allEvents.removeAll(normalEvents);
//                    for (GKInstance inst : allEvents) {
//                        diseaseIds.add(inst.getDBID());
//                        // Want to make sure disease pathways are connected to objects
//                        if (inst.getSchemClass().isa(ReactomeJavaConstants.ReactionlikeEvent)) {
//                            Set<GKInstance> rxtEntities = InstanceUtilities.getReactionParticipants(inst);
//                            for (GKInstance rxtEntity : rxtEntities) {
//                                diseaseIds.add(rxtEntity.getDBID());
//                            }
//                        }
//                    }
////                    Set<GKInstance> allEntities = InstanceUtilities.grepPathwayParticipants(diseasePathway);
////                    allEntities.removeAll(normalEntities);
////                    for (GKInstance inst : allEntities)
////                        diseaseIds.add(inst.getDBID());
//                }
//            }
//            splitComponents();
//        }
//        catch(Exception e) {
//            e.printStackTrace();
//        }
    }

}
