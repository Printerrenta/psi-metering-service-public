# psi-metering-service - public source

Independent Java 21 / Spring Boot service for four counter readings, usage policies and review.

Start with the [system overview and local Compose setup](https://github.com/Printerrenta/PSI-BE-public).
Each service owns its source, tests, Dockerfile and migrations. Data-owning services
have separate PostgreSQL databases and restricted credentials; the gateway has no DB.
Controllers delegate use cases, domain objects enforce invariants, and HTTP/JDBC/JPA
adapters implement local ports. Business models and SQL are not shared between services.

## Build and test

```sh
npm run build
npm test
npm run test:integration
docker build -t psi-metering-service:local .
```

Maven wrapper commands are also available. Integration tests use disposable Docker
PostgreSQL by default. The metering, billing and rental tests also support an
explicit disposable loopback PostgreSQL server through SERVICE_TEST_JDBC_URL;
they create and drop a random test database per class. Never use an operational
server or database tunnel for that test option.

Use the integration workspace for local dependencies and your own development
Auth/JWKS/storage configuration. Hosted services are private. No production token,
database password or deployment identity is needed to review this source.

## Public mirror contract

Application source, tests, resources, migration SQL, Dockerfile and build manifests
are exported byte for byte from a verified main commit. PUBLIC_MIRROR.json records
file checksums and the source commit. A source-<commit> tag identifies the equivalent
public snapshot used by integration release pins.

Real deployment settings, credentials, operating evidence and private Git history
are excluded. Publishing runs after successful source main CI with an allowlist
and secret scan. Publishing uses a short-lived token for this target. Public CI has no
deployment credentials and does not deploy GCP. Generated-file changes belong in
the source before synchronization; direct edits here can be replaced.
