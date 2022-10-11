/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.ui.internal;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.util.resource.Resource;
import org.openhab.core.OpenHAB;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet that serves files from both the filesystem and the local bundle. Supports general file caching as well as
 * serving compressed files
 *
 * @author Dan Cunningham - Initial contribution
 */

@Component(immediate = true, name = "org.openhab.ui", property = { "httpContext.id:String=oh-ui-http-ctx" })
@NonNullByDefault
public class UIServlet extends DefaultServlet {

    private static final long serialVersionUID = 1L;

    private final Logger logger = LoggerFactory.getLogger(UIServlet.class);

    private static final String APP_BASE = "app";
    private static final String SERVLET_NAME = "/";
    private static final String STATIC_PATH = "/static";
    private static final String STATIC_BASE = OpenHAB.getConfigFolder() + "/html";

    private final HttpContext defaultHttpContext;
    private final HttpService httpService;
    private final ContextHandler contextHandler;
    private static final ResourceService resourceService = new ResourceService();

    static {
        resourceService.setAcceptRanges(true);
        resourceService.setDirAllowed(false);
        resourceService.setRedirectWelcome(false);
        List<CompressedContentFormat> ccf = new ArrayList<>();
        ccf.add(CompressedContentFormat.BR);
        ccf.add(CompressedContentFormat.GZIP);
        resourceService.setPrecompressedFormats(ccf.toArray(new CompressedContentFormat[ccf.size()]));
        // _resourceService.setPathInfoOnly(getInitBoolean("pathInfoOnly", _resourceService.isPathInfoOnly()));
        resourceService.setEtags(true);
    }

    @Activate
    public UIServlet(final @Reference HttpService httpService, final @Reference HttpContext httpContext) {
        super(resourceService);
        defaultHttpContext = httpService.createDefaultHttpContext();
        this.httpService = httpService;
        contextHandler = ContextHandler.getCurrentContext().getContextHandler();
    }

    @Activate
    protected void activate(Map<String, Object> config) {
        try {
            logger.debug("Starting up {} at {}", getClass().getSimpleName(), SERVLET_NAME);
            httpService.registerServlet(SERVLET_NAME, this, new Hashtable<>(), defaultHttpContext);
        } catch (NamespaceException e) {
            logger.error("Error during servlet registration - alias {} already in use", SERVLET_NAME, e);
        } catch (ServletException e) {
            logger.error("Error during servlet registration", e);
        }
    }

    @Deactivate
    protected void deactivate() {
        httpService.unregister(SERVLET_NAME);
    }

    @Override
    public @Nullable Resource getResource(@Nullable String path) {
        logger.debug("getResource: {}", path);
        if (path == null) {
            return null;
        }
        if (path.startsWith(STATIC_PATH)) {
            Path filePath = Paths.get(STATIC_BASE + path.substring(new String(STATIC_PATH).length()));
            logger.debug("Local File Path {}", filePath);

            // protect against traversal attacks
            String normalized = filePath.normalize().toString();
            if (!normalized.startsWith(STATIC_BASE)) {
                logger.debug("Request attempted to access a file outside of the user folder");
                return null;
            }
            try {
                return contextHandler.newResource(filePath.toUri());
            } catch (IOException e) {
                logger.debug("Could not load resource", e);
                return null;
            }
        } else {
            // we don't serve directories, try loading an index page for that
            String modifiedReqPath = path.endsWith("/") ? path + "index.html" : path;
            URL url = defaultHttpContext.getResource(APP_BASE + modifiedReqPath);

            // The Main UI Vue.js app has its own router, so return the base page and let it deal with unknown paths.
            url = (url != null) ? url : defaultHttpContext.getResource(APP_BASE + "/index.html");
            logger.debug("Bundle File Path {}", url);
            try {
                return contextHandler.newResource(url);
            } catch (IOException e) {
                logger.debug("Could not load resource", e);
                return null;
            }
        }
    }

    @Override
    protected void doGet(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws ServletException, IOException {
        if (request == null || response == null) {
            return;
        }
        if (!defaultHttpContext.handleSecurity(request, response)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        super.doGet(request, response);
    }
}
