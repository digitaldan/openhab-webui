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
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.OpenHAB;
import org.openhab.core.io.http.servlet.OpenHABServlet;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet that serves files from both the filesystem and the local bundle. Supports general file caching using either
 * the last modified time of the file, or the startup time of this service.
 *
 * @author Dan Cunningham - Initial contribution
 */
@Component(immediate = true, name = "org.openhab.ui", property = { "httpContext.id:String=oh-ui-http-ctx" })
@NonNullByDefault
public class UIServlet extends OpenHABServlet {

    private static final long serialVersionUID = 2880642275858634578L;

    private final Logger logger = LoggerFactory.getLogger(UIServlet.class);

    private static final String APP_BASE = "app";
    private static final String SERVLET_NAME = "/";
    private static final String STATIC_PATH = "/static";
    private static final String STATIC_BASE = OpenHAB.getConfigFolder() + "/html";

    private long startupTime;
    private final HttpContext defaultHttpContext;

    @Activate
    public UIServlet(final @Reference HttpService httpService, final @Reference HttpContext httpContext) {
        super(httpService, httpContext);
        defaultHttpContext = httpService.createDefaultHttpContext();
    }

    @Activate
    protected void activate(Map<String, Object> config) {
        super.activate(SERVLET_NAME);
        startupTime = roundDownMillisconds(System.currentTimeMillis());
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate(SERVLET_NAME);
    }

    @Override
    protected void doGet(@Nullable HttpServletRequest req, @Nullable HttpServletResponse resp)
            throws ServletException, IOException {
        if (req == null || resp == null) {
            return;
        }

        if (!defaultHttpContext.handleSecurity(req, resp)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        String reqPath = req.getRequestURI();
        logger.debug("Request Path {}", reqPath);

        if (reqPath == null) {
            logger.debug("Cannot service a request without a URI");
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String mimeType = getServletContext().getMimeType(reqPath);

        if (reqPath.startsWith(STATIC_PATH)) {
            if (reqPath.endsWith("/")) {
                // we only serve files
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // Files are served from the user 'html' folder
            Path path = Paths.get(STATIC_BASE + reqPath.substring(new String(STATIC_PATH).length()));
            logger.debug("Path {} and type {}", path, mimeType);

            // protect against traversal attacks
            String normalized = path.normalize().toString();
            if (!normalized.startsWith(STATIC_BASE)) {
                logger.debug("Request attempted to access a file outside of the user folder");
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            if (!Files.exists(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
            long modifiedTime = roundDownMillisconds(attr.lastModifiedTime().toMillis());
            if (!modifiedSince(req, modifiedTime)) {
                resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }

            try (InputStream is = Files.newInputStream(path)) {
                resp.setContentType(mimeType);
                resp.setDateHeader("Last-Modified", modifiedTime);
                is.transferTo(resp.getOutputStream());
                resp.flushBuffer();
            } catch (IOException e) {
                logger.error("Failed sending the file stream as a response: {}", e.getMessage());
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        } else {
            if (!modifiedSinceStartup(req)) {
                resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }

            // we don't serve directories, try loading an index page for that
            String modifiedReqPath = reqPath.endsWith("/") ? reqPath + "index.html" : reqPath;
            URL url = defaultHttpContext.getResource(APP_BASE + modifiedReqPath);

            // The Main UI Vue.js app has its own router, so return the base page and let it deal with unknown paths.
            url = (url != null) ? url : defaultHttpContext.getResource(APP_BASE + "/index.html");

            logger.debug("Bundle path URL {}", url);

            if (url == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            try (InputStream is = url.openStream()) {
                resp.setContentType(mimeType);
                resp.setDateHeader("Last-Modified", startupTime);
                is.transferTo(resp.getOutputStream());
                resp.flushBuffer();
            } catch (IOException e) {
                logger.error("Failed sending the file stream as a response: {}", e.getMessage());
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }

    private boolean modifiedSinceStartup(HttpServletRequest req) {
        return modifiedSince(req, startupTime);
    }

    private boolean modifiedSince(HttpServletRequest req, long lastModified) {
        long modifiedSince = req.getDateHeader("If-Modified-Since");
        logger.debug("If-Modified-Since : {} file time {} is modified  {}", modifiedSince, lastModified,
                modifiedSince < lastModified);
        return modifiedSince < lastModified;
    }

    // "If-Modified-Since" uses seconds level precision, drop the last milliseconds
    private long roundDownMillisconds(long milliseconds) {
        return (milliseconds / 1000) * 1000;
    }
}
