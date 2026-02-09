package org.reactome.curation.service;

import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.graphEditor.PathwayEditor;
import org.gk.render.ConnectInfo;
import org.gk.render.ConnectWidget;
import org.gk.render.DefaultRenderConstants;
import org.gk.render.FlowLine;
import org.gk.render.HyperEdge;
import org.gk.render.Node;
import org.gk.render.NodeAttachment;
import org.gk.render.ProcessNode;
import org.gk.render.RenderUtility;
import org.gk.render.Renderable;
import org.gk.render.RenderableCell;
import org.gk.render.RenderableChemical;
import org.gk.render.RenderableChemicalDrug;
import org.gk.render.RenderableComplex;
import org.gk.render.RenderableEntity;
import org.gk.render.RenderableEntitySet;
import org.gk.render.RenderableFeature;
import org.gk.render.RenderableGene;
import org.gk.render.RenderablePathway;
import org.gk.render.RenderableProtein;
import org.gk.render.RenderableProteinDrug;
import org.gk.render.RenderableRNA;
import org.gk.render.RenderableRNADrug;
import org.gk.render.RendererFactory;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.domain.model.AbstractModifiedResidue;
import org.reactome.server.graph.domain.model.CatalystActivity;
import org.reactome.server.graph.domain.model.Cell;
import org.reactome.server.graph.domain.model.ChemicalDrug;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.EntityFunctionalStatus;
import org.reactome.server.graph.domain.model.EntitySet;
import org.reactome.server.graph.domain.model.EntityWithAccessionedSequence;
import org.reactome.server.graph.domain.model.FailedReaction;
import org.reactome.server.graph.domain.model.FunctionalStatus;
import org.reactome.server.graph.domain.model.NegativeRegulation;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.PositiveRegulation;
import org.reactome.server.graph.domain.model.ProteinDrug;
import org.reactome.server.graph.domain.model.PsiMod;
import org.reactome.server.graph.domain.model.RNADrug;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.graph.domain.model.ReferenceDNASequence;
import org.reactome.server.graph.domain.model.ReferenceEntity;
import org.reactome.server.graph.domain.model.ReferenceGeneProduct;
import org.reactome.server.graph.domain.model.ReferenceRNASequence;
import org.reactome.server.graph.domain.model.Regulation;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.model.TranslationalModification;

import lombok.NoArgsConstructor;

/**
 * A helper class for disease pathway diagram overlay. Using a separate class to keep variables
 * as member fields to avoid passing too many parameters in methods.
 */
@NoArgsConstructor
@SuppressWarnings("unchecked")
public class DiagramOverlayHelper {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DiagramOverlayHelper.class);

    private CurationService curationService;
    private CurationRepository curationRepository;
    private RenderablePathway diagram;
    // Use sets to avoid duplicates. But they will be converted to lists when stored as attribute values.
    private Set<Renderable> normalComponents;
    private Set<Renderable> diseaseComponents;
    private Set<Renderable> overlaidComponents;
    private Set<Node> crossedComponents;
    private Set<Node> lofNodes;
    private Map<Long, Renderable> id2comp;
    private Long diseasePathwayId;
    // To reuse mapped nodes
    Map<Node, Map<Long, Node>> normalToDBIDToDiseaseNode;
    // To provide graphics context for ModifiedResidueHandler
    private PathwayEditor pathwayEditor;

    public void setPathwayEditor(PathwayEditor pathwayEditor) {
        this.pathwayEditor = pathwayEditor;
    }

    public void setCurationService(CurationService curationService) {
        this.curationService = curationService;
    }

    public void setCurationRepository(CurationRepository curationRepository) {
        this.curationRepository = curationRepository;
    }

    public void setPathwayDiagram(RenderablePathway diagram) {
        this.diagram = diagram;
    }

    public void setNormalComponents(Set<Renderable> normalComponents) {
        this.normalComponents = normalComponents;
    }

    public void setDiseasePathwayId(Long diseasePathwayId) {
        this.diseasePathwayId = diseasePathwayId;
    }

    private void initialize() {
        // There are four types of components in a disease pathway
        diseaseComponents = new HashSet<>();
        overlaidComponents = new HashSet<>();
        crossedComponents = new HashSet<>();
        lofNodes = new HashSet<>();
        // Cached maps
        id2comp = new HashMap<>();
        // Reuse mapped disease nodes for normal nodes
        normalToDBIDToDiseaseNode = new HashMap<>();
    }

    public void overlayDiseaseDiagram() {
        // Initialize member variables
        initialize();
        // Get all disease reaction ids
        Collection<Long> diseaseReactionIds = curationRepository.fetchPathwayReactionIds(diseasePathwayId);
        // Go over all components in the diagram to find disease related components
        List<Renderable> comps = diagram.getComponents();
        // Get reactions and their connected nodes first
        for (Renderable r : comps) {
            if ((r.getReactomeId() == null) || !(r instanceof HyperEdge))
                continue;
            if (diseaseReactionIds.contains(r.getReactomeId())) {
                diseaseComponents.add(r);
                List<Node> connectedNodes = ((HyperEdge)r).getConnectedNodes();
                diseaseComponents.addAll(connectedNodes);
            }
        }
        // Check FlowLines next
        for (Renderable r : comps) {
            if (!(r instanceof FlowLine))
                continue;
            FlowLine flowLine = (FlowLine) r;
            Node input = flowLine.getInputNode(0);
            Node output = flowLine.getOutputNode(0);
            // Following the original logic in DiseasePatwhayImageEditor, we will
            // add a flowline between a disease node and a ProcessNode 
            // ProcessNode should not be linked to ReactionLikeEvent directly (first loop),
            // Therefore, we need to add here.
            if (diseaseComponents.contains(input) && output instanceof ProcessNode) {
                diseaseComponents.add(r);
                diseaseComponents.add(output);
            }
            else if (diseaseComponents.contains(output) && input instanceof ProcessNode) {
                diseaseComponents.add(r);
                diseaseComponents.add(input);
            }
        }
        // Nodes should be handled in the first loop. No need to do it again.
        // Remove other components that are not covered by both disease and normal components
        for (Iterator<Renderable> it = comps.iterator(); it.hasNext();) {
            Renderable r = it.next();
            // These are needed components
            if (normalComponents.contains(r) || diseaseComponents.contains(r)) {
                if (r.getReactomeId() != null)
                    id2comp.put(r.getReactomeId(), r);
                continue;
            }
            it.remove();
        }
        // Go over all disease reactions
        for (Long diseaseRxtId : diseaseReactionIds) {
            if (id2comp.containsKey(diseaseRxtId))
                continue; // This reaction has been added manually by curators
            // We need to do overlay for this reaction
            overlayReaction(diseaseRxtId);
        }
        // Set attributes
        diagram.setAttributeValue("isDisease", Boolean.TRUE);
        diagram.setAttributeValue("normalComponents", new ArrayList<>(normalComponents));
        diagram.setAttributeValue("diseaseComponents", new ArrayList<>(diseaseComponents));
        diagram.setAttributeValue("crossedComponents", new ArrayList<>(crossedComponents));
        diagram.setAttributeValue("overlaidComponents", new ArrayList<>(overlaidComponents));
        diagram.setAttributeValue("lofNodes", new ArrayList<>(lofNodes));
    }

    /**
     * Perform overlaying of a disease reaction onto its normal reaction. The normal reaction should be annotated
     * at the disease reaction's normalReaction slot.
     * @param diseaseRxtId The dbId of the disease reaction to be overlaid.
     */
    private void overlayReaction(Long diseaseRxtId) {
        // This should be a fully loaded ReactionLikeEvent. However, its attributes are not loaded.
        ReactionLikeEvent diseaseReaction = (ReactionLikeEvent) curationService.findById(diseaseRxtId);
        // Get the normal reaction
        if (diseaseReaction.getNormalReaction() == null || 
                diseaseReaction.getNormalReaction().getDbId() == null ||
                !id2comp.containsKey(diseaseReaction.getNormalReaction().getDbId())) {
            logger.warn("normal reaction is not defined, cannot be found or is not drawn for disease reaction with dbId, " + diseaseRxtId);
            return; // Nothing we can do. 
        }
        HyperEdge normalReactionEdge = (HyperEdge) id2comp.get(diseaseReaction.getNormalReaction().getDbId());
        // Since basically we don't cache anything. There is no need to do this mapping here.
        //        Map<Long, SimpleInstance> dbIdToInstance = createDBIdToMapForDiseasePEs(diseaseReaction);
        // Make a copy of the normal reaction. This is basically the disease reaction to be rendered.
        HyperEdge diseaseReactionEdge = normalReactionEdge.shallowCopy();
        diseaseReactionEdge.setReactomeId(diseaseReaction.getDbId());
        diseaseReactionEdge.setDisplayName(diseaseReaction.getDisplayName());
        diseaseReactionEdge.setLineColor(DefaultRenderConstants.DEFAULT_DISEASE_LINE_COLOR);
        diagram.addComponent(diseaseReactionEdge);
        overlaidComponents.add(diseaseReactionEdge);

        // List objects not listed in the disease reaction as crossed objects
        Set<Long> lofEntityIds = new HashSet<>();
        // PE in this map may not be the same as those in diseaseReaction. The only thing that is 
        // guaranteed is that their dbIds are the same.
        Map<Long, Node> diseasePEIdToNormalNode = mapDiseaseToNormalEntity(diseaseReaction, 
                normalReactionEdge,
                lofEntityIds);
        Set<Node> coveredNormalNodes = new HashSet<>();

        // Collection all lists of nodes first for cleaner code
        List<List<PhysicalEntity>> diseasePEList = new ArrayList<>();
        List<List<Node>> normalNodeLists = new ArrayList<>();
        List<Integer> roles = new ArrayList<>();
        // Register all potential nodes
        // Inputs
        diseasePEList.add(diseaseReaction.getInput());
        normalNodeLists.add(normalReactionEdge.getInputNodes());
        roles.add(HyperEdge.INPUT);
        // Outputs
        diseasePEList.add(diseaseReaction.getOutput());
        normalNodeLists.add(normalReactionEdge.getOutputNodes());
        roles.add(HyperEdge.OUTPUT);
        // Catalysts
        List<PhysicalEntity> cas = getCatalyst(diseaseReaction);
        diseasePEList.add(cas);
        normalNodeLists.add(normalReactionEdge.getHelperNodes());
        roles.add(HyperEdge.CATALYST);
        // Regulators
        List<PhysicalEntity>[] regulators = getRegulators(diseaseReaction);
        // Activators
        diseasePEList.add(regulators[0]);
        normalNodeLists.add(normalReactionEdge.getActivatorNodes());
        roles.add(HyperEdge.ACTIVATOR);
        // Inhibitors
        diseasePEList.add(regulators[1]);
        normalNodeLists.add(normalReactionEdge.getInhibitorNodes());
        roles.add(HyperEdge.INHIBITOR);

        // Now handle the node overlay
        for (int i = 0; i < diseasePEList.size(); i++) {
            List<PhysicalEntity> entities = diseasePEList.get(i);
            List<Node> normalNodes = normalNodeLists.get(i);
            if (entities == null || entities.size() == 0 ||
                    normalNodes == null || normalNodes.size() == 0)
                continue; // Basically nothing we can do in any of the above cases.
            int role = roles.get(i);
            handleDiseaseEntities(diseaseReactionEdge, 
                    diseaseReaction,
                    lofEntityIds,
                    diseasePEIdToNormalNode, 
                    entities,
                    normalNodes,
                    role,
                    coveredNormalNodes);
        }

        collectCrossedNodes(diseaseReaction, 
                normalReactionEdge,
                coveredNormalNodes);
    }

    private void collectCrossedNodes(ReactionLikeEvent diseaseRLE,
                                     HyperEdge normalReaction,
                                     Set<Node> coveredNormalNodes) {
        // Only FailedReaction needs to be handled here
        if (!(diseaseRLE instanceof FailedReaction))
            return;
        // This will create a new copy of list. Nothing to worry about modifying it.
        List<Node> connectedNodes = normalReaction.getConnectedNodes();
        connectedNodes.removeAll(coveredNormalNodes);
        this.crossedComponents.addAll(connectedNodes);
    }

    /**
     * Get the regulators for the disease reaction.
     * @param diseaseReaction
     * @return two elements of lists: the first is activators and the second inhibitors.
     */
    private List<PhysicalEntity>[] getRegulators(ReactionLikeEvent diseaseReaction) {
        List<Regulation> regulations = diseaseReaction.getRegulatedBy();
        if (regulations == null || regulations.size() == 0) {
            return new List[]{Collections.EMPTY_LIST, Collections.EMPTY_LIST};
        }
        // Make sure nothing is duplicated
        // PEs in this map should be fully loaded
        Map<Long, PhysicalEntity> id2PE = new HashMap<>();
        // Split into activators and inhibitors
        List<PhysicalEntity> activators = new ArrayList<>();
        List<PhysicalEntity> inhibitors = new ArrayList<>();
        for (Regulation regulation : regulations) {
            regulation = (Regulation) curationService.findById(regulation.getDbId());
            PhysicalEntity regulator = regulation.getRegulator();
            if (regulator == null)
                continue;
            if (!id2PE.containsKey(regulator.getDbId())) {
                regulator = (PhysicalEntity) curationService.findById(regulator.getDbId());
                // We will cache it no matter what.
                // In case regulator is null, this will prevent repeated searching
                id2PE.put(regulator.getDbId(), regulator);
            }
            regulator = id2PE.get(regulator.getDbId());
            if (regulator == null) 
                continue;
            if (regulation instanceof PositiveRegulation) {
                activators.add(regulator);
            }
            else if (regulation instanceof NegativeRegulation) {
                inhibitors.add(regulator);
            }
        }
        return new List[]{activators, inhibitors};
    }

    private List<PhysicalEntity> getCatalyst(ReactionLikeEvent diseaseReaction) {
        List<CatalystActivity> cas = diseaseReaction.getCatalystActivity();
        if (cas == null || cas.size() == 0)
            return Collections.EMPTY_LIST;
        // We need to use map since the objects may change 
        Map<Long, PhysicalEntity> dbId2PE = new HashMap<>();
        for (CatalystActivity ca : cas) {
            ca = (CatalystActivity) curationService.findById(ca.getDbId()); // Need to pull out the full object
            PhysicalEntity pe = ca.getPhysicalEntity();
            if (pe == null)
                continue;
            if (dbId2PE.containsKey(pe.getDbId()))
                continue;
            pe = (PhysicalEntity) curationService.findById(pe.getDbId());
            if (pe == null)
                continue;
            dbId2PE.put(pe.getDbId(), pe);
        }
        return new ArrayList<>(dbId2PE.values());
    }

    private void handleDiseaseEntities(HyperEdge diseaseReactionEdge,
                                       ReactionLikeEvent diseaseRLE,
                                       Set<Long> lofPEIds,
                                       Map<Long, Node> diseasePEIdToNormalNode,
                                       Collection<PhysicalEntity> diseasePEs,
                                       List<Node> normalNodes,
                                       int role,
                                       Set<Node> coveredAllNodes) {
        Set<Node> coveredNormalNodes = new HashSet<>();
        // Since we are not sure if two PE objects having the same dbId are the really the same.
        // Therefore, we have to go over dbIds to normalize things.
        Map<Long, PhysicalEntity> dbIdToEntity = new HashMap<>();
        Map<Long, Integer> dbIdToStoichiometry = new HashMap<>();
        for (PhysicalEntity pe : diseasePEs) {  
            dbIdToEntity.put(pe.getDbId(), pe);
            Integer stoi = dbIdToStoichiometry.get(pe.getDbId());
            if (stoi == null) {
                dbIdToStoichiometry.put(pe.getDbId(), 1);
            }
            else 
                dbIdToStoichiometry.put(pe.getDbId(), stoi + 1);
        }
        Map<Long, Node> normalIdToNode = normalNodes.stream()
                .filter(n -> n.getReactomeId() != null)
                .collect(Collectors.toMap(Node::getReactomeId, n -> n));
        for (Iterator<Long> it = dbIdToEntity.keySet().iterator(); it.hasNext();) {
            Long dbId = it.next();
            PhysicalEntity entity = dbIdToEntity.get(dbId);
            Integer stoi = dbIdToStoichiometry.get(dbId);
            Node normalNode = normalIdToNode.get(entity.getDbId());
            if (normalNode != null) {
                // Great. Nothing needs to be done except the stoichiometry
                // which may be different
                // Re-link to diseaseNode
                if (stoi != null) {
                    ConnectInfo connectInfo = diseaseReactionEdge.getConnectInfo();
                    List<?> widgets = connectInfo.getConnectWidgets();
                    for (Object obj : widgets) {
                        ConnectWidget widget = (ConnectWidget) obj;
                        if (widget.getConnectedNode() == normalNode && widget.getRole() == role) {
                            widget.setStoichiometry(stoi);
                            break;
                        }
                    }
                }
                it.remove(); // No need to process this entity again
                normalNodes.remove(normalNode);
                coveredNormalNodes.add(normalNode);
                overlaidComponents.add(normalNode); // To be rendered for disease reaction
                continue;
            }
            normalNode = diseasePEIdToNormalNode.get(entity.getDbId());
            if (normalNode != null) {
                Node diseaseNode = replaceNormalNodeByDisease(normalNode,
                        entity, 
                        lofPEIds, 
                        diseaseReactionEdge,
                        role,
                        stoi);
                if (diseaseNode == null)
                    continue; // To be handled by others.
                it.remove();
                normalNodes.remove(normalNode);
                coveredNormalNodes.add(normalNode);
                // The normalNode is not an overlaid component since it is replaced by diseaseNode
                continue;
            }
        }
        if (dbIdToEntity.size() > 0) {
            for (Long dbId : dbIdToEntity.keySet()) {
                PhysicalEntity input = dbIdToEntity.get(dbId);
                Integer stoi = dbIdToStoichiometry.get(dbId);
                // Perform some smart matching :-)
                Set<Long> inputRefIds = new HashSet<>(this.curationRepository.getReferenceEntityDbIdsForPEId(input.getDbId()));
                Node normalNode = findBestPossibleMatch(normalNodes, inputRefIds);
                if (normalNode == null) { // e.g. R-HSA-5602549
                    normalNode = findNodeFromEntitySet(normalNodes, input); // Another try
                }
                // Last test: whatever is left
                if (normalNode == null) {
                    if (dbIdToEntity.size() == 1 && normalNodes.size() == 1) {
                        // Whatever is left
                        normalNode = normalNodes.get(0);
                    }
                }
                if (normalNode != null) {
                    Node diseaseNode = replaceNormalNodeByDisease(normalNode,
                            input,
                            lofPEIds,
                            diseaseReactionEdge,
                            role,
                            stoi);
                    if (diseaseNode == null)
                        continue; // Cannot do anything. TODO: Probably an error should be generated here!
                    normalNodes.remove(normalNode); // We don't want to reuse normal node
                    coveredNormalNodes.add(normalNode);
                }
            }
        }
        normalNodes.removeAll(coveredNormalNodes);
        coveredAllNodes.addAll(coveredNormalNodes);
        // The following code is not used. Therefore, the overlaid FailedReactions
        // may not be consistent to the data model
        //        // Special treatment for FailedReactions to keep these links
        //        if (diseaseRLE.getSchemClass().isa(ReactomeJavaConstants.FailedReaction) && role == HyperEdge.OUTPUT)
        //            return;
        //        // Otherwise, cleaning up
        //        for (Node node : normalNodes)
        //            reactionCopy.remove(node, role);
    }

    private Node findBestPossibleMatch(Collection<Node> nodes, 
                                       Set<Long> refEntIds) {
        Node bestNode = null;
        int bestExtra = Integer.MAX_VALUE;
        for (Node node : nodes) {
            Set<Long> nodeRefEntIds = new HashSet<>(curationRepository.getReferenceEntityDbIdsForPEId(node.getReactomeId()));
            Set<Long> copy = new HashSet<>(nodeRefEntIds);
            nodeRefEntIds.retainAll(refEntIds);
            if (nodeRefEntIds.size() > 0) {
                // First have to make sure there is something similar
                // Then we want to see if there is something extra
                copy.removeAll(nodeRefEntIds); 
                int extra = copy.size();
                // We'd like to keep a node that is matched to refEntIds as much as possible
                // with the minimum left reference entities.
                if (bestNode == null || extra < bestExtra) { 
                    bestNode = node;
                    bestExtra = extra;
                }
            }
        }
        return bestNode;
    }

    private Node findNodeFromEntitySet(List<Node> normalNodes,
                                       PhysicalEntity diseasePE) {
        for (Node node : normalNodes) {
            // This check works only for EntitySet
            if (node.getReactomeId() == null || !(node instanceof RenderableEntitySet))
                continue;
            Collection<Long> memberIds = curationRepository.getMemberIdsForEntitySet(node.getReactomeId());
            if (memberIds.contains(diseasePE.getDbId()))
                return node; // This is a quite weak match.
        }
        return null;
    }

    private Node replaceNormalNodeByDisease(Node normalNode, 
                                            PhysicalEntity input, 
                                            Set<Long> lofPEIds,
                                            HyperEdge diseaseReactionEdge,
                                            int role,
                                            Integer stoi) {
        Node diseaseNode = replaceNormalNodeByDisease(normalNode, 
                input,
                lofPEIds.contains(input.getDbId()));
        if (diseaseNode == null)
            return null; // Just in case
        // Re-link to diseaseNode
        ConnectInfo connectInfo = diseaseReactionEdge.getConnectInfo();
        List<?> widgets = connectInfo.getConnectWidgets();
        for (Object obj : widgets) {
            ConnectWidget widget = (ConnectWidget) obj;
            if (widget.getConnectedNode() == normalNode && widget.getRole() == role) {
                widget.replaceConnectedNode(diseaseNode);
                if (stoi != null)
                    widget.setStoichiometry(stoi);
            }
        }
        return diseaseNode;
    }

    private Node replaceNormalNodeByDisease(Node normalNode,
                                            PhysicalEntity diseaseEntity,
                                            Boolean needDashedBorder) {
        Map<Long, Node> dbIdToDiseaseNode = normalToDBIDToDiseaseNode.get(normalNode);
        if (dbIdToDiseaseNode != null && dbIdToDiseaseNode.containsKey(diseaseEntity.getDbId()))
            return dbIdToDiseaseNode.get(diseaseEntity.getDbId());
        // If a node exists already, it should be used
        for (Renderable r : diseaseComponents) {
            if (diseaseEntity.getDbId().equals(r.getReactomeId()) && r instanceof Node) {
                // This is rather arbitrary: if two nodes are very close,
                // use the existing one.
                int dx = Math.abs(r.getPosition().x - normalNode.getPosition().x);
                int dy = Math.abs(r.getPosition().y - normalNode.getPosition().y);
                if (dx < 100 && dy < 100) {
                    // We don't need to create a new Node if it exists already
                    cacheDiseaseNode(diseaseEntity.getDbId(), (Node)r, normalNode);
                    // Copied from the original Java desktop code. Not sure if this is necessary.
                    // Keep it for now and may be removed later.
                    overlaidComponents.add(r); // Add it to overlaid object to cover edges
                    return (Node)r;
                }
            }
        }

        // If it is an EWAS, we need to load it fully to get its modified residues and ReferenceEntity
        // for type determination
        diseaseEntity = (PhysicalEntity) curationService.findById(diseaseEntity.getDbId());
        Node diseaseNode = convertDiseaseEntityToNode(diseaseEntity);
        RenderUtility.copyRenderInfo(normalNode, diseaseNode);
        // The following should NOT be called since NodeAttachment is
        // related to disease entity only.
        //TODO: Need to support this. Currently it is not supported!!! See example
        // in PI3/AKT cancer pathway.
        //diseaseNode.setNodeAttachmentsLocally(node.getNodeAttachments());
        // Update to support node attachments for disease entities on May 9, 2019
        convertModifiedResiduesToNodeFeatures(diseaseEntity,
                diseaseNode,
                pathwayEditor.getGraphics());
        // Since diseaseNode type may be different from normalNode,
        // we will try to get the original possible renderer
        diseaseNode.setRenderer(RendererFactory.getFactory().getRenderer(diseaseNode));
        //              diseaseNode.setRenderer(normalNode.getRenderer());
        diseaseNode.setLineColor(DefaultRenderConstants.DEFAULT_DISEASE_LINE_COLOR);
        diseaseNode.setNeedDashedBorder(needDashedBorder);
        RenderUtility.hideCompartmentInNodeName(diseaseNode);
        diagram.addComponent(diseaseNode);
        diseaseNode.setContainer(diagram);
        diseaseNode.invalidateBounds();
        overlaidComponents.add(diseaseNode);
        // This has been added to the diagram in convertDiseaseEntityToNode
        cacheDiseaseNode(diseaseEntity.getDbId(), diseaseNode, normalNode);
        return diseaseNode;
    }

    private void cacheDiseaseNode(Long diseaseEntityDbId, 
                                  Node diseaseNode,
                                  Node normalNode) {
        Map<Long, Node> dbIdToDiseaseNode = normalToDBIDToDiseaseNode.get(normalNode);
        if (dbIdToDiseaseNode == null) {
            dbIdToDiseaseNode = new HashMap<>();
            normalToDBIDToDiseaseNode.put(normalNode, dbIdToDiseaseNode);
        }
        dbIdToDiseaseNode.put(diseaseEntityDbId, diseaseNode);
    }

    private Map<Long, Node> mapDiseaseToNormalEntity(ReactionLikeEvent diseaseReaction,
                                                     HyperEdge normalReaction,
                                                     Set<Long> lofPEIds) {
        List<EntityFunctionalStatus> efses = diseaseReaction.getEntityFunctionalStatus();
        // Map mutated entities to normal entities (rendered as nodes) via ReferenceGeneProduct
        Map<Long, Node> diseaseToNormalMap = new HashMap<>();
        if (efses == null || efses.size() == 0)
            return diseaseToNormalMap;

        Map<Long, Node> normalDBIDToNode = normalReaction.getConnectedNodes()
                .stream()
                .filter(node -> node.getReactomeId() != null)
                .collect(Collectors.toMap(Node::getReactomeId, node -> node));

        for (EntityFunctionalStatus efs : efses) {
            // Have to pull out all attributes.
            efs = (EntityFunctionalStatus) curationService.findById(efs.getDbId());
            // Just in case. But it should not happen
            if (efs == null)
                continue;
            PhysicalEntity normalEntity = efs.getNormalEntity();
            if (normalEntity == null)
                continue;
            Node normalNode = normalDBIDToNode.get(normalEntity.getDbId());
            if (normalNode == null)
                continue;
            PhysicalEntity diseaseEntity = efs.getDiseaseEntity();
            if (diseaseEntity == null)
                continue;
            diseaseToNormalMap.put(diseaseEntity.getDbId(), normalNode);
            if (isLOFEntity(efs))
                lofPEIds.add(diseaseEntity.getDbId());
        }
        return diseaseToNormalMap;
    }

    private boolean isLOFEntity(EntityFunctionalStatus efs) {
        PhysicalEntity entity = efs.getDiseaseEntity();
        if (entity == null)
            return false; // Nothing to do
        // This is fully loaded
        List<FunctionalStatus> functionalStatus = efs.getFunctionalStatus();
        if (functionalStatus != null) {
            // This is supposed to be a single valued attribute in the original schema.
            for (FunctionalStatus fs : functionalStatus) {
                // fs is not fully loaded
                String fsName = fs.getDisplayName();
                if (fsName.contains("loss_of_function") || fsName.contains("decreased_"))
                    return true;
            }
        }
        return false;
    }

    private Node convertDiseaseEntityToNode(PhysicalEntity diseaseEntity) {
        Node node = createNode(diseaseEntity);
        // Copy two most important properties
        node.setDisplayName(diseaseEntity.getDisplayName());
        node.setReactomeId(diseaseEntity.getDbId());
        // There is no need to check node is null since createNode should always return a Node
        if (diseaseEntity.getDisease() != null && diseaseEntity.getDisease().size() > 0)
            node.setIsForDisease(true);
        // Check if it has any drug there
        boolean hasDrug = this.curationRepository.complexOrSetHasDrug(diseaseEntity.getDbId());
        if (hasDrug)
            node.setIsForDrug(hasDrug);
        return node;
    }  

    private Node createNode(PhysicalEntity entity) {
        if (entity instanceof Complex)
            return new RenderableComplex();
        if (entity instanceof Cell)
            return new RenderableCell();
        if (entity instanceof SimpleEntity)
            return new RenderableChemical();
        if (entity instanceof ChemicalDrug)
            return new RenderableChemicalDrug();
        if (entity instanceof ProteinDrug)
            return new RenderableProteinDrug();
        if (entity instanceof RNADrug)
            return new RenderableRNADrug();
        if (entity instanceof EntitySet)
            return new RenderableEntitySet();
        if (entity instanceof EntityWithAccessionedSequence) {
            // This should be fully loaded already
            ReferenceEntity referenceEntity = ((EntityWithAccessionedSequence) entity).getReferenceEntity();
            if (referenceEntity instanceof ReferenceGeneProduct)
                return new RenderableProtein();
            if (referenceEntity instanceof ReferenceDNASequence)
                return new RenderableGene();
            if (referenceEntity instanceof ReferenceRNASequence)
                return new RenderableRNA();
            return new RenderableProtein(); // Use the protein as the default type since it should dominate
        }
        return new RenderableEntity(); // Very generic
    }

    private void convertModifiedResiduesToNodeFeatures(PhysicalEntity instance,
                                                       Renderable r,
                                                       Graphics g) {
        // Just in case
        if (!(r instanceof Node))
            return;
        // Only EWAS has modified residues
        if (!(instance instanceof EntityWithAccessionedSequence))
            return;
        EntityWithAccessionedSequence ewas = (EntityWithAccessionedSequence) instance;
        List<AbstractModifiedResidue> modifiedResidues = ewas.getHasModifiedResidue();
        if (modifiedResidues == null || modifiedResidues.size() == 0)
            return;
        // Need to convert to attachments
        List<NodeAttachment> features = new ArrayList<NodeAttachment>();
        for (AbstractModifiedResidue amr : modifiedResidues) {
            // Only translational modification is supported for now. We can add more types later if needed.
            if (!(amr instanceof TranslationalModification))
                continue;
            TranslationalModification modifiedResidue = (TranslationalModification) amr;
            RenderableFeature feature = convertModifiedResidue(modifiedResidue);
            if (feature == null)
                continue;
            if (g != null)
                feature.validateBounds(r.getBounds(), g);
            features.add(feature);
        }
        if (features.size() == 0)
            return;
        Node node = (Node) r;
        node.setNodeAttachmentsLocally(features);
        // If there is no graphics context, we cannot validate bounds and cannot do autolayout
        if (g != null)
            node.layoutNodeAttachemtns();
    }

    private RenderableFeature convertModifiedResidue(TranslationalModification modifiedResidue) {
        RenderableFeature feature = new RenderableFeature();
        feature.setReactomeId(modifiedResidue.getDbId());
        // Have to pull psiMod for label
        //TODO: We may need to cache this later for performance enhancement
        modifiedResidue = (TranslationalModification) curationService.findById(modifiedResidue.getDbId());
        PsiMod psiMod = modifiedResidue.getPsiMod();
        if (psiMod != null) {
            feature.setLabel(psiMod.getLabel());
        }
        // Assign a random position
        setRandomPosition(feature);
        return feature;
    }

    private void setRandomPosition(NodeAttachment attachment) {
        double x = Math.random();
        double y = Math.random();
        // Check if it should be in x or y
        double tmp = Math.random();
        if (tmp < 0.25)
            x = 0.0;
        else if (tmp < 0.50)
            x = 1.0;
        else if (tmp < 0.57)
            y = 0.0;
        else 
            y = 1.0;
        attachment.setRelativePosition(x, y);
    }

}
