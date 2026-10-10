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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
     *
     * Paths in [PROXIED_PREFIXES] are sent to [backendBaseUrl] instead,
     * mirroring the CloudFront behaviors that route them to API Gateway in
     * production. Without this the SPA fallback would swallow them: a shared
     * review page is server-rendered HTML at a path with no file extension,
     * so it looks exactly like a client-side route and would be answered
     * with index.html.
     */
    fun start(rootDir: Path, port: Int, backendBaseUrl: String): Server {
        val server = Server(port)
        val handler = ServletContextHandler()
        handler.contextPath = "/"
        handler.baseResource = org.eclipse.jetty.util.resource.Resource.newResource(rootDir.toFile())

        // Registered before the SPA fallback so a proxied path never reaches
        // it. Order matters: the fallback would otherwise answer these with
        // index.html, which is the bug this exists to prevent.
        handler.addFilter(
            FilterHolder(BackendProxyFilter(backendBaseUrl)),
            "/*",
            EnumSet.of(DispatcherType.REQUEST),
        )

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

    /**
     * Path prefixes served by the backend rather than the SPA bundle, one
     * per CloudFront behavior that targets the API origin.
     *
     * /api is deliberately absent: in production CloudFront proxies it so
     * the browser sees one origin and cookies travel as same-origin, but
     * locally the SPA is built with an absolute api.base.url and calls the
     * backend directly, so there is nothing to proxy.
     */
    val PROXIED_PREFIXES = listOf("/review")

    fun isProxied(path: String): Boolean = PROXIED_PREFIXES.any { prefix ->
        path == prefix || path.startsWith("$prefix/")
    }
}

/**
 * Forwards [StaticServer.PROXIED_PREFIXES] to the backend so local URLs
 * behave like deployed ones.
 *
 * Uses the JDK client rather than Jetty's ProxyServlet to avoid pulling in
 * jetty-proxy for a dev-only convenience. Read-only and deliberately plain:
 * these paths are unauthenticated GETs, so there are no cookies, credentials
 * or request bodies to carry across.
 */
private class BackendProxyFilter(private val backendBaseUrl: String) : Filter {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun init(filterConfig: FilterConfig?) {}
    override fun destroy() {}

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as HttpServletRequest
        val res = response as HttpServletResponse
        if (req.method != "GET" || !StaticServer.isProxied(req.requestURI)) {
            chain.doFilter(request, response)
            return
        }

        val query = req.queryString?.let { "?$it" } ?: ""
        val target = URI.create(backendBaseUrl.trimEnd('/') + req.requestURI + query)
        try {
            val upstream = client.send(
                HttpRequest.newBuilder(target).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            res.status = upstream.statusCode()
            upstream.headers().firstValue("content-type").ifPresent { res.contentType = it }
            res.outputStream.write(upstream.body())
        } catch (e: Exception) {
            // Almost always the backend not being up. Say so plainly rather
            // than letting it surface as a stack trace or, worse, falling
            // through to the SPA and looking like a routing bug.
            res.status = HttpServletResponse.SC_BAD_GATEWAY
            res.contentType = "text/plain; charset=utf-8"
            res.writer.println("Could not reach the backend at $backendBaseUrl to serve ${req.requestURI}")
            res.writer.println("Reason: ${e.message}")
        }
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
