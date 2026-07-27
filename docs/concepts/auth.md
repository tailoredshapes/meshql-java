---
title: Authentication
layout: default
parent: Concepts
nav_order: 4
---

# Authentication and Authorization

MeshQL provides pluggable authentication designed for enterprise deployment behind API gateways.

---

## The Two-Phase Model

Authentication in MeshQL happens in two phases:

```mermaid
sequenceDiagram
    participant Client
    participant Gateway as API Gateway
    participant Auth as Auth Interface
    participant Storage

    Client->>Gateway: Request + JWT
    Gateway->>Gateway: Validate JWT signature
    Gateway->>Auth: Forward validated request

    rect rgb(220, 240, 255)
        note right of Auth: Phase 1: Extract Identity
        Auth->>Auth: Decode JWT payload<br/>Extract sub claim
        Auth-->>Auth: credentials = ["user-123"]
    end

    rect rgb(220, 255, 220)
        note right of Auth: Phase 2: Authorize Access
        Auth->>Storage: Query with credentials filter
        Storage-->>Auth: Only documents where<br/>authorizedTokens ∩ credentials ≠ ∅
    end

    Auth-->>Client: Filtered results
```

**Phase 1 — Identity Extraction** (`getAuthToken`):
Extract the caller's identity from the request context. Returns a list of credentials (user IDs, roles, or both).

**Phase 2 — Authorization Check** (`isAuthorized`):
For each document, check if the caller's credentials overlap with the document's `authorizedTokens`. Documents without matching tokens are filtered out.

---

## Auth Implementations

### JWT (`JWTSubAuthorizer`)

Extracts the `sub` claim from a Bearer token. **Does not verify the signature.**

```java
Auth auth = new JWTSubAuthorizer();
```

This is intentional:

```mermaid
graph LR
    client["Client"] -->|"Bearer eyJ..."| gw["API Gateway<br/>(Kong / Istio / AWS ALB)"]
    gw -->|"Validates signature<br/>Checks expiry<br/>Verifies issuer"| meshql["MeshQL<br/>(Extracts sub claim only)"]

    style gw fill:#f472b6,stroke:#333,color:#fff
    style meshql fill:#4a9eff,stroke:#333,color:#fff
```

**Why no signature verification?**
- Your API gateway already validates JWTs (Kong, Istio, AWS ALB, Cloudflare)
- MeshQL doesn't need access to signing keys
- No cryptographic overhead per request
- Follows the enterprise pattern: **validate at the edge, trust internally**

### Casbin RBAC (`CasbinAuth`)

Wraps another Auth (typically JWT) to add role-based access control:

```java
Auth jwtAuth = new JWTSubAuthorizer();
CasbinAuth auth = CasbinAuth.create("model.conf", "policy.csv", jwtAuth);
```

Usually you don't build it by hand. Set `casbinParams` on the server config and
MeshQL wires exactly this for you - `CasbinAuth` over `JWTSubAuthorizer` - for
the restlettes, the graphlettes, and the Searchers alike:

```java
Config config = Config.builder()
    .casbinParams(List.of("model.conf", "policy.csv"))
    // ... graphlettes and restlettes
    .build();
```

Omit `casbinParams` and the server uses `NoAuth`: every caller is authorized.
That is the correct default inside a trust boundary, and is why credentials are
optional rather than required - the edge authenticates, MeshQL authorizes.

```mermaid
sequenceDiagram
    participant Request
    participant Casbin as CasbinAuth
    participant JWT as JWTSubAuthorizer
    participant Policy as Casbin Policy

    Request->>Casbin: getAuthToken(context)
    Casbin->>JWT: getAuthToken(context)
    JWT-->>Casbin: ["user-123"]
    Casbin->>Policy: getRolesForUser("user-123")
    Policy-->>Casbin: ["admin", "editor"]
    Casbin-->>Request: ["admin", "editor"]
```

The caller's credentials become their **roles**, not their user ID. Documents are authorized by role:

```java
// This document is accessible by admins and editors
new Envelope("doc-1", payload, now, false,
    List.of("admin", "editor"));

// This document is only accessible by admins
new Envelope("doc-2", payload, now, false,
    List.of("admin"));
```

### NoAuth (Development)

Always authorizes. Every document is accessible by everyone.

```java
Auth auth = new NoAuth();  // Returns ["Token"], always authorizes
```

Use during development and testing. Never in production.

---

## Authorization Flow by API

### REST API

```mermaid
sequenceDiagram
    participant Client
    participant Restlette
    participant Auth
    participant Repository
    participant Storage

    Client->>Restlette: GET /hen/api/hen-42<br/>Authorization: Bearer eyJ...

    Restlette->>Auth: getAuthToken(headers)
    Auth-->>Restlette: ["farmer-bob"]

    Restlette->>Repository: read("hen-42", ["farmer-bob"], now)
    Repository->>Storage: SELECT WHERE id='hen-42'<br/>AND 'farmer-bob' IN authorizedTokens
    Storage-->>Repository: Envelope (or empty)

    alt Document found and authorized
        Repository-->>Restlette: Optional.of(envelope)
        Restlette-->>Client: 200 OK + payload
    else Not found or not authorized
        Repository-->>Restlette: Optional.empty()
        Restlette-->>Client: 404 Not Found
    end
```

Note: unauthorized access and not-found are **indistinguishable** to the caller. This prevents information leakage — you can't probe for document existence.

### GraphQL API

```mermaid
sequenceDiagram
    participant Client
    participant Graphlette
    participant Auth
    participant Searcher
    participant Storage

    Client->>Graphlette: POST /hen/graph<br/>{ getById(id: "hen-42") { name } }

    Graphlette->>Auth: getAuthToken(headers)
    Auth-->>Graphlette: ["farmer-bob"]

    Graphlette->>Searcher: find(template, args, ["farmer-bob"], timestamp)
    Searcher->>Storage: Query with token filter
    Storage-->>Searcher: Matching documents

    Searcher-->>Graphlette: Stash (payload) or null
    Graphlette-->>Client: { data: { getById: { name: "Henrietta" } } }
```

---

## Writing Your Own Auth

The `Auth` interface is two methods:

```java
public interface Auth {
    List<String> getAuthToken(Stash context);
    boolean isAuthorized(List<String> credentials, Envelope data);
}
```

`getAuthToken` extracts identity from the request context. `isAuthorized` checks whether those credentials can access a given document. That's the entire contract.

Most enterprises will write their own implementation — and that's expected. Your organization has its own identity provider, its own token format, its own authorization model. The built-in modules are starting points:

- **JWT** shows how to extract identity from a standard token format
- **Casbin** demonstrates how to layer role resolution on top of identity extraction — it's a **blueprint**, not a production-ready RBAC solution. Your policy store, role hierarchy, and tenant model will be specific to your organization.
- **NoAuth** keeps development friction-free

A custom implementation typically takes a single class. If your gateway passes a custom header with pre-resolved roles, the entire auth module might be:

```java
public class GatewayAuth implements Auth {
    public List<String> getAuthToken(Stash context) {
        Stash headers = (Stash) context.get("headers");
        String roles = headers.get("x-gateway-roles").toString();
        return List.of(roles.split(","));
    }

    public boolean isAuthorized(List<String> credentials, Envelope data) {
        if (data.authorizedTokens().isEmpty()) return true;
        return data.authorizedTokens().stream()
            .anyMatch(credentials::contains);
    }
}
```

---

## Choosing an Auth Strategy

| Strategy | Use Case | Credentials | Signature Verification |
|:---------|:---------|:------------|:----------------------|
| **NoAuth** | Development, testing | Static token | None |
| **JWT** | Production behind API gateway | User ID from `sub` | Gateway handles it |
| **Casbin** | Blueprint for role-based access | Roles from policy | Gateway handles JWT; Casbin handles RBAC |
| **Custom** | Your organization's auth model | Whatever you need | Your call |
