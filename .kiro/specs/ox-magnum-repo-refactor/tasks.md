# Implementation Plan: OxPostgresRegistry Magnum Repo Refactor

## Overview

This implementation plan refactors `OxPostgresRegistry` to use Magnum's `Repo` pattern with `@Table` annotated entity classes. The refactoring replaces raw JDBC queries with Magnum's `connect` context and `sql` interpolator, while handling JSONB tags as strings at the repository level.

## Tasks

- [x] 1. Define entity classes with @Table annotation
  - [x] 1.1 Create WorkflowRegistryRow case class with @Table annotation
    - Add `@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)` annotation
    - Define fields: templateId, instanceId, status, createdAt, updatedAt, wakeupAt, tags
    - Use `Option[String]` for tags field (JSON string)
    - Derive `DbCodec`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [x] 1.2 Create WorkflowRegistryCreator case class
    - Define same fields as WorkflowRegistryRow
    - Derive `DbCodec`
    - _Requirements: 1.5_
  - [x] 1.3 Define WorkflowRegistryRepo object
    - Extend `Repo[WorkflowRegistryCreator, WorkflowRegistryRow, (String, String)]`
    - _Requirements: 2.1_

- [x] 2. Refactor upsertInstance method
  - [x] 2.1 Implement upsertInstance using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator for INSERT...ON CONFLICT query
    - Cast tags to `::jsonb` in SQL
    - Use COALESCE for tag preservation
    - _Requirements: 2.2, 2.3, 2.4, 4.1, 4.2, 4.3, 4.4, 4.5_
  - [x] 2.2 Add helper methods for tag serialization
    - Implement `serializeTags(Option[Map[String, String]]): Option[String]`
    - Use existing `MagnumJsonCodec.writeJsonString`
    - _Requirements: 3.1, 3.4_
  - [x] 2.3 Write property test for upsert correctness
    - **Property 2: Upsert Correctness**
    - **Validates: Requirements 4.1, 4.2, 4.3**
  - [x] 2.4 Write property test for tag preservation
    - **Property 3: Tag Preservation on NULL Update**
    - **Validates: Requirements 4.4**

- [ ] 3. Refactor query methods
  - [ ] 3.1 Refactor getStaleWorkflows using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator with parameterized query
    - Return `List[WorkflowInstanceId]`
    - _Requirements: 5.1, 7.1, 7.2, 7.3, 7.4_
  - [ ] 3.2 Refactor getWorkflowsByStatus using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator with parameterized query
    - _Requirements: 5.2, 7.1, 7.4_
  - [ ] 3.3 Refactor getWorkflowsWithPendingWakeups using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator with parameterized query
    - _Requirements: 5.3, 7.1, 7.4_
  - [ ] 3.4 Refactor getWorkflowsByTags using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator with JSONB containment operator
    - _Requirements: 5.4, 3.3, 7.1, 7.4_
  - [ ] 3.5 Refactor getStats using connect and sql interpolator
    - Replace raw JDBC with `connect(transactor) { ... }`
    - Use `sql` interpolator for aggregate query
    - _Requirements: 5.5, 7.1, 7.4_
  - [ ] 3.6 Write property tests for query methods
    - **Property 4: Stale Workflow Filtering**
    - **Property 5: Status Filtering**
    - **Property 6: Pending Wakeup Filtering**
    - **Property 7: Tag Containment Filtering**
    - **Property 8: Stats Aggregation Correctness**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5**

- [ ] 4. Checkpoint - Verify refactoring
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. Add property tests for serialization
  - [ ] 5.1 Write property test for tag serialization round-trip
    - **Property 1: Tag Serialization Round-Trip**
    - **Validates: Requirements 3.1, 3.2**
  - [ ] 5.2 Write property test for Instant codec round-trip
    - **Property 9: Instant Codec Round-Trip**
    - **Validates: Requirements 6.1**

- [ ] 6. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- All tasks are required for comprehensive implementation
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
- The existing `instantCodec` given instance should be preserved and remain in scope
- The `MagnumJsonCodec` object already provides the JSON serialization utilities needed
