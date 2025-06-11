package org.reactome.curation.qa;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.qa.model.QACheckResult;
import org.reactome.server.graph.domain.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReviewStatusSlotCheck extends QAChecker {
    private static final Logger logger = LoggerFactory.getLogger(ReviewStatusSlotCheck.class);
    
    public ReviewStatusSlotCheck() {
        
    }
    
    @Override
    public String getCheckName() {
        return "Review Status Slot Check";
    }
    
    @Override
    public Collection<Class<?>> getTargetClasses() {
        return Stream.of(Event.class).collect(Collectors.toSet());
    }
    
    @Override
    public QACheckResult performQACheck(SimpleInstance instance) {
        if (!shouldCheck(instance)) {
            return null;
        }
        List<String> issues = checkReviewStatus(instance);
        if (issues.isEmpty()) {
            return getEmptyResult(); // No issues found
        }
        String[] columns = {"Review Status", "Issues"};
        String reviewStatus = issues.remove(issues.size() - 1); // Last item is the review status
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {reviewStatus, String.join("; ", issues)});
        return createResult(columns, rows);
    }
    
    public List<String> checkReviewStatus(SimpleInstance instance) {
        // the directions of relationships for IEs may be different
        String query = String.format(
                "MATCH (e:%s {dbId: %d})-[:reviewStatus]->(rs:ReviewStatus) " +
                "OPTIONAL MATCH (e)-[:structureModified]-(sm:InstanceEdit) " +
                "OPTIONAL MATCH (e)-[:internalReviewed]-(ir:InstanceEdit) " +
                "OPTIONAL MATCH (e)-[:reviewed]-(rv:InstanceEdit) " +
                "RETURN rs.displayName AS reviewStatus, " +
                "       COLLECT(DISTINCT sm.dateTime) AS structureModifiedDates, " +
                "       COLLECT(DISTINCT ir.dateTime) AS internalReviewedDates, " +
                "       COLLECT(DISTINCT rv.dateTime) AS reviewedDates",
                instance.getSchemaClassName(), instance.getDbId()
        );
        
        Collection<Map<String, Object>> all = getNeoj4Client().query(query).fetch().all();

        if (all.isEmpty())
            return Collections.emptyList();
        // Expect to get only one result
        Map<String, Object> row = all.iterator().next();
        String reviewStatus = (String) row.get("reviewStatus");
        List<String> structureModifiedDates = toStringList(row.get("structureModifiedDates"));
        List<String> internalReviewedDates = toStringList(row.get("internalReviewedDates"));
        List<String> reviewedDates = toStringList(row.get("reviewedDates"));
        ReviewStatusData data = new ReviewStatusData(reviewStatus, structureModifiedDates, internalReviewedDates, reviewedDates);
        return evaluateReviewStatus(data);
    }

    private List<String> evaluateReviewStatus(ReviewStatusData data) {
        List<String> issues = new ArrayList<>();

        Date latestStructure = parseLatest(data.getStructureModifiedDates());
        Date latestInternal = parseLatest(data.getInternalReviewedDates());
        Date latestReviewed = parseLatest(data.getReviewedDates());

        String reviewStatus = data.getReviewStatus();

        if ("three stars".equals(reviewStatus)) {
            if (latestInternal == null)
                issues.add("Missing internalReviewed");
            if (latestReviewed != null)
                issues.add("Reviewed set when not expected");
            if (latestInternal != null && latestStructure != null && latestInternal.before(latestStructure))
                issues.add("internalReviewed is before structureModified");

        } else if ("four stars".equals(reviewStatus)) {
            if (latestStructure == null)
                issues.add("Missing structureModified");
            if (latestInternal == null)
                issues.add("Missing internalReviewed");
            if (latestReviewed == null)
                issues.add("Missing reviewed");

            if (latestInternal != null && latestStructure != null && latestInternal.before(latestStructure))
                issues.add("internalReviewed is before structureModified");

            if (latestReviewed != null && latestStructure != null && !latestReviewed.before(latestStructure))
                issues.add("reviewed is not before structureModified");

        } else if ("five stars".equals(reviewStatus)) {
            if (latestReviewed == null)
                issues.add("Missing reviewed");

            if (latestReviewed != null && latestStructure != null && latestReviewed.before(latestStructure))
                issues.add("reviewed is before structureModified");
        } 
        if (!issues.isEmpty())
            issues.add(reviewStatus); // Add the review status at the end for clarity
        return issues;
    }

    private Date parseLatest(List<String> dateTimes) {
        List<Date> parsed = new ArrayList<>();
        for (String dt : dateTimes) {
            Date zdt = parseDate(dt);
            if (zdt != null) {
                parsed.add(zdt);
            }
        }
        if (parsed.isEmpty()) return null;
        parsed.sort(Comparator.naturalOrder());
        return parsed.get(parsed.size() - 1);
    }
    
    
    private Date parseDate(String dateTime) {
        try {
            // All are in GMT
            TimeZone timeZone = TimeZone.getTimeZone("GMT");
            if (dateTime.matches("(\\d){14}")) {
                SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
                format.setTimeZone(timeZone);
                return format.parse(dateTime);
            }
            else if (dateTime.matches("(\\d){4}-(\\d){2}-(\\d){2} (\\d){2}:(\\d){2}:(\\d){2}.(\\d)*")) {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");
                format.setTimeZone(timeZone);
                return format.parse(dateTime);
            }
            else if (dateTime.matches("(\\d){4}-(\\d){2}-(\\d){2} (\\d){2}:(\\d){2}:(\\d){2}")) { // For MySQL 8
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                format.setTimeZone(timeZone);
                return format.parse(dateTime);
            }
        }
        catch(Exception e) {
            logger.error("Cannot parse date: " + dateTime, e);
        }
        return null; // Return null if parsing fails
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object neo4jValue) {
        if (neo4jValue == null || !(neo4jValue instanceof List)) 
            return Collections.emptyList();
        return (List<String>) neo4jValue;
    }

    private static class ReviewStatusData {
        private final String reviewStatus;
        private final List<String> structureModifiedDates;
        private final List<String> internalReviewedDates;
        private final List<String> reviewedDates;

        public ReviewStatusData(String reviewStatus,
                                List<String> structureModifiedDates,
                                List<String> internalReviewedDates,
                                List<String> reviewedDates) {
            this.reviewStatus = reviewStatus;
            this.structureModifiedDates = structureModifiedDates;
            this.internalReviewedDates = internalReviewedDates;
            this.reviewedDates = reviewedDates;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public List<String> getStructureModifiedDates() {
            return structureModifiedDates;
        }

        public List<String> getInternalReviewedDates() {
            return internalReviewedDates;
        }

        public List<String> getReviewedDates() {
            return reviewedDates;
        }
    }

}
