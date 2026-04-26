package com.seanshubin.vote.tools.lib

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.nio.file.Path

object StaticServer {

    /**
     * Start a Jetty server that serves static files from [rootDir] on [port].
     * Returns the running [Server] so the caller can stop it.
     */
    fun start(rootDir: Path, port: Int): Server {
        val server = Server(port)
        val handler = ServletContextHandler()
        handler.contextPath = "/"
        handler.baseResource = org.eclipse.jetty.util.resource.Resource.newResource(rootDir.toFile())

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
