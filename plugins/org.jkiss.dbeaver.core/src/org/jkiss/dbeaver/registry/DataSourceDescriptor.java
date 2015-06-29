/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2015 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.registry;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.ui.ISaveablePart;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.core.Log;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.data.DBDDataFormatterProfile;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.edit.DBEPrivateObjectEditor;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.impl.data.DefaultValueHandler;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerType;
import org.jkiss.dbeaver.model.net.DBWTunnel;
import org.jkiss.dbeaver.model.runtime.DBRProcessDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.model.virtual.DBVModel;
import org.jkiss.dbeaver.runtime.RuntimeUtils;
import org.jkiss.dbeaver.model.qm.QMMCollector;
import org.jkiss.dbeaver.model.qm.meta.QMMSessionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMTransactionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMTransactionSavepointInfo;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.actions.DataSourcePropertyTester;
import org.jkiss.dbeaver.ui.dialogs.ConfirmationDialog;
import org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionDialog;
import org.jkiss.dbeaver.ui.dialogs.connection.EditConnectionWizard;
import org.jkiss.dbeaver.runtime.properties.PropertyCollector;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.util.*;

/**
 * DataSourceDescriptor
 */
public class DataSourceDescriptor
    implements
        DBSDataSourceContainer,
        DBPImageProvider,
        IAdaptable,
        DBEPrivateObjectEditor,
        DBSObjectStateful,
        DBPRefreshableObject
{
    static final Log log = Log.getLog(DataSourceDescriptor.class);

    public static final int END_TRANSACTION_WAIT_TIME = 3000;

    public static class FilterMapping {
        public final Class<?> type;
        public DBSObjectFilter defaultFilter;
        public Map<String, DBSObjectFilter> customFilters = new HashMap<String, DBSObjectFilter>();

        FilterMapping(Class<?> type)
        {
            this.type = type;
        }

        FilterMapping(FilterMapping mapping)
        {
            this.type = mapping.type;
            this.defaultFilter = mapping.defaultFilter == null ? null : new DBSObjectFilter(mapping.defaultFilter);
            for (Map.Entry<String, DBSObjectFilter> entry : mapping.customFilters.entrySet()) {
                this.customFilters.put(entry.getKey(), new DBSObjectFilter(entry.getValue()));
            }
        }

        @Nullable
        public DBSObjectFilter getFilter(@Nullable DBSObject parentObject, boolean firstMatch)
        {
            if (parentObject == null) {
                return defaultFilter;
            }
            if (!customFilters.isEmpty()) {
                String objectID = DBUtils.getObjectUniqueName(parentObject);
                DBSObjectFilter filter = customFilters.get(objectID);
                if ((filter != null && filter.isEnabled()) || firstMatch) {
                    return filter;
                }
            }

            return firstMatch ? null : defaultFilter;
        }
    }

    @NotNull
    private final DataSourceRegistry registry;
    @NotNull
    private DriverDescriptor driver;
    @NotNull
    private DBPConnectionConfiguration connectionInfo;
    private DBPConnectionConfiguration tunnelConnectionInfo;
    @NotNull
    private String id;
    private String name;
    private String description;
    private boolean savePassword;
    private boolean showSystemObjects;
    private boolean connectionReadOnly;
    private final Map<Class<?>, FilterMapping> filterMap = new IdentityHashMap<Class<?>, FilterMapping>();
    private Date createDate;
    private Date updateDate;
    private Date loginDate;
    private DBDDataFormatterProfile formatterProfile;
    private DBPClientHome clientHome;
    private DataSourcePreferenceStore preferenceStore;
    @Nullable
    private DBPDataSource dataSource;

    private final List<DBPDataSourceUser> users = new ArrayList<DBPDataSourceUser>();

    private volatile boolean connectFailed = false;
    private volatile Date connectTime = null;
    private volatile boolean disposed = false;
    private volatile boolean connecting = false;
    private final List<DBRProcessDescriptor> childProcesses = new ArrayList<DBRProcessDescriptor>();
    private DBWTunnel tunnel;
    private String folderPath;

    private DBVModel virtualModel;

    public DataSourceDescriptor(
        @NotNull DataSourceRegistry registry,
        @NotNull String id,
        @NotNull DriverDescriptor driver,
        @NotNull DBPConnectionConfiguration connectionInfo)
    {
        this.registry = registry;
        this.id = id;
        this.driver = driver;
        this.connectionInfo = connectionInfo;
        this.createDate = new Date();
        this.preferenceStore = new DataSourcePreferenceStore(this);
        this.virtualModel = new DBVModel(this);

        this.driver.addUser(this);
        refreshConnectionInfo();
    }

    void refreshConnectionInfo()
    {
        if (!CommonUtils.isEmpty(connectionInfo.getClientHomeId())) {
            this.clientHome = driver.getClientHome(connectionInfo.getClientHomeId());
        }
    }

    public boolean isDisposed()
    {
        return disposed;
    }

    public void dispose()
    {
        if (disposed) {
            log.warn("Dispose of already disposed data source");
            return;
        }
        synchronized (users) {
            users.clear();
        }
        driver.removeUser(this);
        disposed = true;
    }

    @NotNull
    @Override
    public String getId() {
        return id;
    }

    @NotNull
    @Override
    public DriverDescriptor getDriver()
    {
        return driver;
    }

    public void setDriver(@NotNull DriverDescriptor driver)
    {
        if (driver == this.driver) {
            return;
        }
        this.driver.removeUser(this);
        this.driver = driver;
        this.driver.addUser(this);
    }

    @NotNull
    @Override
    public DBPConnectionConfiguration getConnectionConfiguration()
    {
        return connectionInfo;
    }

    public void setConnectionInfo(@NotNull DBPConnectionConfiguration connectionInfo)
    {
        this.connectionInfo = connectionInfo;
    }

    @Override
    public DBPConnectionConfiguration getActualConnectionConfiguration()
    {
        return tunnelConnectionInfo != null ? tunnelConnectionInfo : connectionInfo;
    }

    @Override
    @Property(viewable = true, order = 1)
    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    @Nullable
    @Override
    public String getDescription()
    {
        return description;
    }

    public boolean isSavePassword()
    {
        return savePassword;
    }

    public void setSavePassword(boolean savePassword)
    {
        this.savePassword = savePassword;
    }

    @Override
    public boolean isShowSystemObjects()
    {
        return showSystemObjects;
    }

    public void setShowSystemObjects(boolean showSystemObjects)
    {
        this.showSystemObjects = showSystemObjects;
    }

    @Override
    public boolean isConnectionReadOnly()
    {
        return connectionReadOnly;
    }

    public void setConnectionReadOnly(boolean connectionReadOnly)
    {
        this.connectionReadOnly = connectionReadOnly;
    }

    @Override
    public boolean isDefaultAutoCommit()
    {
        if (connectionInfo.getBootstrap().getDefaultAutoCommit() != null) {
            return connectionInfo.getBootstrap().getDefaultAutoCommit();
        } else {
            return getConnectionConfiguration().getConnectionType().isAutocommit();
        }
    }

    @Override
    public void setDefaultAutoCommit(final boolean autoCommit, DBCExecutionContext updateContext, boolean updateConnection)
    {
        if (updateContext != null) {
            final DBCTransactionManager txnManager = DBUtils.getTransactionManager(updateContext);
            if (updateConnection && txnManager != null) {
                try {
                    DBeaverUI.runInProgressDialog(new DBRRunnableWithProgress() {
                        @Override
                        public void run(DBRProgressMonitor monitor)
                            throws InvocationTargetException, InterruptedException {
                            monitor.beginTask("Set auto-commit mode", 1);
                            try {
                                // Change auto-commit mode
                                txnManager.setAutoCommit(monitor, autoCommit);
                            } catch (DBCException e) {
                                throw new InvocationTargetException(e);
                            } finally {
                                monitor.done();
                            }
                        }
                    });
                } catch (InvocationTargetException e) {
                    UIUtils.showErrorDialog(null, "Auto-Commit", "Error while toggle auto-commit", e.getTargetException());
                    return;
                }
            }
        }
        // Save in preferences
        if (autoCommit == getConnectionConfiguration().getConnectionType().isAutocommit()) {
            connectionInfo.getBootstrap().setDefaultAutoCommit(null);
        } else {
            connectionInfo.getBootstrap().setDefaultAutoCommit(autoCommit);
        }
    }

    @Nullable
    @Override
    public DBPTransactionIsolation getActiveTransactionsIsolation()
    {
        if (dataSource != null) {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(dataSource.getDefaultContext(false));
            if (txnManager != null) {
                try {
                    return txnManager.getTransactionIsolation();
                } catch (DBCException e) {
                    log.debug("Can't determine isolation level", e);
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public Integer getDefaultTransactionsIsolation() {
        return connectionInfo.getBootstrap().getDefaultTransactionIsolation();
    }

    @Override
    public void setDefaultTransactionsIsolation(@Nullable final DBPTransactionIsolation isolationLevel)
    {
        try {
            if (isolationLevel == null) {
                connectionInfo.getBootstrap().setDefaultTransactionIsolation(null);
            } else {
                connectionInfo.getBootstrap().setDefaultTransactionIsolation(isolationLevel.getCode());
                if (dataSource != null) {
                    DBeaverUI.runInProgressService(new DBRRunnableWithProgress() {
                        @Override
                        public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                            DBCTransactionManager txnManager = DBUtils.getTransactionManager(dataSource.getDefaultContext(false));
                            if (txnManager != null) {
                                try {
                                    if (!txnManager.getTransactionIsolation().equals(isolationLevel)) {
                                        txnManager.setTransactionIsolation(monitor, isolationLevel);
                                    }
                                } catch (DBCException e) {
                                    throw new InvocationTargetException(e);
                                }
                            }
                        }
                    });
                }
            }
        } catch (InvocationTargetException e) {
            UIUtils.showErrorDialog(
                null,
                "Transactions Isolation",
                "Can't set transaction isolation level to '" + isolationLevel.getTitle() + "'",
                e.getTargetException());
        } catch (InterruptedException e) {
            // ok
        }
    }

    public void setDefaultActiveObject(String defaultActiveObject) {
        connectionInfo.getBootstrap().setDefaultObjectName(defaultActiveObject);
    }

    public Collection<FilterMapping> getObjectFilters()
    {
        return filterMap.values();
    }

    @Nullable
    @Override
    public DBSObjectFilter getObjectFilter(Class<?> type, DBSObject parentObject)
    {
        return getObjectFilter(type, parentObject, false);
    }

    @Nullable
    public DBSObjectFilter getObjectFilter(Class<?> type, @Nullable DBSObject parentObject, boolean firstMatch)
    {
        if (filterMap.isEmpty()) {
            return null;
        }
        // Test all super classes
        for (Class<?> testType = type; testType != null; testType = testType.getSuperclass()) {
            FilterMapping filterMapping = filterMap.get(testType);
            DBSObjectFilter filter;
            if (filterMapping == null) {
                // Try to find using interfaces and superclasses
                for (Class<?> it : testType.getInterfaces()) {
                    filterMapping = filterMap.get(it);
                    if (filterMapping != null) {
                        filter = filterMapping.getFilter(parentObject, firstMatch);
                        if (filter != null && (firstMatch || filter.isEnabled())) return filter;
                    }
                }
            }
            if (filterMapping != null) {
                filter = filterMapping.getFilter(parentObject, firstMatch);
                if (filter != null && (firstMatch || filter.isEnabled())) return filter;
            }
        }

        return null;
    }

    public void setObjectFilter(Class<?> type, DBSObject parentObject, DBSObjectFilter filter)
    {
        updateObjectFilter(type, parentObject == null ? null : DBUtils.getObjectUniqueName(parentObject), filter);
    }

    void updateObjectFilter(Class<?> type, @Nullable String objectID, DBSObjectFilter filter)
    {
        FilterMapping filterMapping = filterMap.get(type);
        if (filterMapping == null) {
            filterMapping = new FilterMapping(type);
            filterMap.put(type, filterMapping);
        }
        if (objectID == null) {
            filterMapping.defaultFilter = filter;
        } else {
            filterMapping.customFilters.put(objectID, filter);
        }
    }

    @Override
    public DBVModel getVirtualModel()
    {
        return virtualModel;
    }

    @Override
    public DBPClientHome getClientHome()
    {
        return clientHome;
    }

    @Override
    public DBSObject getParentObject()
    {
        return null;
    }

    @Override
    public boolean refreshObject(DBRProgressMonitor monitor)
        throws DBException
    {
        this.reconnect(monitor, false);

        getRegistry().fireDataSourceEvent(
                DBPEvent.Action.OBJECT_UPDATE,
                DataSourceDescriptor.this);

        return true;
    }

    public void setDescription(@Nullable String description)
    {
        this.description = description;
    }

    public boolean hasNetworkHandlers() {
        for (DBWHandlerConfiguration handler : connectionInfo.getDeclaredHandlers()) {
            if (handler.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public Date getCreateDate()
    {
        return createDate;
    }

    public void setCreateDate(Date createDate)
    {
        this.createDate = createDate;
    }

    public Date getUpdateDate()
    {
        return updateDate;
    }

    public void setUpdateDate(Date updateDate)
    {
        this.updateDate = updateDate;
    }

    public Date getLoginDate()
    {
        return loginDate;
    }

    public void setLoginDate(Date loginDate)
    {
        this.loginDate = loginDate;
    }

    @Nullable
    @Override
    public DBPDataSource getDataSource()
    {
        return dataSource;
    }

    @Override
    public boolean isPersisted()
    {
        return true;
    }

    @NotNull
    @Override
    public DataSourceRegistry getRegistry()
    {
        return registry;
    }

    public String getFolderPath()
    {
        return folderPath;
    }

    public void setFolderPath(String folderPath)
    {
        this.folderPath = folderPath;
    }

    @Override
    public void persistConfiguration()
    {
        registry.saveDataSources();
    }

    @Override
    public boolean isConnected()
    {
        return dataSource != null && dataSource.getDefaultContext(false).isConnected();
    }

    @Override
    public boolean connect(DBRProgressMonitor monitor)
        throws DBException
    {
        return connect(monitor, true, true);
    }

    public boolean connect(DBRProgressMonitor monitor, boolean initialize, boolean reflect)
        throws DBException
    {
        if (connecting) {
            log.debug("Can't connect - connect/disconnect is in progress");
            return false;
        }
        if (this.isConnected()) {
            log.debug("Can't connect - already connected");
            return false;
        }

        connecting = true;
        DBPConnectionConfiguration savedConnectionInfo = null;
        tunnelConnectionInfo = null;
        try {
            // Handle tunnel
            // Open tunnel and replace connection info with new one
            this.tunnel = null;
            DBWHandlerConfiguration tunnelConfiguration = null;
            for (DBWHandlerConfiguration handler : connectionInfo.getDeclaredHandlers()) {
                if (handler.isEnabled() && handler.getType() == DBWHandlerType.TUNNEL) {
                    tunnelConfiguration = handler;
                    break;
                }
            }
            monitor.beginTask("Connect to " + getName(), tunnelConfiguration != null ? 3 : 2);

            if (tunnelConfiguration != null) {
                monitor.subTask("Initialize tunnel");
                tunnel = tunnelConfiguration.createHandler(DBWTunnel.class);
                try {
                    tunnelConnectionInfo = tunnel.initializeTunnel(monitor, tunnelConfiguration, connectionInfo);
                } catch (Exception e) {
                    throw new DBCException("Can't initialize tunnel", e);
                }
                monitor.worked(1);
            }
            if (tunnelConnectionInfo != null) {
                savedConnectionInfo = connectionInfo;
                connectionInfo = tunnelConnectionInfo;
            }
            monitor.subTask("Connect to data source");
            dataSource = getDriver().getDataSourceProvider().openDataSource(monitor, this);
            monitor.worked(1);

            if (initialize) {
                monitor.subTask("Initialize data source");
                dataSource.initialize(monitor);
                // Change connection properties

                initConnectionState(monitor);
            }

            connectFailed = false;
            connectTime = new Date();

            if (reflect) {
                getRegistry().fireDataSourceEvent(
                    DBPEvent.Action.OBJECT_UPDATE,
                    DataSourceDescriptor.this,
                    true);
            }
            firePropertyChange();

            return true;
        } catch (Exception e) {
            // Failed
            connectFailed = true;
            //if (reflect) {
                getRegistry().fireDataSourceEvent(
                    DBPEvent.Action.OBJECT_UPDATE,
                    DataSourceDescriptor.this,
                    false);
            //}
            if (e instanceof DBException) {
                throw (DBException)e;
            } else {
                throw new DBException("Internal error connecting to " + getName(), e);
            }
        } finally {
            monitor.done();
            if (savedConnectionInfo != null) {
                connectionInfo = savedConnectionInfo;
            }
            connecting = false;
        }
    }

    private void initConnectionState(DBRProgressMonitor monitor) throws DBException {
        if (dataSource == null) {
            return;
        }

        // Set active object
        if (dataSource instanceof DBSObjectSelector && dataSource instanceof DBSObjectContainer) {
            String activeObject = getConnectionConfiguration().getBootstrap().getDefaultObjectName();
            if (!CommonUtils.isEmptyTrimmed(activeObject)) {
                DBSObject child = ((DBSObjectContainer) dataSource).getChild(monitor, activeObject);
                if (child != null) {
                    try {
                        ((DBSObjectSelector) dataSource).selectObject(monitor, child);
                    } catch (DBException e) {
                        log.warn("Can't select active object", e);
                    }
                } else {
                    log.debug("Object '" + activeObject + "' not found");
                }
            }
        }

    }

    @Override
    public boolean disconnect(final DBRProgressMonitor monitor)
        throws DBException
    {
        return disconnect(monitor, true);
    }

    public boolean disconnect(final DBRProgressMonitor monitor, boolean reflect)
        throws DBException
    {
        if (dataSource == null) {
            log.error("Datasource is not connected");
            return true;
        }
        if (connecting) {
            log.error("Connect/disconnect is in progress");
            return false;
        }

        connecting = true;
        try {
            {
                List<DBPDataSourceUser> usersStamp;
                synchronized (users) {
                    usersStamp = new ArrayList<DBPDataSourceUser>(users);
                }
                int jobCount = 0;
                // Save all unsaved data
                for (DBPDataSourceUser user : usersStamp) {
                    if (user instanceof Job) {
                        jobCount++;
                    }
                    if (user instanceof ISaveablePart) {
                        if (!UIUtils.validateAndSave(monitor, (ISaveablePart) user)) {
                            return false;
                        }
                    }
                    if (user instanceof DBPDataSourceHandler) {
                        ((DBPDataSourceHandler) user).beforeDisconnect();
                    }
                }
                if (jobCount > 0) {
                    monitor.beginTask("Waiting for all active tasks to finish", jobCount);
                    // Stop all jobs
                    for (DBPDataSourceUser user : usersStamp) {
                        if (user instanceof Job) {
                            Job job = (Job) user;
                            monitor.subTask("Stop '" + job.getName() + "'");
                            if (job.getState() == Job.RUNNING) {
                                job.cancel();
                                try {
                                    // Wait for 3 seconds
                                    for (int i = 0; i < 10; i++) {
                                        Thread.sleep(300);
                                        if (job.getState() != Job.RUNNING) {
                                            break;
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    // its ok, do nothing
                                }
                            }
                            monitor.worked(1);
                        }
                    }
                    monitor.done();
                }
            }

            monitor.beginTask("Disconnect from '" + getName() + "'", 4);

            // Close datasource
            monitor.subTask("Close connection");
            if (dataSource != null) {
                dataSource.close();
            }
            monitor.worked(1);

            // Close tunnel
            if (tunnel != null) {
                monitor.subTask("Close tunnel");
                try {
                    tunnel.closeTunnel(monitor, connectionInfo);
                } catch (Exception e) {
                    log.warn("Error closing tunnel", e);
                } finally {
                    this.tunnel = null;
                }
            }
            monitor.worked(1);

            monitor.done();

            // Terminate child processes
            synchronized (childProcesses) {
                for (Iterator<DBRProcessDescriptor> iter = childProcesses.iterator(); iter.hasNext(); ) {
                    DBRProcessDescriptor process = iter.next();
                    if (process.isRunning() && process.getCommand().isTerminateAtDisconnect()) {
                        process.terminate();
                    }
                    iter.remove();
                }
            }

            dataSource = null;
            connectTime = null;

            if (reflect) {
                // Reflect UI
                getRegistry().fireDataSourceEvent(
                    DBPEvent.Action.OBJECT_UPDATE,
                    this,
                    false);
                firePropertyChange();
            }

            return true;
        }
        finally {
            connecting = false;
        }
    }

    public boolean closeActiveTransaction(final DBRProgressMonitor monitor)
    {
        if (dataSource == null) {
            return true;
        }

        Boolean commitTxn = null;
        for (DBCExecutionContext context : dataSource.getAllContexts()) {
            // First rollback active transaction
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            try {
                if (txnManager == null || txnManager.isAutoCommit()) {
                    continue;
                }

                // If there are some executions in last savepoint then ask user about commit/rollback
                QMMCollector qmm = DBeaverCore.getInstance().getQueryManager().getMetaCollector();
                if (qmm != null) {
                    QMMSessionInfo qmmSession = qmm.getSessionInfo(context);
                    QMMTransactionInfo txn = qmmSession == null ? null : qmmSession.getTransaction();
                    QMMTransactionSavepointInfo sp = txn == null ? null : txn.getCurrentSavepoint();
                    if (sp != null && (sp.getPrevious() != null || sp.getLastExecute() != null)) {
                        boolean hasUserExec = false;
                        if (true) {
                            // Do not check whether we have user queries, just ask for confirmation
                            hasUserExec = true;
                        } else {
                            for (QMMTransactionSavepointInfo psp = sp; psp != null; psp = psp.getPrevious()) {
                                if (psp.hasUserExecutions()) {
                                    hasUserExec = true;
                                    break;
                                }
                            }
                        }
                        if (hasUserExec) {
                            DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End active transaction");
                            try {
                                if (commitTxn == null) {
                                    // Ask for confirmation
                                    TransactionCloseConfirmer closeConfirmer = new TransactionCloseConfirmer(getName());
                                    UIUtils.runInUI(null, closeConfirmer);
                                    switch (closeConfirmer.result) {
                                        case IDialogConstants.YES_ID:
                                            commitTxn = true;
                                            break;
                                        case IDialogConstants.NO_ID:
                                            commitTxn = false;
                                            break;
                                        default:
                                            return false;
                                    }
                                }
                                monitor.subTask("End active transaction");
                                EndTransactionTask task = new EndTransactionTask(session, commitTxn);
                                RuntimeUtils.runTask(task, END_TRANSACTION_WAIT_TIME);
                            } finally {
                                session.close();
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                log.warn("Can't rollback active transaction before disconnect", e);
            } finally {
                monitor.worked(1);
            }
        }
        return true;
    }

    @Override
    public boolean reconnect(final DBRProgressMonitor monitor)
        throws DBException
    {
        return reconnect(monitor, true);
    }

    public boolean reconnect(final DBRProgressMonitor monitor, boolean reflect)
        throws DBException
    {
        if (connecting) {
            log.debug("Can't reconnect - connect/disconnect is in progress");
            return false;
        }
        if (isConnected()) {
            if (!disconnect(monitor, reflect)) {
                return false;
            }
        }
        return connect(monitor, true, reflect);
    }

    @Override
    public Collection<DBPDataSourceUser> getUsers()
    {
        synchronized (users) {
            return new ArrayList<DBPDataSourceUser>(users);
        }
    }

    @Override
    public void acquire(DBPDataSourceUser user)
    {
        synchronized (users) {
            if (users.contains(user)) {
                log.warn("Datasource user '" + user + "' already registered in datasource '" + getName() + "'");
            } else {
                users.add(user);
            }
        }
    }

    @Override
    public void release(DBPDataSourceUser user)
    {
        synchronized (users) {
            if (!users.remove(user)) {
                if (!isDisposed()) {
                    log.warn("Datasource user '" + user + "' is not registered in datasource '" + getName() + "'");
                }
            }
        }
    }

    @Override
    public void fireEvent(DBPEvent event) {
        registry.fireDataSourceEvent(event);
    }

    @Override
    public DBDDataFormatterProfile getDataFormatterProfile()
    {
        if (this.formatterProfile == null) {
            this.formatterProfile = new DataFormatterProfile(getId(), preferenceStore);
        }
        return this.formatterProfile;
    }

    @Override
    public void setDataFormatterProfile(DBDDataFormatterProfile formatterProfile)
    {
        this.formatterProfile = formatterProfile;
    }

    @NotNull
    @Override
    public DBDValueHandler getDefaultValueHandler()
    {
        return DefaultValueHandler.INSTANCE;
    }

    @Override
    public DataSourcePreferenceStore getPreferenceStore()
    {
        return preferenceStore;
    }

    public void resetPassword()
    {
        connectionInfo.setUserPassword(null);
    }

    @Nullable
    @Override
    public Object getAdapter(Class adapter)
    {
        if (DBSDataSourceContainer.class.isAssignableFrom(adapter)) {
            return this;
        } else if (adapter == DBPPropertySource.class) {
            PropertyCollector coll = new PropertyCollector(this, true);
            coll.collectProperties();
            if (dataSource != null) {
                int conIndex = 0;
                for (DBCExecutionContext context : dataSource.getAllContexts()) {
                    conIndex++;
                    coll.addProperty("Connections", conIndex, String.valueOf(conIndex), new ContextInfo(context));
                }
            }
            return coll;
        }
        return null;
    }

    @Nullable
    @Override
    public DBPImage getObjectImage()
    {
        return driver.getPlainIcon();
    }

    @Override
    public DBSObjectState getObjectState()
    {
        if (isConnected()) {
            return DBSObjectState.ACTIVE;
        } else if (connectFailed) {
            return DBSObjectState.INVALID;
        } else {
            return DBSObjectState.NORMAL;
        }
    }

    @Override
    public void refreshObjectState(DBRProgressMonitor monitor)
    {
        // just do nothing
    }

    public static String generateNewId(DriverDescriptor driver)
    {
        long rnd = new Random().nextLong();
        if (rnd < 0) rnd = -rnd;
        return driver.getId() + "-" + Long.toHexString(System.currentTimeMillis()) + "-" + Long.toHexString(rnd);
    }

    private void firePropertyChange()
    {
        DataSourcePropertyTester.firePropertyChange(DataSourcePropertyTester.PROP_CONNECTED);
        DataSourcePropertyTester.firePropertyChange(DataSourcePropertyTester.PROP_TRANSACTIONAL);
    }

    @Property(viewable = true, order = 2, category = "Driver")
    public String getPropertyDriverType()
    {
        return driver.getName();
    }

    @Property(order = 3, category = "Server")
    public String getPropertyAddress()
    {
        StringBuilder addr = new StringBuilder();
        if (!CommonUtils.isEmpty(connectionInfo.getHostName())) {
            addr.append(connectionInfo.getHostName());
        }
        if (!CommonUtils.isEmpty(connectionInfo.getHostPort())) {
            addr.append(':').append(connectionInfo.getHostPort());
        }
        return addr.toString();
    }

    @Property(order = 4, category = "Server")
    public String getPropertyDatabase()
    {
        return connectionInfo.getDatabaseName();
    }

    @Property(order = 5, category = "Server")
    public String getPropertyURL()
    {
        return connectionInfo.getUrl();
    }

    @Nullable
    @Property(order = 6, category = "Server")
    public String getPropertyServerName()
    {
        if (dataSource != null) {
            String serverName = dataSource.getInfo().getDatabaseProductName();
            String serverVersion = dataSource.getInfo().getDatabaseProductVersion();
            if (serverName != null) {
                return serverName + (serverVersion == null ? "" : " [" + serverVersion + "]");
            }
        }
        return null;
    }

    @Nullable
    @Property(order = 7, category = "Driver")
    public String getPropertyDriver()
    {
        if (dataSource != null) {
            String driverName = dataSource.getInfo().getDriverName();
            String driverVersion = dataSource.getInfo().getDriverVersion();
            if (driverName != null) {
                return driverName + (driverVersion == null ? "" : " [" + driverVersion + "]");
            }
        }
        return null;
    }

    @Nullable
    @Property(order = 8)
    public String getPropertyConnectTime()
    {
        if (connectTime != null) {
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(connectTime);
        }
        return null;
    }

    @Property(order = 9)
    public String getPropertyConnectType()
    {
        return connectionInfo.getConnectionType().getName();
    }

    @Override
    public void editObject()
    {
        EditConnectionDialog dialog = new EditConnectionDialog(DBeaverUI.getActiveWorkbenchWindow(), new EditConnectionWizard(this));
        dialog.open();
    }

    public void addChildProcess(DBRProcessDescriptor process)
    {
        synchronized (childProcesses) {
            childProcesses.add(process);
        }
    }

    public void copyFrom(DataSourceDescriptor descriptor) {
        filterMap.clear();
        for (FilterMapping mapping : descriptor.getObjectFilters()) {
            filterMap.put(mapping.type, new FilterMapping(mapping));
        }
        virtualModel.copyFrom(descriptor.getVirtualModel());

        setDescription(descriptor.getDescription());
        setSavePassword(descriptor.isSavePassword());
        setShowSystemObjects(descriptor.isShowSystemObjects());
        setConnectionReadOnly(descriptor.isConnectionReadOnly());
        folderPath = descriptor.folderPath;
    }

    @Override
    public String toString() {
        return name + " [" + driver + "]";
    }

    private static class EndTransactionTask implements DBRRunnableWithProgress {
        private final DBCSession session;
        private final boolean commit;

        private EndTransactionTask(DBCSession session, boolean commit) {
            this.session = session;
            this.commit = commit;
        }

        @Override
        public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(session.getExecutionContext());
            if (txnManager != null) {
                try {
                    if (commit) {
                        txnManager.commit(session);
                    } else {
                        txnManager.rollback(session, null);
                    }
                } catch (DBCException e) {
                    throw new InvocationTargetException(e);
                }
            }
        }
    }

    private static class TransactionCloseConfirmer implements Runnable {
        final String name;
        int result = IDialogConstants.NO_ID;

        private TransactionCloseConfirmer(String name) {
            this.name = name;
        }

        @Override
        public void run()
        {
            result = ConfirmationDialog.showConfirmDialog(
                null,
                DBeaverPreferences.CONFIRM_TXN_DISCONNECT,
                ConfirmationDialog.QUESTION_WITH_CANCEL,
                name);
        }
    }

    public static class ContextInfo implements DBPObject {
        private final DBCExecutionContext context;

        public ContextInfo(DBCExecutionContext context) {
            this.context = context;
        }

        @Property(viewable = true, order = 1)
        public String getName() {
            return context.getContextName();
        }

        @Override
        public String toString() {
            return getName();
        }
    }

}
