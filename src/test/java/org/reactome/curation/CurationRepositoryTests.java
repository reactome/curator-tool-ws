package org.reactome.curation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gk.model.ReactomeJavaConstants;
import org.junit.jupiter.api.Test;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Relationship;
import org.neo4j.cypherdsl.core.StatementBuilder;
import org.neo4j.cypherdsl.core.renderer.Renderer;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.repository.CurationFileRepository;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.domain.model.Complex;
import org.reactome.server.graph.domain.model.LiteratureReference;
import org.reactome.server.graph.domain.model.PhysicalEntity;
import org.reactome.server.graph.domain.model.Publication;
import org.reactome.server.graph.domain.model.Reaction;
import org.reactome.server.graph.domain.model.SimpleEntity;
import org.reactome.server.graph.domain.model.Summation;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        List<SimpleInstance> instances = repository.listInstances(className, 10, 10, null);
        instances.forEach(instance -> System.out.println(instance));
        className = "Pathway";
        System.out.println("\nList some pathways having TP53:");
        instances = repository.listInstances(className, 0, 10, "TP53");
        instances.forEach(instance -> System.out.println(instance));
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
    public void testDeleteInstance() {
        Reaction reaction = new Reaction();
        reaction.setDbId(12241241L);
        logger.info("Deleting " + reaction + "...");
        // Don't expect to delete this reaction after a test.
        boolean rtn = repository.delete(reaction);
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

        String fileName = "test.json";
        // Save
        CurationFileRepository fileRepo = new CurationFileRepository();
        fileRepo.persist(instances, fileName);
        // Load back
        List<SimpleInstance> loaded = fileRepo.load(fileName);
        System.out.println("Loaded instances: " + loaded);
    }

    @Test
    public void testListInstancesFilter() throws Exception {
        var className = "Reaction";
        String attributes[] = new String[]{"displayName", "compartment"};
        String attributeTypes[] = new String[]{"primitive", "instance"};
        String operands[] = new String[]{"contains", "contains"};
        String searchKeys[] = new String[]{"phosphorylates MDM2", "nucleoplasm"};

        var instance = Cypher.node(className).named("inst");
        var query = Cypher.match(instance);
        // TODO: if attribute is an instance, need to go into this node

        List<Condition> attributeConditions = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        List<Condition> relationshipConditions = new ArrayList<>();

        for (int i = 0; i < attributes.length; i++) {
            if (attributeTypes[i].equals("primitive")) {
                var attributeName = instance.property(attributes[i]);
                attributeConditions.add(attributeName.contains(Cypher.literalOf(searchKeys[i])));
            } else {
                var attributeNode = Cypher.node("DatabaseObject").named(attributes[i]);
                relationships.add(instance.relationshipBetween(attributeNode, attributes[i]));
                var displayName = attributeNode.property("displayName");
                relationshipConditions.add(displayName.matches(".*(?i)" + searchKeys[i] + ".*"));
            }
        }
        for (Condition attCondition : attributeConditions) {
            if (attCondition != null)
                query.where(attCondition);
        }
        for(Relationship relationship : relationships){
            if(relationship != null)
                query.match(relationship);
                query.where(relationshipConditions.get(0));
        }
        var queryBuild = query.returning(instance.property("dbId"),
                        instance.property("displayName"),
                        instance.property("schemaClass"))
                .orderBy(instance.property("displayName"))
                .build();

        System.out.println(Renderer.getDefaultRenderer().render(queryBuild));

    }

}
