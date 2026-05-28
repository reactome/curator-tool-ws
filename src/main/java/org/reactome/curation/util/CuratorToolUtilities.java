package org.reactome.curation.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;


public class CuratorToolUtilities {
    private static final Logger auditLogger = LoggerFactory.getLogger("curation-audit");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    public static String getDateTime() {
        // Use GMT to ensure the same time zone for all curators
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
        return now.format(FORMATTER);
    }
}
