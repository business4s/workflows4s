# Requirements Document

## Introduction

This document specifies the requirements for refactoring the `OxPostgresRegistry` class to use Magnum's `Repo` pattern instead of raw JDBC queries. The refactoring aims to leverage Magnum's compile-time generated repository methods for common database operations while simplifying the JSON handling by treating JSONB columns as strings at the repository level.

## Glossary

- **OxPostgresRegistry**: The PostgreSQL-backed workflow registry that tracks workflow instances with their execution status, timestamps, and optional tags.
- **Magnum_Repo**: Magnum's repository abstraction that auto-generates common SQL methods (findById, insert, update, delete) at compile-time using the `@Table` annotation.
- **DbCodec**: Magnum's typeclass for JDBC reading and writing of Scala types.
- **WorkflowRegistryRow**: The entity class representing a row in the workflow_registry table.
- **WorkflowRegistryCreator**: The entity-creator class for inserting new rows (may omit auto-generated fields).
- **Transactor**: Magnum's wrapper around a DataSource that manages database connections and transactions.
- **JSONB**: PostgreSQL's binary JSON column type used for storing tags.
- **Tags**: Custom metadata extracted from workflow state as a `Map[String, String]`, stored as JSONB in the database but handled as a JSON string at the repository level.

## Requirements

### Requirement 1: Define Entity Classes with @Table Annotation

**User Story:** As a developer, I want the workflow registry entity classes to use Magnum's `@Table` annotation, so that the repository methods are generated at compile-time.

#### Acceptance Criteria

1. THE WorkflowRegistryRow class SHALL be annotated with `@Table` specifying PostgreSQL database type and snake_case column mapping
2. THE WorkflowRegistryRow class SHALL derive `DbCodec` for automatic serialization
3. THE WorkflowRegistryRow class SHALL contain fields for: instance_id, template_id, status, created_at, updated_at, wakeup_at, and tags
4. THE tags field SHALL be represented as `Option[String]` (JSON string) rather than `Option[Map[String, String]]`
5. THE WorkflowRegistryCreator class SHALL be defined as an effective subclass of WorkflowRegistryRow for insert operations

### Requirement 2: Implement Repository Using Magnum Repo

**User Story:** As a developer, I want to use Magnum's `Repo` class for database operations, so that common queries are generated at compile-time and the code is more maintainable.

#### Acceptance Criteria

1. THE OxPostgresRegistry SHALL define a `Repo[WorkflowRegistryCreator, WorkflowRegistryRow, (String, String)]` for the composite primary key (template_id, instance_id)
2. WHEN upserting a workflow instance, THE OxPostgresRegistry SHALL use the repository's generated methods where applicable
3. THE OxPostgresRegistry SHALL use `connect(transactor)` context for all database operations
4. THE OxPostgresRegistry SHALL NOT use raw JDBC connection management (getConnection/close patterns)

### Requirement 3: Handle JSONB Tags as Strings

**User Story:** As a developer, I want tags to be stored as JSONB in the database but handled as JSON strings at the repository level, so that the code is simpler and avoids complex DbCodec implementations.

#### Acceptance Criteria

1. WHEN storing tags, THE OxPostgresRegistry SHALL serialize `Map[String, String]` to a JSON string before passing to the repository
2. WHEN reading tags, THE OxPostgresRegistry SHALL deserialize the JSON string back to `Map[String, String]`
3. THE database column SHALL remain JSONB type to support PostgreSQL JSONB operators like `@>` for containment queries
4. WHEN tags are None or empty, THE OxPostgresRegistry SHALL store NULL in the database

### Requirement 4: Preserve Upsert Semantics

**User Story:** As a developer, I want the upsert operation to maintain the same behavior as before, so that existing workflows continue to work correctly.

#### Acceptance Criteria

1. WHEN a workflow instance does not exist, THE upsertInstance method SHALL insert a new row
2. WHEN a workflow instance already exists, THE upsertInstance method SHALL update the existing row
3. WHEN updating, THE OxPostgresRegistry SHALL update status, updated_at, wakeup_at, and tags fields
4. WHEN updating tags, THE OxPostgresRegistry SHALL use COALESCE to preserve existing tags if new tags are NULL
5. THE upsert operation SHALL use PostgreSQL's `ON CONFLICT ... DO UPDATE` syntax

### Requirement 5: Preserve Query Methods

**User Story:** As a developer, I want all existing query methods to continue working, so that monitoring and workflow management features remain functional.

#### Acceptance Criteria

1. THE getStaleWorkflows method SHALL return workflows with status 'Running' that haven't been updated for the specified duration
2. THE getWorkflowsByStatus method SHALL return all workflows matching the specified status
3. THE getWorkflowsWithPendingWakeups method SHALL return workflows with wakeup_at <= specified time and status in ('Running', 'Awaiting')
4. THE getWorkflowsByTags method SHALL use PostgreSQL JSONB containment operator (@>) for tag filtering
5. THE getStats method SHALL return aggregate statistics including counts by status and timestamp information

### Requirement 6: Maintain DbCodec for Instant

**User Story:** As a developer, I want the existing `DbCodec[Instant]` to continue working, so that timestamp fields are correctly serialized and deserialized.

#### Acceptance Criteria

1. THE instantCodec given instance SHALL convert between `java.time.Instant` and `java.sql.Timestamp`
2. THE instantCodec SHALL handle null values correctly
3. THE instantCodec SHALL be available in scope for the entity class derivation

### Requirement 7: Simplify Connection Management

**User Story:** As a developer, I want to use Magnum's `connect` context function consistently, so that connection management is handled automatically and the code is cleaner.

#### Acceptance Criteria

1. WHEN executing database operations, THE OxPostgresRegistry SHALL use `connect(transactor) { ... }` pattern
2. THE OxPostgresRegistry SHALL NOT manually call `getConnection()` or `close()` on connections
3. THE OxPostgresRegistry SHALL NOT manually manage commits or rollbacks for simple operations
4. IF custom SQL is needed, THE OxPostgresRegistry SHALL use Magnum's `sql` interpolator within the connect block
