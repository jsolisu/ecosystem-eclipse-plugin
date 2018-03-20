/******************************************************************************
 * Copyright (c) 2018 Oracle
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/

package org.eclipse.payara.tools.utils;

import static org.eclipse.payara.tools.GlassfishToolsPlugin.logMessage;
import static org.eclipse.payara.tools.sdk.TaskEvent.AUTH_FAILED;
import static org.eclipse.payara.tools.sdk.TaskEvent.BAD_GATEWAY;
import static org.eclipse.payara.tools.sdk.utils.ServerUtils.isAdminPortListening;
import static org.eclipse.payara.tools.server.ServerStatus.NOT_DEFINED;
import static org.eclipse.payara.tools.server.ServerStatus.RUNNING_CONNECTION_ERROR;
import static org.eclipse.payara.tools.server.ServerStatus.RUNNING_CREDENTIAL_PROBLEM;
import static org.eclipse.payara.tools.server.ServerStatus.RUNNING_DOMAIN_MATCHING;
import static org.eclipse.payara.tools.server.ServerStatus.RUNNING_PROXY_ERROR;
import static org.eclipse.payara.tools.server.ServerStatus.RUNNING_REMOTE_NOT_SECURE;
import static org.eclipse.payara.tools.server.ServerStatus.STOPPED_DOMAIN_NOT_MATCHING;
import static org.eclipse.payara.tools.server.ServerStatus.STOPPED_NOT_LISTENING;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.payara.tools.GlassfishToolsPlugin;
import org.eclipse.payara.tools.sdk.TaskEvent;
import org.eclipse.payara.tools.sdk.TaskState;
import org.eclipse.payara.tools.sdk.TaskStateListener;
import org.eclipse.payara.tools.sdk.admin.CommandLocation;
import org.eclipse.payara.tools.sdk.admin.ResultMap;
import org.eclipse.payara.tools.sdk.admin.ServerAdmin;
import org.eclipse.payara.tools.server.GlassFishRuntime;
import org.eclipse.payara.tools.server.GlassFishServer;
import org.eclipse.payara.tools.server.ServerStatus;
import org.eclipse.payara.tools.server.deploying.GlassFishServerBehaviour;
import org.eclipse.wst.server.core.IServer;

public class ServerStatusHelper {

    public static ServerStatus checkServerStatus(GlassFishServer server) {
        try {
            Thread.sleep(Math.round(Math.random() * 1000));
        } catch (InterruptedException e) {
            e.printStackTrace();
            return NOT_DEFINED;
        }

        if (!isAdminPortListening(server)) {
            return STOPPED_NOT_LISTENING;
        }

        if (server.isRemote()) {
            IServer server1 = server.getServer();
            String remoteServerVersion = GlassFishServerBehaviour.getVersion(server);
            GlassFishRuntime gfRuntime = (GlassFishRuntime) server1.getRuntime().loadAdapter(GlassFishRuntime.class,
                    null);
            String thisServerVersion = gfRuntime.getVersion().toString();
            int n = thisServerVersion.indexOf(".X");
            if (n > 0) {
                thisServerVersion = thisServerVersion.substring(0, n + 1);
            }
            if (remoteServerVersion != null && remoteServerVersion.indexOf(thisServerVersion) < 0) {
                return STOPPED_DOMAIN_NOT_MATCHING;
            }
        }

        CommandLocation command = new CommandLocation();
        LastTaskEventListener listener = new LastTaskEventListener();
        ResultMap<String, String> result = null;
        Future<ResultMap<String, String>> locationTask = null;

        try {
            locationTask = ServerAdmin.<ResultMap<String, String>>exec(server, command, listener);
            result = locationTask.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            GlassfishToolsPlugin.logMessage("ServerStatusMonitor for " + server.getName() + " location interrupted");
        } catch (ExecutionException e) {
            logMessage("ServerStatusMonitor for " + server.getName() + " location throws exception");
            e.printStackTrace();
        } catch (TimeoutException e) {
            logMessage("ServerStatusMonitor for " + server.getName() + " location timed out");
        } finally {
            if (result == null) {
                if (locationTask != null) {
                    locationTask.cancel(true);
                }
                return RUNNING_CONNECTION_ERROR;
            }
        }

        ServerStatus s = null;
        switch (result.getState()) {
        case COMPLETED:
            try {
                if (domainMatching(server, result.getValue())) {
                    s = RUNNING_DOMAIN_MATCHING;
                } else {
                    s = STOPPED_DOMAIN_NOT_MATCHING;
                }
            } catch (IOException e) {

            }
            break;
        case FAILED:
            if (isAuthException(listener.getLastEvent(), result)) {
                s = RUNNING_CREDENTIAL_PROBLEM;
            } else if (isRemoteAdminException(result)) {
                s = RUNNING_REMOTE_NOT_SECURE;
            } else if (listener.getLastEvent().equals(BAD_GATEWAY)) {
                s = RUNNING_PROXY_ERROR;
            } else {
                s = RUNNING_CONNECTION_ERROR;
            }
            break;
        case RUNNING:
            logMessage("ServerStatusMonitor for " + server.getName() + " location takes long time...");
            locationTask.cancel(true);
            s = NOT_DEFINED;
            break;
        default:
            logMessage("ServerStatusMonitor for " + server.getName() + " location in ready state");
            s = NOT_DEFINED;
            break;
        }

        return s;
    }

    private static boolean domainMatching(GlassFishServer server, Map<String, String> locationResult)
            throws IOException {
        if (server.isRemote()) {
            return true;
        }
        String expectedDomainRoot = server.getDomainsFolder() + File.separator + server.getDomainName();
        String actualDomainRoot = locationResult.get("Domain-Root_value");
        if ((expectedDomainRoot != null) && (actualDomainRoot != null)) {
            File expected = new File(expectedDomainRoot);
            File actual = new File(actualDomainRoot);
            if (expected.getCanonicalPath().equals(actual.getCanonicalPath())) {
                return true;
            }
        }

        return false;
    }

    private static boolean isAuthException(TaskEvent event, ResultMap<String, String> result) {
        // for now handle remote admin access exception as auth issue
        return event.equals(AUTH_FAILED)
                || ((result.getValue() != null) && (result.getValue().get("message") != null)
                        && (result.getValue().get("message").contains("javax.security.auth.login.LoginException")));
    }

    private static boolean isRemoteAdminException(ResultMap<String, String> result) {
        return (result.getValue() != null) && (result.getValue().get("message") != null)
                && result.getValue().get("message").contains("org.glassfish.internal.api.RemoteAdminAccessException");
    }

    /**
     * This listener stores the last task event that occurred during command execution. It's return
     * value is well defined only after corresponding Future.get method returned. It can also return
     * null if no event was observed or execution timed out. Do not put the same instance into two exec
     * calls that run concurrently, it is not thread safe.
     *
     * @author Peter Benedikovic
     *
     */
    public static class LastTaskEventListener implements TaskStateListener {

        private TaskEvent lastEvent;

        @Override
        public void operationStateChanged(TaskState newState, TaskEvent event, String... args) {
            lastEvent = event;
        }

        public TaskEvent getLastEvent() {
            return lastEvent;
        }

    }

}
