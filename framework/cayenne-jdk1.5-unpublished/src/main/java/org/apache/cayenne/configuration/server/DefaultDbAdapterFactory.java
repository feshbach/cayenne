/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/
package org.apache.cayenne.configuration.server;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.access.QueryLogger;
import org.apache.cayenne.configuration.AdhocObjectFactory;
import org.apache.cayenne.configuration.DataNodeDescriptor;
import org.apache.cayenne.dba.AutoAdapter;
import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.dba.JdbcAdapter;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.di.Provider;

/**
 * A factory of DbAdapters that either loads user-provided adapter or guesses the adapter
 * type from the database metadata.
 * 
 * @since 3.1
 */
public class DefaultDbAdapterFactory implements DbAdapterFactory {

    public static final String DETECTORS_LIST = "org.apache.cayenne.configuration.server.DefaultDbAdapterFactory.detectors";

    @Inject
    protected AdhocObjectFactory objectFactory;
    protected List<DbAdapterDetector> detectors;

    public DefaultDbAdapterFactory(
            @Inject(DETECTORS_LIST) List<DbAdapterDetector> detectors) {
        if (detectors == null) {
            throw new NullPointerException("Null detectors list");
        }

        this.detectors = detectors;
    }

    public DbAdapter createAdapter(
            final DataNodeDescriptor nodeDescriptor,
            final DataSource dataSource) throws Exception {

        String adapterType = nodeDescriptor.getAdapterType();
        if (adapterType != null) {
            return objectFactory.newInstance(DbAdapter.class, adapterType);
        }
        else {
            return new AutoAdapter(new Provider<DbAdapter>() {

                public DbAdapter get() {
                    return detectAdapter(nodeDescriptor, dataSource);
                }
            });
        }
    }

    protected DbAdapter detectAdapter(
            DataNodeDescriptor nodeDescriptor,
            DataSource dataSource) {

        if (detectors.isEmpty()) {
            return defaultAdapter();
        }

        try {
            Connection c = dataSource.getConnection();

            try {
                return detectAdapter(c.getMetaData());
            }
            finally {
                try {
                    c.close();
                }
                catch (SQLException e) {
                    // ignore...
                }
            }
        }
        catch (SQLException e) {
            throw new CayenneRuntimeException("Error detecting database type: "
                    + e.getLocalizedMessage(), e);
        }
    }

    protected DbAdapter detectAdapter(DatabaseMetaData metaData) throws SQLException {
        // iterate in reverse order to allow custom factories to take precedence over the
        // default ones configured in constructor
        for (int i = detectors.size() - 1; i >= 0; i--) {
            DbAdapterDetector detector = detectors.get(i);
            DbAdapter adapter = detector.createAdapter(metaData);

            if (adapter != null) {
                QueryLogger.log("Detected and installed adapter: "
                        + adapter.getClass().getName());
                return adapter;
            }
        }

        return defaultAdapter();
    }

    protected DbAdapter defaultAdapter() {
        QueryLogger.log("Failed to detect database type, using generic adapter");
        return new JdbcAdapter();
    }
}