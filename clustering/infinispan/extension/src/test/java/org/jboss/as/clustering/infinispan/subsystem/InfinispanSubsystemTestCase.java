/*
* JBoss, Home of Professional Open Source.
* Copyright 2011, Red Hat Middleware LLC, and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.as.clustering.infinispan.subsystem;

import java.util.EnumSet;
import java.util.Properties;

import org.jboss.as.clustering.controller.CommonRequirement;
import org.jboss.as.clustering.controller.CommonUnaryRequirement;
import org.jboss.as.clustering.jgroups.subsystem.JGroupsSubsystemResourceDefinition;
import org.jboss.as.clustering.subsystem.ClusteringSubsystemTest;
import org.jboss.as.subsystem.test.AdditionalInitialization;
import org.jboss.dmr.ModelNode;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.wildfly.clustering.jgroups.spi.JGroupsDefaultRequirement;
import org.wildfly.clustering.jgroups.spi.JGroupsRequirement;

/**
 * Tests parsing / booting / marshalling of Infinispan configurations.
 *
 * The current XML configuration is tested, along with supported legacy configurations.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @author Richard Achmatowicz (c) 2013 Red Hat Inc.
 */
@RunWith(value = Parameterized.class)
public class InfinispanSubsystemTestCase extends ClusteringSubsystemTest<InfinispanSchema> {

    @Parameters
    public static Iterable<InfinispanSchema> parameters() {
        return EnumSet.allOf(InfinispanSchema.class);
    }

    public InfinispanSubsystemTestCase(InfinispanSchema schema) {
        super(InfinispanExtension.SUBSYSTEM_NAME, new InfinispanExtension(), schema, "subsystem-infinispan-%d_%d.xml", "schema/jboss-as-infinispan_%d_%d.xsd");
    }

    @Override
    protected AdditionalInitialization createAdditionalInitialization() {
        return new InfinispanSubsystemInitialization()
                .require(CommonUnaryRequirement.OUTBOUND_SOCKET_BINDING, "hotrod-server-1", "hotrod-server-2")
                .require(CommonUnaryRequirement.DATA_SOURCE, "ExampleDS")
                .require(CommonUnaryRequirement.PATH, "jboss.server.temp.dir")
                .require(JGroupsRequirement.CHANNEL, "maximal-channel")
                .require(JGroupsRequirement.CHANNEL_FACTORY, "ee-maximal", "maximal-channel", "maximal-cluster")
                .require(JGroupsDefaultRequirement.CHANNEL_FACTORY)
                .require(CommonRequirement.LOCAL_TRANSACTION_PROVIDER)
                .require(TransactionResourceDefinition.TransactionRequirement.XA_RESOURCE_RECOVERY_REGISTRY)
                ;
    }

    @Override
    public void testSchemaOfSubsystemTemplates() throws Exception {
        // Skip
    }

    @Override
    protected void compare(ModelNode model1, ModelNode model2) {
        purgeJGroupsModel(model1);
        purgeJGroupsModel(model2);
        super.compare(model1, model2);
    }

    private static void purgeJGroupsModel(ModelNode model) {
        model.get(JGroupsSubsystemResourceDefinition.PATH.getKey()).remove(JGroupsSubsystemResourceDefinition.PATH.getValue());
    }

    @Override
    protected Properties getResolvedProperties() {
        Properties properties = new Properties();
        properties.put("java.io.tmpdir", "/tmp");
        return properties;
    }
}
