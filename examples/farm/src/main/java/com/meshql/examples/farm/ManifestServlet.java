package com.meshql.examples.farm;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Serves a fixed JSON string (the committed manifest) at GET. */
public class ManifestServlet extends HttpServlet {
    private final String manifestJson;

    public ManifestServlet(String manifestJson) {
        this.manifestJson = manifestJson;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(manifestJson);
    }
}
