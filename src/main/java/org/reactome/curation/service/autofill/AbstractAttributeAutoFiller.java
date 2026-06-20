/*
 * Created on Aug 4, 2005
 *
 */
package org.reactome.curation.service.autofill;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.JOptionPane;

import org.gk.database.AttributeAutoFiller;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.PersistenceAdaptor;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.PersistenceManager;
import org.gk.persistence.XMLFileAdaptor;
import org.reactome.curation.config.CuratorToolEnv;
import org.reactome.curation.repository.CurationRepository;
import org.reactome.server.graph.repository.AdvancedDatabaseObjectRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * This is a template for concrete implementation of AttributeAutoFiller.
 * @author guanming
 *
 */
public abstract class AbstractAttributeAutoFiller {
    @Autowired
    protected CurationRepository curationRepository;
    @Autowired
    protected AdvancedDatabaseObjectRepository objectRepository;
    @Autowired
    protected CuratorToolEnv config;
    
}
