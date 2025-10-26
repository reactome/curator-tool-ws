package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.reactome.curation.model.CuratorToolReferrerList;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.ListOperand;
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
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
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
    @Autowired
    private AdvancedDatabaseObjectRepository queryRepo;
    

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
        Long dbId = 179837L; // Physical Entity (EGFR)
        //Long dbId = 179837L;
        Collection<CuratorToolReferrerList> instances = repository.getReferrers(dbId);
        for(CuratorToolReferrerList ref : instances){
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
}
