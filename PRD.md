# PRD: Algolia to Self-Hosted Typesense Search Infrastructure Migration

**Document Version:** 1.0.0  
**Status:** In Review  
**Last Updated:** 2026-06-05  
**Contributors:** Abinash Deka, Arif Rezza   
**Cross-Functional Stakeholders:** Web/Angular Team, Mobile App Team

---

## 1. Executive Summary

This document specifies the requirements, architecture, and phased execution plan for migrating the Vantage Perks search pipeline from Algolia (SaaS) to a self-hosted Typesense instance. The migration targets a flat monthly infrastructure cost of ~$200, eliminates variable SaaS billing, and requires minimal client-side code changes through a backend adapter pattern implemented inside the Play Framework API gateway.

---

## 2. Problem Statement

The current search infrastructure relies on Algolia, a third-party SaaS provider. As search query volume and indexed document count scale with product growth, Algolia's pricing model introduces unpredictable variable costs. There is no technical justification for the premium given the team's capability to self-host an equivalent open-source solution on existing internal nodes.

**Key pain points:**
- Variable Algolia billing that scales with record count and search operations
- External dependency on a third-party Service Level Agreement for a core product feature
- Limited control over indexing pipelines, relevance tuning, and data residency

---

## 3. Goals & Success Metrics

| Goal | Metric | Target            |
|---|---|-------------------|
| Cost reduction | Monthly infrastructure spend | ≤ $200/month flat |
| Client compatibility | Frontend/mobile code changes required | very less         |
| Index freshness | Lag between DB write and search index update | ≤ 2 seconds       |
| Uptime | Search service availability | ≥ 99.5%           |
| Migration downtime | Outage window during cutover | 0 minutes         |

---

## 4. Non-Goals

- Replacing the frontend search UI components or Angular HTTP bindings (explicitly out of scope)
- Migrating any service other than the perks search pipeline in this phase


---

## 5. Technical Architecture

### 5.1 System Overview
#  System Architecture & Data Flow

This diagram illustrates the backend adapter pattern. The backend acts as an API gateway, insulating the client layers (Angular and Mobile) from the underlying search engine migration while pulling the source of truth from MySQL.

```text
┌──────────────────────────────────────────────────────────┐
│                      Client Layer                        │
│   Angular Web App     |    Mobile App            │
│   HTTP → /v1/search           |    HTTP → /v1/search     │
└─────────────────────┬────────────────────────────────────┘
                      │
                      │ Existing API Contract (UNCHANGED)
                      v
┌──────────────────────────────────────────────────────────┐
│              Play Framework API Gateway (Scala)          │
│                                                          │
│  SearchController → SearchService                        │
│  - Receives client query params                          │
│  - Forwards to Typesense REST API (internal)             │
│  - Intercepts Typesense response                         │
│  - Maps to legacy Algolia-compatible JSON schema         │
│  - Returns unified SearchResponse to clients             │
└─────────────────────┬────────────────────────────────────┘
                      │
                      │ Internal network call
                      v
┌──────────────────────────────────────────────────────────┐
│           Self-Hosted Typesense (Docker)                 │
│   - Port 8108                                            │
│   - In-memory index, persisted to /data volume           │
│   - CORS enabled                                         │
│   - restart: always                                      │
└──────────────────────────────────────────────────────────┘
                      │
                      │ Read source
                      v
┌──────────────────────────────────────────────────────────┐
│                 MySQL 8.0 (Primary DB)                   │
│   - vantage_perks schema                                 │
│   - perks table                                          │
└──────────────────────────────────────────────────────────┘

```
### 5.2 Backend Adapter Pattern (Critical Requirement)

Cross-team discovery confirmed that neither the Angular web app nor the mobile app uses Algolia's InstantSearch SDK or any Algolia-specific client libraries.

**The Play backend is the single integration point.** It acts as an opaque API gateway:

1. Receives the existing query parameters (`q`, `dealType`) from clients
2. Translates them into a Typesense search request
3. Receives the Typesense response (Typesense JSON schema)
4. Transforms it into the legacy response schema that clients expect (`hits[]`, `found`)
5. Returns the transformed payload — clients never see a Typesense response directly

This design guarantees **zero client-side code changes** during and after migration.

### 5.3 Infrastructure Specifications

**Typesense Server Requirements:**

| Parameter | Specification |
|---|---|
| Deployment | Docker container on internal company node |
| Image | `typesense/typesense:26.0` |
| RAM (dedicated) | 8 GB minimum, 16 GB recommended |
| CORS | Enabled (`--enable-cors`) |
| API Key | Managed via environment variable, not hardcoded |

**Reasoning for RAM target:** Typesense is an in-memory search platform. All indexed documents and inverted indexes reside in RAM during operation. For the current perks catalog size, 8 GB provides substantial headroom. As the catalog grows, scaling to 16 GB on the same node is the recommended first step before any horizontal scaling consideration.

---

## 6. Migration Strategy: Three-Phase Zero-Downtime Pipeline

### Phase 1: Historical Batch Backfill (Quill)

**Objective:** Populate the Typesense index with all existing records from MySQL before any traffic is switched.

**Approach:**
- A one-time Scala migration job using the Quill JDBC library reads all rows from the `perks` table in batches.
- Each batch is serialized into `TypesenseDocument` objects and bulk-imported via Typesense's `/collections/perks/documents/import` endpoint (JSONL format).
- The job is idempotent: documents are upserted using `action=upsert` to allow safe re-runs.
- Completion criterion: Typesense document count equals MySQL row count.

**Duration estimate:** < 30 minutes for catalogs up to 500,000 records on the target node.

### Phase 2: Real-Time Dual-Sync Bridge

**Objective:** Keep Typesense in sync with MySQL write operations during the transition period without switching live search traffic.

**Approach:**
- All write paths that mutate the `perks` table (INSERT, UPDATE, DELETE) are modified to call a `DualSyncService` after the primary DB transaction commits.
- `DualSyncService` fires an asynchronous, non-blocking call to `SearchService.indexPerk()` (or a delete equivalent).
- Failures in Typesense sync are logged and retried via an internal queue but **do not fail the primary transaction**. MySQL is the source of truth.
- A scheduled reconciliation job runs nightly during Phase 2, comparing counts and checksums between MySQL and Typesense and backfilling any missed documents.

**Exit criterion for Phase 2:** 72-hour monitoring window with zero reconciliation drift detected.

### Phase 3: Production Cutover

**Objective:** Switch live search traffic from Algolia to Typesense with zero downtime.

**Approach:**
- A feature flag (`search.provider = typesense | algolia`) is added to the Play application config.
- Cutover is performed by toggling the flag and reloading config (no redeploy required).
- The Play `SearchController` routes to either `AlgoliaSearchService` or `TypesenseSearchService` based on the flag. Both implement the same `SearchService` trait and return the identical `SearchResponse` schema.
- Algolia is kept live but idle for 14 days post-cutover as a rollback option.
- After 14 days with zero incidents, Algolia subscription is cancelled.

**Rollback:** Toggle feature flag back to `algolia`. No data loss risk as MySQL is source of truth throughout.

---

## 7. API Contract (Unchanged Client-Facing Schema)

### Request

* GET /v1/search?q=<query>&dealType=<optional_filter>


### Response (SearchResponse — unchanged from current Algolia adapter)
```json
{
  "hits": [
    {
      "id": 1,
      "name": "50% Off SaaS Tools",
      "dealType": "software",
      "companyName": "Acme Corp"
    }
  ],
  "found": 1
}
```

---

## 8. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Typesense node OOM under high index load | Medium | High | Set Docker memory limit; monitor with cAdvisor; pre-warm index before cutover |
| Schema mismatch in adapter layer | Low | High | Full integration test suite covering SearchResponse serialization before Phase 3 |
| Dual-sync lag causes stale results during Phase 2 | Medium | Low | Nightly reconciliation job; Phase 2 traffic still served by Algolia |
| Typesense container crash | Low | High | `restart: always` policy; health check endpoint monitored by internal alerting |
| Accidental Algolia cancellation before validation | Low | Critical | Algolia cancellation is a manual step gated behind 14-day post-cutover sign-off |

---



