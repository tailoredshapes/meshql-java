package com.meshql.repositories.merksql;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.repos.certification.SearcherCertification;

public class MerkSqlSearcherCertificationTest extends SearcherCertification {
    private static final Handlebars handlebars = new Handlebars();

    @Override
    public void init() {
        MerkSqlConfig config = MerkSqlPlugin.tempConfig("cert-search-test");
        long handle = MerkSqlNative.create(config.dataDir);
        MerkSqlStore store = new MerkSqlStore(handle, config.topic);
        Auth noAuth = new NoAuth();

        this.repository = new MerkSqlRepository(store);
        this.searcher = new MerkSqlSearcher(store);
        this.templates = createTemplates();
    }

    private static SearcherTemplates createTemplates() {
        try {
            Template findById = handlebars.compileInline("id = '{{id}}'");
            Template findByName = handlebars.compileInline("name = '{{id}}'");
            Template findAllByType = handlebars.compileInline("type = '{{id}}'");
            Template findByNameAndType = handlebars.compileInline("name = '{{name}}' AND type = '{{type}}'");
            return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile templates", e);
        }
    }
}
