package com.seanshubin.vote.tools.lib

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.FilterHolder
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet

object StaticServer {

    /**
     * Start a Jetty server that serves static files from [rootDir] on [port].
     * Returns the running [Server] so the caller can stop it.
     *
     * Includes an SPA fallback: any GET that doesn't resolve to a real file
     * under [rootDir] is rewritten to `/index.html` so the client-side
     * router (rememberRouter / history.pushState) handles the path. Without
     * this, a refresh on a deep link like `/elections/Foo` would 404 here
     * even though the route works in production (where CloudFront / S3
     * map unmatched paths to index.html). Matches that production behavior
     * locally so deep links survive a refresh during development.
     */
    fun start(rootDir: Path, port: Int): Server {
        val server = Server(port)
        val handler = ServletContextHandler()
        handler.contextPath = "/"
        handler.baseResource = org.eclipse.jetty.util.resource.Resource.newResource(rootDir.toFile())

        // Filter runs BEFORE the DefaultServlet so we can rewrite the
        // request path to /index.html when the requested file does not
        // exist. Has to be a forward (not a redirect) so the browser URL
        // stays put — the SPA reads window.location to pick its initial
        // route, and a redirect to /index.html would lose the path.
        handler.addFilter(
            FilterHolder(SpaFallbackFilter(rootDir)),
            "/*",
            EnumSet.of(DispatcherType.REQUEST),
        )

        val holder = ServletHolder("default", DefaultServlet())
        holder.setInitParameter("dirAllowed", "true")
        holder.setInitParameter("acceptRanges", "true")
        holder.setInitParameter("etags", "true")
        handler.addServlet(holder, "/")

        server.handler = handler
        server.start()
        return server
    }
}

/**
 * Forwards GETs for non-existent paths to `/index.html` so client-side
 * routes survive a page refresh. Only GET, only paths under the context
 * root, only when the target isn't a regular file on disk. POSTs / asset
 * misses on extensioned paths (e.g. a missing favicon.ico) fall through
 * to the DefaultServlet's normal 404, since rewriting those to index.html
 * would mask real bugs.
 */
private class SpaFallbackFilter(private val rootDir: Path) : Filter {
    override fun init(filterConfig: FilterConfig?) {}
    override fun destroy() {}

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as HttpServletRequest
        val res = response as HttpServletResponse
        val path = req.requestURI.trimStart('/')
        val target = if (path.isEmpty()) rootDir else rootDir.resolve(path).normalize()
        val withinRoot = target.startsWith(rootDir)
        val isFile = withinRoot && Files.isRegularFile(target)
        if (req.method == "GET" && !isFile && !path.contains('.')) {
            req.getRequestDispatcher("/index.html").forward(req, res)
        } else {
            chain.doFilter(request, response)
        }
    }
}
