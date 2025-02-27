/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
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

package org.jboss.as.clustering.infinispan;

import java.util.concurrent.ThreadFactory;

import org.infinispan.commons.executors.NonBlockingResource;
import org.wildfly.clustering.context.DefaultThreadFactory;

/**
 * Thread factory for non-blocking threads.
 * @author Paul Ferraro
 */
public class DefaultNonBlockingThreadFactory extends DefaultThreadFactory implements NonBlockingResource {

    public DefaultNonBlockingThreadFactory(Class<?> targetClass) {
        super(targetClass);
    }

    public DefaultNonBlockingThreadFactory(ThreadFactory factory) {
        super(factory);
    }

    public DefaultNonBlockingThreadFactory(ThreadFactory factory, Class<?> targetClass) {
        super(factory, targetClass);
    }
}