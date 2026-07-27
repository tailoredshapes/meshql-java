package com.meshql.core;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.tailoredshapes.stash.Stash;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Searcher {
    /**
     * Compile a configured query fragment into the template this searcher will
     * later be handed.
     *
     * <p>Compilation belongs to the searcher because escaping does. A query
     * fragment such as {@code id = '{{id}}'} is written in the query language of
     * whatever store sits behind this searcher, and how a user-supplied value
     * has to be written so that it means itself - and cannot end the literal it
     * sits inside - is a fact about that language, not about Handlebars.
     *
     * <p>The default is Handlebars' own default, {@code EscapingStrategy.HTML_ENTITY},
     * which is what every backend used before this method existed. It is safe in
     * the sense that no value can break out of a quoted literal, but it is wrong
     * in the sense that {@code O'Brien} renders as {@code O&amp;#x27;Brien} and so
     * matches nothing. Backends whose query language is known should override
     * this - see {@code RDBMSSearcher} - rather than leave the default in place.
     *
     * @param query the query fragment from configuration
     * @return the compiled template
     */
    default Template compileQuery(String query) {
        try {
            return new Handlebars().compileInline(query);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to compile query template: " + query, e);
        }
    }

    Stash find(
            Template queryTemplate,
            Stash args,
            List<String> creds,
            long timestamp
    );

    List<Stash> findAll(
            Template queryTemplate,
            Stash args,
            List<String> creds,
            long timestamp
    );
}