package com.meshql.repositories.memory;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.repos.certification.SearcherCertification;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

public class InMemorySearcherCertificationTest extends SearcherCertification {
    private static final Handlebars handlebars = new Handlebars();

    @Override
    public void init() {
        InMemoryStore store = new InMemoryStore();
        Auth noAuth = new NoAuth();

        this.repository = new InMemoryRepository(store);
        this.searcher = new InMemorySearcher(store);
        this.templates = createTemplates();
    }

    private static SearcherTemplates createTemplates() {
        Template findById = rethrow(() -> handlebars.compileInline("id = '{{id}}'"));
        Template findByName = rethrow(() -> handlebars.compileInline("name = '{{id}}'"));
        Template findAllByType = rethrow(() -> handlebars.compileInline("type = '{{id}}'"));
        Template findByNameAndType = rethrow(() -> handlebars.compileInline("name = '{{name}}' AND type = '{{type}}'"));

        return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
    }
}
