# Defining Attributes Matching Query Implementation

## Overview
Added a Cypher query builder to find existing instances that match a new instance based on **defining attributes**. In Reactome's data model, defining attributes determine if two instances should be considered identical.

---

## Defining Attributes Explained

In the Reactome data model, **defining attributes** are special attributes that determine instance identity. There are two types:

### 1. **ALL_DEFINING** (`DefiningType.ALL_DEFINING`)
- **All values must match exactly**
- Used for attributes that are critical for identity
- Example: A Pathway's `name` + `species` must all match

### 2. **ANY_DEFINING** (`DefiningType.ANY_DEFINING`)
- **At least one value must match**
- Used for attributes where partial matching is acceptable
- Example: A Publication's identifiers - any matching identifier suggests same publication

### 3. **NONE_DEFINING** (`DefiningType.NONE_DEFINING`)
- Not used for identity matching
- Example: Display names, descriptions, etc.

---

## Implementation

### New Method: `findMatchingInstancesByDefiningAttributes()`

**Location:** `/src/main/java/org/reactome/curation/repository/CypherQueryUtilities.java`

**Signature:**
```java
public List<Long> findMatchingInstancesByDefiningAttributes(
    String schemaClass,
    Map<String, DefiningAttributeValue> definingAttributes,
    Neo4jClient neo4jClient)
```

**Parameters:**
- `schemaClass`: The schema class name (e.g., "Pathway", "Reaction", "Complex")
- `definingAttributes`: Map of attribute name → DefiningAttributeValue
- `neo4jClient`: Neo4j client for query execution

**Returns:**
- List of `dbId`s of matching instances
- Empty list if no matches found

---

## Helper Class: `DefiningAttributeValue`

Encapsulates defining attribute information:

```java
public static class DefiningAttributeValue {
    private final Object value;
    private final DefiningType definingType;
    private final boolean isReference;
    
    public DefiningAttributeValue(Object value, DefiningType definingType, boolean isReference)
}
```

**Fields:**
- `value`: The attribute value (can be single value or Collection)
- `definingType`: `ALL_DEFINING` or `ANY_DEFINING`
- `isReference`: `true` if attribute references another instance, `false` for primitive types

---

## Usage Examples

### Example 1: Matching a Pathway

```java
// Check if a Pathway with specific name and species exists
Map<String, DefiningAttributeValue> attributes = new HashMap<>();

// Pathway name (simple attribute, ALL_DEFINING)
attributes.put("name", new DefiningAttributeValue(
    "Glycolysis", 
    DefiningType.ALL_DEFINING, 
    false  // not a reference
));

// Pathway species (reference attribute, ALL_DEFINING)
attributes.put("species", new DefiningAttributeValue(
    48887L,  // dbId of Homo sapiens
    DefiningType.ALL_DEFINING, 
    true  // is a reference to Species instance
));

// Find matches
List<Long> matchingDbIds = cypherQueryUtilities.findMatchingInstancesByDefiningAttributes(
    "Pathway", 
    attributes, 
    neo4jClient
);

if (!matchingDbIds.isEmpty()) {
    System.out.println("Found matching pathway(s): " + matchingDbIds);
} else {
    System.out.println("No matching pathway found - safe to create new one");
}
```

### Example 2: Matching a Complex

```java
Map<String, DefiningAttributeValue> attributes = new HashMap<>();

// Complex name (ALL_DEFINING)
attributes.put("name", new DefiningAttributeValue(
    "EGFR:EGF Complex", 
    DefiningType.ALL_DEFINING, 
    false
));

// Complex compartment (reference attribute, ALL_DEFINING)
attributes.put("compartment", new DefiningAttributeValue(
    70101L,  // dbId of plasma membrane
    DefiningType.ALL_DEFINING, 
    true
));

// Complex hasComponent (reference attribute, ANY_DEFINING)
// At least one component must match
List<Long> componentIds = Arrays.asList(12345L, 67890L);
attributes.put("hasComponent", new DefiningAttributeValue(
    componentIds,
    DefiningType.ANY_DEFINING,
    true
));

List<Long> matches = cypherQueryUtilities.findMatchingInstancesByDefiningAttributes(
    "Complex",
    attributes,
    neo4jClient
);
```

### Example 3: Matching with Multi-valued Attributes

```java
Map<String, DefiningAttributeValue> attributes = new HashMap<>();

// Multiple names (collection)
List<String> names = Arrays.asList("Pathway A", "Alternate Name");
attributes.put("name", new DefiningAttributeValue(
    names,
    DefiningType.ALL_DEFINING,
    false
));

// All names must be present in the target instance
List<Long> matches = cypherQueryUtilities.findMatchingInstancesByDefiningAttributes(
    "Pathway",
    attributes,
    neo4jClient
);
```

---

## Generated Cypher Queries

### Example Query 1: Simple Attributes Only
```cypher
MATCH (n:Pathway)
WHERE (n.name = $name_value AND n.displayName = $displayName_value)
RETURN n.dbId AS dbId
```

### Example Query 2: With Reference Attributes
```cypher
MATCH (n:Complex)
WHERE (
    n.name = $name_value 
    AND (n)-[:compartment]->(ref:DatabaseObject {dbId: $compartment_dbId})
)
RETURN n.dbId AS dbId
```

### Example Query 3: Mixed ALL_DEFINING and ANY_DEFINING
```cypher
MATCH (n:Complex)
WHERE (
    n.name = $name_value 
    AND (n)-[:compartment]->(ref:DatabaseObject {dbId: $compartment_dbId})
) 
AND (
    (n)-[:hasComponent]->(ref:DatabaseObject {dbId: $hasComponent_dbId})
)
RETURN n.dbId AS dbId
```

---

## How It Works

### 1. **Query Building Process**

```
Input: Schema Class + Defining Attributes
    ↓
Separate ALL_DEFINING and ANY_DEFINING attributes
    ↓
For each attribute:
    ├─ Is Reference? → Match via relationship
    └─ Is Primitive? → Match via property
    ↓
Build WHERE clauses:
    ├─ ALL_DEFINING → Join with AND
    └─ ANY_DEFINING → Join with OR
    ↓
Combine with AND (ALL clauses AND ANY clauses)
    ↓
Execute query and return dbIds
```

### 2. **Matching Logic**

**ALL_DEFINING attributes (AND logic):**
```
Match if: ALL specified values match
Example: name="X" AND species=123 AND compartment=456
```

**ANY_DEFINING attributes (OR logic):**
```
Match if: AT LEAST ONE specified value matches
Example: identifier="ABC" OR identifier="XYZ"
```

**Combined:**
```
Match if: (ALL of ALL_DEFINING) AND (ANY of ANY_DEFINING)
```

---

## Integration with CurationAttribute

The method uses `CurationAttribute.DefiningType` enum from the existing model:

```java
public static enum DefiningType {
    ALL_DEFINING,
    ANY_DEFINING,
    NONE_DEFINING,
    UNDEFINED;
}
```

This enum maps directly to the schema definition constants:
- `GKSchemaAttribute.ALL_DEFINING` (value: 2)
- `GKSchemaAttribute.ANY_DEFINING` (value: 1)
- `GKSchemaAttribute.NONE_DEFINING` (value: 0)

---

## Typical Workflow

### When Creating a New Instance:

1. **Collect defining attribute values** from the new instance
2. **Build DefiningAttributeValue map** with appropriate types
3. **Call findMatchingInstancesByDefiningAttributes()**
4. **Check results:**
   - Empty list → No match, safe to create new instance
   - Non-empty list → Match found, reuse existing instance(s)

```java
// 1. Extract defining attributes from new instance
Map<String, DefiningAttributeValue> definingAttrs = extractDefiningAttributes(newInstance);

// 2. Find matches
List<Long> existingMatches = cypherQueryUtilities.findMatchingInstancesByDefiningAttributes(
    newInstance.getSchemaClass(),
    definingAttrs,
    neo4jClient
);

// 3. Handle results
if (existingMatches.isEmpty()) {
    // Create new instance
    repository.save(newInstance);
} else {
    // Reuse existing instance
    Long existingDbId = existingMatches.get(0);
    return repository.findById(existingDbId);
}
```

---

## Performance Considerations

1. **Indexing**: Ensure Neo4j indexes exist for:
   - `dbId` on all DatabaseObject nodes
   - Frequently queried properties (e.g., `displayName`, `name`)

2. **Query Optimization**:
   - Method uses parameterized queries (prevents injection)
   - Leverages Neo4j's query planner for optimal execution
   - Limits returned data to just `dbId` for efficiency

3. **Caching**: Consider caching results for frequently checked instances

---

## Error Handling

- **Null values**: Automatically skipped (not included in WHERE clause)
- **Empty collections**: Automatically skipped
- **No defining attributes**: Returns empty list with warning log
- **Invalid parameters**: Will result in Neo4j query error

---

## Testing

### Unit Test Example:

```java
@Test
public void testFindMatchingPathway() {
    Map<String, DefiningAttributeValue> attributes = new HashMap<>();
    attributes.put("name", new DefiningAttributeValue(
        "Test Pathway", 
        DefiningType.ALL_DEFINING, 
        false
    ));
    attributes.put("species", new DefiningAttributeValue(
        48887L, 
        DefiningType.ALL_DEFINING, 
        true
    ));
    
    List<Long> matches = cypherQueryUtilities.findMatchingInstancesByDefiningAttributes(
        "Pathway",
        attributes,
        neo4jClient
    );
    
    assertNotNull(matches);
    // Add more assertions based on test data
}
```

---

## Logging

The method provides debug-level logging:
- Generated Cypher query
- Parameter values
- Warnings for empty defining attributes

Enable debug logging:
```properties
logging.level.org.reactome.curation.repository.CypherQueryUtilities=DEBUG
```

---

## Summary

✅ **Method Added**: `findMatchingInstancesByDefiningAttributes()`
✅ **Helper Class**: `DefiningAttributeValue`
✅ **Supports**: ALL_DEFINING and ANY_DEFINING attributes
✅ **Handles**: Simple attributes and reference attributes
✅ **Handles**: Single and multi-valued attributes
✅ **Returns**: List of matching instance dbIds
✅ **Thread-safe**: Uses stateless query building
✅ **Performance**: Parameterized queries with Neo4j optimization

**Use Case**: Prevent duplicate instances by checking if an equivalent instance already exists based on defining attributes before creating a new one.
