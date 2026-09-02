package com.meshql.api.graphql;

import com.meshql.auth.token.TokenSession;

import com.meshql.core.config.QueryConfig;
import com.meshql.core.config.VectorResolverConfig;
import com.meshql.core.config.RootConfig;
import com.meshql.core.Auth;
import com.meshql.core.Searcher;
import com.tailoredshapes.stash.Stash;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RootTest {
    @Mock private Searcher searcher;
    @Mock private Auth authorizer;

    private DTOFactory dtoFactory;
    private RootConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        config = RootConfig.builder()
            .singleton("user", "SELECT * FROM users WHERE id = {{id}}")
            .vector("users", "SELECT * FROM users")
            .vectorResolver("posts", "user", "userPosts", "http://localhost:8080/graphql")
            .build();

        dtoFactory = new DTOFactory(
            Collections.emptyList(),
            config.vectorResolvers(),
            Collections.emptyList(),
            Collections.emptyList(),
            stash()
        );
    }

    @Test
    void testSingletonQuery() throws Exception {
        // Setup mocks
        when(authorizer.authenticate(any())).thenReturn(new TokenSession(list("test-token")));
        
        Stash mockResult = stash("id", "123", "name", "Test User");
        when(searcher.find(any(), any(), any(), anyLong()))
            .thenReturn(mockResult);

        // Create root
        Map<String, DataFetcher> root = Root.create(searcher, dtoFactory, authorizer, config);

        // Test singleton query
        DataFetcher userFetcher = root.get("user");
        assertNotNull(userFetcher);

        // Create mock environment
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(Map.of("id", "123"));
        
        // Execute the fetcher
        Object result = userFetcher.get(env);
        assertTrue(result instanceof Map);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> userResult = (Map<String, Object>) result;
        assertEquals("123", userResult.get("id"));
        assertEquals("Test User", userResult.get("name"));
    }

    @Test
    void testVectorQuery() throws Exception {
        // Setup mocks
        when(authorizer.authenticate(any())).thenReturn(new TokenSession(list("test-token")));
        
        List<Stash> mockResults = list(
            stash("id", "123", "name", "User 1"),
            stash("id", "456", "name", "User 2")
        );
        when(searcher.findAll(any(), any(), any(), anyLong()))
            .thenReturn(mockResults);

        // Create root
        Map<String, DataFetcher> root = Root.create(searcher, dtoFactory, authorizer, config);

        // Test vector query
        DataFetcher usersFetcher = root.get("users");
        assertNotNull(usersFetcher);

        // Create mock environment
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(Map.of());
        
        // Execute the fetcher
        Object result = usersFetcher.get(env);
        assertTrue(result instanceof List);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> usersResult = (List<Map<String, Object>>) result;
        assertEquals(2, usersResult.size());
        assertEquals("User 1", usersResult.get(0).get("name"));
        assertEquals("User 2", usersResult.get(1).get("name"));
    }

    @Test
    void testTimestampHandling() throws Exception {
        // Setup mocks
        when(authorizer.authenticate(any())).thenReturn(new TokenSession(list("test-token")));
        
        Stash mockResult = stash("id", "123", "name", "Test User");
        when(searcher.find(any(), any(), any(), eq(123456789L)))
            .thenReturn(mockResult);

        // Create root
        Map<String, DataFetcher> root = Root.create(searcher, dtoFactory, authorizer, config);

        // Test with explicit timestamp
        DataFetcher userFetcher = root.get("user");
        
        // Create mock environment with timestamp
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(Map.of(
            "id", "123",
            "at", 123456789L
        ));
        
        // Execute the fetcher
        Object result = userFetcher.get(env);
        assertTrue(result instanceof Map);

        Map<String, Object> userResult = (Map<String, Object>) result;
        assertEquals(123456789L, userResult.get("_timestamp"));
    }
}