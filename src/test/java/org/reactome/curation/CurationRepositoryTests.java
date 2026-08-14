package org.reactome.curation;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.model.CurationAttribute.DefiningAttributeValue;
import org.reactome.curation.model.CurationAttribute.DefiningType;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.model.UserInstances;
import org.reactome.curation.repository.CurationFileRepository;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.domain.model.CandidateSet;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.model.Summation;
import org.reactome.server.graph.domain.model.Taxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.ObjectMapper;

//Note: To test the newly saved or stored objects in the neo4j database using the content service API, try to 
// start a content service at an external terminal by following README at https://github.com/reactome/content-service
// Make sure a correct maven profile is passed into the running env. Eclipse may need some configuration to make it work.
@SpringBootTest
class CurationRepositoryTests {
    private final static Logger logger = LoggerFactory.getLogger(CurationRepositoryTests.class);

    @Autowired
    private CurationRepository repository;
    
    @Test
    void contextLoads() {
    }
    
    @Test
    public void testGetReferenceEntityIdsforPEId() {
        assertNotNull(repository);
        // This is a complex
        Long dbId = 6794288L;
        Collection<Long> refIds = repository.getReferenceEntityDbIdsForPEId(dbId);
        logger.info(dbId + " has total referenceEntities: " + refIds.size());
        // EntitySet
        dbId = 500062L;
        refIds = repository.getReferenceEntityDbIdsForPEId(dbId);
        logger.info(dbId + " has total referenceEntities: " + refIds.size());
        // EWAS
        dbId = 5272096L;
        refIds = repository.getReferenceEntityDbIdsForPEId(dbId);
        logger.info(dbId + " has total referenceEntities: " + refIds.size());
        // Not a PE. Nothing should be returned
        dbId = 9827477L;
        refIds = repository.getReferenceEntityDbIdsForPEId(dbId);
        logger.info(dbId + " has total referenceEntities: " + refIds.size());
    }
    
    
    @Test
    public void testComplexOrSetHasDrug() throws Exception {
        assertNotNull(repository);
        // This EntitySet has drug
        Long dbId = 9659678L;
        boolean hasDrug = repository.complexOrSetHasDrug(dbId);
        logger.info(dbId + " has drug: " + hasDrug);
        // This complex doesn't have a drug
        dbId = 629600L;
        hasDrug = repository.complexOrSetHasDrug(dbId);
        logger.info(dbId + " has drug: " + hasDrug);
    }

    //TODO: To test store, we need the following use cases
    //1). Summation having more than one LiteratureReferences to check order
    //2). Summation with a new LiteartureReference as the second with the first and the third are old
    //3). Reaction: input, output. Check order and stoichioemtry.
    //4). Complex: New with new EWAS instances with multiple copies. Check order and stoichiometires and store.

    @Test // This is jupiter.api.Test. Not JUnit test. Otherwise, it doesn't work!!!
    public void testSaveSummations() throws Exception {
        logger.info("Test storing...");
        // Create a total new instance
        Summation summation = createSummation("Test Summation");
        Long dbId = repository.store(summation).getDbId();
        logger.info("Saved new Summation: " + dbId);
        // A new summation link to an old literature
        summation = createSummation("Test Summation with old lit from scratch");
        // Add literature references to test list and order
        Long[] refIds = {9626035L, 9624149L, 9615711L};
        List<Publication> refs = Stream.of(refIds).map(id -> new LiteratureReference(id)).collect(Collectors.toList());
        summation.setLiteratureReference(refs);
        // By using a simple empty object, the node properties will be gone though 
        // the relationships are still kept. This is not good. We have to load
        // all properties to make it work. Or just need to save relationships!
        Long dbId1 = repository.store(summation).getDbId();
        logger.info("Saved new Summation with an old lit created locally: " + dbId1);
        assertEquals(dbId + 1, dbId1);
        logger.info("Done with testing store.");
    }

    private Summation createSummation(String displayName) {
        Summation summation = new Summation();
        summation.setText("This is a test summation!");
        summation.setDisplayName(displayName);
        return summation;
    }

    @Test
    public void testListInstances() {
        logger.info("Test listInstance...");
        String className = "ProteinDrug";
        System.out.println("List some protein drugs:");
        InstanceList instances = repository.listInstances(className, 10, 10, null);
        System.out.println("Total counts: " + instances.getTotalCount());
        instances.getInstances().forEach(instance -> System.out.println(instance));
        
        className = "Pathway";
        System.out.println("\nList some pathways having TP53:");
        instances = repository.listInstances(className, 0, 10, "TP53");
        System.out.println("Total counts: " + instances.getTotalCount());
        instances.getInstances().forEach(instance -> System.out.println(instance));
        
        // With some space
        className = "Reaction";
        System.out.println("\nList some reaction having a text with space:");
        // https://curator.reactome.org/cgi-bin/instancebrowser?DB=gk_central&ID=6792863
        instances = repository.listInstances(className, 0, 10, "R5C deph");
        System.out.println("Total counts: " + instances.getTotalCount());
        instances.getInstances().forEach(instance -> System.out.println(instance));
    }

    @Test
    public void testFindInstance() {
        logger.info("Test findInstance based on display name...");
        List<String> clsNames = new ArrayList<>();
        clsNames.add(ReactomeJavaConstants.PhysicalEntity);
        clsNames.add(ReactomeJavaConstants.Species);
        String displayName = "homo sapiens"; // Should be case insensitive
        SimpleInstance instance = repository.findInstance(displayName, clsNames);
        logger.info("Found instance for " + displayName + "...");
        logger.info(instance.toString());
    }
    
    /**
     * To test this method, make sure to change the reset method from private to public.
     * @throws Exception
     */
    @Test
    public void testReset() throws Exception {
        DatabaseObject obj = new CandidateSet();
        obj.setDbId(5632097L);
        repository.resetNode(obj);
    }
    
    @Test
    public void testQueryInstanceTaxons() throws Exception {
        DatabaseObject obj = new Complex();
        obj.setDbId(9907837L);
        List<Taxon> taxons = repository.queryInstanceTaxon(obj);
        for (Taxon taxon : taxons)
            System.out.println(taxon);
    }

    /**
     * Reactions use Stoichiometry for inputs and outputs. The following test is used to check
     * if this works.
     * Note: the content-service api shows the detailed information once only. Others are just listed
     * by their DB_IDs. e.g. R-HSA-453337 has 2 ATP as its input. The first ATP shows its detailed information
     * while the second just its DB_ID, 113592, in JSON.
     *
     * @throws Exception
     */
    @Test
    public void testSaveReactions() throws Exception {
        logger.info("Test storing Reaction...");
        // Create a total new instance
        Reaction reaction = new Reaction();
        reaction.setDisplayName("Test reaction");
        // ATP as the input
        SimpleEntity atp = new SimpleEntity();
        atp.setDbId(29358L);
        List<PhysicalEntity> input = new ArrayList<>();
        int stoi = 5;
        for (int i = 0; i < stoi; i++)
            input.add(atp);
        SimpleEntity adp = new SimpleEntity();
        adp.setDbId(113582L);
        List<PhysicalEntity> output = new ArrayList<>();
        for (int i = 0; i < stoi; i++)
            output.add(adp);
        reaction.setInput(input);
        reaction.setOutput(output);
        Long dbId = repository.store(reaction).getDbId();
        logger.info("A new reaction stored with dbId: " + dbId);
        // ADP as the output
        logger.info("Done with testing storing Reaction.");
    }

    /**
     * This method is used to test store a new instance using another new instance as one of its value.
     * It is expected that both of these values should be saved.
     * Note: Complex.setHashComponent(List<PhysicalEntity>) relies on DB_IDs to key HasComponent
     * in a Set. This is not good. We have to give them some fake DB_IDs first and then remove them.
     *
     * @throws Exception
     */
    @Test
    public void testSaveWithNewInstanceAsValue() throws Exception {
        logger.info("Test storing Instance using another new Instance...");
        Complex complex = CurationWSTestHelper.createComplexWithNewComplexAndSubunit();
        Long dbId = repository.store(complex).getDbId();
        logger.info("Newly stored complex having multiple layers of new values: " + dbId);
        logger.info("Done with storing test.");
    }

    /**
     * Use this method to test delete a DatabaseObject.
     */
    @Test
    public void testDeleteInstance() throws Exception {
        Reaction reaction = new Reaction();
        reaction.setDbId(12241241L);
        logger.info("Deleting " + reaction + "...");
        // Don't expect to delete this reaction after a test.
        boolean rtn = repository.delete(reaction, null);
        logger.info("Done: " + rtn);
    }

    /**
     * Test file-based persistence repository.
     *
     * @throws Exception
     */
    @Test
    public void testFileRepository() throws Exception {
        SimpleInstance inst1 = new SimpleInstance();
        inst1.setDisplayName("TestReaction");
        inst1.setDbId(1000L);
        inst1.setSchemaClassName(ReactomeJavaConstants.Reaction);
        inst1.setAttribute("name", "TestReaction");
        inst1.setAttribute("created", "test");

        SimpleInstance inst2 = new SimpleInstance();
        inst2.setDisplayName("TestEWAS");
        inst2.setDbId(1002L);
        inst2.setSchemaClassName(ReactomeJavaConstants.EntityWithAccessionedSequence);
        List<SimpleInstance> instances = new ArrayList<>();

        instances.add(inst1);
        instances.add(inst2);
        
        inst2 = new SimpleInstance();
        inst2.setDisplayName("TestEWAS");
        inst2.setDbId(1002L);
        inst2.setSchemaClassName(ReactomeJavaConstants.EntityWithAccessionedSequence);
        List<SimpleInstance> bookmarks = new ArrayList<>();
        bookmarks.add(inst2); // Use the same dbId to test load

        String fileName = "test.json";
        // Save
        CurationFileRepository fileRepo = new CurationFileRepository();
        // Just a test to use the same list for all attributes
        UserInstances userInstances = new UserInstances();
        userInstances.setBookmarks(bookmarks);
//        userInstances.setDeletedInstances(instances);
        userInstances.setUpdatedInstances(instances);
//        userInstances.setNewInstances(instances);
        fileRepo.persist(userInstances, fileName);
        // Load back
        UserInstances loaded = fileRepo.load(fileName);
        System.out.println("Loaded instances: " + loaded);
    }

    @Test
    public void testListInstancesForSearch() throws Exception {
        
        logger.info("Test reactions for display name and compartment...");
        
        var className = "Reaction";
//        List<String> attributes = new ArrayList<>(List.of("compartment"));
//        List<String> attributeTypes = new ArrayList<>(List.of("instance"));
//        List<ListOperand> operands = new ArrayList<>(List.of(ListOperand.IS_NULL));
//        List<String> searchKeys = new ArrayList<>(List.of("nucleoplasm"));
//        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
        List<String> attributes = new ArrayList<>(List.of("displayName", "compartment"));
        List<String> attributeTypes = new ArrayList<>(List.of("string", "instance"));
        List<ListOperand> operands = new ArrayList<>(List.of(ListOperand.CONTAINS, ListOperand.IS_NULL));
        List<String> searchKeys = new ArrayList<>(List.of("phosphorylates MDM2", "nucleoplasm"));
        listInstances(className, attributes, attributeTypes, operands, searchKeys);

        
        // Limit the result for dbId to check different attribute type
        // Expect to limit to a smaller number of the above result
        logger.info("Added a new property check...");
        attributes.add("dbId");
        attributeTypes.add("long");
        operands.add(ListOperand.CONTAINS);
        searchKeys.add("7"); // Expect 2 instances
        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
        // Further limit by adding another relationship condition
        logger.info("Added another relationship check...");
        attributes.add("input");
        attributeTypes.add("instance");
        operands.add(ListOperand.CONTAINS);
        searchKeys.add("H2O"); // Expect 1 instance
        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
        logger.info("Add a not null check for name. Same results...");
        attributes.add("name");
        attributeTypes.add("string");
        operands.add(ListOperand.IS_NOT_NULL);
        searchKeys.add(null); // Expect 1 instance
        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
        logger.info("Add a null check for regulatedBy. Same results...");
        attributes.add("regulatedBy");
        attributeTypes.add("instance");
        operands.add(ListOperand.IS_NULL);
        searchKeys.add(null); // Still same result
        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
        // Further limit by adding another relationship condition using is not null
        logger.info("Added another relationship check...");
        attributes.add("output");
        attributeTypes.add("instance");
        operands.add(ListOperand.IS_NOT_NULL);
        searchKeys.add(null); // Expect 1 instance
        listInstances(className, attributes, attributeTypes, operands, searchKeys);
        
    }

    private void listInstances(String className,
                               List<String> attributes,
                               List<String> attributeTypes,
                               List<ListOperand> operands,
                               List<String> searchKeys) {
        InstanceList instances;
        instances = repository.listInstances(className, 0, 10, attributes, attributeTypes, operands, searchKeys);
        System.out.println("Total counts: " + instances.getTotalCount());
        instances.getInstances().forEach(instance -> System.out.println(instance));
    }
    
    /**
     * Tests for the raw-Cypher rewrite of listInstances.
     */
    @Test
    public void testListInstancesNoFilter() {
        // Basic listing without any filter should return paged results
        InstanceList result = repository.listInstances("Pathway", 0, 5, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Expected non-zero pathway count");
        assertTrue(result.getInstances().size() <= 5, "Page size should be at most 5");
        logger.info("Pathways total={}, page={}", result.getTotalCount(), result.getInstances().size());
    }

    @Test
    public void testListInstancesTextContains() {
        // Simple text search delegates to the overloaded method
        InstanceList result = repository.listInstances("Pathway", 0, 10, "TP53");
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        result.getInstances().forEach(inst ->
                assertTrue(inst.getDisplayName().toLowerCase().contains("tp53"),
                        "displayName should contain TP53 (case-insensitive)"));
        logger.info("Pathways containing 'TP53': total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesByDbId() {
        // Numeric text triggers dbId EQUAL search
        InstanceList result = repository.listInstances("Pathway", 0, 5, "69620");
        assertNotNull(result);
        // Should find exactly the pathway with that dbId (or 0 if it doesn't exist)
        logger.info("Pathway with dbId 69620: total={}", result.getTotalCount());
        if (result.getTotalCount() != null && result.getTotalCount() > 0)
            assertEquals(Long.valueOf(69620L), result.getInstances().get(0).getDbId());
    }

    @Test
    public void testListInstancesRelationshipIsNull() {
        // Reactions with no compartment assigned
        InstanceList result = repository.listInstances("Reaction", 0, 10,
                List.of("compartment"),
                List.of("instance"),
                List.of(ListOperand.IS_NULL),
                Collections.singletonList(null));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        logger.info("Reactions with no compartment: total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesRelationshipIsNotNull() {
        // Reactions that have at least one input
        InstanceList result = repository.listInstances("Reaction", 0, 10,
                List.of("input"),
                List.of("instance"),
                List.of(ListOperand.IS_NOT_NULL),
                Collections.singletonList(null));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Should find reactions with input");
        logger.info("Reactions with input: total={}", result.getTotalCount());
    }

    /**
     * Regression test: Event.inferredFrom is declared as the INCOMING direction of the
     * "inferredTo" relationship (see graph-core's Event.java) - there is no "inferredFrom"
     * relationship type in the graph at all. Before the fix, listInstances() built its
     * relationship pattern from the literal attribute name ("inferredFrom"), which matches
     * nothing, so this always returned zero results even though many Reactions do have
     * inferredFrom set. After the fix, the actual relationship type/direction is resolved
     * via the @Relationship annotation on the domain class.
     */
    @Test
    public void testListInstancesReverseAttributeInferredFrom() {
        InstanceList result = repository.listInstances("Reaction", 0, 10,
                List.of("inferredFrom"),
                List.of("instance"),
                List.of(ListOperand.IS_NOT_NULL),
                Collections.singletonList(null));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Should find Reactions with inferredFrom set");
        logger.info("Reactions with inferredFrom set: total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesCombinedFilters() {
        // Reactions whose displayName contains "phosphorylates" and have no compartment
        InstanceList result = repository.listInstances("Reaction", 0, 10,
                List.of("displayName", "compartment"),
                List.of("string", "instance"),
                List.of(ListOperand.CONTAINS, ListOperand.IS_NULL),
                Arrays.asList("phosphorylates", null));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        logger.info("Reactions matching 'phosphorylates' with no compartment: total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesPagination() {
        // Verify skip/limit work: page 0 and page 1 should not overlap
        InstanceList page0 = repository.listInstances("Pathway", 0, 5, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        InstanceList page1 = repository.listInstances("Pathway", 5, 5, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertNotNull(page0);
        assertNotNull(page1);
        if (page0.getInstances() != null && page1.getInstances() != null && !page1.getInstances().isEmpty()) {
            Long firstOnPage0 = page0.getInstances().get(0).getDbId();
            Long firstOnPage1 = page1.getInstances().get(0).getDbId();
            assertNotEquals(firstOnPage0, firstOnPage1, "Pages should not overlap");
        }
        logger.info("Page 0 size={}, page 1 size={}",
                page0.getInstances() == null ? 0 : page0.getInstances().size(),
                page1.getInstances() == null ? 0 : page1.getInstances().size());
    }

    @Test
    public void testListInstancesRelationshipContains() {
        // Reactions that have an input whose displayName contains "ATP"
        InstanceList result = repository.listInstances("Reaction", 0, 10,
                List.of("input"),
                List.of("instance"),
                List.of(ListOperand.CONTAINS),
                List.of("ATP"));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Should find reactions with ATP input");
        logger.info("Reactions with ATP input: total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesTextWithSpace() {
        // Spaces in the search key should still work
        InstanceList result = repository.listInstances("Reaction", 0, 10, "R5C deph");
        assertNotNull(result);
        logger.info("Reactions containing 'R5C deph': total={}", result.getTotalCount());
    }

    @Test
    public void testListInstancesListAttribute() {
        // geneName is a List<String> in ReferenceGeneProduct (stored as StringArray in Neo4j).
        // toString() throws a TypeError on arrays, so the "list" attributeType must use ANY().
        InstanceList result = repository.listInstances("ReferenceGeneProduct", 0, 10,
                List.of("geneName"),
                List.of("list"),
                List.of(ListOperand.CONTAINS),
                List.of("TP53"));
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Should find ReferenceGeneProducts with geneName containing TP53");
        logger.info("ReferenceGeneProducts with geneName containing 'TP53': total={}", result.getTotalCount());

        // IS_NOT_NULL on a list attribute
        InstanceList notNull = repository.listInstances("ReferenceGeneProduct", 0, 5,
                List.of("geneName"),
                List.of("list"),
                List.of(ListOperand.IS_NOT_NULL),
                Collections.singletonList(null));
        assertNotNull(notNull);
        assertNotNull(notNull.getTotalCount());
        assertTrue(notNull.getTotalCount() > 0, "Should find ReferenceGeneProducts that have geneName");
        logger.info("ReferenceGeneProducts with non-empty geneName: total={}", notNull.getTotalCount());
    }

    @Test
    public void testListInstancesNullText() {
        // Null text should return all instances (paged)
        InstanceList result = repository.listInstances("Species", 0, 5, (String) null);
        assertNotNull(result);
        assertNotNull(result.getTotalCount());
        assertTrue(result.getTotalCount() > 0, "Should return some species");
        logger.info("Species total={}", result.getTotalCount());
    }

    @Test
    public void testFetchReactionWithParticipants() throws Exception {
        // This reaction has a catalyst
        Long dbId = 5679205L;
        SimpleInstance inst = repository.fetchReactionWithParticipants(dbId);
        ObjectMapper mapper = CurationWSTestHelper.createObjectMapper();
        String json = mapper.writeValueAsString(inst);
        System.out.println("Reaction with dbId: " + dbId + "\n" + json);
        
        // This reaction has two activators
        dbId = 9631065L;
        inst = repository.fetchReactionWithParticipants(dbId);
        json = mapper.writeValueAsString(inst);
        System.out.println("Reaction with dbId: " + dbId + "\n" + json);
    }

    @Test
    public void testReferrers() throws Exception {
        //Long dbId = 9815367L; // Pathway
        //Long dbId = 9815366L; // Instance Edit
        // Long dbId = 179837L; // Physical Entity (EGFR)
        //Long dbId = 179837L;
        Long dbId = 182053L; // This reaction is used as value in inferredFrom. It should have one referrer.
        Collection<NamedReferrerList> instances = repository.getReferrers(dbId);
        for(NamedReferrerList ref : instances){
            System.out.println("referrers " + ref.getAttributeName()  + ": " );
            for(SimpleInstance obj : ref.getReferrers()) {
                System.out.println(obj.getDisplayName());
            }
        }
    }
    
    
    @Test
    public void testFindInstances() throws Exception {
        List<Long> dbIds = List.of(73894L, 9612973L, 162582L);
        List<DatabaseObject> instances = repository.findInstances(dbIds);
        for(DatabaseObject inst : instances) {
            System.out.println(inst.getDbId() + "\t" + inst.getDisplayName() + "\t" + inst.getSchemaClass());
        }
    }

    /**
     * Regression test for the ANY_DEFINING reference bug in
     * CypherQueryUtilities.findMatchingInstancesByDefiningAttributes().
     *
     * Before the fix, OPTIONAL MATCH + WHERE was used for ANY_DEFINING references.
     * In Neo4j a WHERE placed directly after OPTIONAL MATCH is part of the optional
     * pattern: when the condition fails, the alias is set to null instead of the outer
     * row being excluded, so every PathwayDiagram was returned.
     *
     * After the fix (EXISTS subquery), only the one PathwayDiagram whose
     * representedPathway.dbId = 382556 should be returned.
     */
    @Test
    public void testFindMatchingInstancesByDefiningAttributes_AnyDefiningRef() {
        logger.info("Test findMatchingInstancesByDefiningAttributes with ANY_DEFINING reference...");
        Long pathwayDbId = 382556L;
        Map<String, DefiningAttributeValue> definingAttributes = new HashMap<>();
        definingAttributes.put("representedPathway",
                new DefiningAttributeValue(pathwayDbId, DefiningType.ANY_DEFINING, true));

        List<SimpleInstance> matched = repository.findMatchedInstances("PathwayDiagram", definingAttributes);
        logger.info("Matched PathwayDiagram instances for representedPathway dbId={}: {}", pathwayDbId, matched.size());
        matched.forEach(inst -> logger.info("  dbId={}, displayName={}", inst.getDbId(), inst.getDisplayName()));

        assertEquals(1, matched.size(),
                "Expected exactly one PathwayDiagram for representedPathway dbId=" + pathwayDbId);
    }

    /**
     * Regression test for the ALL_DEFINING reference-collection bug in
     * CypherQueryUtilities.findMatchingInstancesByDefiningAttributes().
     *
     * Before the fix, a Collection value for an ALL_DEFINING reference attribute was
     * matched with a single required MATCH plus "dbId IN $param", which only demands
     * that ONE of the relationship targets be in the list - i.e. ANY_DEFINING semantics.
     * After the fix (ALL()+EXISTS), every value in the collection must have its own
     * matching relationship.
     *
     * Complex 444551 has exactly two hasComponent relationships: to 444505 and 444506.
     */
    @Test
    public void testFindMatchingInstancesByDefiningAttributes_AllDefiningRefCollection() {
        logger.info("Test findMatchingInstancesByDefiningAttributes with ALL_DEFINING reference collection...");
        Long complexDbId = 444551L;

        // All of the Complex's actual components: should match.
        Map<String, DefiningAttributeValue> allPresent = new HashMap<>();
        allPresent.put("hasComponent",
                new DefiningAttributeValue(List.of(444505L, 444506L), DefiningType.ALL_DEFINING, true));
        List<SimpleInstance> matched = repository.findMatchedInstances("Complex", allPresent);
        assertTrue(matched.stream().anyMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " to match when all its components are given");

        // One real component plus one that is not a component of this Complex: must NOT match,
        // since ALL_DEFINING requires every listed value to be present.
        Map<String, DefiningAttributeValue> oneMissing = new HashMap<>();
        oneMissing.put("hasComponent",
                new DefiningAttributeValue(List.of(444505L, -1L), DefiningType.ALL_DEFINING, true));
        List<SimpleInstance> notMatched = repository.findMatchedInstances("Complex", oneMissing);
        assertTrue(notMatched.stream().noneMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " NOT to match when one listed component is not actually a component");

        // Only one of the Complex's two actual components: must NOT match either, since
        // ALL_DEFINING means the two sets of components must be equal, not just that the
        // listed value(s) are a subset of the candidate's actual components.
        Map<String, DefiningAttributeValue> subsetOnly = new HashMap<>();
        subsetOnly.put("hasComponent",
                new DefiningAttributeValue(List.of(444505L), DefiningType.ALL_DEFINING, true));
        List<SimpleInstance> subsetMatched = repository.findMatchedInstances("Complex", subsetOnly);
        assertTrue(subsetMatched.stream().noneMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " NOT to match when only a subset of its components is given");

        // A duplicated value (e.g. asking for a homodimer of two copies of 444505) must NOT
        // match a candidate that actually has one edge to 444505 and one to a DIFFERENT target
        // (444506) - i.e. a heterodimer. A MATCH-clause-per-list-entry approach can't tell
        // these apart, since separate MATCH statements aren't required to bind to distinct
        // relationships; per-distinct-value size() checks are what makes this work.
        Map<String, DefiningAttributeValue> duplicatedValue = new HashMap<>();
        duplicatedValue.put("hasComponent",
                new DefiningAttributeValue(List.of(444505L, 444505L), DefiningType.ALL_DEFINING, true));
        List<SimpleInstance> duplicateMatched = repository.findMatchedInstances("Complex", duplicatedValue);
        assertTrue(duplicateMatched.stream().noneMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " (a heterodimer of 444505+444506) NOT to match a query for two copies of 444505");
    }

    /**
     * Regression test for the ANY_DEFINING non-reference-collection bug in
     * CypherQueryUtilities.findMatchingInstancesByDefiningAttributes().
     *
     * Before the fix, a Collection value for a simple (non-reference) attribute always
     * built an ALL(...) clause regardless of defining type, so ANY_DEFINING was enforced
     * as if it were ALL_DEFINING. After the fix, ANY_DEFINING uses ANY(...) so at least
     * one listed value matching is sufficient.
     *
     * Complex 374150 has name = ["GPER1:ESTG", "GPER1:Estrogen"].
     */
    @Test
    public void testFindMatchingInstancesByDefiningAttributes_AnyDefiningNonRefCollection() {
        logger.info("Test findMatchingInstancesByDefiningAttributes with ANY_DEFINING non-reference collection...");
        Long complexDbId = 374150L;

        // Only one of the two listed names is real; the other does not exist for this Complex.
        // ANY_DEFINING should still match since at least one value is present.
        Map<String, DefiningAttributeValue> oneMatches = new HashMap<>();
        oneMatches.put("name",
                new DefiningAttributeValue(List.of("GPER1:ESTG", "Not A Real Name"), DefiningType.ANY_DEFINING, false));
        List<SimpleInstance> matched = repository.findMatchedInstances("Complex", oneMatches);
        assertTrue(matched.stream().anyMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " to match when at least one listed name is present (ANY_DEFINING)");

        // Neither listed name is real: should not match.
        Map<String, DefiningAttributeValue> noneMatch = new HashMap<>();
        noneMatch.put("name",
                new DefiningAttributeValue(List.of("Not A Real Name", "Also Not Real"), DefiningType.ANY_DEFINING, false));
        List<SimpleInstance> notMatched = repository.findMatchedInstances("Complex", noneMatch);
        assertTrue(notMatched.stream().noneMatch(inst -> complexDbId.equals(inst.getDbId())),
                "Expected Complex " + complexDbId + " NOT to match when no listed name is present");
    }
}
